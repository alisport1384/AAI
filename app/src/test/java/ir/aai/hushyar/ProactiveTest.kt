package ir.aai.hushyar

import ir.aai.hushyar.domain.AttributeSpec
import ir.aai.hushyar.domain.ExtractedPage
import ir.aai.hushyar.domain.KnowledgeItem
import ir.aai.hushyar.domain.KnowledgeResult
import ir.aai.hushyar.domain.LayerState
import ir.aai.hushyar.domain.LayerStatus
import ir.aai.hushyar.domain.Listing
import ir.aai.hushyar.domain.MarketPack
import ir.aai.hushyar.domain.PlanBuilder
import ir.aai.hushyar.domain.PlanProgress
import ir.aai.hushyar.domain.ProactiveBudget
import ir.aai.hushyar.domain.ProactiveResearcher
import ir.aai.hushyar.domain.ProductGroup
import ir.aai.hushyar.domain.ResearchPlan
import ir.aai.hushyar.domain.ResearchStop
import ir.aai.hushyar.domain.SearchReport
import ir.aai.hushyar.domain.WebHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The assistant must not wait to be asked: after the goal is known it starts collecting on its own,
 * big body first, and it must be *bounded* and *resumable* while doing it.
 *
 * Everything here runs on a fake transport: zero network calls, and the call counters are the proof
 * of the two promises — "it really went and looked" and "a repeat costs nothing".
 */
class ProactiveTest {

    private val now = 1_700_000_000_000L

    private val pack = MarketPack(
        id = "p-test",
        name = "لپ‌تاپ",
        attributes = listOf(AttributeSpec("ram", "رم"))
    )

    private fun product(name: String) = ProductGroup(
        id = "g-$name",
        canonicalName = name,
        listings = listOf(
            Listing(
                id = "l-$name",
                title = name,
                titleNorm = name,
                priceRaw = "30000000",
                priceIrt = 30_000_000,
                priceNote = "",
                source = "test",
                createdAt = now
            )
        ),
        attrs = emptyMap()
    )

    /** Every layer done except the listed ones — the cursor shape the researcher reads. */
    private fun cursor(p: ResearchPlan, pending: Set<String> = emptySet()): PlanProgress =
        PlanProgress(
            p.goal,
            p.layers.associate { layer ->
                layer.id to LayerState(
                    layer.id,
                    if (layer.id in pending) LayerStatus.PENDING else LayerStatus.DONE
                )
            }
        )

    private fun plan(): ResearchPlan =
        PlanBuilder.build(pack, listOf(product("لنوو"), product("ایسوس")), emptyList(), "fa", now)

    /** Fake world: one result per query, one page per result. Counters are the assertions. */
    private class FakeWeb(
        private val text: String = "قیمت لپ‌تاپ لنوو ۳۰ میلیون تومان است و در بازار تهران عرضه می‌شود.",
        private val withHits: Boolean = true
    ) {
        var searches = 0
        var fetches = 0

        fun search(query: String): SearchReport {
            searches++
            if (!withHits) return SearchReport(query, emptyList())
            return SearchReport(
                query,
                listOf(WebHit("https://shop.example.org/p", "فروشگاه", "قیمت", "fake", 1))
            )
        }

        fun fetch(url: String): ExtractedPage? {
            fetches++
            return ExtractedPage(url = url, title = "فروشگاه", text = text)
        }
    }

    private fun researcher(web: FakeWeb, store: ((KnowledgeItem) -> KnowledgeResult)? = null) = ProactiveResearcher(
        search = { query, _ -> web.search(query) },
        fetch = { url, _ -> web.fetch(url) },
        now = { now },
        store = store
    )

    @Test
    fun theFirstRunWorksOnTheCoarsestLayerOnly() {
        val web = FakeWeb()
        val outcome = researcher(web).run(plan(), PlanProgress("لپ‌تاپ"), ProactiveBudget.NORMAL, "fa", pack)

        assertEquals(listOf("market"), outcome.ranLayers)
        assertTrue(web.searches > 0)
        assertNotNull(outcome.progressAfter.states["market"])
        assertEquals(LayerStatus.DONE, outcome.progressAfter.states["market"]!!.status)
        // Coarse before fine: the products layer is still untouched in this run.
        assertEquals(LayerStatus.PENDING, outcome.progressAfter.state("products").status)
        assertEquals("products", outcome.nextLayer?.id)
    }

    @Test
    fun aDeeperBudgetUnlocksTheNextLayerOnTheNextRun() {
        val web = FakeWeb()
        val first = researcher(web).run(plan(), PlanProgress("لپ‌تاپ"), ProactiveBudget.NORMAL, "fa", pack)
        val second = researcher(web).run(first.plan, first.progressAfter, ProactiveBudget.NORMAL, "fa", pack)

        assertEquals(listOf("products"), second.ranLayers)
        assertEquals(LayerStatus.DONE, second.progressAfter.state("products").status)
    }

    @Test
    fun everyRunIsBoundedByTheBudget() {
        val web = FakeWeb()
        val outcome = researcher(web).run(
            plan(),
            PlanProgress("لپ‌تاپ"),
            ProactiveBudget(maxLayers = 2, maxQueries = 1, maxPages = 1, maxMillis = 60_000L),
            "fa",
            pack
        )
        assertTrue("queries must respect the budget", outcome.queries.size <= 1)
        assertTrue("pages must respect the budget", outcome.pages.size <= 1)
    }

    @Test
    fun aRunWithNoResultsStopsAndReportsWhy() {
        val web = FakeWeb(withHits = false)
        val outcome = researcher(web).run(plan(), PlanProgress("لپ‌تاپ"), ProactiveBudget.NORMAL, "fa", pack)
        assertTrue(outcome.learned.isEmpty())
        assertTrue(outcome.stop != ResearchStop.SUFFICIENT)
    }

    @Test
    fun whatSurvivesTheGateIsWrittenToTheRealStore() {
        val web = FakeWeb()
        val written = mutableListOf<KnowledgeItem>()
        val store: (KnowledgeItem) -> KnowledgeResult = { item ->
            written.add(item)
            KnowledgeResult(item.id, ir.aai.hushyar.domain.KnowledgeDecision.NEW, "ok")
        }
        val outcome = researcher(web, store).run(plan(), PlanProgress("لپ‌تاپ"), ProactiveBudget.NORMAL, "fa", pack)

        // Nothing is *reported* as learned unless it was actually persisted.
        assertEquals(written.size, outcome.learned.size)
        if (outcome.learned.isNotEmpty()) assertTrue(written.isNotEmpty())
    }

    @Test
    fun aProvenCaseMakesTheRepeatRunCostNoNetwork() {
        val web = FakeWeb()
        val base = PlanProgress("لپ‌تاپ")

        // Run the dossier layer first so a case exists for exactly that question.
        val asked = "قیمت لپ‌تاپ لنوو در بازار تهران چقدر است"
        val withDossier = PlanBuilder.build(pack, listOf(product("لنوو")), listOf(asked), "fa", now)
        val first = researcher(web).run(
            withDossier,
            cursor(withDossier, pending = setOf("dossier")),
            ProactiveBudget.DEEP,
            "fa",
            pack,
            cases = emptyList()
        )

        assertEquals(listOf("dossier"), first.ranLayers)
        assertTrue(first.cases.isNotEmpty())

        // Second pass over the same dossier, with the case store: no search, no fetch.
        val web2 = FakeWeb()
        val second = researcher(web2).run(
            first.plan,
            first.progressAfter.with(LayerState("dossier", LayerStatus.PENDING)),
            ProactiveBudget.DEEP,
            "fa",
            pack,
            cases = first.cases,
            playbook = first.playbook,
            domains = first.domains
        )
        assertTrue(second.ranLayers.isEmpty() || second.queries.isEmpty())
        assertEquals("a fresh case must cost zero searches", 0, web2.searches)
        assertEquals("a fresh case must cost zero fetches", 0, web2.fetches)
    }

    @Test
    fun aFinishedPlanIsNotResearchedAgain() {
        val web = FakeWeb()
        val p = plan()
        val allDone = cursor(p)
        assertNull(p.next(allDone.statuses()))
        assertTrue(researcher(web).run(p, allDone, ProactiveBudget.NORMAL, "fa", pack).ranLayers.isEmpty() || web.searches == 0)
    }

    @Test
    fun deepenAfterAnAnswerStudiesTheQuestionItself() {
        val web = FakeWeb()
        val question = "قیمت لپ‌تاپ لنوو در بازار تهران چقدر است"
        val outcome = researcher(web).deepen(
            question = question,
            plan = plan(),
            progress = PlanProgress("لپ‌تاپ"),
            langTag = "fa",
            pack = pack,
            budget = ProactiveBudget.LIGHT
        )
        // deepen() prepends its own one-question layer, so the answer is about *this* question.
        assertEquals(listOf("dossier-now"), outcome.ranLayers)
        assertTrue(web.searches >= 1)
        assertTrue(outcome.progressAfter.state("dossier-now").lastRunAt > 0L)
    }
}
