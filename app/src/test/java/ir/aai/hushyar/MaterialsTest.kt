package ir.aai.hushyar

import ir.aai.hushyar.core.HttpRequest
import ir.aai.hushyar.core.HttpResponse
import ir.aai.hushyar.core.HttpTransport
import ir.aai.hushyar.domain.Engines
import ir.aai.hushyar.domain.MaterialBrain
import ir.aai.hushyar.domain.MaterialAsk
import ir.aai.hushyar.domain.Materials
import ir.aai.hushyar.domain.PriceBasis
import ir.aai.hushyar.domain.ProductKind
import ir.aai.hushyar.domain.SearchCore
import ir.aai.hushyar.domain.SteelMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.10 — «ورق ۲ میل قیمت» و «تیرآهن ۱۸ و ۱۶ قیمت» باید بدون اینکه کاربر بنویسد «تحقیق کن»
 * فهمیده شوند، وزنشان محاسبه شود، قیمتشان یا از دانش قبلی یا از جستجو بیاید و **یکا تبدیل شود**.
 */
class MaterialsTest {

    // ---------------------------------------------------------------- فهم پرسش

    @Test
    fun vagueSheetQuestionIsUnderstoodNotRejected() {
        val r = Materials.read("ورق ۲ میل قیمت")!!
        assertEquals(ProductKind.SHEET, r.product.kind)
        assertEquals(listOf(2.0), r.sizes)
        assertEquals(MaterialAsk.PRICE, r.ask)
        assertNull("پرسش پایهٔ قیمت ندارد، پس هیچ پایه‌ای نباید حدس زده شود", r.basis)
        assertTrue("باید توضیح دهد «میل» یعنی میلی‌متر", r.lines.any { it.contains("میلی") || it.contains("میل") })
        assertTrue("باید بگوید ورق چیست", r.lines.first().contains("ورق"))
        assertTrue("باید فرض برگ استاندارد را صریح بگوید", r.assumptions.any { it.contains("برگ") })
    }

    @Test
    fun beamWithTwoSizesIsReadAsTwoSizes() {
        val r = Materials.read("تیرآهن ۱۸ و ۱۶ قیمت")!!
        assertEquals(ProductKind.BEAM, r.product.kind)
        assertEquals(listOf(18.0, 16.0), r.sizes)
        assertEquals(MaterialAsk.PRICE, r.ask)
    }

    @Test
    fun priceBasisIsReadWhenTheUserSaysIt() {
        assertEquals(PriceBasis.PER_KG, Materials.read("قیمت هر کیلو تیرآهن ۱۸")!!.basis)
        assertEquals(PriceBasis.PER_BRANCH, Materials.read("قیمت شاخه تیرآهن ۱۸")!!.basis)
        assertEquals(PriceBasis.PER_METRE, Materials.read("قیمت هر متر میلگرد ۱۶")!!.basis)
        assertEquals(PriceBasis.PER_SHEET, Materials.read("قیمت برگ ورق ۲")!!.basis)
    }

    @Test
    fun weightQuestionIsSeparatedFromPriceQuestion() {
        val r = Materials.read("تیرآهن ۱۸ چند کیلو است")!!
        assertEquals(MaterialAsk.WEIGHT, r.ask)
        assertEquals(18.0, r.size)
    }

    @Test
    fun dimensionChainIsReadForBoxAndPipe() {
        val box = Materials.read("قیمت قوطی ۴۰×۴۰×۲")!!
        assertEquals(ProductKind.BOX, box.product.kind)
        assertEquals(listOf(40.0, 40.0), box.sizes)
        assertEquals(2.0, box.thicknessMm)
        val pipe = Materials.read("قیمت لوله ۶۰×۳")!!
        assertEquals(listOf(60.0), pipe.sizes)
        assertEquals(3.0, pipe.thicknessMm)
    }

    @Test
    fun bigNumberForBeamMeansMillimetres() {
        assertEquals(listOf(18.0), Materials.read("قیمت تیرآهن ۱۸۰")!!.sizes)
    }

    @Test
    fun quantityIsNotMistakenForSize() {
        val r = Materials.read("قیمت ۲۰۰ کیلو میلگرد ۱۶")!!
        assertEquals(listOf(16.0), r.sizes)
        assertEquals(200.0, r.quantity)
        assertEquals(PriceBasis.PER_KG, r.quantityUnit)
    }

    @Test
    fun unrelatedQuestionIsNotTakenOver() {
        assertNull(Materials.read("قیمت گوشی سامسونگ"))
        assertNull(Materials.read("هوا امروز چطور است"))
    }

    // ---------------------------------------------------------------- محاسبه

    @Test
    fun beamWeightComesFromTheStandardTable() {
        val w = SteelMath.weight(Materials.read("تیرآهن ۱۸")!!)
        assertNotNull(w.kgPerMetre)
        assertEquals(18.8, w.kgPerMetre!!, 0.001)
        assertTrue(w.exact)
        val r = Materials.read("تیرآهن ۱۸")!!
        val piece = SteelMath.pieceWeight(r, w)!!
        assertEquals(225.6, piece.value, 0.01)   // ۱۲ متر × ۱۸٫۸
    }

    @Test
    fun sheetWeightPerSquareMetreAndPerSheet() {
        val r = Materials.read("ورق ۲ میل")!!
        val w = SteelMath.weight(r)!!
        val kg = w.kgPerMetre!!
        assertEquals(15.7, kg, 0.001)
        assertEquals(31.4, SteelMath.pieceWeight(r, w)!!.value, 0.001)
        assertEquals(31.8, SteelMath.piecesPerTon(r, w)!!.value, 0.1)
    }

    @Test
    fun rebarWeightFollowsTheFormula() {
        val w = SteelMath.weight(Materials.read("میلگرد ۱۶")!!)
        assertEquals(1.580, w.kgPerMetre!!, 0.01)
    }

    @Test
    fun unknownBeamSizeSaysSoInsteadOfInventing() {
        val w = SteelMath.weight(Materials.read("تیرآهن ۱۳")!!)
        assertNull(w.kgPerMetre)
        assertTrue(w.formula.contains("ندارم"))
    }

    @Test
    fun pricePerMetreIsComputedFromPricePerKilo() {
        val r = Materials.read("قیمت تیرآهن ۱۸")!!
        val w = SteelMath.weight(r)!!
        val perMetre = SteelMath.priceIn(PriceBasis.PER_METRE, 42_000.0, r, w)!!
        assertEquals(18.8 * 42_000.0, perMetre.value, 0.5)
        assertTrue("فرمول باید چاپ شود", perMetre.formula.contains("۱۸٫۸") || perMetre.formula.contains("18.8"))
        val perBranch = SteelMath.priceIn(PriceBasis.PER_BRANCH, 42_000.0, r, w)!!
        assertEquals(225.6 * 42_000.0, perBranch.value, 1.0)
    }

    @Test
    fun branchPriceCanBeTurnedBackIntoPricePerKilo() {
        val r = Materials.read("تیرآهن ۱۸")!!
        val w = SteelMath.weight(r)!!
        val perKg = SteelMath.perKgFrom(PriceBasis.PER_BRANCH, 9_024_000.0, r, w)!!
        assertEquals(40_000.0, perKg.value, 1.0)
    }

    @Test
    fun implausiblePriceIsRejected() {
        assertFalse(SteelMath.plausiblePricePerKg(50.0))
        assertFalse(SteelMath.plausiblePricePerKg(9_000_000.0))
        assertTrue(SteelMath.plausiblePricePerKg(42_000.0))
    }

    @Test
    fun moneyIsWrittenWithPersianDigits() {
        val s = SteelMath.money(789_600.0)
        assertTrue(s.contains("\u066C") || s.contains("۷۸۹"))
        assertTrue(s.endsWith("تومان"))
    }
}

/** Transport that answers the three price sites with a real-shaped Persian price page. */
private class PriceTransport(
    private val perKgToman: Long,
    private val failAll: Boolean = false,
    val requested: MutableList<String> = mutableListOf()
) : HttpTransport {
    override fun get(request: HttpRequest): HttpResponse {
        requested += request.url
        if (failAll) return HttpResponse(request.url, 503, "", error = "down")
        val body = """
            <html><body>
            <a href="https://ahanonline.example/price/beam-18">قیمت روز تیرآهن ۱۸ امروز</a>
            <p>قیمت هر کیلو تیرآهن ۱۸ در بازار امروز $perKgToman تومان اعلام شد. تیرآهن ۱۸ شاخه ۱۲ متری است.</p>
            </body></html>
        """.trimIndent()
        // Only the engines that actually answer HTML get content; Google's page stays a JS shell.
        return if (request.url.contains("google.com/search")) {
            HttpResponse(request.url, 200, "<html><body>enablejs</body></html>")
        } else {
            HttpResponse(request.url, 200, body)
        }
    }
}

class MaterialBrainTest {

    private fun tools(transport: HttpTransport): FakeTools {
        val core = SearchCore(
            transport,
            engines = Engines.all,
            pacing = 0L,
            sleep = {},
            maxCallMillis = 60_000L
        )
        val pack = ir.aai.hushyar.domain.Interview.build(
            mapOf("market_name" to "بازار آهن", "attributes" to "سایز، ضخامت، وزن", "currency_unit" to "تومان"),
            1_000L
        ).pack
        return FakeTools(pack, core)
    }

    @Test
    fun priceQuestionGetsComputedAnswerAndStoresWhatItLearned() {
        val fake = tools(PriceTransport(42_000))
        val brain = MaterialBrain(fake)
        val outcome = brain.answer("قیمت تیرآهن ۱۸", "fa") { _, _, _, _ -> }

        assertNotNull(outcome)
        val a = outcome!!.answer
        assertEquals("material.price", a.intent)
        assertFalse(a.refused)
        val bullets = a.bullets.joinToString("\n")
        assertTrue("قیمت هر کیلو باید در پاسخ باشد", bullets.contains("۴۲٬۰۰۰") || bullets.contains("42,000"))
        assertTrue("قیمت هر متر باید محاسبه شده باشد", bullets.contains("تومان/متر"))
        assertTrue("فرمول محاسبه باید چاپ شود", bullets.contains("کیلوگرم/متر ×"))
        assertTrue("منبع قیمت باید نام برده شود", bullets.contains("ahanonline.example"))
        assertTrue("باید بگوید چه چیزی یاد گرفت", a.learned.any { it.contains("تیراهن") || it.contains("تیرآهن") })

        val stored = fake.knowledge.filter { it.attribute == MaterialBrain.PRICE_ATTRIBUTE }
        assertEquals("قیمت باید در دانش ذخیره شود", 1, stored.size)
        assertEquals(42_000.0, stored.first().numericValue!!, 0.01)
    }

    @Test
    fun secondQuestionOnSameMaterialNeedsNoNetwork() {
        val transport = PriceTransport(42_000)
        val fake = tools(transport)
        val brain = MaterialBrain(fake)
        brain.answer("قیمت تیرآهن ۱۸", "fa") { _, _, _, _ -> }
        val afterFirst = transport.requested.size
        assertTrue("سؤال اول باید جستجو کرده باشد", afterFirst > 0)

        val second = brain.answer("قیمت تیرآهن ۱۸ کیلو", "fa") { _, _, _, _ -> }!!.answer
        assertEquals("سؤال دوم نباید شبکه را لمس کند", afterFirst, transport.requested.size)
        assertTrue(second.bullets.joinToString("\n").contains("تومان/کیلوگرم") || second.text.contains("کیلوگرم"))
    }

    @Test
    fun whenNoPriceExistsItSaysSoAndStillAnswersWithWeight() {
        val fake = tools(PriceTransport(42_000, failAll = true))
        val brain = MaterialBrain(fake)
        val a = brain.answer("قیمت ورق ۲ میل", "fa") { _, _, _, _ -> }!!.answer
        val bullets = a.bullets.joinToString("\n")
        assertTrue("نباید قیمت جعلی بسازد", bullets.contains("پیدا نکردم"))
        assertTrue("ولی وزن را می‌داند", bullets.contains("۱۵٫۷") || bullets.contains("15.7"))
        assertTrue(a.missing.any { it.contains("قیمت") })
        assertTrue("حداقل باید منبع عدد وزن را بگوید", bullets.contains("۷٫۸۵"))
    }

    @Test
    fun definitionQuestionIsAnsweredOfflineWithNoSearch() {
        val transport = PriceTransport(42_000)
        val fake = tools(transport)
        val a = MaterialBrain(fake).answer("تیرآهن چیه", "fa") { _, _, _, _ -> }!!.answer
        assertEquals("material.definition", a.intent)
        assertEquals("برای تعریف نباید شبکه لمس شود", 0, transport.requested.size)
        assertTrue(a.bullets.joinToString("\n").contains("IPE") || a.bullets.joinToString("\n").contains("استاندارد"))
    }

    @Test
    fun weightQuestionWithQuantityMultiplies() {
        val fake = tools(PriceTransport(42_000))
        val a = MaterialBrain(fake).answer("وزن ۲۰ شاخه تیرآهن ۱۸", "fa") { _, _, _, _ -> }!!.answer
        val bullets = a.bullets.joinToString("\n")
        assertTrue(bullets.contains("کیلوگرم"))
        // ۲۰ × ۱۲ × ۱۸٫۸ = ۴۵۱۲ کیلوگرم
        assertTrue(bullets.contains("۴۵۱۲") || bullets.contains("۴٬۵۱۲") || bullets.contains("4512"))
    }

    @Test
    fun unrelatedQuestionReturnsNullSoTheGeneralPathKeepsWorking() {
        val fake = tools(PriceTransport(42_000))
        assertNull(MaterialBrain(fake).answer("قیمت گوشی سامسونگ", "fa") { _, _, _, _ -> })
    }
}
