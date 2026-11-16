package ir.aai.hushyar.core

data class HttpRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap()
)

data class HttpResponse(
    val url: String,
    val status: Int,
    val body: String,
    val contentType: String? = null,
    val error: String? = null
) {
    val ok: Boolean get() = status in 200..299 && error == null
}

/** Transport port: the app uses OkHttp, tests use a fixture transport. */
interface HttpTransport {
    fun get(request: HttpRequest): HttpResponse
}

/** Honest, identifiable user agent (no evasion, no fingerprint spoofing). */
object HttpDefaults {

    const val USER_AGENT: String =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Hushyar/0.2 (+on-device research)"

    fun headers(langTag: String): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,text/plain;q=0.9",
        "Accept-Language" to if (langTag == "fa") "fa-IR,fa;q=0.9,en;q=0.6" else "en-US,en;q=0.9,fa;q=0.6"
    )
}
