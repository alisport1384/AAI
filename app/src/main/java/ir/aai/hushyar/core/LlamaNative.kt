package ir.aai.hushyar.core

/**
 * The on-device inference engine: a thin binding to `libhushyar_llama.so`
 * (llama.cpp v1.0.0, GGUF models). Built by `scripts/build-llama-android.sh`
 * and shipped per-ABI in `app/src/main/jniLibs/`.
 *
 * Nothing here downloads, chooses or verifies a model — that is
 * `data/LocalModel.kt`. This file only answers one question honestly:
 * *is the native engine present on this device at all?*
 *
 * If the library fails to load (unsupported ABI, stripped install, corrupted
 * APK), [loadFailure] holds the reason and every caller must refuse to run
 * inference instead of pretending a model is there.
 */
object LlamaNative {

    /** Non-null when the shared library could not be loaded; the message is shown to the user. */
    val loadFailure: String? = runCatching { System.loadLibrary("hushyar_llama") }
        .exceptionOrNull()
        ?.let { it.message ?: it::class.java.simpleName }

    val available: Boolean get() = loadFailure == null

    /** e.g. "ARM_NEON = 1 | ..." — real capability string from ggml, used in the UI/log. */
    fun backendInfo(): String =
        if (!available) "engine missing: $loadFailure" else runCatching { nativeBackendInfo() }
            .getOrElse { "engine error: ${it.message}" }

    /** Why the last load/generate failed, as reported by the C++ side. */
    fun lastError(): String =
        if (!available) loadFailure ?: "engine missing" else runCatching { nativeLastError() }.getOrElse { "" }

    /** Returns a handle, or 0 on failure (reason in [lastError]). */
    fun load(modelPath: String, contextTokens: Int, threads: Int): Long =
        if (!available) 0L else runCatching { nativeLoad(modelPath, contextTokens, threads) }.getOrDefault(0L)

    /**
     * Runs one completion. The prompt crosses the JNI boundary as raw UTF-8 bytes and the answer
     * comes back as raw UTF-8 bytes, so Persian text and emoji survive token boundaries that
     * split a code point in half.
     */
    fun generate(handle: Long, promptUtf8: ByteArray, maxTokens: Int, temperature: Float, seed: Int): ByteArray? =
        if (!available || handle == 0L) null
        else runCatching { nativeGenerate(handle, promptUtf8, maxTokens, temperature, seed) }.getOrNull()

    fun cancel(handle: Long) {
        if (available && handle != 0L) runCatching { nativeCancel(handle) }
    }

    fun free(handle: Long) {
        if (available && handle != 0L) runCatching { nativeFree(handle) }
    }

    private external fun nativeBackendInfo(): String
    private external fun nativeLastError(): String
    private external fun nativeLoad(path: String, contextTokens: Int, threads: Int): Long
    private external fun nativeGenerate(
        handle: Long,
        promptUtf8: ByteArray,
        maxTokens: Int,
        temperature: Float,
        seed: Int
    ): ByteArray?

    private external fun nativeCancel(handle: Long)
    private external fun nativeFree(handle: Long)
}
