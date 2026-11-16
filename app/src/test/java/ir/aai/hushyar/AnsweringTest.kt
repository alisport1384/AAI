package ir.aai.hushyar

import ir.aai.hushyar.core.Text
import ir.aai.hushyar.domain.AgentTools as AgentTools_
import ir.aai.hushyar.domain.Catalog
import ir.aai.hushyar.domain.KnowledgeDecision
import ir.aai.hushyar.domain.KnowledgeItem
import ir.aai.hushyar.domain.KnowledgeResult
import ir.aai.hushyar.domain.Listing
import ir.aai.hushyar.domain.LogEvent
import ir.aai.hushyar.domain.MemoryHit
import ir.aai.hushyar.domain.MemoryItem
import ir.aai.hushyar.domain.AnswerFocus
import ir.aai.hushyar.domain.AnswerShapes
import ir.aai.hushyar.domain.AskType
import ir.aai.hushyar.domain.DeepResearcher
import ir.aai.hushyar.domain.ExtractedPage
import ir.aai.hushyar.domain.Interview
import ir.aai.hushyar.domain.MarketPack
import ir.aai.hushyar.domain.QuestionReader
import ir.aai.hushyar.domain.Agent
import ir.aai.hushyar.domain.ResearchBudget
import ir.aai.hushyar.domain.SearchReport
import ir.aai.hushyar.domain.KnowledgeBank
import ir.aai.hushyar.domain.WebHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The user's report was: "what it searches is not the answer to the question, and it only fills its
 * own data — wrongly". These tests hold the fix to that report: a question is answered only when the
 * answer's *shape* is present, sentences about other things are never the answer, and when the
 * answer is missing the assistant says exactly what is missing.
 */
class AnsweringTest {

    private val now = 1_700_000_000_000L

    private fun page(url: String, body: String): ExtractedPage =
        ExtractedPage(url = url, title = "بررسی", text = body, trust = "untrusted")

    private fun pack(): MarketPack = Interview.build(
        mapOf(
            "market_name" to "بازار پاسخ",
            "attributes" to "حافظه، رم، گارانتی",
            "currency_unit" to "تومان"
        ),
        1_000L
    ).pack

    /** Runs the real research loop over a fixed set of pages; `perQuery` picks what each round sees. */
    private fun research(
        question: String,
        pages: List<ExtractedPage>,
        budget: ResearchBudget = ResearchBudget(3, 3, 8, 60_000L, 0.70)
    ) = DeepResearcher(
        search = { q, _ ->
            SearchReport(q, pages.map { WebHit(it.url, it.title, "خلاصه", "google", 1) }, mapOf("google" to pages.size))
        },
        fetch = { url, _ -> pages.firstOrNull { it.url == url } },
        budget = budget,
        now = { now }
    ).research(question, "fa", pack())

    // ---------- 1. reading the question ----------

    @Test
    fun readsWhatEachQuestionIsAskingFor() {
        val cases = listOf(
            "قیمت گوشی سامسونگ گلکسی s24 چقدر است؟" to AskType.PRICE,
            "هزینهٔ تعمیر لپ‌تاپ لنوو چقدر است؟" to AskType.PRICE,
            "باتری این گوشی چند میلی‌آمپر است؟" to AskType.AMOUNT,
            "گلکسی s24 چه زمانی معرفی شد؟" to AskType.DATE,
            "هوشیار چیست؟" to AskType.DEFINITION,
            "چرا گوشی هنگام بازی داغ می‌کند؟" to AskType.REASON,
            "چگونه حافظهٔ گوشی را آزاد کنم؟" to AskType.METHOD,
            "چه کسی گلکسی را ساخت؟" to AskType.PERSON,
            "گوشی در کدام کشور ساخته می‌شود؟" to AskType.PLACE,
            "فرق گلکسی s24 با s23 چیست؟" to AskType.COMPARISON,
            "بهترین گوشی زیر ۲۰ میلیون تومان کدام است؟" to AskType.OPINION,
            "آیا گلکسی s24 گارانتی دارد؟" to AskType.BOOLEAN
        )
        for ((question, expected) in cases) {
            assertEquals("پرسش: $question", expected, QuestionReader.read(question).ask)
        }
    }

    @Test
    fun subjectIsTheThingAskedAboutNotTheQuestionWords() {
        val model = QuestionReader.read("قیمت گوشی سامسونگ گلکسی s24 چقدر است؟")
        assertEquals(listOf("گوشی", "سامسونگ", "گلکسی", "s24"), model.subject)
        assertEquals("قیمت", model.attribute)
        assertTrue(model.numeric)
    }

    // ---------- 2. the reported failure: topical text instead of the answer ----------

    @Test
    fun priceQuestionGetsThePriceAndNotTopicalText() {
        val pages = listOf(
            page(
                "https://b.example/2",
                "گوشی سامسونگ گلکسی s24 صفحهٔ نمایش بزرگ و باتری خوبی دارد و برای کارهای روزمره مناسب است."
            ),
            page(
                "https://a.example/1",
                "قیمت گوشی سامسونگ گلکسی s24 در بازار امروز حدود ۵۰ میلیون تومان است و گارانتی رسمی دارد."
            )
        )
        val outcome = research("قیمت گوشی سامسونگ گلکسی s24 چقدر است؟", pages)
        val plan = assertNotNull(outcome.answer).let { outcome.answer!! }
        assertTrue(plan.answered)
        assertTrue("direct=${plan.direct}", plan.direct!!.contains("۵۰ میلیون تومان"))
        assertEquals("a.example", KnowledgeBank.draftHost(plan.source!!.page.url))
    }

    @Test
    fun aPageAboutAnotherProductIsNeverTheAnswer() {
        val pages = listOf(
            page(
                "https://laptop.example/1",
                "قیمت لپ‌تاپ لنوو ایدیاپد در بازار امروز ۳۵ میلیون تومان است و برای دانشجویان مناسب است."
            )
        )
        val outcome = research("قیمت گوشی سامسونگ گلکسی s24 چقدر است؟", pages)
        val plan = outcome.answer!!
        assertFalse("پاسخِ صفحهٔ نامرتبط پذیرفته شد: ${plan.direct}", plan.answered)
        assertTrue(plan.missing.any { it.contains("قیمت") })
    }

    @Test
    fun aTopicalPageWithoutTheAnswerIsRefusedNotAnswered() {
        // The trap: maximum keyword overlap with the question, still no price in it.
        val pages = listOf(
            page(
                "https://seo.example/1",
                "قیمت گوشی گلکسی s24 مقایسهٔ قیمت گوشی سامسونگ گلکسی s24 بررسی قیمت گوشی گلکسی s24 در فروشگاه‌ها.\n" +
                    "در این صفحه فهرست فروشگاه‌هایی آمده که گوشی سامسونگ گلکسی s24 را عرضه می‌کنند و راهنمای انتخاب فروشنده است.\n" +
                    "کاربران می‌توانند نظر خود را دربارهٔ گوشی گلکسی s24 بنویسند و تجربهٔ خریدشان از فروشگاه‌ها را با دیگران در میان بگذارند."
            )
        )
        val outcome = research("قیمت گوشی سامسونگ گلکسی s24 چقدر است؟", pages)
        val plan = outcome.answer!!
        assertFalse(plan.answered)
        // …but the topic *was* found, and the refusal says so instead of pretending nothing was found.
        assertTrue(plan.context.isNotEmpty())
        assertTrue(plan.missing.any { it.contains("اما پاسخ") })
    }

    // ---------- 3. the other answering shapes ----------

    @Test
    fun dateAmountDefinitionReasonAndBooleanAreAnswered() {
        val date = research(
            "گوشی سامسونگ گلکسی s24 چه زمانی معرفی شد؟",
            listOf(page("https://a.example/1", "گوشی سامسونگ گلکسی s24 در ژانویهٔ ۲۰۲۴ معرفی شد و از بهمن در بازار ایران بود."))
        )
        assertTrue(date.answer!!.direct!!.contains("۲۰۲۴"))

        val amount = research(
            "باتری گوشی گلکسی s24 چند میلی‌آمپر است؟",
            listOf(page("https://a.example/1", "باتری گوشی گلکسی s24 ظرفیت ۴۰۰۰ میلی‌آمپر ساعت دارد و یک روز دوام می‌آورد."))
        )
        assertTrue(amount.answer!!.direct!!.contains("۴۰۰۰"))

        val definition = research(
            "هوشیار چیست؟",
            listOf(page("https://a.example/1", "هوشیار یک دستیار محلی است که آگهی‌ها را تحلیل می‌کند و بدون ابر کار می‌کند."))
        )
        assertTrue(definition.answer!!.direct!!.contains("دستیار"))

        val reason = research(
            "چرا گوشی هنگام بازی داغ می‌کند؟",
            listOf(page("https://a.example/1", "گوشی هنگام بازی داغ می‌کند چون پردازنده زیر بار سنگین گرمای زیادی تولید می‌کند."))
        )
        assertTrue(reason.answer!!.answered)
        assertTrue(reason.answer!!.direct!!.contains("چون"))

        val yesNo = research(
            "آیا گوشی گلکسی s24 گارانتی دارد؟",
            listOf(page("https://a.example/1", "گوشی گلکسی s24 گارانتی رسمی هجده ماهه دارد و خدمات پس از فروش آن سراسری است."))
        )
        assertTrue(yesNo.answer!!.direct!!.contains("بله"))
    }

    @Test
    fun aDateIsNotReadOutOfAnUnrelatedFourDigitNumber() {
        // "۴۰۰۰ میلی‌آمپر" must not be mistaken for a year when a date is asked.
        assertTrue(AnswerShapes.dateOf("باتری آن ۴۰۰۰ میلی‌آمپر ساعت است") == null)
        assertNotNull(AnswerShapes.dateOf("این مدل در ۱۴۰۳ عرضه شد"))
    }

    // ---------- 4. disagreement between sources ----------

    @Test
    fun disagreeingSourcesAreReportedNotHidden() {
        val pages = listOf(
            page("https://a.example/1", "قیمت گوشی سامسونگ گلکسی s24 در فروشگاه اول ۵۰ میلیون تومان است."),
            page("https://b.example/2", "قیمت گوشی سامسونگ گلکسی s24 در فروشگاه دوم ۶۵ میلیون تومان است.")
        )
        val plan = research("قیمت گوشی سامسونگ گلکسی s24 چقدر است؟", pages).answer!!
        assertTrue(plan.answered)
        assertTrue("اختلاف منابع گزارش نشد", plan.disagreement.isNotEmpty())
        assertTrue(plan.disagreement.any { it.first.contains("۶۵") || it.first.contains("۵۰") })
    }

    // ---------- 5. drift control on rewrites ----------

    @Test
    fun everyRewriteKeepsTheSubject() {
        val pages = listOf(
            page("https://a.example/1", "گوشی سامسونگ گلکسی s24 صفحهٔ نمایش روشنی دارد و برای کارهای روزمره خوب است.")
        )
        val outcome = research("قیمت گوشی سامسونگ گلکسی s24 چقدر است؟", pages)
        assertTrue(outcome.queries.size >= 1)
        val model = QuestionReader.read("قیمت گوشی سامسونگ گلکسی s24 چقدر است؟")
        for (query in outcome.queries) {
            assertTrue("پرسش منحرف شد: $query", model.keepsAnchor(query))
        }
    }

    // ---------- 6. what may be stored and answered from ----------

    @Test
    fun storedClaimsThatDoNotAnswerTheQuestionAreNotUsedForAnswering() {
        val model = QuestionReader.read("قیمت گوشی سامسونگ گلکسی s24 چقدر است؟")
        val topical = "گوشی سامسونگ گلکسی s24 صفحهٔ نمایش بزرگ و باتری خوبی دارد"
        val answering = "قیمت گوشی سامسونگ گلکسی s24 حدود ۵۰ میلیون تومان است"
        assertFalse(AnswerShapes.answers(AskType.PRICE, topical, pack()))
        assertTrue(AnswerShapes.answers(AskType.PRICE, answering, pack()))

        // A knowledge base made only of topical claims must produce "no answer", not a guess.
        val plan = AnswerFocus.answer(model, listOf(page("https://a.example/1", topical)), pack())
        assertFalse(plan.answered)
    }

    // ---------- 7. measured accuracy over a golden set ----------

    private data class Golden(
        val question: String,
        val pages: List<ExtractedPage>,
        val expectAnswered: Boolean,
        val mustContain: String? = null
    )

    @Test
    fun goldenSetAnswerAccuracy() {
        val golden = listOf(
            Golden(
                "قیمت گوشی سامسونگ گلکسی s24 چقدر است؟",
                listOf(page("https://a.example/1", "قیمت گوشی سامسونگ گلکسی s24 حدود ۵۰ میلیون تومان است.")),
                true, "۵۰ میلیون تومان"
            ),
            Golden(
                "قیمت گوشی سامسونگ گلکسی s24 چقدر است؟",
                listOf(page("https://l.example/1", "قیمت لپ‌تاپ لنوو ۳۵ میلیون تومان است.")),
                false, null
            ),
            Golden(
                "قیمت گوشی سامسونگ گلکسی s24 چقدر است؟",
                listOf(page("https://a.example/1", "گوشی سامسونگ گلکسی s24 دوربین ۵۰ مگاپیکسلی دارد.")),
                false, null
            ),
            Golden(
                "باتری گوشی گلکسی s24 چند میلی‌آمپر است؟",
                listOf(page("https://a.example/1", "ظرفیت باتری گوشی گلکسی s24 برابر ۴۰۰۰ میلی‌آمپر ساعت است.")),
                true, "۴۰۰۰"
            ),
            Golden(
                "گوشی گلکسی s24 چه زمانی معرفی شد؟",
                listOf(page("https://a.example/1", "این گوشی در ژانویهٔ ۲۰۲۴ معرفی شد.")),
                true, "۲۰۲۴"
            ),
            Golden(
                "هوشیار چیست؟",
                listOf(page("https://a.example/1", "هوشیار یک دستیار محلی برای تحلیل بازار است.")),
                true, "دستیار"
            ),
            Golden(
                "چه کسی گلکسی را ساخت؟",
                listOf(page("https://a.example/1", "سازندهٔ این گوشی شرکت سامسونگ است و در کره تولید می‌شود.")),
                true, "سامسونگ"
            ),
            Golden(
                "فرق گلکسی s24 با s23 چیست؟",
                listOf(page("https://a.example/1", "گلکسی s24 در مقایسه با نسل قبل باتری بیشتری دارد و سریع‌تر است.")),
                true, "مقایسه"
            ),
            Golden(
                "چرا گوشی هنگام بازی داغ می‌کند؟",
                listOf(page("https://a.example/1", "گوشی هنگام بازی داغ می‌کند چون پردازنده زیر بار سنگین گرم می‌شود.")),
                true, "چون"
            ),
            Golden(
                "قیمت گوشی سامسونگ گلکسی s24 چقدر است؟",
                listOf(page("https://seo.example/1", "قیمت گوشی گلکسی s24 بررسی قیمت گوشی سامسونگ گلکسی s24 در بازار.")),
                false, null
            )
        )

        var correct = 0
        val failures = mutableListOf<String>()
        for (case in golden) {
            val plan = research(case.question, case.pages).answer!!
            val ok = if (case.expectAnswered) {
                plan.answered && case.mustContain != null && plan.direct!!.contains(case.mustContain)
            } else {
                !plan.answered
            }
            if (ok) correct++ else failures.add("«${case.question}» → direct=${plan.direct} missing=${plan.missing}")
        }
        println("golden accuracy: $correct/${golden.size}")
        assertTrue("شکست‌ها: ${failures.joinToString(" | ")}", correct >= golden.size - 0)
    }

    // ---------- 8. the whole agent path, including the wording ----------

    @Test
    fun agentSaysTheAnswerFirstAndTheSourceWithIt() {
        val pack = pack()
        val pages = listOf(
            page(
                "https://a.example/1",
                "قیمت گوشی سامسونگ گلکسی s24 در بازار امروز ۵۰ میلیون تومان است و گارانتی رسمی دارد."
            )
        )
        val tools = AnswerTools(pack, pages)
        val outcome = Agent(tools).run("قیمت گوشی سامسونگ گلکسی s24 چقدر است؟")
        assertFalse(outcome.answer.refused)
        assertTrue("متن پاسخ: ${outcome.answer.text}", outcome.answer.text.contains("پاسخ"))
        assertTrue(outcome.answer.text.contains("۵۰ میلیون تومان"))
        assertTrue(outcome.answer.text.contains("a.example"))
        // the number is not left without a source
        assertTrue(outcome.answer.citations.isNotEmpty())
        // and the number is not double-counted in a bullet without evidence
        assertTrue(outcome.answer.bullets.any { it.contains("قیمت") })
    }

    @Test
    fun agentRefusesWithTheMissingShapeWhenTheAnswerIsAbsent() {
        val pack = pack()
        val pages = listOf(
            page(
                "https://a.example/1",
                "گوشی سامسونگ گلکسی s24 صفحهٔ نمایش بزرگ و باتری خوبی دارد و برای کارهای روزمره مناسب است."
            )
        )
        val tools = AnswerTools(pack, pages)
        val outcome = Agent(tools).run("قیمت گوشی سامسونگ گلکسی s24 چقدر است؟")
        assertTrue(outcome.answer.refused)
        val text = outcome.answer.text + " " + outcome.answer.missing.joinToString(" ")
        assertTrue("متن امتناع: $text", text.contains("قیمت"))
        assertTrue(text.contains("موضوع پرسش"))
    }

    @Test
    fun messyRealWorldWordingStillAnswers() {
        // Prices written with thousands separators and no verb.
        val separated = research(
            "قیمت گوشی گلکسی s24 چقدره؟",
            listOf(page("https://shop.example/1", "گلکسی s24 — قیمت: ۵۰,۰۰۰,۰۰۰ تومان. موجود در انبار و ارسال فوری به سراسر کشور."))
        )
        assertTrue("direct=${separated.answer!!.direct}", separated.answer!!.answered)
        assertTrue(separated.answer!!.direct!!.contains("تومان"))

        // The price sits in the second paragraph, after an unrelated introduction.
        val laterInPage = research(
            "قیمت گوشی گلکسی s24 چقدر است؟",
            listOf(page(
                "https://blog.example/1",
                "گلکسی s24 یکی از پرفروش‌ترین گوشی‌های بازار است و کاربران زیادی آن را می‌شناسند.\n" +
                    "این گوشی حافظهٔ ۲۵۶ گیگابایتی دارد و دوربین آن برای عکاسی روزمره کافی است.\n" +
                    "قیمت گوشی گلکسی s24 در فروشگاه‌های رسمی ۵۲ میلیون تومان اعلام شده است."
            ))
        )
        assertTrue(laterInPage.answer!!.direct!!.contains("۵۲ میلیون تومان"))
    }

    @Test
    fun questionMarkAndZwnjDoNotBreakTheReading() {
        val model = QuestionReader.read("قيمت گوشي سامسونگ گلکسي s24 چند است؟")
        assertEquals(AskType.PRICE, model.ask)
        assertTrue(model.subject.containsAll(listOf("گوشی", "سامسونگ")))
        assertEquals(Text.normalize("هوشیار"), "هوشیار")
    }
}

/**
 * Minimal in-memory agent tools for the answering tests: a fixed "web" of pages, a knowledge store
 * that uses the real merge policy, and nothing else. No Android, no network, no HTTP.
 */
class AnswerTools(
    private val pack: MarketPack,
    private val pages: List<ExtractedPage>,
    var budget: ResearchBudget = ResearchBudget(3, 3, 8, 60_000L, 0.70)
) : AgentTools_ {

    private var clock = 1_700_000_000_000L
    private val knowledge = mutableListOf<KnowledgeItem>()
    private val memories = mutableListOf<MemoryItem>()
    private val logs = mutableListOf<LogEvent>()
    val queries = mutableListOf<String>()

    override fun now(): Long = clock
    override fun catalog(): Catalog = Catalog(pack, emptyList(), emptyList())
    override fun researchBudget(): ResearchBudget = budget

    override fun webSearch(query: String, langTag: String): SearchReport {
        queries.add(query)
        return SearchReport(query, pages.map { WebHit(it.url, it.title, "خلاصه", "google", 1) }, mapOf("google" to pages.size))
    }

    override fun fetchPage(url: String, langTag: String): ExtractedPage? = pages.firstOrNull { it.url == url }

    override fun memoryWrite(kind: String, text: String, source: String): MemoryItem {
        val item = MemoryItem("m" + (memories.size + 1), kind, text, clock)
        memories.add(item)
        return item
    }

    override fun memorySearch(query: String, limit: Int): List<MemoryHit> = emptyList()
    override fun memoryAll(limit: Int): List<MemoryItem> = memories.take(limit)
    override fun memoryDelete(id: String): Boolean = memories.removeAll { it.id == id }
    override fun memoryTouch(ids: List<String>) = Unit

    override fun knowledgeAnswerable(question: String, limit: Int): List<KnowledgeItem> =
        KnowledgeBank.answerable(question, knowledge, clock, limit)

    override fun knowledgeAll(limit: Int): List<KnowledgeItem> = knowledge.take(limit)
    override fun knowledgeStale(limit: Int): List<KnowledgeItem> = KnowledgeBank.staleItems(knowledge, clock).take(limit)

    override fun knowledgeStore(item: KnowledgeItem): KnowledgeResult {
        val merge = KnowledgeBank.merge(knowledge, item)
        if (merge.decision != KnowledgeDecision.IGNORE) {
            knowledge.clear()
            knowledge.addAll(merge.items)
        }
        return KnowledgeResult(merge.touchedId ?: item.id, merge.decision, merge.decision.name)
    }

    override fun knowledgeDelete(id: String): Boolean = knowledge.removeAll { it.id == id }
    override fun insertListings(listings: List<Listing>): Int = 0

    override fun logAppend(kind: String, actor: String, message: String, detail: String) {
        logs.add(LogEvent(logs.size + 1L, clock, kind, actor, message, detail))
    }

    override fun logRecent(limit: Int): List<LogEvent> = logs.takeLast(limit)
    override fun backupNow(): String = ""
    override fun exportLogMarkdown(): String = "# log\n"
}
