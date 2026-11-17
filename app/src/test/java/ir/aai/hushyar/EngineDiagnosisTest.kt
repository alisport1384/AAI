package ir.aai.hushyar

import ir.aai.hushyar.core.Elf
import ir.aai.hushyar.domain.EngineDiagnosis
import ir.aai.hushyar.domain.ModelPhase
import ir.aai.hushyar.domain.ModelUiRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * «کتابخانهٔ موتور بارگذاری نشد» with no reason was a dead end for the user. These tests pin the
 * diagnosis: every sentence the app now prints is decided from measured facts, and the one
 * constant it relies on is checked against the real `.so` we ship.
 */
class EngineDiagnosisTest {

    private fun facts(
        failure: String? = "dlopen failed: library not found",
        abis: List<String> = listOf("arm64-v8a", "armeabi-v7a"),
        page: Long = 4096,
        align: Long? = 4096,
        present: Boolean = true,
        api: Int = 34
    ) = EngineDiagnosis.Facts(
        loadFailure = failure,
        abis = abis,
        pageSizeBytes = page,
        apiLevel = api,
        deviceModel = "Test Phone",
        libAlign = align,
        libPresent = present
    )

    // ---------------------------------------------------------------- the shipped artifact

    @Test
    fun thePinnedAlignmentMatchesTheLibraryWeActuallyShip() {
        val file = File("src/main/jniLibs/arm64-v8a/libhushyar_llama.so")
        assertTrue("the committed .so must exist at ${file.absolutePath}", file.isFile)
        val align = Elf.maxLoadAlign(file.readBytes())
        assertEquals(
            "EngineDiagnosis.SHIPPED_LIB_ALIGN must describe the real file",
            EngineDiagnosis.SHIPPED_LIB_ALIGN,
            align
        )
        assertEquals("the shipped library is a 4 KB-page build", 4096L, align)
    }

    @Test
    fun theX8664LibraryHasTheSameAlignment() {
        val file = File("src/main/jniLibs/x86_64/libhushyar_llama.so")
        assertEquals(EngineDiagnosis.SHIPPED_LIB_ALIGN, Elf.maxLoadAlign(file.readBytes()))
    }

    @Test
    fun theElfReaderSeesBothLibrariesAsArmAndX86_64() {
        val arm = Elf.headers(File("src/main/jniLibs/arm64-v8a/libhushyar_llama.so").readBytes())!!
        val x86 = Elf.headers(File("src/main/jniLibs/x86_64/libhushyar_llama.so").readBytes())!!
        assertTrue(arm.is64 && x86.is64)
        assertEquals(183, arm.machine)   // EM_AARCH64
        assertEquals(62, x86.machine)    // EM_X86_64
    }

    @Test
    fun aSixteenKilobyteBuildIsReportedAsSixteenKilobytes() {
        // Same header as a real file, with every PT_LOAD alignment rewritten to 16 KB.
        val bytes = File("src/main/jniLibs/arm64-v8a/libhushyar_llama.so").readBytes()
        val phoff = readInt(bytes, 32)
        val phentsize = readShort(bytes, 54)
        val phnum = readShort(bytes, 56)
        for (i in 0 until phnum) {
            val at = phoff + i * phentsize
            if (readInt(bytes, at) == 1) writeLong(bytes, at + 48, 16_384L)
        }
        assertEquals(16_384L, Elf.maxLoadAlign(bytes))
    }

    @Test
    fun rubbishIsNotAnElfFile() {
        assertEquals(null, Elf.maxLoadAlign(ByteArray(64)))
        assertEquals(null, Elf.headers("not an elf".toByteArray()))
    }

    // ---------------------------------------------------------------- verdicts

    @Test
    fun aLoadedEngineNeedsNoExplanation() {
        assertEquals(EngineDiagnosis.Verdict.OK, EngineDiagnosis.verdict(facts(failure = null)))
    }

    @Test
    fun aThirtyTwoBitPhoneIsNamedAsAnUnsupportedArchitecture() {
        val verdict = EngineDiagnosis.verdict(
            facts(abis = listOf("armeabi-v7a", "armeabi"), failure = "dlopen failed: library \"libhushyar_llama.so\" not found")
        )
        assertEquals(EngineDiagnosis.Verdict.WRONG_ABI, verdict)
        assertTrue(EngineDiagnosis.advice(verdict).contains("معماری"))
    }

    @Test
    fun aSixteenKilobytePagePhoneIsNamedAsAPageSizeMismatch() {
        val verdict = EngineDiagnosis.verdict(
            facts(abis = listOf("arm64-v8a"), page = 16_384, align = 4096, failure = "dlopen failed: invalid ELF alignment")
        )
        assertEquals(EngineDiagnosis.Verdict.PAGE_SIZE, verdict)
        val advice = EngineDiagnosis.advice(verdict)
        assertTrue("the advice must say the library has to be rebuilt: $advice", advice.contains("ساخته شود"))
    }

    @Test
    fun aThirtyTwoBitPhoneIsNotBlamedOnPageSize() {
        // A 16 KB phone whose ABI we do not ship must not be reported as a page-size problem.
        val verdict = EngineDiagnosis.verdict(
            facts(abis = listOf("armeabi-v7a"), page = 16_384, align = 4096)
        )
        assertEquals(EngineDiagnosis.Verdict.WRONG_ABI, verdict)
    }

    @Test
    fun aMissingLibraryIsItsOwnVerdict() {
        val verdict = EngineDiagnosis.verdict(facts(present = false))
        assertEquals(EngineDiagnosis.Verdict.LIBRARY_MISSING, verdict)
        assertTrue(EngineDiagnosis.advice(verdict).contains("APK"))
    }

    @Test
    fun anUnexplainedFailureSaysSoAndAsksForTheReport() {
        val verdict = EngineDiagnosis.verdict(
            facts(abis = listOf("arm64-v8a"), page = 4096, align = 4096, failure = "something else entirely")
        )
        assertEquals(EngineDiagnosis.Verdict.UNKNOWN, verdict)
        assertTrue(EngineDiagnosis.advice(verdict).contains("گزارش"))
    }

    @Test
    fun theLoaderMessageTellsUsWhenTheFileItselfIsGone() {
        assertTrue(EngineDiagnosis.libraryMissingFromError("dlopen failed: library \"libhushyar_llama.so\" not found"))
        assertTrue(EngineDiagnosis.libraryMissingFromError("java.lang.UnsatisfiedLinkError: couldn't find \"libhushyar_llama.so\""))
        assertFalse(EngineDiagnosis.libraryMissingFromError("dlopen failed: invalid ELF alignment"))
        assertFalse(EngineDiagnosis.libraryMissingFromError(null))
    }

    // ---------------------------------------------------------------- the report

    @Test
    fun theReportCarriesTheRawNumbersNotJustAConclusion() {
        val report = EngineDiagnosis.report(facts(abis = listOf("arm64-v8a"), page = 16_384, align = 4096))
        assertTrue(report.contains("API 34"))
        assertTrue(report.contains("arm64-v8a"))
        assertTrue("page size must be visible", report.contains("۱۶ کیلوبایت"))
        assertTrue("library alignment must be visible", report.contains("۴ کیلوبایت"))
        assertTrue(report.contains("حکم:"))
        assertTrue("the raw loader error must survive", report.contains("dlopen failed"))
    }

    @Test
    fun aHealthyDeviceStillGetsAReportThatSaysSo() {
        val report = EngineDiagnosis.report(facts(failure = null))
        assertTrue(report.contains(EngineDiagnosis.label(EngineDiagnosis.Verdict.OK)))
        assertFalse(report.contains("حکم: علت نامعلوم"))
    }

    @Test
    fun everyVerdictHasAPersianLabel() {
        EngineDiagnosis.Verdict.entries.forEach { verdict ->
            val label = EngineDiagnosis.label(verdict)
            assertTrue(label.isNotBlank())
            assertTrue("$verdict label must be Persian: $label", label.any { it in '\u0600'..'\u06FF' })
        }
    }

    // ---------------------------------------------------------------- what the screen may offer

    @Test
    fun theDownloadIsOfferedEvenWhenTheEngineCannotLoad() {
        assertTrue(ModelUiRules.canDownload(ModelPhase.ENGINE_ERROR, busy = false))
        assertTrue(ModelUiRules.canDownload(ModelPhase.MISSING, busy = false))
        assertFalse("nothing to download when the file is already there", ModelUiRules.canDownload(ModelPhase.READY, false))
        assertFalse("not while a download runs", ModelUiRules.canDownload(ModelPhase.ENGINE_ERROR, busy = true))
    }

    @Test
    fun theExportFollowsTheFileNotTheEngine() {
        assertTrue("an installed file can be exported even if the engine never loads", ModelUiRules.canExport(true))
        assertFalse(ModelUiRules.canExport(false))
    }

    @Test
    fun theWarningOnSuchADeviceIsHonestAboutBothHalves() {
        val warning = ModelUiRules.engineWarning(EngineDiagnosis.Verdict.PAGE_SIZE)
        assertTrue("it must say what is broken", warning.contains("بارگذاری نمی‌شود"))
        assertTrue("it must say the file is still worth downloading", warning.contains("ذخیره"))
        assertTrue("it must say the app works without the model", warning.contains("بدون مدل"))
        assertEquals("no warning when the engine is fine", "", ModelUiRules.engineWarning(EngineDiagnosis.Verdict.OK))
    }

    // ---------------------------------------------------------------- byte helpers

    private fun readShort(b: ByteArray, at: Int) =
        (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8)

    private fun readInt(b: ByteArray, at: Int) =
        (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8) or
            ((b[at + 2].toInt() and 0xff) shl 16) or ((b[at + 3].toInt() and 0xff) shl 24)

    private fun writeLong(b: ByteArray, at: Int, value: Long) {
        for (i in 0 until 8) b[at + i] = ((value ushr (8 * i)) and 0xff).toByte()
    }
}
