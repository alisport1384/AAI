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
    fun thePinnedAlignmentMatchesEveryLibraryWeActuallyShip() {
        EngineDiagnosis.SHIPPED_ABIS.forEach { abi ->
            val file = File("src/main/jniLibs/$abi/libhushyar_llama.so")
            assertTrue("the committed .so must exist at ${file.absolutePath}", file.isFile)
            assertEquals(
                "SHIPPED_LIB_ALIGN[$abi] must describe the real file",
                EngineDiagnosis.SHIPPED_LIB_ALIGN[abi],
                Elf.maxLoadAlign(file.readBytes())
            )
        }
    }

    @Test
    fun theSixtyFourBitLibrariesAreLinkedForSixteenKilobytePages() {
        // Android 15+ phones with 16 KB pages cannot dlopen a 4 KB-aligned library at all.
        listOf("arm64-v8a", "x86_64").forEach { abi ->
            val align = Elf.maxLoadAlign(File("src/main/jniLibs/$abi/libhushyar_llama.so").readBytes())
            assertEquals("$abi must be a 16 KB-page build", 16_384L, align)
        }
    }

    @Test
    fun theElfReaderSeesAllThreeLibraries() {
        val arm32 = Elf.headers(File("src/main/jniLibs/armeabi-v7a/libhushyar_llama.so").readBytes())!!
        val arm = Elf.headers(File("src/main/jniLibs/arm64-v8a/libhushyar_llama.so").readBytes())!!
        val x86 = Elf.headers(File("src/main/jniLibs/x86_64/libhushyar_llama.so").readBytes())!!
        assertFalse("armeabi-v7a is a 32-bit ELF", arm32.is64)
        assertTrue(arm.is64 && x86.is64)
        assertEquals(40, arm32.machine)  // EM_ARM
        assertEquals(183, arm.machine)   // EM_AARCH64
        assertEquals(62, x86.machine)    // EM_X86_64
    }

    @Test
    fun aThirtyTwoBitArmPhoneIsNowSupported() {
        // The C1 report: "couldn't find libhushyar_llama.so" on a phone whose processes run
        // armeabi-v7a. Shipping that ABI is the fix, so the verdict must stop blaming the device.
        val verdict = EngineDiagnosis.verdict(
            facts(abis = listOf("armeabi-v7a", "armeabi"), page = 4096, align = 4096, failure = null)
        )
        assertEquals(EngineDiagnosis.Verdict.OK, verdict)
        assertTrue(EngineDiagnosis.SHIPPED_ABIS.contains("armeabi-v7a"))
    }

    @Test
    fun aThirtyTwoBitPhoneWhoseLibraryIsMissingStillGetsTheGapNamed() {
        val verdict = EngineDiagnosis.verdict(
            facts(abis = listOf("armeabi-v7a"), page = 4096, align = 4096, present = false)
        )
        assertEquals(EngineDiagnosis.Verdict.LIBRARY_MISSING, verdict)
    }

    @Test
    fun whateverAlignmentTheFileDeclaresIsWhatGetsReported() {
        // The real 64-bit file, with every PT_LOAD alignment rewritten to a value that is neither
        // of the two we ship, so the test proves the reader reads rather than assumes.
        val bytes = File("src/main/jniLibs/arm64-v8a/libhushyar_llama.so").readBytes()
        val phoff = readInt(bytes, 32)
        val phentsize = readShort(bytes, 54)
        val phnum = readShort(bytes, 56)
        for (i in 0 until phnum) {
            val at = phoff + i * phentsize
            if (readInt(bytes, at) == 1) writeLong(bytes, at + 48, 2048L)
        }
        assertEquals(2048L, Elf.maxLoadAlign(bytes))
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
    fun anArchitectureWeDoNotShipIsNamedAsUnsupported() {
        val verdict = EngineDiagnosis.verdict(
            facts(abis = listOf("x86", "mips"), failure = "dlopen failed: library \"libhushyar_llama.so\" not found")
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
    fun anUnsupportedArchitectureIsNotBlamedOnPageSize() {
        // A 16 KB phone whose ABI we do not ship must not be reported as a page-size problem.
        val verdict = EngineDiagnosis.verdict(
            facts(abis = listOf("x86"), page = 16_384, align = 4096)
        )
        assertEquals(EngineDiagnosis.Verdict.WRONG_ABI, verdict)
    }

    @Test
    fun aSixteenKilobytePhoneWithOurNewLibraryIsNoLongerAPageSizeProblem() {
        val verdict = EngineDiagnosis.verdict(
            facts(abis = listOf("arm64-v8a"), page = 16_384, align = 16_384, failure = null)
        )
        assertEquals(EngineDiagnosis.Verdict.OK, verdict)
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
