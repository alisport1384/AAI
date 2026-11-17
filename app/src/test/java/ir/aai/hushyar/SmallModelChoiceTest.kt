package ir.aai.hushyar

import ir.aai.hushyar.domain.DeviceMemory
import ir.aai.hushyar.domain.FitVerdict
import ir.aai.hushyar.domain.LocalPrompt
import ir.aai.hushyar.domain.ModelCatalog
import ir.aai.hushyar.domain.ModelFit
import ir.aai.hushyar.domain.ModelSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.11.3 — «من می‌خواهم Llama 3.3 3B یا Qwen 7B را روی همین گوشی اجرا کنم.»
 *
 * پاسخ صادقانه دو بخش دارد و این تست‌ها هر دو را قفل می‌کنند:
 *
 *  ۱. **سقف** اندازه‌گیری می‌شود: پردازهٔ ۳۲بیتی + حافظهٔ آزاد، حداکثر حجم مدلی را می‌سازد که
 *     موتور می‌تواند نقشه کند. بزرگ‌تر از آن، ۷B یا ۳B، روی این گوشی بارگذاری نمی‌شود — نه با
 *     تنظیمات، نه با کوانت دیگر (۷B با کم‌ترین کوانت هم چند گیگابایت است).
 *  ۲. **در پایین سقف، بهترین گزینه** پیشنهاد می‌شود و فهرست مدل‌ها بزرگ‌تر شد: Qwen3 0.6B با
 *     دقت ۸ و ۴ بیت، Llama 3.2 1B، و Qwen2.5 1.5B با کوانت ۲ بیت برای کسی که پارامتر بیشتر
 *     می‌خواهد.
 */
class SmallModelChoiceTest {

    private val mb = 1024L * 1024

    private fun phone32(availMb: Long = 870, diskMb: Long = 6000) = DeviceMemory(
        totalBytes = 2048 * mb,
        availBytes = availMb * mb,
        lowMemory = false,
        is64Bit = false,
        freeDiskBytes = diskMb * mb,
        abis = listOf("armeabi-v7a", "armeabi")
    )

    private fun phone64(availMb: Long = 3072, diskMb: Long = 20000) = DeviceMemory(
        totalBytes = 6144 * mb,
        availBytes = availMb * mb,
        lowMemory = false,
        is64Bit = true,
        freeDiskBytes = diskMb * mb,
        abis = listOf("arm64-v8a")
    )

    // ------------------------------------------------------------ the catalogue grew

    @Test
    fun theCatalogueHasOneDefaultAndItIsSmallEnoughForAnyPhone() {
        assertEquals("exactly one model may be marked recommended", 1, ModelCatalog.all.count { it.recommended })
        val recommended = ModelCatalog.recommended
        assertTrue(
            "the default download must fit even a 32-bit phone: ${recommended.title}",
            recommended.bytes <= 500 * mb
        )
        assertTrue(recommended.megabytes in 300..500)
    }

    @Test
    fun everyEntryIsPinnedWithARealHashAndAUniqueFile() {
        assertEquals(ModelCatalog.all.size, ModelCatalog.all.map { it.id }.distinct().size)
        assertEquals(ModelCatalog.all.size, ModelCatalog.all.map { it.fileName }.distinct().size)
        ModelCatalog.all.forEach { spec ->
            assertEquals("${spec.id}: sha256 must be a full 64-hex digest", 64, spec.sha256.length)
            assertTrue("${spec.id}: sha256 must be hex", spec.sha256.all { it in "0123456789abcdef" })
            assertTrue("${spec.id}: size must be real", spec.bytes > 100 * mb)
            assertTrue("${spec.id}: https only", spec.url.startsWith("https://"))
            assertTrue("${spec.id}: the file name must appear in the url", spec.url.endsWith(spec.fileName))
            assertTrue("${spec.id}: a Persian note is required", spec.note.length > 20)
        }
    }

    @Test
    fun theNewModelsAreInTheCatalogueWithTheirMeasuredSizes() {
        val expected = mapOf(
            "qwen3-0.6b-q8" to 639_447_744L,
            "qwen3-0.6b-q4km" to 396_705_472L,
            "llama-3.2-1b-q4km" to 807_694_464L,
            "qwen2.5-1.5b-q2k" to 752_880_160L
        )
        expected.forEach { (id, bytes) ->
            val spec = ModelCatalog.byId[id]
            assertNotNull("$id must be in the catalogue", spec)
            assertEquals("$id size", bytes, spec!!.bytes)
        }
    }

    // ------------------------------------------------------------ the ceiling is measured

    @Test
    fun aThreeBillionParameterModelIsBeyondAThirtyTwoBitPhoneAndTheAppSaysSo() {
        val phone = phone32()
        val threeB = ModelCatalog.byId.getValue("llama-3.2-3b-q4km")
        assertEquals(
            FitVerdict.THIRTY_TWO_BIT,
            ModelFit.verdict(phone, threeB.bytes, installed = true)
        )
        val capacity = ModelFit.maxUsableBytes(phone)
        assertTrue("the ceiling must be below a 3B model", capacity < threeB.bytes)
        assertEquals("a 32-bit process is capped by the safe mapping size", 900 * mb, capacity)
    }

    @Test
    fun aSevenBillionParameterModelCannotBeOfferedOnThisPhoneAtAll() {
        // Qwen 7B at Q4 is ~4.7 GB; even the smallest useful quant is over 2.7 GB. Both are five
        // to ten times the ceiling of this phone, so no quantisation rescue exists.
        val phone = phone32()
        assertEquals(900 * mb, ModelFit.maxUsableBytes(phone))
        assertTrue("7B Q4 is 4.7 GB", 4_700 * mb > ModelFit.maxUsableBytes(phone) * 4)
        val advisory = ModelFit.advisory(phone, ModelCatalog.byId.getValue("llama-3.2-3b-q4km"), installed = false)
        assertTrue(advisory!!.contains("۳۲بیتی"))
    }

    @Test
    fun theAppNamesTheBiggestModelThisPhoneCanLoad() {
        val best = ModelFit.bestFor(phone32(), ModelCatalog.all)
        assertNotNull(best)
        val capacity = ModelFit.maxUsableBytes(phone32())
        assertTrue("${best!!.title} must fit the ceiling", best.bytes <= capacity)
        // …and nothing bigger in the catalogue still qualifies.
        ModelCatalog.all.filter { it.bytes > capacity }.forEach { tooBig ->
            assertFalse(
                "${tooBig.title} is above the ceiling and must not be offered as the best choice",
                tooBig.id == best.id
            )
        }
    }

    @Test
    fun aSixtyFourBitPhoneWithRoomGetsABiggerAnswer() {
        val phone = phone64()
        val best = ModelFit.bestFor(phone, ModelCatalog.all)!!
        assertTrue(
            "a 6 GB phone with 3 GB free must be offered something larger than the 32-bit ceiling",
            best.bytes > 900 * mb
        )
    }

    @Test
    fun theCapacityLineCarriesTheMeasuredNumbers() {
        val line = ModelFit.capacityLine(phone32(availMb = 870))
        assertTrue(line, line.contains("۹۰۰"))
        assertTrue(line, line.contains("۳۲بیتی"))
        assertTrue(line, line.contains("۸۷۰"))
    }

    @Test
    fun anUnmeasuredDeviceGetsNoCeilingClaim() {
        assertEquals(0L, ModelFit.maxUsableBytes(DeviceMemory.UNKNOWN))
        assertTrue(ModelFit.capacityLine(DeviceMemory.UNKNOWN).contains("اندازه‌گیری نشد"))
    }

    // ------------------------------------------------------------ Qwen3 grounding

    @Test
    fun qwen3PromptsAskTheModelNotToThinkOutLoud() {
        val spec: ModelSpec = ModelCatalog.byId.getValue("qwen3-0.6b-q8")
        val prompt = LocalPrompt.chat(spec.family, "دستور", "پرسش")
        assertTrue(prompt, prompt.contains("/no_think"))
        assertTrue(prompt.contains("<|im_start|>system"))
    }

    @Test
    fun aThinkBlockIsStrippedBeforeTheAnswerIsUsed() {
        val raw = "  thinking\nقیمت را باید از منبع بگیرم\nend_of_thought>\nقیمت هر عدد ۱۸۰٬۰۰۰ تومان است."
        assertEquals("قیمت هر عدد ۱۸۰٬۰۰۰ تومان است.", LocalPrompt.stripThinking(raw))
        assertEquals("بدون بلوک", LocalPrompt.stripThinking("بدون بلوک"))
    }

    @Test
    fun theRecommendedDefaultIsAlsoTheCheapOneForAnOldPhone() {
        assertEquals("qwen3-0.6b-q4km", ModelCatalog.recommended.id)
        assertEquals(
            FitVerdict.FITS,
            ModelFit.verdict(phone32(availMb = 700), ModelCatalog.recommended.bytes, installed = true)
        )
    }
}
