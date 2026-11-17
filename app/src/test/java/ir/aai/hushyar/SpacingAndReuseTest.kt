package ir.aai.hushyar

import ir.aai.hushyar.core.Text
import ir.aai.hushyar.domain.Agent
import ir.aai.hushyar.domain.CaseStore
import ir.aai.hushyar.domain.Engines
import ir.aai.hushyar.domain.FrameParser
import ir.aai.hushyar.domain.Interview
import ir.aai.hushyar.domain.KnowledgeBank
import ir.aai.hushyar.domain.KnowledgeItem
import ir.aai.hushyar.domain.QuestionReader
import ir.aai.hushyar.domain.SearchCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.11.1 — دو شکایت دقیق کاربر:
 *
 * 1. «وقتی یک پرسش کلمه‌به‌کلمه با فاصله پرسیده می‌شود، هوش چطور دریافتش می‌کند؟»
 * 2. «اگه کلمهٔ اول قیمت باشه، هوش سراغ کل جمله نمی‌ره؛ چون سؤال قبلی هم با قیمت شروع شده، هر دو
 *    را یکی می‌دونه و به جای جستجو در وب، فقط دادهٔ درونی رو می‌گرده.»
 *
 * تست‌ها هر دو را قفل می‌کنند: نوشتار درست خوانده می‌شود، و اشتراک در واژهٔ «قیمت» هرگز دلیل
 * یکی‌بودن دو پرسش نیست.
 */
class SpacingAndReuseTest {

    // ------------------------------------------------------------ نوشتار

    @Test
    fun letterByLetterWritingIsJoined() {
        assertEquals("قیمت", Text.normalize("ق ی م ت"))
        assertEquals("قیمت تیشرت", Text.normalize("ق ی م ت   ت ی ش ر ت"))
        assertEquals("قیمت شلوار نخی", Text.normalize("ق ی م ت  ش ل و ا ر  ن خ ی"))
    }

    @Test
    fun dottedAndDashedWritingIsJoined() {
        assertEquals("قیمت", Text.normalize("ق.ی.م.ت"))
        assertEquals("قیمت", Text.normalize("ق-ی-م-ت"))
        assertEquals("قیمت", Text.normalize("ق_ی_م_ت"))
    }

    @Test
    fun theConjunctionLetterIsNotGluedIntoAWord() {
        // «و» خودش یک حرف است؛ نباید داخل دستهٔ تک‌حرفی بلعیده شود.
        val out = Text.normalize("ق ی م ت  و  ت ی ش ر ت")
        assertTrue("قیمت باید ساخته شود: $out", out.contains("قیمت"))
        assertTrue("تیشرت باید ساخته شود: $out", out.contains("تیشرت"))
        assertTrue("«و» نباید به کلمه‌ها بچسبد: $out", out.split(" ").any { it == "و" })
    }

    @Test
    fun normalWritingIsLeftAlone() {
        assertEquals("قیمت ورق 2 میل امروز", Text.normalize("قیمت ورق ۲ میل امروز"))
        assertEquals("من فروشگاه تیشرت هستم", Text.normalize("من فروشگاه تیشرت هستم"))
    }

    @Test
    fun aSpacedQuestionIsStillFramedAsAProductQuestion() {
        val f = FrameParser.parse("ق ی م ت  ت ی ش ر ت  ن خ ی")!!
        assertEquals("tshirt", f.entity)
        assertTrue(f.entityKnown)
        assertEquals(ir.aai.hushyar.domain.Ask.PRICE, f.ask)
    }

    @Test
    fun theSearchQueryIsSentAsWholeWords() {
        val transport = FakeTransport(
            mapOf("https://www.bing.com" to (200 to "<html><body><a href=\"https://a.example/x\">تیشرت نخی</a></body></html>"))
        )
        val core = SearchCore(transport, pacing = 0L, sleep = {})
        core.search(Text.normalize("ق ی م ت  ت ی ش ر ت"), "fa")
        val asked = transport.requested.joinToString("\n")
        assertTrue("واژه‌ها باید کامل به موتور برسند", asked.contains("%D9%82%DB%8C%D9%85%D8%AA"))
        assertFalse("نباید حرف‌های جدا فرستاده شود", asked.contains("%D9%82%20%DB%8C%20%D9%85"))
    }

    // ------------------------------------------------------------ دانشِ همان‌موضوع

    private fun itemFor(claim: String, topic: String, price: Long? = null) = KnowledgeItem(
        id = "k-" + claim.hashCode(),
        topic = topic,
        question = "قیمت ${topic.replace('-', ' ')}",
        claim = claim,
        category = "قیمت",
        attribute = "price",
        numericValue = price?.toDouble(),
        unit = if (price != null) "تومان" else null,
        url = "https://poshak.example/1",
        domain = "poshak.example",
        confidence = 0.8,
        createdAt = SPACING_NOW,
        lastVerifiedAt = SPACING_NOW
    )

    @Test
    fun knowledgeAboutOneProductDoesNotCoverAnotherProduct() {
        val tishrt = itemFor("قیمت هر عدد تیشرت نخی ۱۸۰٬۰۰۰ تومان", "قیمت-تیشرت", 180_000)
        val answerable = KnowledgeBank.answerable("قیمت شلوار", listOf(tishrt), SPACING_NOW, 5)
        assertTrue("واژهٔ مشترک «قیمت» نباید کافی باشد", answerable.isEmpty())

        val coverage = KnowledgeBank.coverage("قیمت شلوار", listOf(tishrt), SPACING_NOW, null)
        assertEquals("پوشش باید صفر باشد، وگرنه بی‌جستجو جواب می‌دهد", 0.0, coverage, 0.0001)
    }

    @Test
    fun knowledgeAboutTheSameProductIsStillFound() {
        val tishrt = itemFor("قیمت هر عدد تیشرت نخی ۱۸۰٬۰۰۰ تومان", "قیمت-تیشرت", 180_000)
        assertTrue(KnowledgeBank.answerable("قیمت تیشرت نخی", listOf(tishrt), SPACING_NOW, 5).isNotEmpty())
        assertTrue(KnowledgeBank.coverage("قیمت تیشرت نخی", listOf(tishrt), SPACING_NOW, null) > 0.5)
    }

    @Test
    fun subjectWordsIgnoreTheAskWord() {
        val subject = KnowledgeBank.subjectTokensOf("قیمت تیشرت نخی سایز L")
        assertFalse("«قیمت» موضوع نیست", subject.contains("قیمت"))
        assertTrue(subject.contains("تیشرت"))
    }

    // ------------------------------------------------------------ سطح عامل

    private class SearchCapture {
        val asked = mutableListOf<String>()
    }

    private fun fakeTools(capture: SearchCapture, body: String): FakeTools {
        val transport = object : ir.aai.hushyar.core.HttpTransport {
            override fun get(request: ir.aai.hushyar.core.HttpRequest): ir.aai.hushyar.core.HttpResponse {
                capture.asked += request.url
                return if (request.url.contains("google.com/search")) {
                    ir.aai.hushyar.core.HttpResponse(request.url, 200, "<html><body>enablejs</body></html>")
                } else if (request.url.contains("rss")) {
                    ir.aai.hushyar.core.HttpResponse(request.url, 200, "<rss></rss>")
                } else {
                    ir.aai.hushyar.core.HttpResponse(request.url, 200, body)
                }
            }
        }
        val core = SearchCore(transport, engines = Engines.all, pacing = 0L, sleep = {}, maxCallMillis = 60_000)
        val pack = Interview.build(
            mapOf("market_name" to "فروشگاه پوشاک", "attributes" to "سایز، جنس", "currency_unit" to "تومان"),
            1_000L
        ).pack
        return FakeTools(pack, core)
    }

    @Test
    fun aSecondQuestionWithTheSameFirstWordStillSearchesTheWeb() {
        val capture = SearchCapture()
        val body = """
            <html><body>
            <a href="https://poshak.example/shalvar">قیمت شلوار جین عمده</a>
            <p>قیمت هر عدد شلوار جین در بازار عمده ۴۵۰٬۰۰۰ تومان است.</p>
            </body></html>
        """.trimIndent()
        val tools = fakeTools(capture, body)
        // دانشِ پرسش اول: فقط تی‌شرت
        tools.knowledge.add(itemFor("قیمت هر عدد تیشرت نخی ۱۸۰٬۰۰۰ تومان", "قیمت-تیشرت", 180_000))

        val agent = Agent(tools)
        val outcome = agent.run("قیمت شلوار", allowWeb = true)

        assertTrue("پرسش دوم باید واقعاً جستجو کند", capture.asked.isNotEmpty())
        val text = (listOf(outcome.answer.text) + outcome.answer.bullets).joinToString("\n")
        assertFalse("پاسخ تی‌شرت نباید به شلوار نسبت داده شود", text.contains("۱۸۰٬۰۰۰") && !text.contains("۴۵۰"))
        assertEquals("شمارش جستجوها در لاگ", true, tools.logs.any { it.kind == "web" })
    }

    @Test
    fun aSpacedCaseQuestionNeverReusesAnotherProductsFile() {
        val model = QuestionReader.read("ق ی م ت  ت ی ش ر ت", "fa")
        val cases = CaseStore.learn(
            emptyList(), model, Text.normalize("ق ی م ت  ت ی ش ر ت"),
            "قیمت هر عدد تیشرت ۱۸۰٬۰۰۰ تومان است.", null, SPACING_NOW
        )
        val other = QuestionReader.read("ق ی م ت  ش ل و ا ر", "fa")
        assertNull(
            "پروندهٔ تی‌شرت نباید به شلوار جواب بدهد",
            CaseStore.find(cases, other, Text.normalize("ق ی م ت  ش ل و ا ر"), SPACING_NOW)
        )
    }
}

private const val SPACING_NOW = 1_700_000_000_000L
