package ir.aai.hushyar

import ir.aai.hushyar.core.HttpRequest
import ir.aai.hushyar.core.HttpResponse
import ir.aai.hushyar.core.HttpTransport
import ir.aai.hushyar.domain.AnyDomainBrain
import ir.aai.hushyar.domain.Ask
import ir.aai.hushyar.domain.DomainPacks
import ir.aai.hushyar.domain.Engines
import ir.aai.hushyar.domain.DomainFact
import ir.aai.hushyar.domain.FactExtractor
import ir.aai.hushyar.domain.Facts
import ir.aai.hushyar.domain.FrameParser
import ir.aai.hushyar.domain.Interview
import ir.aai.hushyar.domain.LocalGeneration
import ir.aai.hushyar.domain.LocalGenerator
import ir.aai.hushyar.domain.ModelCatalog
import ir.aai.hushyar.domain.Rates
import ir.aai.hushyar.domain.SearchCore
import ir.aai.hushyar.domain.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.11 — «چیزهایی که در مورد ورق و تیرآهن گفتم فقط مثال بود!»
 *
 * این تست‌ها هیچ اسم فلزی ندارند: تی‌شرت، شلوار، پارچه. اگر این‌ها بگذرند، یعنی دانشِ برنامه به
 * دستهٔ کالا گره نخورده و هر بازار تازه‌ای با همان کد کار می‌کند.
 */
class AnyDomainTest {

    // ---------------------------------------------------------------- یکاها

    @Test
    fun unitsConvertInsideTheirOwnDimension() {
        assertEquals(12.0, Units.convert(1.0, "dozen", "count")!!, 0.001)
        assertEquals(2000.0, Units.convert(2.0, "kg", "g")!!, 0.001)
        assertEquals(1.5, Units.convert(1500.0, "mm", "m")!!, 0.001)
        assertNull("کیلو به متر تبدیل نمی‌شود", Units.convert(1.0, "kg", "m"))
        assertEquals("کیلوگرم", Units.of("kg")!!.fa)
    }

    // ---------------------------------------------------------------- چارچوب پرسش

    @Test
    fun aTshirtQuestionIsFramedWithoutAnyMetal() {
        val f = FrameParser.parse("قیمت تی‌شرت نخی سایز L دوجین")!!
        assertEquals("tshirt", f.entity)
        assertTrue(f.entityKnown)
        assertEquals(Ask.PRICE, f.ask)
        assertEquals("dozen", f.basis)
        assertTrue("جنس باید خوانده شود", f.qualifiers.any { it.value.contains("پنبه") })
        assertTrue("سایز باید خوانده شود", f.qualifiers.any { it.value == "L" })
    }

    @Test
    fun pantsWaistNumberIsReadAsSize() {
        val f = FrameParser.parse("قیمت شلوار جین سایز ۳۲")!!
        assertEquals("pants", f.entity)
        assertTrue(f.qualifiers.any { it.name == "size" })
    }

    @Test
    fun anUnknownProductIsNotClaimedAsKnown() {
        val f = FrameParser.parse("قیمت گوشی سامسونگ")!!
        assertFalse("کالای ناشناس نباید «شناخته‌شده» علامت بخورد", f.entityKnown)
    }

    @Test
    fun aPackOfFiveIsUnderstoodAsAPackBase() {
        val f = FrameParser.parse("قیمت بسته تی‌شرت")!!
        assertEquals("pack", f.basis)
        assertTrue(f.gaps.any { it.contains("پایه") } || f.basis != null)
    }

    // ---------------------------------------------------------------- دانش نوع‌دار

    @Test
    fun aFactSurvivesTheRoundTripThroughTheKnowledgeStore() {
        val now = 1_700_000_000_000L
        val fact = DomainFact(entity = "tshirt", attribute = "price", value = 180_000.0, unit = "toman", per = "count", at = now)
        val item = Facts.toItem(fact, now)
        assertEquals("fact:tshirt|price", item.topic)
        assertEquals("fact_price", item.attribute)
        val back = Facts.from(item)!!
        assertEquals(180_000.0, back.value, 0.001)
        assertEquals("toman", back.unit)
        assertEquals("count", back.per)
        assertTrue(back.fresh(now, 3))
        assertFalse(back.fresh(now + 5L * 86_400_000L, 3))
    }

    // ---------------------------------------------------------------- تبدیل نرخ

    @Test
    fun aPerPiecePriceBecomesAPerDozenPrice() {
        val (value, formula) = Rates.convert(180_000.0, "count", "dozen", null, null)!!
        assertEquals(2_160_000.0, value, 0.5)
        assertTrue(formula.contains("۱۲"))
    }

    @Test
    fun aPackPriceNeedsTheLearnedPackSize() {
        assertNull("بدون دانستن تعداد بسته، تبدیل حدس زده نمی‌شود", Rates.convert(500_000.0, "pack", "count", null, null))
        val packSize = DomainFact(entity = "tshirt", attribute = "pack_size", value = 5.0, unit = "count")
        val (value, _) = Rates.convert(500_000.0, "pack", "count", null, packSize)!!
        assertEquals(100_000.0, value, 0.5)
    }

    @Test
    fun aWeightFactBridgesPieceAndKilogram() {
        val weight = DomainFact(entity = "tshirt", attribute = "weight", value = 0.2, unit = "kg")
        val (perKg, _) = Rates.convert(180_000.0, "count", "kg", weight, null)!!
        assertEquals(900_000.0, perKg, 1.0)
    }

    // ---------------------------------------------------------------- استخراج

    private fun hit(title: String, snippet: String, url: String = "https://shop.example/p/1") =
        ir.aai.hushyar.domain.WebHit(url, title, snippet, "bing", 1)

    @Test
    fun aPriceInACurrencyIsExtractedWithItsBasis() {
        val frame = FrameParser.parse("قیمت تی‌شرت نخی")!!
        val found = FactExtractor.read(
            listOf(hit("قیمت تی‌شرت نخی", "قیمت هر عدد تی‌شرت نخی ۱۸۰٬۰۰۰ تومان است")),
            frame, 1_700_000_000_000L
        )
        assertTrue(found.isNotEmpty())
        val fact = found.first().second
        assertEquals("price", fact.attribute)
        assertEquals(180_000.0, fact.value, 0.5)
        assertEquals("count", fact.per)
        assertEquals("shop.example", fact.domain)
    }

    @Test
    fun aNumberWithoutCurrencyIsNotAPrice() {
        val frame = FrameParser.parse("قیمت تی‌شرت")!!
        val found = FactExtractor.read(listOf(hit("تی‌شرت", "تی‌شرت ۱۸۰ عدد در انبار موجود است")), frame, 0L)
        assertTrue("بدون یکای پول، قیمت ساخته نمی‌شود", found.none { it.second.attribute == "price" })
    }

    @Test
    fun aResultThatDoesNotMentionTheProductIsIgnored() {
        val frame = FrameParser.parse("قیمت تی‌شرت")!!
        val found = FactExtractor.read(listOf(hit("قیمت کفش", "قیمت هر عدد کفش ۹۰۰٬۰۰۰ تومان است")), frame, 0L)
        assertTrue(found.isEmpty())
    }

    /** مدلِ ساختگی: خط درست می‌دهد، ولی عددی که در متن نیست نباید پذیرفته شود. */
    private class FakeModel(private val reply: String) : LocalGenerator {
        override val ready: Boolean = true
        var calls = 0
        override fun generate(prompt: String, maxTokens: Int): LocalGeneration {
            calls++
            return LocalGeneration(text = reply, tokens = reply.length / 4, millis = 10)
        }
        override fun cancel() {}
    }

    @Test
    fun theModelCanProposeAFactThatTheTextSupports() {
        val frame = FrameParser.parse("قیمت تی‌شرت")!!
        val model = FakeModel("قیمت=۱۸۰۰۰۰ تومان")
        val found = FactExtractor.withModel(
            listOf(hit("قیمت تی‌شرت", "قیمت هر عدد تی‌شرت ۱۸۰۰۰۰ تومان است")),
            frame, model, ModelCatalog.recommended, 0L
        )
        assertEquals("مدل باید واقعاً صدا زده شود", 1, model.calls)
        assertTrue(found.isNotEmpty())
        assertEquals(180_000.0, found.first().second.value, 0.5)
    }

    @Test
    fun theModelCannotInventANumberThatIsNotInTheText() {
        val frame = FrameParser.parse("قیمت تی‌شرت")!!
        val model = FakeModel("قیمت=۹۹۹۹۹۹ تومان")
        val found = FactExtractor.withModel(
            listOf(hit("قیمت تی‌شرت", "قیمت هر عدد تی‌شرت ۱۸۰۰۰۰ تومان است")),
            frame, model, ModelCatalog.recommended, 0L
        )
        assertTrue("عددی که در منبع نیست، حتی از مدل پذیرفته نمی‌شود", found.isEmpty())
    }

    @Test
    fun aMalformedModelLineIsDropped() {
        assertNull(FactExtractor.parseFactLine("قیمت حدوداً خوب است"))
        assertNull(FactExtractor.parseFactLine("سلام=۱ تومان"))
        assertNotNull(FactExtractor.parseFactLine("قیمت=۱۸۰۰۰۰ تومان"))
    }

    // ---------------------------------------------------------------- مسیر کامل

    private class GarmentTransport(val body: String, val fail: Boolean = false) : HttpTransport {
        val asked = mutableListOf<String>()
        override fun get(request: HttpRequest): HttpResponse {
            asked += request.url
            if (fail) return HttpResponse(request.url, 503, "", error = "down")
            return if (request.url.contains("google.com/search")) {
                HttpResponse(request.url, 200, "<html><body>enablejs</body></html>")
            } else if (request.url.contains("rss")) {
                HttpResponse(request.url, 200, "<rss></rss>")
            } else {
                HttpResponse(request.url, 200, body)
            }
        }
    }

    private fun tools(transport: HttpTransport): FakeTools {
        val core = SearchCore(transport, engines = Engines.all, pacing = 0L, sleep = {}, maxCallMillis = 60_000)
        val pack = Interview.build(
            mapOf("market_name" to "فروشگاه پوشاک", "attributes" to "سایز، رنگ، جنس", "currency_unit" to "تومان"),
            1_000L
        ).pack
        return FakeTools(pack, core)
    }

    @Test
    fun aGarmentPriceIsLearnedThenAnsweredOfflineWithConversion() {
        val body = """
            <html><body>
            <a href="https://poshak.example/tshirt-cotton">قیمت تی‌شرت نخی عمده</a>
            <p>قیمت هر عدد تی‌شرت نخی سایز L در بازار عمده ۱۸۰٬۰۰۰ تومان است.</p>
            </body></html>
        """.trimIndent()
        val transport = GarmentTransport(body)
        val fake = tools(transport)
        val brain = AnyDomainBrain(fake)

        val first = brain.answer("قیمت تی‌شرت نخی سایز L دوجین", "fa") { _, _, _, _ -> }!!
        assertEquals("domain.price", first.answer.intent)
        val text = (listOf(first.answer.text) + first.answer.bullets).joinToString("\n")
        assertTrue("قیمت دوجین باید محاسبه شود (۱۸۰٬۰۰۰ × ۱۲)", text.contains("۲٬۱۶۰٬۰۰۰") || text.contains("2,160,000"))
        assertTrue("منبع باید گفته شود", text.contains("poshak.example"))
        assertTrue("باید بگوید چه یاد گرفت", first.answer.learned.isNotEmpty())
        assertTrue("دانش نوع‌دار ذخیره شده باشد", fake.knowledge.any { it.attribute == "fact_price" })

        val afterFirst = transport.asked.size
        val second = brain.answer("قیمت تی‌شرت دوجین", "fa") { _, _, _, _ -> }!!
        assertEquals("سؤال دوم نباید شبکه را لمس کند", afterFirst, transport.asked.size)
        assertTrue(
            (listOf(second.answer.text) + second.answer.bullets).joinToString("\n").contains("۲٬۱۶۰٬۰۰۰")
        )
    }

    @Test
    fun withNoPriceItSaysSoAndAsksOnlyWhatItNeeds() {
        val fake = tools(GarmentTransport("", fail = true))
        val answer = AnyDomainBrain(fake).answer("قیمت شلوار جین سایز ۳۲", "fa") { _, _, _, _ -> }!!.answer
        val text = (listOf(answer.text) + answer.bullets).joinToString("\n")
        assertTrue("پاسخ باید بگوید پیدا نکرد", text.contains("پیدا نکردم") || text.contains("پاسخ قطعی ندارم"))
        assertTrue("نباید عدد جعلی داشته باشد", !text.contains("تومان/") || text.contains("لازم دارم"))
        assertTrue("باید بگوید چه چیزی لازم دارد", answer.missing.isNotEmpty() || text.contains("لازم دارم"))
    }

    @Test
    fun aDefinitionQuestionIsAnsweredFromThePackWithNoNetwork() {
        val transport = GarmentTransport("")
        val fake = tools(transport)
        val answer = AnyDomainBrain(fake).answer("تی‌شرت چیه", "fa") { _, _, _, _ -> }!!.answer
        assertEquals("domain.definition", answer.intent)
        assertEquals(0, transport.asked.size)
        assertTrue(answer.bullets.joinToString("\n").contains("سایز"))
    }

    @Test
    fun aBusinessDescriptionBecomesAPack() {
        val pack = DomainPacks.fromDescription("من فروشگاه تی‌شرت و شلوار هستم، سایز و رنگ و جنس مهم است")!!
        assertTrue(pack.entities.isNotEmpty())
        assertTrue("ویژگی‌های نام‌برده باید در بسته باشند", pack.entities.first().attributes.any { it.id == "size" })
        assertTrue(pack.entities.first().attributes.any { it.id == "color" })
    }

    @Test
    fun anUnknownProductNeverStealsTheGeneralPath() {
        val fake = tools(GarmentTransport(""))
        assertNull("گوشی در هیچ بسته‌ای نیست، پس این مغز دخالت نمی‌کند", AnyDomainBrain(fake).answer("قیمت گوشی سامسونگ", "fa") { _, _, _, _ -> })
    }
}
