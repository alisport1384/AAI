package ir.aai.hushyar.data

import ir.aai.hushyar.core.Json
import ir.aai.hushyar.core.MiniJson
import ir.aai.hushyar.domain.Answer
import ir.aai.hushyar.domain.PersonalBackendConfig
import ir.aai.hushyar.domain.PersonalDocument
import ir.aai.hushyar.domain.PersonalDocsCodec
import ir.aai.hushyar.domain.PersonalIndexResult
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** Only the explicit personal-document route uses this port. Fake transport works without sockets. */
fun interface PersonalTransport {
    fun send(config: PersonalBackendConfig, method: String, path: String, body: String?): String
}

class PersonalBackendClient(private val transport: PersonalTransport = PinnedHttpsTransport()) {
    fun health(config: PersonalBackendConfig): String =
        PersonalDocsCodec.readHealth(transport.send(config, "GET", "/health", null))

    fun list(config: PersonalBackendConfig, threadId: String): List<PersonalDocument> =
        PersonalDocsCodec.readDocuments(transport.send(config, "GET", "/v1/documents?thread_id=${scope(threadId)}", null))

    fun index(config: PersonalBackendConfig, threadId: String, name: String, text: String, kind: String): PersonalIndexResult =
        PersonalDocsCodec.readIndex(transport.send(config, "POST", "/v1/documents",
            PersonalDocsCodec.requestDocument(threadId, name, text, kind)))

    fun ask(config: PersonalBackendConfig, threadId: String, question: String): Answer =
        PersonalDocsCodec.readAnswer(transport.send(config, "POST", "/v1/query",
            PersonalDocsCodec.requestQuestion(threadId, question)))

    fun delete(config: PersonalBackendConfig, threadId: String, id: String): Boolean {
        require(id.matches(Regex("[a-f0-9]{32}"))) { "شناسهٔ سند معتبر نیست" }
        return PersonalDocsCodec.readDeleted(transport.send(config, "DELETE",
            "/v1/documents/$id?thread_id=${scope(threadId)}", null))
    }

    fun purgeThread(config: PersonalBackendConfig, threadId: String): Int =
        PersonalDocsCodec.readDeletedCount(transport.send(config, "DELETE",
            "/v1/threads/${scope(threadId)}/documents", null))

    private fun scope(threadId: String): String {
        require(threadId.matches(Regex("[A-Za-z0-9_-]{3,70}"))) { "شناسهٔ گفتگو معتبر نیست" }
        return threadId
    }
}

/** Pin the *leaf certificate* of the user's own HTTPS server. No redirects or plaintext HTTP. */
class PinnedHttpsTransport : PersonalTransport {
    override fun send(config: PersonalBackendConfig, method: String, path: String, body: String?): String {
        require(method in setOf("GET", "POST", "DELETE") && path.startsWith("/") && !path.startsWith("//")) {
            "درخواست بک‌اند معتبر نیست"
        }
        var connection: HttpsURLConnection? = null
        try {
            val manager = object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                    throw CertificateException("گواهی سمت کاربر پذیرفته نیست")
                }
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    if (chain.isEmpty()) throw CertificateException("سرور گواهی ندارد")
                    chain[0].checkValidity()
                    val digest = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)
                    val expected = ByteArray(32) { index ->
                        config.pinSha256.substring(index * 2, index * 2 + 2).toInt(16).toByte()
                    }
                    if (!MessageDigest.isEqual(digest, expected)) {
                        throw CertificateException("اثر انگشت گواهی بک‌اند با مقدار ثبت‌شده برابر نیست")
                    }
                }
            }
            val tls = SSLContext.getInstance("TLS")
            tls.init(null, arrayOf(manager), SecureRandom())
            val opened = URL(config.baseUrl + path).openConnection() as HttpsURLConnection
            connection = opened
            opened.apply {
                sslSocketFactory = tls.socketFactory
                // Keep the platform hostname verifier: the certificate SAN must match the configured IP/host.
                instanceFollowRedirects = false
                requestMethod = method
                connectTimeout = 8_000
                readTimeout = 12_000
                useCaches = false
                setRequestProperty("Authorization", "Bearer ${config.token}")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Cache-Control", "no-store")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    require(bytes.size <= 160_000) { "درخواست از سقف ۱۶۰ کیلوبایت بیشتر است" }
                    setFixedLengthStreamingMode(bytes.size)
                    outputStream.use { it.write(bytes) }
                }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.use { input ->
                val buffer = ByteArray(8192)
                val out = ByteArrayOutputStream()
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (out.size() + n > 200_000) throw IllegalStateException("پاسخ سرور بیش از حد طولانی است")
                    out.write(buffer, 0, n)
                }
                out.toString(Charsets.UTF_8.name())
            }.orEmpty()
            if (status !in 200..299) {
                val code = runCatching {
                    (MiniJson.parse(response) as? Json.Obj)?.get("error")?.get("code")?.asStringOrNull()
                }.getOrNull()
                val message = when (status) {
                    401 -> "کلید دسترسی بک‌اند شخصی نادرست است"
                    413 -> "سند یا درخواست از سقف بک‌اند بیشتر است"
                    429 -> "درخواست‌ها بیش از حد مجاز بودند؛ بعداً دوباره تلاش کن"
                    else -> "سرویس شخصی پاسخ نداد (HTTP $status${code?.take(32)?.let { ", $it" }.orEmpty()})"
                }
                throw IllegalStateException(message)
            }
            return response
        } finally {
            connection?.disconnect()
        }
    }
}
