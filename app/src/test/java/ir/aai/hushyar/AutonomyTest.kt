package ir.aai.hushyar

import ir.aai.hushyar.core.HttpRequest
import ir.aai.hushyar.core.HttpResponse
import ir.aai.hushyar.core.HttpTransport
import ir.aai.hushyar.domain.Agent
import ir.aai.hushyar.domain.AnswerFocus
import ir.aai.hushyar.domain.AnswerShapes
import ir.aai.hushyar.domain.EngineKind
import ir.aai.hushyar.domain.Engines
import ir.aai.hushyar.domain.ExtractedPage
import ir.aai.hushyar.domain.KnowledgeItem
import ir.aai.hushyar.domain.LayerStatus
import ir.aai.hushyar.domain.MarketPack
import ir.aai.hushyar.domain.PlanBuilder
import ir.aai.hushyar.domain.QuestionReader
import ir.aai.hushyar.domain.SearchCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.6 answers three complaints that all come from the same place: the app was too dependent on
 * one search engine answering, and on the user asking the right thing the right way.
 *
 *  1. every engine the app knows must be tried honestly, in a sensible order, with a cooldown
 *     instead of a permanent ban;
 *  2. when no engine answers, a stored answer must still be given — labelled as stored;
 *  3. a yes/no question must not be answered with a guess, and a stated goal must be enough for the
 *     app to plan and start on its own.
 */
class AutonomyTest {

    private val now = 1_700_000_000_000L

    // ---------- 1. the engine catalogue ----------

    @Test
    fun everyEngineIsAUsablePublicHtmlEndpoint() {
        assertTrue("فهرست موتورها باید گسترده باشد", Engines.all.size >= 20)
        val ids = Engines.all.map { it.id }
        assertEquals("شناسه‌ها باید یکتا باشند", ids.size, ids.distinct().size)
        for (engine in Engines.all) {
            assertTrue("${engine.id}: قالب باید {q} داشته باشد", engine.template.contains("{q}"))
            assertTrue("${engine.id}: باید https باشد", engine.template.startsWith("https://"))
            assertTrue("${engine.id}: باید میزبان داشته باشد", engine.hosts.isNotEmpty())
            val url = engine.url("قیمت گوشی سامسونگ", "fa")
            assertTrue("${engine.id}: پرسش باید کدگذاری شود", url.contains("%20") || !url.contains(" "))
            assertTrue("${engine.id}: هیچ کلیدی نباید لازم باشد", !url.contains("key=") && !url.contains("api_key"))
        }
    }

    @Test
    fun enginesAreTriedCheapestFirstAndPersianFirstWhenAsked() {
        val fa = Engines.ordered("fa")
        assertEquals(Engines.all.size, fa.size)
        // Persian-first: a Persian engine must come before the JavaScript-only ones.
        val firstPersian = fa.indexOfFirst { it.kind == EngineKind.PERSIAN }
        val firstHeavyForeign = fa.indexOfFirst { it.friction >= 3 && it.kind != EngineKind.PERSIAN }
        if (firstPersian >= 0 && firstHeavyForeign >= 0) {
            assertTrue("موتور فارسی باید جلوتر از موتورهای سنگین بیاید", firstPersian < firstHeavyForeign)
        }
        assertTrue("موتور سبک باید اول باشد", fa.first().friction <= 2)
    }

    @Test
    fun theCatalogueCoversTheKindsWeClaim() {
        assertTrue(Engines.byKind(EngineKind.WIKI).isNotEmpty())
        assertTrue(Engines.byKind(EngineKind.META).isNotEmpty())
        assertTrue(Engines.byKind(EngineKind.PERSIAN).isNotEmpty())
    }

    // ---------- 2. a blocked engine rests, and the search still gives a truthful report ----------

    private class BlockedTransport(private val status: Int) : HttpTransport {
        val asked = mutableListOf<String>()
        override fun get(request: HttpRequest): HttpResponse {
            asked.add(request.url)
            return HttpResponse(request.url, status, "", error = null)
        }
    }

    @Test
    fun whenEveryEngineBlocksTheSearchStopsAndSaysSo() {
        val transport = BlockedTransport(429)
        val core = SearchCore(transport, pacing = 0L, sleep = {}, defaultEngines = 4)
        val report = core.search("قیمت گوشی", "fa")
        assertTrue(report.hits.isEmpty())
        assertTrue("باید خطاها گزارش شوند، نه پنهان", report.failures.isNotEmpty())
        assertTrue("بیش از سقف تعیین‌شده موتور نپرسد", transport.asked.size <= 4)
    }

    @Test
    fun aCooledEngineIsSkippedThenRetriedAfterTheCooldown() {
        var clock = 0L
        val transport = BlockedTransport(429)
        // One engine only, so "was it asked again?" measures the cooldown and nothing else.
        val single = listOf(Engines.byId("mojeek")!!)
        val core = SearchCore(
            transport, engines = single, pacing = 0L, sleep = {}, defaultEngines = 1, clock = { clock }
        )
        core.search("پرسش", "fa")
        val afterFirst = transport.asked.size
        core.search("پرسش دوم", "fa")
        assertEquals("موتور در حال استراحت نباید دوباره پرسیده شود", afterFirst, transport.asked.size)
        clock += SearchCore.COOLDOWN_MS + 1
        core.search("پرسش سوم", "fa")
        assertTrue("پس از استراحت باید دوباره شانس داشته باشد", transport.asked.size > afterFirst)
    }

    @Test
    fun theSelfTestReportsEveryEngineHonestly() {
        val transport = BlockedTransport(403)
        val core = SearchCore(transport, pacing = 0L, sleep = {})
        val probes = core.probe("قیمت گوشی", "fa")
        assertEquals(Engines.all.size, probes.size)
        assertTrue(probes.none { it.ok })
        assertTrue(probes.all { it.detail.contains("403") })
    }

    // ---------- 3. a stored answer is still an answer (labelled) ----------

    private val pack = MarketPack(id = "p", name = "گوشی")

    private fun toolsWithKnowledge(): FakeTools {
        val core = SearchCore(BlockedTransport(429), engines = Engines.all, pacing = 0L, sleep = {})
        val fake = FakeTools(pack, core)
        fake.knowledge.add(
            KnowledgeItem(
                id = "k1",
                topic = "قیمت-گوشی-سامسونگ",
                question = "قیمت گوشی سامسونگ چقدر است",
                claim = "قیمت گوشی سامسونگ ۲۰ میلیون تومان است",
                category = "دانسته",
                attribute = "قیمت",
                numericValue = 20_000_000.0,
                unit = "تومان",
                url = "https://shop.example.org/samsung",
                domain = "shop.example.org",
                confidence = 0.7,
                createdAt = now,
                lastVerifiedAt = now,
                ttlDays = 45
            )
        )
        return fake
    }

    @Test
    fun whenTheEnginesFailTheStoredAnswerIsGivenWithItsDate() {
        val tools = toolsWithKnowledge()
        val outcome = Agent(tools).run("قیمت گوشی سامسونگ چقدر است")
        assertFalse("نباید فقط امتناع کند؛ دانش ذخیره‌شده دارد", outcome.answer.refused)
        assertTrue(
            "پاسخ باید صریح بگوید از دانش ذخیره‌شده است: ${outcome.answer.intent}",
            outcome.answer.intent == "knowledge.answer" || outcome.answer.intent == "knowledge.partial"
        )
        assertTrue("باید منبع داشته باشد", outcome.answer.citations.isNotEmpty())
        assertTrue(
            "باید تاریخ بازبینی را نشان دهد",
            outcome.answer.text.contains("/") || outcome.answer.bullets.any { it.contains("/") }
        )
    }

    @Test
    fun withNoStoredKnowledgeAndNoEngineItStillRefusesPrecisely() {
        val core = SearchCore(BlockedTransport(429), pacing = 0L, sleep = {})
        val tools = FakeTools(pack, core)
        val outcome = Agent(tools).run("قیمت گوشی سامسونگ چقدر است")
        assertTrue("بدون شاهد باید امتناع کند", outcome.answer.refused)
        assertTrue(
            "امتناع باید بگوید چه کم بود",
            outcome.answer.missing.any { it.contains("قیمت") }
        )
    }

    // ---------- 4. no guessing on yes/no questions ----------

    @Test
    fun aYesNoQuestionIsNotAnsweredByTopicalTextAlone() {
        val model = QuestionReader.read("آیا گوشی سامسونگ گارانتی دارد", "fa")
        assertEquals(ir.aai.hushyar.domain.AskType.BOOLEAN, model.ask)
        val topical = ExtractedPage(
            url = "https://example.org/samsung",
            title = "گوشی سامسونگ",
            text = "گوشی سامسونگ گلکسی اس ۲۴ با صفحه نمایش بزرگ و دوربین قوی در بازار موجود است."
        )
        assertNull(
            "جملهٔ بی‌موضع نباید «بله» بسازد",
            AnswerShapes.slot(model.ask, topical.text, pack, QuestionReader.attributeWords(model.attribute))
        )
        val plan = AnswerFocus.answer(model, listOf(topical), pack)
        assertFalse("پس پرسش باید بی‌پاسخ بماند", plan.answered)

        val explicit = ExtractedPage(
            url = "https://shop.example.org/warranty",
            title = "گارانتی",
            text = "این گوشی سامسونگ ۱۸ ماه گارانتی رسمی دارد."
        )
        val answered = AnswerFocus.answer(model, listOf(explicit), pack)
        assertTrue("جملهٔ دارای موضع باید پاسخ بدهد", answered.answered)
        assertEquals("بله", answered.direct)
    }

    @Test
    fun anOpinionQuestionNeedsARecommendationNotJustTheSubject() {
        val model = QuestionReader.read("بهترین گوشی سامسونگ کدام است", "fa")
        val plain = ExtractedPage("https://example.org/a", "a", "گوشی سامسونگ در چند مدل عرضه می‌شود و قیمت‌های مختلفی دارد.")
        assertFalse(AnswerFocus.answer(model, listOf(plain), pack).answered)
        val recommendation = ExtractedPage(
            "https://shop.example.org/b", "b",
            "بهترین گوشی سامسونگ برای کار روزمره مدل میان‌رده است."
        )
        assertTrue(AnswerFocus.answer(model, listOf(recommendation), pack).answered)
    }

    // ---------- 5. a stated goal is enough to plan and start ----------

    @Test
    fun aStatedGoalBecomesARealPlanWithoutAMarketPack() {
        val plan = PlanBuilder.buildFromGoal(
            listOf("خرید گوشی سامسونگ زیر ۲۰ میلیون"), emptyList(), "fa", now
        )
        assertTrue("پلان از هدف باید چند لایه داشته باشد", plan.layers.size >= 4)
        assertEquals("goal", plan.next(PlanBuilder.emptyProgress(plan))?.id)
        assertTrue("پرسش‌ها باید از دل هدف ساخته شوند", plan.layers.first().queries.all { it.contains("سامسونگ") })
        // The chain is locked: specs cannot run before the price layer.
        val progress = PlanBuilder.emptyProgress(plan) + mapOf("goal" to LayerStatus.DONE)
        assertEquals("goal-price", plan.next(progress)?.id)
    }

    @Test
    fun aGoalPlanIsMergedAheadOfThePackPlan() {
        val packPlan = PlanBuilder.build(
            MarketPack(id = "p2", name = "لپ‌تاپ", attributes = emptyList()),
            emptyList(),
            emptyList(),
            "fa",
            now
        )
        val goalPlan = PlanBuilder.buildFromGoal(listOf("خرید لپ‌تاپ تا ۴۰ میلیون"), emptyList(), "fa", now)
        val merged = PlanBuilder.merge(packPlan, goalPlan)
        assertEquals("goal", merged.layers.first().id)
        assertTrue("لایه‌های پک هم باید بمانند", merged.layers.any { it.id == "market" })
        assertEquals(merged.layers.size, merged.layers.map { it.id }.distinct().size)
    }

    @Test
    fun statingAGoalIsACommandAndStartsTheWork() {
        val core = SearchCore(BlockedTransport(429), pacing = 0L, sleep = {})
        val tools = FakeTools(pack, core)
        val outcome = Agent(tools).run("هدفم خرید گوشی سامسونگ زیر ۲۰ میلیون است")
        assertEquals("goal.set", outcome.answer.intent)
        assertEquals(1, tools.memories.size)
        assertEquals("هدف", tools.memories.first().category)
        assertNotNull(outcome.steps.firstOrNull { it.tool == "goal.set" })
        assertTrue(
            "پاسخ باید بگوید خودش شروع می‌کند",
            outcome.answer.text.contains("خودم") || outcome.answer.text.contains("خود")
        )
    }
}
