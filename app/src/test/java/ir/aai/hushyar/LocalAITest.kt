package ir.aai.hushyar

import ir.aai.hushyar.data.FetchHandle
import ir.aai.hushyar.data.InstallResult
import ir.aai.hushyar.data.LocalEngine
import ir.aai.hushyar.data.ModelFetcher
import ir.aai.hushyar.data.ModelInstaller
import ir.aai.hushyar.data.ModelStore
import ir.aai.hushyar.data.NativeLlama
import ir.aai.hushyar.domain.AnswerDraft
import ir.aai.hushyar.domain.Citation
import ir.aai.hushyar.domain.Fact
import ir.aai.hushyar.domain.Grounding
import ir.aai.hushyar.domain.LocalChatter
import ir.aai.hushyar.domain.LocalGeneration
import ir.aai.hushyar.domain.LocalGenerator
import ir.aai.hushyar.domain.LocalPhraser
import ir.aai.hushyar.domain.LocalPrompt
import ir.aai.hushyar.domain.ModelCatalog
import ir.aai.hushyar.domain.ModelSpec
import ir.aai.hushyar.domain.TemplatePhraser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

/**
 * Tests for the v0.7 on-device core. There is no phone, no native library and no 500 MB file
 * here: the engine is a fake that obeys the same port, which is exactly how the real one is
 * wired in. What is asserted is the part that can silently go wrong — grounding, fallback,
 * download verification — because "the model said something" is never good enough on its own.
 */
class LocalAITest {

    // ---------------------------------------------------------------- catalogue

    @Test
    fun catalogueIsPinnedAndDownloadable() {
        assertTrue("catalogue must not be empty", ModelCatalog.all.isNotEmpty())
        assertEquals(1, ModelCatalog.all.count { it.recommended })
        assertEquals(ModelCatalog.recommended, ModelCatalog.defaultOrRecommended())
        val ids = ModelCatalog.all.map { it.id }
        assertEquals("model ids must be unique", ids.size, ids.toSet().size)
        ModelCatalog.all.forEach { spec ->
            assertTrue("${spec.id} must use https", spec.url.startsWith("https://"))
            assertTrue("${spec.id} must point at a .gguf file", spec.url.endsWith(spec.fileName))
            assertEquals("${spec.id} sha256 must be 64 hex chars", 64, spec.sha256.length)
            assertTrue("${spec.id} sha256 must be hex", spec.sha256.all { it in "0123456789abcdef" })
            assertTrue("${spec.id} size must be positive", spec.bytes > 0)
            assertTrue("${spec.id} context too small", spec.contextTokens >= 2048)
            assertTrue("${spec.id} needs a licence", spec.license.isNotBlank())
            assertTrue("${spec.id} needs an honest note", spec.note.length > 20)
        }
    }

    @Test
    fun unknownModelFallsBackToRecommended() {
        assertEquals(ModelCatalog.recommended, ModelCatalog.find("does-not-exist"))
        assertEquals(ModelCatalog.recommended, ModelCatalog.find(null))
    }

    // ---------------------------------------------------------------- prompts

    @Test
    fun eachFamilyGetsItsOwnChatTemplate() {
        val qwen = LocalPrompt.chat("qwen2.5", "S", "U")
        assertTrue(qwen.contains("<|im_start|>system\nS<|im_end|>"))
        assertTrue(qwen.contains("<|im_start|>user\nU<|im_end|>"))
        assertTrue("prompt must end where the assistant speaks", qwen.endsWith("<|im_start|>assistant\n"))

        val gemma = LocalPrompt.chat("gemma3", "S", "U")
        assertTrue(gemma.contains("<start_of_turn>user"))
        assertTrue(gemma.contains("S"))
        assertTrue(gemma.endsWith("<start_of_turn>model\n"))

        val generic = LocalPrompt.chat("something-else", "S", "U")
        assertTrue(generic.contains("### System") && generic.contains("### Assistant"))
    }

    @Test
    fun groundedPromptCarriesEveryFactAndForbidsInvention() {
        val draft = priceDraft()
        val prompt = LocalPrompt.groundedRewrite(ModelCatalog.recommended, "fa", draft)
        draft.facts.forEach { fact ->
            assertTrue("prompt must contain fact '${fact.label}'", prompt.contains(fact.label))
            assertTrue("prompt must contain value '${fact.value}'", prompt.contains(fact.value))
        }
        assertTrue("the model must be told not to invent numbers", prompt.contains("هیچ عدد"))
        assertTrue("the answer cue must be last", prompt.trimEnd().endsWith("assistant") || prompt.contains("جمله:"))
        assertTrue("the one-shot example must be present", prompt.contains("لپ‌تاپ ایسوس"))
    }

    // ---------------------------------------------------------------- grounding

    @Test
    fun persianAndLatinDigitsCanonicaliseToTheSameNumber() {
        assertEquals("12000", Grounding.canonical("۱۲٬۰۰۰"))
        assertEquals("12000", Grounding.canonical("12,000"))
        assertEquals("12000.5", Grounding.canonical("۱۲٬۰۰۰٫۵"))
        assertEquals("1.5", Grounding.canonical("1.50"))
        assertEquals("0", Grounding.canonical("۰"))
    }

    @Test
    fun groundedSentenceIsAccepted() {
        val draft = priceDraft()
        val text = "قیمت این کالا در آخرین آگهی ۱۲٬۰۰۰٬۰۰۰ تومان است."
        assertTrue(Grounding.check(draft, text, "fa").ok)
    }

    /**
     * Verbatim output of the real Qwen2.5-0.5B-Instruct Q4_K_M file (downloaded and run on
     * 2026-11-16 with this exact prompt template). The guard must accept it: this is what the
     * shipped prompt actually produces, not what we hope it produces.
     */
    @Test
    fun realModelOutputIsAcceptedByTheGuard() {
        val text = REAL_QWEN_OUTPUT
        val verdict = Grounding.check(priceDraft(), text, "fa")
        assertTrue("real model output must pass the guard: ${verdict.reason}", verdict.ok)
    }

    /** The same file, without the example turn, echoed the data list instead of answering. */
    @Test
    fun echoingTheDataListIsRejectedAsTooLong() {
        val echo = "موضوع: قیمت «سامسونگ A55» بر پایهٔ ۳ آگهی\n\nآخرین قیمت: ۱۲٬۰۰۰٬۰۰۰ تومان\n\n" +
            "میانه: ۱۱٬۸۰۰٬۰۰۰ تومان\n\nتعداد آگهی: ۳\n\nمنابع: دیوار • ۱۲٬۰۰۰٬۰۰۰؛ شیپور • ۱۱٬۸۰۰٬۰۰۰"
        val verdict = Grounding.check(priceDraft(), echo, "fa")
        assertFalse("a data dump is not an answer", verdict.ok)
    }

    @Test
    fun inventedNumberIsRejected() {
        val draft = priceDraft()
        val text = "قیمت این کالا ۹۹۹٬۰۰۰٬۰۰۰ تومان است."
        val verdict = Grounding.check(draft, text, "fa")
        assertFalse(verdict.ok)
        assertTrue("reason must name the number", verdict.reason!!.contains("999"))
    }

    @Test
    fun inventedLinkIsRejected() {
        val verdict = Grounding.check(priceDraft(), "طبق https://example.com قیمت مشخص است.", "fa")
        assertFalse(verdict.ok)
        assertTrue(verdict.reason!!.contains("لینک"))
    }

    @Test
    fun nonPersianAnswerIsRejectedForPersianDrafts() {
        val verdict = Grounding.check(priceDraft(), "The price is 12,000,000 toman.", "fa")
        assertFalse(verdict.ok)
        assertTrue(verdict.reason!!.contains("فارسی"))
    }

    @Test
    fun tooShortAndTooLongAreRejected() {
        assertFalse(Grounding.check(priceDraft(), "خوب", "fa").ok)
        assertFalse(Grounding.check(priceDraft(), "قیمت ".repeat(200), "fa").ok)
    }

    @Test
    fun preambleIsStrippedOrRejected() {
        val draft = priceDraft()
        val verdict = Grounding.check(draft, "پاسخ: قیمت ۱۲٬۰۰۰٬۰۰۰ تومان است.", "fa")
        assertTrue("a 'پاسخ:' prefix is cleaned, not fatal", verdict.ok)
        assertEquals("قیمت ۱۲٬۰۰۰٬۰۰۰ تومان است.", Grounding.clean("پاسخ: قیمت ۱۲٬۰۰۰٬۰۰۰ تومان است."))
    }

    // ---------------------------------------------------------------- phraser

    @Test
    fun localPhraserReplacesTheSentenceAndKeepsTheEvidence() {
        val draft = priceDraft()
        val fake = FakeGenerator("قیمت این کالا ۱۲٬۰۰۰٬۰۰۰ تومان است.")
        val answer = LocalPhraser("fa", fake, ModelCatalog.recommended).render(draft)

        assertEquals("قیمت این کالا ۱۲٬۰۰۰٬۰۰۰ تومان است.", answer.text)
        assertEquals("bullets must still come from the facts", draft.facts.size, answer.bullets.size)
        assertEquals("citations must be untouched", draft.citations, answer.citations)
        assertEquals(draft.confidence, answer.confidence, 0.0001)
        assertTrue(answer.trace.any { it.contains("مدل محلی") })
        assertEquals(1, fake.calls)
    }

    @Test
    fun inventedNumberFallsBackToTheTemplateWithAReason() {
        val draft = priceDraft()
        val fake = FakeGenerator("قیمت این کالا ۷۷۷٬۰۰۰٬۰۰۰ تومان است.")
        val phraser = LocalPhraser("fa", fake, ModelCatalog.recommended)
        val answer = phraser.render(draft)

        assertEquals("fallback text is the deterministic headline", draft.headline, answer.text)
        assertTrue(answer.trace.any { it.contains("قالب") })
        assertNotNull(phraser.lastVerdict)
        assertFalse(phraser.lastVerdict!!.ok)
        assertTrue(phraser.lastVerdict!!.reason!!.contains("777"))
    }

    @Test
    fun failureToGenerateFallsBackAndSaysSo() {
        val fake = FakeGenerator(text = null, error = "context window too small")
        val phraser = LocalPhraser("fa", fake, ModelCatalog.recommended)
        val answer = phraser.render(priceDraft())
        assertEquals(priceDraft().headline, answer.text)
        assertTrue(answer.trace.any { it.contains("context window too small") })
    }

    @Test
    fun refusedDraftNeverReachesTheModel() {
        val fake = FakeGenerator("قیمت ۱۲٬۰۰۰٬۰۰۰ تومان است.")
        val draft = priceDraft().copy(refused = true, missing = listOf("قیمت ثبت‌شده"))
        val answer = LocalPhraser("fa", fake, ModelCatalog.recommended).render(draft)
        assertEquals("the model must not be asked to speak over a refusal", 0, fake.calls)
        assertTrue(answer.refused)
    }

    @Test
    fun missingEngineFallsBackBeforeGenerating() {
        val fake = FakeGenerator("قیمت ۱۲٬۰۰۰٬۰۰۰ تومان است.", ready = false)
        val answer = LocalPhraser("fa", fake, ModelCatalog.recommended).render(priceDraft())
        assertEquals(0, fake.calls)
        assertEquals(priceDraft().headline, answer.text)
        assertTrue(answer.trace.any { it.contains("آماده نیست") })
    }

    @Test
    fun englishDraftAcceptsEnglishSentence() {
        val draft = priceDraft(lang = "en")
        val fake = FakeGenerator("The latest listing for this item is 12,000,000 toman.")
        val answer = LocalPhraser("en", fake, ModelCatalog.recommended).render(draft)
        assertTrue(answer.text.startsWith("The latest listing"))
    }

    // ---------------------------------------------------------------- local chat

    @Test
    fun localChatRefusesWithoutAnEngine() {
        val reply = LocalChatter(FakeGenerator("سلام", ready = false)).reply("سلام")
        assertFalse(reply.ok)
        assertNotNull(reply.error)
        assertTrue(reply.error!!.contains("نصب") || reply.error!!.contains("بارگذاری"))
    }

    @Test
    fun localChatReturnsModelTextWhenReady() {
        val reply = LocalChatter(FakeGenerator("هوای آفتابی برای پیاده‌روی خوب است.")).reply("هوا چطور است؟")
        assertTrue(reply.ok)
        assertEquals("هوای آفتابی برای پیاده‌روی خوب است.", reply.text)
    }

    @Test
    fun emptyLocalQuestionIsRejected() {
        assertFalse(LocalChatter(FakeGenerator("x")).reply("   ").ok)
    }

    // ---------------------------------------------------------------- engine

    @Test
    fun engineRefusesToLoadAMissingFile() {
        val engine = LocalEngine(FakeNative())
        val folder = File.createTempFile("model", ".dir").also { it.delete(); it.mkdirs() }
        val ok = engine.load(File(folder, "nothing.gguf"), smallSpec(bytes = 4))
        assertFalse(ok)
        assertFalse(engine.ready)
        assertTrue(engine.lastError!!.contains("پیدا نشد"))
    }

    @Test
    fun engineReportsNativeFailureInsteadOfPretending() {
        val native = FakeNative(loadReturnsZero = true)
        val engine = LocalEngine(native)
        val file = File.createTempFile("model", ".gguf").apply { writeBytes(ByteArray(4)) }
        val ok = engine.load(file, smallSpec(bytes = 4))
        assertFalse(ok)
        assertTrue(engine.lastError!!.contains("ناموفق"))
        assertFalse(engine.ready)
    }

    @Test
    fun engineGeneratesAndUnloads() {
        val native = FakeNative()
        val engine = LocalEngine(native)
        val file = File.createTempFile("model", ".gguf").apply { writeBytes(ByteArray(4)) }
        assertTrue(engine.load(file, smallSpec(bytes = 4)))
        assertTrue(engine.ready)

        val generation = engine.generate("سلام", 16)
        assertTrue(generation.ok)
        assertEquals("سلام دنیا", generation.text)
        assertEquals(1, native.generations)

        engine.unload()
        assertFalse(engine.ready)
        assertFalse(engine.generate("سلام", 16).ok)
        assertEquals("freed", native.freed)
    }

    @Test
    fun engineSurfacesNativeErrorText() {
        val engine = LocalEngine(FakeNative(generateBytes = null))
        val file = File.createTempFile("model", ".gguf").apply { writeBytes(ByteArray(4)) }
        engine.load(file, smallSpec(bytes = 4))
        val generation = engine.generate("سلام", 16)
        assertFalse(generation.ok)
        assertTrue(generation.error!!.contains("decode"))
    }

    // ---------------------------------------------------------------- download

    @Test
    fun storeSeesOnlyACompleteVerifiedFileAsInstalled() {
        val folder = temporaryFolder.newFolder("models")
        val store = ModelStore(folder)
        val spec = smallSpec()
        assertFalse(store.installed(spec))
        store.file(spec).writeBytes(ByteArray(spec.bytes.toInt() - 1))
        assertFalse("a truncated file is never 'installed'", store.installed(spec))
        store.file(spec).writeBytes(ByteArray(spec.bytes.toInt()))
        assertTrue(store.installed(spec))
        assertTrue(store.delete(spec))
        assertFalse(store.installed(spec))
        assertEquals(0L, store.totalBytes())
    }

    @Test
    fun installerWritesVerifiesAndPromotesTheFile() {
        val payload = "GGUF-test-payload-0123456789".toByteArray()
        val spec = smallSpec(bytes = payload.size.toLong(), sha256 = sha256(payload))
        val store = ModelStore(temporaryFolder.newFolder("models"))
        val result = ModelInstaller(store, FakeFetcher(payload)).install(spec)
        assertTrue(result is InstallResult.Installed)
        assertTrue(store.installed(spec))
        assertFalse("no .part file may survive a success", store.part(spec).exists())
        assertArrayEquals(payload, store.file(spec).readBytes())
    }

    @Test
    fun checksumMismatchDeletesTheDownload() {
        val payload = "payload".toByteArray()
        val spec = smallSpec(bytes = payload.size.toLong(), sha256 = "0".repeat(64))
        val store = ModelStore(temporaryFolder.newFolder("models"))
        val result = ModelInstaller(store, FakeFetcher(payload)).install(spec)
        assertTrue(result is InstallResult.Failed)
        assertTrue((result as InstallResult.Failed).reason.contains("SHA-256"))
        assertFalse(store.part(spec).exists())
        assertFalse(store.file(spec).exists())
    }

    @Test
    fun shortDownloadIsRejected() {
        val payload = "0123456789".toByteArray()
        val spec = smallSpec(bytes = 20, sha256 = sha256(payload))
        val store = ModelStore(temporaryFolder.newFolder("models"))
        val result = ModelInstaller(store, FakeFetcher(payload)).install(spec)
        assertTrue(result is InstallResult.Failed)
        assertTrue((result as InstallResult.Failed).reason.contains("حجم"))
        assertFalse(store.part(spec).exists())
    }

    @Test
    fun interruptedDownloadResumesFromThePartFile() {
        val payload = "0123456789abcdef".toByteArray()
        val spec = smallSpec(bytes = payload.size.toLong(), sha256 = sha256(payload))
        val store = ModelStore(temporaryFolder.newFolder("models"))
        store.part(spec).writeBytes(payload.copyOfRange(0, 6))

        val fetcher = FakeFetcher(payload, honourRange = true)
        val progress = mutableListOf<Long>()
        val result = ModelInstaller(store, fetcher).install(spec, onProgress = { done, _ -> progress.add(done) })

        assertTrue(result is InstallResult.Installed)
        assertEquals("must ask for the remaining bytes only", 6L, fetcher.lastFromByte)
        assertArrayEquals(payload, store.file(spec).readBytes())
        assertTrue(progress.isNotEmpty())
    }

    @Test
    fun hostThatIgnoresRangeRestartsCleanly() {
        val payload = "0123456789abcdef".toByteArray()
        val spec = smallSpec(bytes = payload.size.toLong(), sha256 = sha256(payload))
        val store = ModelStore(temporaryFolder.newFolder("models"))
        store.part(spec).writeBytes(payload.copyOfRange(0, 6))

        // honourRange = false: the server answers 200 with the whole body.
        val result = ModelInstaller(store, FakeFetcher(payload, honourRange = false)).install(spec)
        assertTrue(result is InstallResult.Installed)
        assertArrayEquals(payload, store.file(spec).readBytes())
    }

    @Test
    fun cancelledDownloadKeepsThePartForNextTime() {
        val payload = ByteArray(4096) { it.toByte() }
        val spec = smallSpec(bytes = payload.size.toLong(), sha256 = sha256(payload))
        val store = ModelStore(temporaryFolder.newFolder("models"))
        var checks = 0
        val result = ModelInstaller(store, FakeFetcher(payload)).install(
            spec,
            isCancelled = { checks++ > 0 }
        )
        assertTrue(result is InstallResult.Cancelled)
        assertFalse(store.installed(spec))
    }

    @Test
    fun alreadyInstalledFileIsNotDownloadedAgain() {
        val payload = "abc".toByteArray()
        val spec = smallSpec(bytes = 3, sha256 = sha256(payload))
        val store = ModelStore(temporaryFolder.newFolder("models"))
        store.file(spec).writeBytes(payload)
        val fetcher = FakeFetcher(payload)
        val result = ModelInstaller(store, fetcher).install(spec)
        assertTrue(result is InstallResult.Installed)
        assertEquals("no network call for an installed model", 0, fetcher.opens)
    }

    @Test
    fun httpErrorIsReportedNotSwallowed() {
        val payload = "abc".toByteArray()
        val spec = smallSpec(bytes = 3, sha256 = sha256(payload))
        val store = ModelStore(temporaryFolder.newFolder("models"))
        val result = ModelInstaller(store, ThrowingFetcher()).install(spec)
        assertTrue(result is InstallResult.Failed)
        assertTrue((result as InstallResult.Failed).reason.contains("اتصال"))
    }

    // ---------------------------------------------------------------- ui state

    @Test
    fun uiStateComputesRealProgress() {
        val ui = ir.aai.hushyar.domain.LocalModelUi(
            specId = "qwen2.5-0.5b",
            downloadedBytes = 245_700_016,
            totalBytes = 491_400_032
        )
        assertEquals(50, ui.percent)
        assertEquals(0.5f, ui.progress, 0.01f)
        assertEquals("qwen2.5-0.5b", ui.spec.id)
    }

    @Test
    fun missingEngineIsVisibleNotSilent() {
        val ui = ir.aai.hushyar.domain.LocalModelUi(phase = ir.aai.hushyar.domain.ModelPhase.ENGINE_ERROR)
        assertEquals(0f, ui.progress, 0f)
        assertEquals(0, ui.percent)
        assertNotNull("phase must be expressible in the UI", ui.phase)
        assertNull(ui.message)
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Verbatim `llama-completion` output of Qwen2.5-0.5B-Instruct Q4_K_M with the exact prompt
     * this app builds (checked 2026-11-16, greedy, 2 threads). Kept next to the guard because a
     * prompt change that breaks it should fail here, not on a user's phone.
     */
    private val REAL_QWEN_OUTPUT =
        "قیمت «سامسونگ A55» ۱۲٬۰۰۰٬۰۰۰ تومان است و ۳ آگهی از آن موجود است."

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun ModelCatalog.defaultOrRecommended(): ModelSpec = ModelCatalog.recommended

    private fun priceDraft(lang: String = "fa"): AnswerDraft = AnswerDraft(
        intent = "price.lookup",
        headline = if (lang == "en") "Price of \"Samsung A55\" from 3 listings:"
        else "قیمت «سامسونگ A55» بر پایهٔ ۳ آگهی:",
        facts = listOf(
            Fact("آخرین قیمت", "۱۲٬۰۰۰٬۰۰۰ تومان"),
            Fact("میانه", "۱۱٬۸۰۰٬۰۰۰ تومان"),
            Fact("تعداد آگهی", "۳")
        ),
        citations = listOf(
            Citation("L:1", "دیوار • ۱۲٬۰۰۰٬۰۰۰", "https://divar.ir/x"),
            Citation("L:2", "شیپور • ۱۱٬۸۰۰٬۰۰۰", "https://sheypoor.com/y")
        ),
        missing = emptyList(),
        confidence = 0.74,
        refused = false,
        lang = lang
    )

    private fun smallSpec(bytes: Long = 10, sha256: String = "a".repeat(64)): ModelSpec = ModelSpec(
        id = "test-model",
        title = "Test Model",
        family = "qwen2.5",
        fileName = "test-model.gguf",
        url = "https://example.invalid/test-model.gguf",
        bytes = bytes,
        sha256 = sha256,
        contextTokens = 2048,
        license = "test",
        note = "a note long enough to look like a real one"
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        assertEquals(String(expected, Charsets.UTF_8), String(actual, Charsets.UTF_8))
        assertEquals(expected.size, actual.size)
    }

    // ---------------------------------------------------------------- fakes

    private class FakeGenerator(
        private val text: String? = "قیمت ۱۲٬۰۰۰٬۰۰۰ تومان است.",
        private val error: String? = null,
        override val ready: Boolean = true
    ) : LocalGenerator {
        var calls = 0
            private set

        override fun generate(prompt: String, maxTokens: Int): LocalGeneration {
            calls++
            return LocalGeneration(text = text, error = error, millis = 12, tokens = 8)
        }

        override fun cancel() = Unit
    }

    private class FakeNative(
        private val loadReturnsZero: Boolean = false,
        private val generateBytes: ByteArray? = "سلام دنیا".toByteArray()
    ) : NativeLlama {
        var generations = 0
            private set
        var freed = "none"

        override val available: Boolean = true
        override fun backendInfo(): String = "fake-arm64"
        override fun lastError(): String = if (generateBytes == null) "llama_decode failed (rc=-1)" else ""
        override fun load(path: String, contextTokens: Int, threads: Int): Long = if (loadReturnsZero) 0L else 7L
        override fun generate(handle: Long, prompt: ByteArray, maxTokens: Int, temperature: Float, seed: Int): ByteArray? {
            generations++
            return generateBytes
        }

        override fun cancel(handle: Long) = Unit
        override fun free(handle: Long) {
            freed = "freed"
        }
    }

    /** Serves [payload] from [fromByte], like a Range-capable host (or one that ignores Range). */
    private class FakeFetcher(
        private val payload: ByteArray,
        private val honourRange: Boolean = true
    ) : ModelFetcher {
        var opens = 0
            private set
        var lastFromByte = 0L
            private set

        override fun open(url: String, fromByte: Long): FetchHandle {
            opens++
            lastFromByte = fromByte
            val start = if (honourRange) fromByte else 0L
            val slice = payload.copyOfRange(start.toInt().coerceAtMost(payload.size), payload.size)
            return FetchHandle(
                status = if (honourRange && fromByte > 0) 206 else 200,
                totalBytes = payload.size.toLong(),
                stream = ByteArrayInputStream(slice)
            )
        }
    }

    private class ThrowingFetcher : ModelFetcher {
        override fun open(url: String, fromByte: Long): FetchHandle =
            throw java.io.IOException("network unreachable")
    }
}
