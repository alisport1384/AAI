package ir.aai.hushyar

import ir.aai.hushyar.core.Gguf
import ir.aai.hushyar.core.GgufRead
import ir.aai.hushyar.core.MemInfo
import ir.aai.hushyar.data.LocalEngine
import ir.aai.hushyar.data.NativeLlama
import ir.aai.hushyar.domain.DeviceMemory
import ir.aai.hushyar.domain.FitVerdict
import ir.aai.hushyar.domain.ModelFit
import ir.aai.hushyar.domain.ModelSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * v0.11.2 — «مدل‌های دیگر خطای بارگذاری دارند.»
 *
 * کاربر سه مدل ۱٫۱ تا ۲ گیگابایتی را دانلود کرد و موتور با یک جملهٔ کلی ردشان کرد:
 * «llama_model_load_from_file failed (file missing, truncated or not a GGUF file)».
 * این تست‌ها سه چیز را قفل می‌کنند:
 *
 *  ۱. خودمان سربرگ GGUF را می‌خوانیم، پس «فایل سالم است» یا «فایل ناقص است» اندازه‌گیری می‌شود؛
 *  ۲. داوری «جا می‌شود / جا نمی‌شود» از عددهای واقعی گوشی می‌آید و همان عددها در متن می‌آیند؛
 *  ۳. فهرست معماری‌های پشتیبانی‌شده با خودِ `.so` تعهدشده می‌خواند، نه با حافظهٔ من.
 */
class GgufAndFitTest {

    // ---------------------------------------------------------------- GGUF header

    /** Builds a byte-valid GGUF v3 metadata block (no tensors' data, which we never read). */
    private fun gguf(
        version: Int = 3,
        arch: String? = "qwen2",
        split: Int? = null,
        extraFloat: Boolean = true,
        extraArrays: Boolean = true,
        tensorCount: Long = 4
    ): ByteArray {
        val out = ByteArrayOutputStream()
        fun u32(v: Int) = out.write(byteArrayOf(
            (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()
        ))
        fun u64(v: Long) {
            val b = ByteArray(8)
            for (i in 0 until 8) b[i] = ((v shr (8 * i)) and 0xFF).toByte()
            out.write(b)
        }
        fun str(v: String) {
            val bytes = v.toByteArray(Charsets.UTF_8)
            u64(bytes.size.toLong())
            out.write(bytes)
        }
        var kv = 0L
        val kvs = mutableListOf<() -> Unit>()
        if (arch != null) {
            kv++
            kvs += { str("general.architecture"); u32(8); str(arch!!) }
        }
        kv++
        kvs += { str("general.name"); u32(8); str("Test-Model") }
        if (split != null) {
            kv++
            kvs += { str("split.count"); u32(4); u32(split) }
        }
        if (extraFloat) {
            kv++
            kvs += { str("some.rope.freq_base"); u32(6); u32(0x40000000) } // 2.0f
        }
        if (extraArrays) {
            kv++
            kvs += {
                str("some.uint.array"); u32(9); u32(4); u64(3L); u32(1); u32(0); u32(1)
            }
            kv++
            kvs += {
                str("tokenizer.ggml.tokens"); u32(9); u32(8); u64(2L); str("<s>"); str("</s>")
            }
        }
        out.write("GGUF".toByteArray(Charsets.US_ASCII))
        u32(version)
        u64(tensorCount)
        u64(kv)
        kvs.forEach { it() }
        // a little "weight" data so the file is not empty
        out.write(ByteArray(64))
        return out.toByteArray()
    }

    private fun tempFile(bytes: ByteArray, suffix: String = ".gguf"): File =
        File.createTempFile("model", suffix).apply { writeBytes(bytes); deleteOnExit() }

    @Test
    fun aRealGgufHeaderIsReadWithItsArchitecture() {
        val read = Gguf.read(tempFile(gguf(arch = "qwen2")))
        assertTrue("$read", read is GgufRead.Ok)
        val header = (read as GgufRead.Ok).header
        assertEquals(3, header.version)
        assertEquals("qwen2", header.architecture)
        assertEquals("Test-Model", header.name)
        assertEquals(4L, header.tensorCount)
        assertTrue(header.singleFile)
        assertTrue(header.describe().contains("qwen2"))
    }

    @Test
    fun anHtmlPageDisguisedAsAModelIsNamedAsSuch() {
        val read = Gguf.read(tempFile("<!DOCTYPE html><html>404</html>".toByteArray()))
        assertTrue(read is GgufRead.Bad)
        val reason = (read as GgufRead.Bad).reason
        assertTrue("reason must name the real bytes: $reason", reason.contains("GGUF"))
        assertFalse("must not claim the file is truncated", reason.contains("ناقص"))
    }

    @Test
    fun aTruncatedFileIsReportedAsTruncatedAndNotAsAMissingFile() {
        val full = gguf()
        val cut = full.copyOfRange(0, 12) // magic + version + half of the tensor count
        val read = Gguf.read(tempFile(cut))
        assertTrue("$read", read is GgufRead.Bad)
    }

    @Test
    fun anUnsupportedGgufVersionIsSaidPlainly() {
        val read = Gguf.read(tempFile(gguf(version = 1)))
        assertTrue(read is GgufRead.Bad)
        assertTrue((read as GgufRead.Bad).reason.contains("نسخه"))
    }

    @Test
    fun aMissingFileIsNotBlamedOnTheEngine() {
        val read = Gguf.read(File("/tmp/definitely-not-here-${System.nanoTime()}.gguf"))
        assertTrue(read is GgufRead.Bad)
        assertTrue((read as GgufRead.Bad).reason.contains("وجود ندارد"))
    }

    @Test
    fun splitFilesAreRecognised() {
        val read = Gguf.read(tempFile(gguf(split = 3)))
        val header = (read as GgufRead.Ok).header
        assertEquals(3, header.splitCount)
        assertFalse("a 3-part model is not a single-file model", header.singleFile)
    }

    // ---------------------------------------------------------------- the shipped engine

    @Test
    fun everyArchitectureWeClaimTheEngineKnowsIsActuallyInsideTheShippedLibrary() {
        val so = File("src/main/jniLibs/arm64-v8a/libhushyar_llama.so")
        assertTrue("the committed .so must exist", so.isFile)
        val text = String(so.readBytes(), Charsets.ISO_8859_1)
        ModelFit.SUPPORTED_ARCH.forEach { arch ->
            assertTrue(
                "ModelFit.SUPPORTED_ARCH claims \"$arch\" but the shipped engine has no such string",
                text.contains(arch)
            )
        }
    }

    // ---------------------------------------------------------------- memory measurement

    @Test
    fun memInfoParsingReadsTheTwoNumbersWeUse() {
        val snapshot = MemInfo.parse(
            """
            MemTotal:        3886044 kB
            MemFree:          104857 kB
            MemAvailable:    1548288 kB
            Buffers:            4096 kB
            """.trimIndent()
        )!!
        assertEquals(3_886_044L * 1024, snapshot.totalBytes)
        assertEquals(1_548_288L * 1024, snapshot.availableBytes)
    }

    @Test
    fun memInfoFallsBackToMemFreeWhenAvailableIsMissing() {
        val snapshot = MemInfo.parse("MemTotal: 1000 kB\nMemFree: 200 kB")!!
        assertEquals(200L * 1024, snapshot.availableBytes)
    }

    @Test
    fun memInfoWithoutMemTotalIsRefusedInsteadOfGuessed() {
        assertNull(MemInfo.parse("MemFree: 200 kB"))
    }

    // ---------------------------------------------------------------- fit verdicts

    private val gb = 1024L * 1024 * 1024

    private fun device(
        total: Long = 4 * gb,
        avail: Long = 2 * gb,
        is64: Boolean = true,
        disk: Long = 20 * gb
    ) = DeviceMemory(total, avail, lowMemory = false, is64Bit = is64, freeDiskBytes = disk)

    private fun spec(bytes: Long, title: String = "Test Model"): ModelSpec = ModelSpec(
        id = "test",
        title = title,
        family = "qwen2.5",
        fileName = "test.gguf",
        url = "https://example.invalid/test.gguf",
        bytes = bytes,
        sha256 = "a".repeat(64),
        contextTokens = 4096,
        license = "Apache-2.0",
        note = ""
    )

    @Test
    fun aBigModelInA32BitProcessIsRefusedWithTheRealReason() {
        val verdict = ModelFit.verdict(device(is64 = false), 1_107_409_472L, installed = true)
        assertEquals(FitVerdict.THIRTY_TWO_BIT, verdict)
        val text = ModelFit.explain(verdict, device(is64 = false), spec(1_107_409_472L))
        assertTrue(text, text.contains("۳۲بیتی"))
        assertTrue("the numbers must be in the sentence: $text", text.contains("۱٬۰۵۶"))
    }

    @Test
    fun aSmallModelIsFineOnTheSame32BitPhone() {
        assertEquals(
            FitVerdict.FITS,
            ModelFit.verdict(device(is64 = false), 491_400_032L, installed = true)
        )
    }

    @Test
    fun tooLittleFreeMemoryIsSaidWithBothNumbers() {
        val low = device(avail = 200L * 1024 * 1024)
        val verdict = ModelFit.verdict(low, 1_117_320_736L, installed = true)
        assertEquals(FitVerdict.LOW_RAM, verdict)
        val text = ModelFit.explain(verdict, low, spec(1_117_320_736L))
        assertTrue(text, text.contains("۲۰۰"))
        assertTrue(text, text.contains("برآورد"))
    }

    @Test
    fun aHealthyPhoneFitsTheBiggestModelAndSaysNothingAlarming() {
        val healthy = device(total = 8 * gb, avail = 5 * gb, disk = 40 * gb)
        assertEquals(FitVerdict.FITS, ModelFit.verdict(healthy, 2_019_377_696L, installed = true))
        assertNull("no advisory for a device that fits", ModelFit.advisory(healthy, spec(2_019_377_696L), true))
    }

    @Test
    fun noDiskSpaceIsCaughtBeforeTheDownload() {
        val tight = device(disk = 300L * 1024 * 1024)
        assertEquals(FitVerdict.NO_DISK, ModelFit.verdict(tight, 2_019_377_696L, installed = false))
        assertTrue(ModelFit.advisory(tight, spec(2_019_377_696L), false)!!.contains("جا نیست"))
    }

    @Test
    fun anUnknownArchitectureIsNotCalledAMemoryProblem() {
        val header = (Gguf.read(tempFile(gguf(arch = "brandnewarch"))) as GgufRead.Ok).header
        assertEquals(
            FitVerdict.UNKNOWN_ARCH,
            ModelFit.verdict(device(), 806_058_272L, installed = true, header = header)
        )
    }

    @Test
    fun unmeasuredDeviceIsNotJudged() {
        assertEquals(
            FitVerdict.UNKNOWN,
            ModelFit.verdict(DeviceMemory.UNKNOWN, 1_107_409_472L, installed = true)
        )
    }

    @Test
    fun theFailureReportAlwaysCarriesTheFilesOwnNumbers() {
        val header = (Gguf.read(tempFile(gguf(arch = "qwen3"))) as GgufRead.Ok).header
        val report = ModelFit.failureReport(device(is64 = false), spec(1_107_409_472L), header)
        assertTrue(report, report.contains("qwen3"))
        assertTrue(report, report.contains("تانسور"))
        assertTrue(report, report.contains("۳۲بیتی"))
        assertFalse("it must not repeat the vague engine sentence as the verdict", report.contains("not a GGUF"))
    }

    // ---------------------------------------------------------------- engine behaviour

    private class ScriptedNative(
        private val okAtContext: Int,
        private val error: String = "llama_model_load_from_file failed (file missing, truncated or not a GGUF file)"
    ) : NativeLlama {
        var loads = 0
            private set
        var lastContext = 0
            private set

        override val available: Boolean = true
        override fun backendInfo(): String = "fake"
        override fun lastError(): String = error
        override fun load(path: String, contextTokens: Int, threads: Int): Long {
            loads++
            lastContext = contextTokens
            return if (contextTokens <= okAtContext) 9L else 0L
        }

        override fun generate(handle: Long, prompt: ByteArray, maxTokens: Int, temperature: Float, seed: Int): ByteArray? =
            ByteArray(0)

        override fun cancel(handle: Long) = Unit
        override fun free(handle: Long) = Unit
    }

    private fun fileOf(bytes: Int): File =
        File.createTempFile("model", ".gguf").apply { writeBytes(ByteArray(bytes)); deleteOnExit() }

    private fun specOf(bytes: Long) = ModelSpec(
        id = "t", title = "T", family = "qwen2.5", fileName = "t.gguf",
        url = "https://example.invalid/t.gguf", bytes = bytes, sha256 = "a".repeat(64),
        contextTokens = 4096, license = "Apache-2.0", note = ""
    )

    @Test
    fun theEngineRetriesOnceWithASmallerContextBeforeGivingUp() {
        val native = ScriptedNative(okAtContext = 2048)
        val engine = LocalEngine(native)
        assertTrue(engine.load(fileOf(8), specOf(8)))
        assertTrue(engine.ready)
        assertEquals("first try 4096, then 2048", 2, native.loads)
        assertEquals(2048, native.lastContext)
    }

    @Test
    fun aFailedLoadCarriesTheMeasuredExplanationWhenOneIsAvailable() {
        val native = ScriptedNative(okAtContext = 0)
        val engine = LocalEngine(native)
        engine.diagnosisFor = { _, _ -> "فایل GGUF سالم است: معماری qwen2؛ حافظهٔ آزاد ۶۱۲ مگابایت" }
        assertFalse(engine.load(fileOf(8), specOf(8)))
        val message = engine.lastError!!
        assertTrue(message, message.contains("ناموفق"))
        assertTrue("the engine's own sentence stays: $message", message.contains("llama_model_load_from_file"))
        assertTrue("and the measured facts are appended: $message", message.contains("۶۱۲"))
    }

    @Test
    fun aFailedLoadWithoutADiagnosisStillSaysItTriedTheSmallerContext() {
        val engine = LocalEngine(ScriptedNative(okAtContext = 0))
        assertFalse(engine.load(fileOf(8), specOf(8)))
        assertTrue(engine.lastError!!, engine.lastError!!.contains("۲۰۴۸"))
    }
}
