package ir.aai.hushyar.data

import android.content.Context
import ir.aai.hushyar.core.HttpDefaults
import ir.aai.hushyar.core.LlamaNative
import ir.aai.hushyar.domain.LocalGeneration
import ir.aai.hushyar.domain.LocalGenerator
import ir.aai.hushyar.domain.ModelSpec
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The one download in the app that is *not* a web page: a single GGUF file, fetched once,
 * verified, and then used offline forever.
 *
 * Everything lands in `filesDir/hushyar/models/` — app-private storage, no permission, no
 * shared folder, removed with the app. Nothing here runs unless the user presses the button.
 */
class ModelStore(root: File) {

    constructor(context: Context) : this(File(context.filesDir, "hushyar/models"))

    val dir: File = root.apply { mkdirs() }

    fun file(spec: ModelSpec): File = File(dir, spec.fileName)

    /** In-flight or interrupted download, kept so a retry resumes instead of restarting. */
    fun part(spec: ModelSpec): File = File(dir, spec.fileName + ".part")

    fun installed(spec: ModelSpec): Boolean {
        val f = file(spec)
        return f.isFile && f.length() == spec.bytes
    }

    fun bytesOnDisk(spec: ModelSpec): Long =
        listOf(file(spec), part(spec)).filter { it.isFile }.sumOf { it.length() }

    fun delete(spec: ModelSpec): Boolean {
        val a = file(spec).delete()
        val b = part(spec).delete()
        return a || b
    }

    /** Total bytes this app spends on models (shown in the UI so nothing is hidden). */
    fun totalBytes(): Long = dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    fun fileNames(): List<String> = dir.listFiles()?.filter { it.isFile }?.map { it.name }?.sorted() ?: emptyList()

    fun freeSpace(): Long = dir.usableSpace

    companion object {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered(1 shl 16).use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/** One open byte stream from the host, with the Range bookkeeping we need to resume. */
data class FetchHandle(val status: Int, val totalBytes: Long, val stream: InputStream)

/** Port so the installer can be tested without a 500 MB network round trip. */
interface ModelFetcher {
    fun open(url: String, fromByte: Long): FetchHandle
}

sealed class InstallResult {
    object Installed : InstallResult()
    data class Failed(val reason: String) : InstallResult()
    object Cancelled : InstallResult()
}

/**
 * Downloads, resumes and verifies one model file.
 *
 * Failure is always explicit: wrong size, wrong SHA-256, no space, HTTP error. A file that
 * does not verify is deleted, so the app can never load a half-written model and crash later.
 */
class ModelInstaller(
    private val store: ModelStore,
    private val fetcher: ModelFetcher
) {

    fun install(
        spec: ModelSpec,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false }
    ): InstallResult {
        if (store.installed(spec)) return InstallResult.Installed

        val target = store.file(spec)
        val part = store.part(spec)
        if (part.isFile && part.length() > spec.bytes) part.delete()

        var have = if (part.isFile) part.length() else 0L
        if (have > 0 && have == spec.bytes) {
            // A complete but never-finalised part: verify and promote instead of re-downloading.
            return finish(spec, part, target, onProgress)
        }

        val needed = spec.bytes - have
        val free = store.freeSpace()
        if (free in 1 until needed + (64L * 1024 * 1024)) {
            return InstallResult.Failed(
                "فضای کافی نیست: ${(needed / (1024 * 1024))} مگابایت لازم است، " +
                    "${(free / (1024 * 1024))} مگابایت آزاد است."
            )
        }

        var handle: FetchHandle = try {
            fetcher.open(spec.url, have)
        } catch (t: Throwable) {
            return InstallResult.Failed("اتصال به سرور مدل برقرار نشد: ${t.message ?: t::class.java.simpleName}")
        }

        // The host may ignore Range (200 instead of 206): then we start over, honestly.
        if (have > 0 && handle.status != 206) {
            runCatching { handle.stream.close() }
            part.delete()
            have = 0L
            handle = try {
                fetcher.open(spec.url, 0)
            } catch (t: Throwable) {
                return InstallResult.Failed("اتصال به سرور مدل برقرار نشد: ${t.message ?: t::class.java.simpleName}")
            }
        }
        if (handle.status !in 200..299) {
            runCatching { handle.stream.close() }
            return InstallResult.Failed("سرور مدل پاسخ ${handle.status} داد (${spec.url})")
        }

        val total = if (handle.totalBytes > 0) handle.totalBytes else spec.bytes
        try {
            FileOutputStream(part, have > 0).use { out ->
                handle.stream.use { input ->
                    val buffer = ByteArray(1 shl 16)
                    var written = have
                    var lastTick = 0L
                    while (true) {
                        if (isCancelled()) return InstallResult.Cancelled
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        written += read
                        if (written - lastTick >= (1L shl 20) || written >= total) {
                            lastTick = written
                            onProgress(written, total)
                        }
                    }
                    out.flush()
                    onProgress(written, total)
                }
            }
        } catch (t: Throwable) {
            return InstallResult.Failed("دانلود قطع شد: ${t.message ?: t::class.java.simpleName}")
        }

        return finish(spec, part, target, onProgress)
    }

    private fun finish(
        spec: ModelSpec,
        part: File,
        target: File,
        onProgress: (Long, Long) -> Unit
    ): InstallResult {
        val size = if (part.isFile) part.length() else 0L
        if (size != spec.bytes) {
            return InstallResult.Failed(
                "حجم فایل درست نیست: ${size} بایت دریافت شد، ${spec.bytes} بایت لازم بود. فایل ناقص پاک می‌شود."
            ).also { part.delete() }
        }
        val digest = runCatching { ModelStore.sha256(part) }.getOrNull()
        if (digest == null || !digest.equals(spec.sha256, ignoreCase = true)) {
            return InstallResult.Failed(
                "چک‌سام SHA-256 مطابق انتظار نبود (${digest?.take(12) ?: "خطا"}…)؛ فایل پاک شد و " +
                    "هیچ مدلی نصب نشد."
            ).also { part.delete() }
        }
        if (!part.renameTo(target)) {
            return InstallResult.Failed("فایل دانلود شد ولی جای‌گذاری آن در پوشهٔ مدل‌ها ناموفق بود.")
        }
        onProgress(spec.bytes, spec.bytes)
        return InstallResult.Installed
    }
}

/** Real network path: plain HTTPS GET with Range, no SDK, no key, same identity as the rest. */
class HttpModelFetcher(private val userAgent: String = HttpDefaults.USER_AGENT) : ModelFetcher {

    override fun open(url: String, fromByte: Long): FetchHandle {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "application/octet-stream,*/*")
            if (fromByte > 0) setRequestProperty("Range", "bytes=$fromByte-")
        }
        val status = connection.responseCode
        val declared = connection.contentLengthLong.let { length ->
            if (status == 206) {
                // Content-Range: bytes 1000-999999/1000000 → the full size is after the slash.
                connection.getHeaderField("Content-Range")
                    ?.substringAfterLast('/')
                    ?.toLongOrNull()
                    ?: (length + fromByte)
            } else {
                length
            }
        }
        val stream = if (status in 200..299) connection.inputStream else {
            val body = runCatching { connection.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
            throw IllegalStateException("HTTP $status" + (body?.take(120)?.let { ": $it" } ?: ""))
        }
        return FetchHandle(status, declared, stream)
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 60_000
    }
}

/**
 * The native engine, wrapped so the rest of the app never touches JNI directly.
 *
 * One model is kept resident (a 0.5 GB file is not something to reload per question). Every
 * call is guarded: if the library is missing or the model was deleted behind our back, the
 * answer is a [LocalGeneration] carrying the reason — never a silent empty string.
 */
interface NativeLlama {
    val available: Boolean
    fun backendInfo(): String
    fun lastError(): String
    fun load(path: String, contextTokens: Int, threads: Int): Long
    fun generate(handle: Long, prompt: ByteArray, maxTokens: Int, temperature: Float, seed: Int): ByteArray?
    fun cancel(handle: Long)
    fun free(handle: Long)
}

object LlamaNativeAdapter : NativeLlama {
    override val available: Boolean get() = LlamaNative.available
    override fun backendInfo(): String = LlamaNative.backendInfo()
    override fun lastError(): String = LlamaNative.lastError()
    override fun load(path: String, contextTokens: Int, threads: Int): Long =
        LlamaNative.load(path, contextTokens, threads)

    override fun generate(handle: Long, prompt: ByteArray, maxTokens: Int, temperature: Float, seed: Int): ByteArray? =
        LlamaNative.generate(handle, prompt, maxTokens, temperature, seed)

    override fun cancel(handle: Long) = LlamaNative.cancel(handle)
    override fun free(handle: Long) = LlamaNative.free(handle)
}

class LocalEngine(
    private val native: NativeLlama = LlamaNativeAdapter,
    private val threads: () -> Int = { Runtime.getRuntime().availableProcessors().coerceIn(1, 4) }
) : LocalGenerator {

    @Volatile
    private var handle = 0L

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var loadedSpec: ModelSpec? = null
        private set

    override val ready: Boolean get() = handle != 0L

    val backend: String get() = if (native.available) native.backendInfo() else "engine missing"

    /** Is the native library present at all? Separate from "is a model loaded". */
    val nativeAvailable: Boolean get() = native.available

    /** Loads (or swaps) the model. Returns false with a reason in [lastError] on failure. */
    fun load(file: File, spec: ModelSpec, contextTokens: Int = spec.contextTokens.coerceAtMost(4096)): Boolean {
        if (!native.available) {
            lastError = "کتابخانهٔ موتور روی این دستگاه بارگذاری نشد: ${LlamaNative.loadFailure}"
            return false
        }
        if (!file.isFile || file.length() != spec.bytes) {
            lastError = "فایل مدل پیدا نشد یا کامل نیست: ${file.name}"
            return false
        }
        unload()
        val h = native.load(file.absolutePath, contextTokens, threads())
        if (h == 0L) {
            lastError = "بارگذاری مدل ناموفق بود: ${native.lastError()}"
            return false
        }
        handle = h
        loadedSpec = spec
        lastError = null
        return true
    }

    fun unload() {
        val h = handle
        handle = 0L
        loadedSpec = null
        if (h != 0L) native.free(h)
    }

    override fun generate(prompt: String, maxTokens: Int): LocalGeneration {
        val h = handle
        if (h == 0L) return LocalGeneration(error = lastError ?: "مدل محلی بارگذاری نشده است")
        val started = System.currentTimeMillis()
        val bytes = native.generate(h, prompt.toByteArray(Charsets.UTF_8), maxTokens, 0f, 0)
        val millis = System.currentTimeMillis() - started
        if (bytes == null || bytes.isEmpty()) {
            return LocalGeneration(error = native.lastError().ifBlank { "مدل خروجی نداد" }, millis = millis)
        }
        val text = String(bytes, Charsets.UTF_8).trim()
        if (text.isEmpty()) return LocalGeneration(error = "مدل خروجی خالی داد", millis = millis)
        return LocalGeneration(text = text, millis = millis, tokens = text.length / 4)
    }

    override fun cancel() {
        val h = handle
        if (h != 0L) native.cancel(h)
    }
}
