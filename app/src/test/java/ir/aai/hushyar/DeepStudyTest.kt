package ir.aai.hushyar

import ir.aai.hushyar.domain.ExtractedPage
import ir.aai.hushyar.domain.FactHarvester
import ir.aai.hushyar.domain.DeepStudy
import ir.aai.hushyar.domain.KnowledgeDecision
import ir.aai.hushyar.domain.KnowledgeBank
import ir.aai.hushyar.domain.KnowledgeItem
import ir.aai.hushyar.domain.KnowledgeResult
import ir.aai.hushyar.domain.LocalBrain
import ir.aai.hushyar.domain.LocalGeneration
import ir.aai.hushyar.domain.LocalGenerator
import ir.aai.hushyar.domain.SearchReport
import ir.aai.hushyar.domain.StudyBudget
import ir.aai.hushyar.domain.StudyPlanner
import ir.aai.hushyar.domain.StudyTrigger
import ir.aai.hushyar.domain.WebHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The v0.8 promise, tested without a network: «همهٔ موتورها را بگرد، دسته‌بندی و لایه‌بندی کن،
 * و باز بگرد تا تمام شود.» Every collaborator is injected, so the loop's *behaviour* — stopping
 * conditions, layering, deduplication, gaps — is what is under test, not the internet.
 */
class DeepStudyTest {

    // ---------------------------------------------------------------- planning

    @Test
    fun aTopicIsBrokenIntoTheSixLayers() {
        val facets = StudyPlanner.plan("هدفون سونی", "fa")
        assertEquals(LocalBrain.LAYERS.size, facets.size)
        assertEquals(LocalBrain.LAYERS.toSet(), facets.map { it.layer }.toSet())
        assertTrue("every facet needs a real query", facets.all { it.question.contains("هدفون سونی") })
    }

    @Test
    fun facetQuestionsAreInTheQuestionLanguage() {
        assertTrue(StudyPlanner.question(LocalBrain.LAYER_PRICE, "sony xm5", fa = false).contains("price"))
        assertTrue(StudyPlanner.question(LocalBrain.LAYER_PRICE, "سونی", fa = true).contains("قیمت"))
    }

    @Test
    fun theModelCanAddAFacetTheTemplateWouldNotThinkOf() {
        val brain = LocalBrain(FakeGenerator("سازگاری با گوشی\nگارانتی"), testSpec())
        val facets = StudyPlanner.plan("هدفون سونی", "fa", brain)
        assertTrue("a model facet must appear", facets.any { it.question.contains("سازگاری") })
        assertTrue("facets must stay bounded", facets.size <= LocalBrain.LAYERS.size + 2)
    }

    @Test
    fun emptyTopicPlansNothing() {
        assertTrue(StudyPlanner.plan("   ", "fa").isEmpty())
    }

    // ---------------------------------------------------------------- trigger

    @Test
    fun learningRequestsAreRecognised() {
        assertTrue(StudyTrigger.wantsStudy("یاد بگیر دربارهٔ هدفون سونی"))
        assertTrue(StudyTrigger.wantsStudy("همه چیز درباره پاوربانک خورشیدی"))
        assertTrue(StudyTrigger.wantsStudy("research solar chargers"))
    }

    @Test
    fun anOrdinaryQuestionDoesNotTriggerACrawl() {
        assertFalse(StudyTrigger.wantsStudy("قیمت هدفون سونی چقدر است؟"))
        assertFalse(StudyTrigger.wantsStudy("ساعت چند است"))
    }

    @Test
    fun theTopicIsStrippedOfTheRequest() {
        assertEquals("هدفون سونی", StudyTrigger.topicOf("یاد بگیر دربارهٔ هدفون سونی"))
        assertEquals("پاوربانک خورشیدی", StudyTrigger.topicOf("همه چیز درباره پاوربانک خورشیدی"))
        assertEquals("solar chargers", StudyTrigger.topicOf("research solar chargers"))
    }

    // ---------------------------------------------------------------- harvesting

    @Test
    fun harvestKeepsSentencesAboutTheTopicAndDropsNoise() {
        val page = page(
            "هدفون سونی WH-1000XM5 با قیمت ۱۲٬۵۰۰٬۰۰۰ تومان در بازار عرضه می‌شود.",
            "بسیاری از مردم ترجیح می‌دهند صبح زود بیدار شوند و پیاده‌روی کنند.",
            "باتری این هدفون تا ۳۰ ساعت دوام می‌آورد و شارژ سریع دارد."
        )
        val facts = FactHarvester.harvest("هدفون سونی", page, LocalBrain.LAYER_PRICE)
        assertEquals("only two sentences are about the topic", 2, facts.size)
        assertTrue(facts.all { it.layer == LocalBrain.LAYER_PRICE })
        assertTrue(facts.all { it.topic == "هدفون سونی" })
        assertTrue("the source must be recorded", facts.all { it.domain == "shop.test" })
    }

    @Test
    fun aNumberWithoutAUnitIsNotAFact() {
        val page = page("هدفون سونی مدل 1000 در بازار موجود است و فروش زیادی دارد.")
        assertTrue(FactHarvester.harvest("هدفون سونی", page, LocalBrain.LAYER_BASICS).isEmpty())
    }

    @Test
    fun harvestedFactsAreContentAddressedNotRandom() {
        val page = page("هدفون سونی با گارانتی ۱۸ ماهه عرضه می‌شود.")
        val a = FactHarvester.harvest("هدفون سونی", page, LocalBrain.LAYER_BASICS).first()
        val b = FactHarvester.harvest("هدفون سونی", page, LocalBrain.LAYER_BASICS).first()
        assertEquals("same page + same claim ⇒ same id", a.id, b.id)
    }

    // ---------------------------------------------------------------- the loop

    @Test
    fun studyCoversFacetsAndStopsWhenARoundAddsNothing() {
        val store = FakeStore()
        val study = DeepStudy(
            search = { query, _ -> report(query, "https://shop.test/${query.hashCode()}", "https://x.test/a") },
            fetch = { url, _ -> if (url.contains("shop.test")) pageFor("هدفون سونی", url, "قیمت ۱۲٬۵۰۰٬۰۰۰ تومان") else null },
            store = store::store,
            existing = { emptyList() },
            clock = monotonic()
        )
        val outcome = study.study("هدفون سونی", "fa", StudyBudget(rounds = 4, facets = 3))

        assertTrue("something must be learned", outcome.learned.isNotEmpty())
        assertTrue("never more rounds than the budget", outcome.rounds.size <= 4)
        assertTrue("it must stop for a stated reason", outcome.stop.isNotBlank())
        assertTrue("a repeated page must not be stored twice", store.stored.size == store.stored.distinctBy { it.claim }.size)
    }

    @Test
    fun studyReportsEveryLayerItCouldNotFill() {
        val study = DeepStudy(
            search = { query, _ -> if (query.contains("قیمت")) report(query) else report(query, "https://shop.test/a") },
            fetch = { url, _ -> pageFor("هدفون سونی", url, "هدفون سونی گارانتی دارد و مشخصات کامل آن منتشر شده است.") },
            store = { item -> KnowledgeResult(item.id, KnowledgeDecision.NEW, "ok") },
            clock = monotonic()
        )
        val outcome = study.study("هدفون سونی", "fa", StudyBudget(rounds = 1, facets = 6, minFactsPerFacet = 1))

        assertTrue("the price layer had no sources and must be a gap", outcome.gaps.contains(LocalBrain.LAYER_PRICE))
        assertTrue("coverage must reflect the gaps", outcome.coverage < 1.0)
        assertTrue(outcome.summary.contains("بی‌نتیجه"))
    }

    @Test
    fun aSearchThatReturnsNothingIsNotTreatedAsSuccess() {
        val study = DeepStudy(
            search = { query, _ -> SearchReport(query, emptyList(), emptyMap(), mapOf("bing" to "HTTP 429")) },
            fetch = { _, _ -> null },
            store = { item -> KnowledgeResult(item.id, KnowledgeDecision.NEW, "ok") },
            clock = monotonic()
        )
        val outcome = study.study("هدفون سونی", "fa", StudyBudget(rounds = 2))
        assertTrue(outcome.learned.isEmpty())
        assertEquals(0.0, outcome.coverage, 0.001)
        assertTrue("the engine failure must be reported", outcome.failures.containsKey("bing"))
        assertTrue("stop reason must say so", outcome.stop.isNotBlank())
    }

    @Test
    fun everyEngineThatAnsweredIsCountedInTheOutcome() {
        val study = DeepStudy(
            search = { query, _ ->
                SearchReport(query, listOf(WebHit("https://shop.test/a", "هدفون سونی قیمت", "txt", "bing", 1)),
                    mapOf("bing" to 1, "mwmbl" to 2), emptyMap())
            },
            fetch = { _, _ -> pageFor("هدفون سونی", "https://shop.test/a", "قیمت ۵٬۰۰۰٬۰۰۰ تومان برای این هدفون") },
            store = { item -> KnowledgeResult(item.id, KnowledgeDecision.NEW, "ok") },
            clock = monotonic()
        )
        val outcome = study.study("هدفون سونی", "fa", StudyBudget(rounds = 1, facets = 2))
        assertTrue("every engine that answered must be counted", outcome.engines.values.sum() >= 3)
    }

    @Test
    fun existingKnowledgeIsNotLearnedTwice() {
        val claim = "قیمت ۵٬۰۰۰٬۰۰۰ تومان برای این هدفون سونی است"
        val known = KnowledgeItem(
            id = "kn-known", topic = "هدفون سونی", question = "قیمت", claim = claim,
            category = "study", layer = LocalBrain.LAYER_PRICE, url = "https://shop.test/a"
        )
        var stored = 0
        val study = DeepStudy(
            search = { query, _ -> report(query, "https://shop.test/a") },
            fetch = { _, _ ->
                ExtractedPage("https://shop.test/a", "هدفون سونی", "$claim.", "untrusted")
            },
            store = { item -> stored++; KnowledgeResult(item.id, KnowledgeDecision.REFRESH, "ok") },
            existing = { listOf(known) },
            clock = monotonic()
        )
        val outcome = study.study("هدفون سونی", "fa", StudyBudget(rounds = 2, facets = 2))
        assertEquals("the known claim must not be re-stored", 0, stored)
        assertTrue("the known claim still counts as known material", outcome.learned.isNotEmpty())
    }

    @Test
    fun theTimeBudgetStopsTheLoop() {
        var now = 0L
        val study = DeepStudy(
            search = { query, _ -> now += 5_000; report(query, "https://shop.test/a") },
            fetch = { _, _ -> pageFor("هدفون سونی", "https://shop.test/a", "هدفون سونی ۳۰ ساعت باتری دارد") },
            store = { item -> KnowledgeResult(item.id, KnowledgeDecision.NEW, "ok") },
            clock = { now }
        )
        val outcome = study.study("هدفون سونی", "fa", StudyBudget(rounds = 9, facets = 6, millis = 22_000))
        assertTrue("must stop on time, not on rounds", outcome.stop.contains("زمان") || outcome.rounds.size < 9)
    }

    // ---------------------------------------------------------------- the local brain

    @Test
    fun theModelIsNotTrustedBlindlyForFacets() {
        val noisy = LocalBrain(FakeGenerator("۱. قیمت\n2) http://evil.test\n- باتری\nخیلی خیلی طولانی " + "ا".repeat(120) + "\n\n"))
        val facets = noisy.facets("هدفون", "fa")
        assertTrue("urls must be dropped", facets.none { it.contains("http") })
        assertTrue("absurdly long lines must be dropped", facets.none { it.length > 60 })
        assertTrue(facets.isNotEmpty())
    }

    @Test
    fun aModelThatIsNotReadyAddsNothing() {
        val brain = LocalBrain(FakeGenerator("قیمت", ready = false))
        assertTrue(brain.facets("هدفون", "fa").isEmpty())
        assertTrue(brain.queries("هدفون", "قیمت", "fa").isEmpty())
        assertEquals(null, brain.layer("هدفون ۱۲ تومان است", "fa"))
    }

    @Test
    fun layerLabelsAreValidatedAgainstTheFixedTaxonomy() {
        assertEquals(LocalBrain.LAYER_PRICE, LocalBrain(FakeGenerator("قیمت")).layer("قیمت آن ۵ تومان است", "fa"))
        assertEquals(
            "a label outside the taxonomy must be refused",
            null,
            LocalBrain(FakeGenerator("چیز دیگری")).layer("متن", "fa")
        )
    }

    @Test
    fun queriesAreClampedAndCleaned() {
        val brain = LocalBrain(FakeGenerator("قیمت هدفون سونی\n\"ارزان‌ترین هدفون سونی\"\nخط سوم\nخط چهارم"))
        val queries = brain.queries("هدفون سونی", "قیمت", "fa")
        assertTrue("at most three queries", queries.size <= 3)
        assertTrue("quotes must be trimmed away", queries.none { it.startsWith("\"") })
    }

    // ---------------------------------------------------------------- learnt knowledge is reused

    @Test
    fun whatIsLearnedDuringStudyAnswersTheNextQuestion() {
        val now = 1_760_000_000_000L
        val store = FakeStore()
        val study = DeepStudy(
            search = { query, _ -> report(query, "https://shop.test/sony-xm5") },
            fetch = { _, _ ->
                ExtractedPage(
                    "https://shop.test/sony-xm5", "هدفون سونی",
                    "قیمت هدفون سونی WH-1000XM5 در بازار ۱۲٬۵۰۰٬۰۰۰ تومان است و گارانتی ۱۸ ماهه دارد. " +
                        "وزن هدفون سونی حدود ۲۵۰ گرم است.", "untrusted"
                )
            },
            store = store::store,
            clock = monotonic()
        )
        val outcome = study.study("هدفون سونی", "fa", StudyBudget(rounds = 1, facets = 2))
        assertTrue(outcome.learned.isNotEmpty())

        // What the database does with a harvested fact: stamp it, then let the knowledge gate see it.
        val stored = store.stored.map { KnowledgeBank.stamp(it, now) }
        assertTrue("a learnt fact must not be stale the moment it is written", stored.none { KnowledgeBank.isStale(it, now) })
        val answerable = KnowledgeBank.answerable("قیمت هدفون سونی چند است؟", stored, now)
        assertTrue("the next question must be answerable from what was learnt", answerable.isNotEmpty())
        assertTrue(
            "the price fact must be among them",
            answerable.any { it.claim.contains("۱۲٬۵۰۰٬۰۰۰") }
        )
    }

    @Test
    fun anUnstampedHarvestIsStaleUntilItIsStamped() {
        val item = KnowledgeItem(
            id = "k1", topic = "هدفون سونی", question = "قیمت", claim = "قیمت ۵ تومان است", category = "study"
        )
        assertTrue("this is exactly the bug stamp() exists for", KnowledgeBank.isStale(item, 1_760_000_000_000L))
        assertFalse(KnowledgeBank.isStale(KnowledgeBank.stamp(item, 1_760_000_000_000L), 1_760_000_000_000L))
    }

    // ---------------------------------------------------------------- helpers

    private fun testSpec(): ir.aai.hushyar.domain.ModelSpec = ir.aai.hushyar.domain.ModelCatalog.recommended

    private fun page(vararg sentences: String): ExtractedPage = ExtractedPage(
        url = "https://shop.test/p/1",
        title = "هدفون سونی",
        text = sentences.joinToString(" "),
        trust = "untrusted"
    )

    private fun pageFor(topic: String, url: String, sentence: String): ExtractedPage =
        ExtractedPage(url = url, title = topic, text = "$sentence $topic دارد.", trust = "untrusted")

    private fun report(query: String, vararg urls: String): SearchReport = SearchReport(
        query = query,
        hits = urls.mapIndexed { i, url -> WebHit(url, "هدفون سونی $i", "", "bing", i + 1) },
        perEngine = if (urls.isEmpty()) emptyMap() else mapOf("bing" to urls.size),
        failures = if (urls.isEmpty()) mapOf("bing" to "بدون نتیجه") else emptyMap()
    )

    private fun monotonic(): () -> Long {
        var t = 0L
        return {
            t += 100
            t
        }
    }

    private class FakeStore {
        val stored = mutableListOf<KnowledgeItem>()

        fun store(item: KnowledgeItem): KnowledgeResult {
            stored.add(item)
            return KnowledgeResult(item.id, KnowledgeDecision.NEW, "ok")
        }
    }

    private class FakeGenerator(
        private val text: String?,
        override val ready: Boolean = true
    ) : LocalGenerator {
        override fun generate(prompt: String, maxTokens: Int): LocalGeneration =
            LocalGeneration(text = text, millis = 5, tokens = 4)

        override fun cancel() = Unit
    }
}
