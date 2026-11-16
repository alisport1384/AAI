package ir.aai.hushyar.data

import ir.aai.hushyar.core.HttpRequest
import ir.aai.hushyar.core.HttpResponse
import ir.aai.hushyar.core.HttpTransport
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * The one and only network egress of the app: plain HTTPS GET via HttpURLConnection.
 * No SDK, no key, no telemetry, no cookies persisted, no UA spoofing, no proxy rotation.
 * Bounded body size and hard timeouts keep a slow or hostile server from stalling the app.
 */
class HttpUrlTransport(private val maxBytes: Int = 800_000) : HttpTransport {

    override fun get(request: HttpRequest): HttpResponse {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                useCaches = false
                for ((name, value) in request.headers) setRequestProperty(name, value)
            }
            val status = connection.responseCode
            val contentType = connection.contentType
            val stream: InputStream? = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.let { readLimited(it) }
            HttpResponse(
                url = connection.url.toString(),
                status = status,
                body = bytes?.let { decode(it, charsetOf(contentType)) } ?: "",
                contentType = contentType
            )
        } catch (t: Throwable) {
            HttpResponse(request.url, 0, "", error = t.message ?: t::class.java.simpleName)
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    private fun readLimited(stream: InputStream): ByteArray {
        stream.use { input ->
            val out = ByteArrayOutputStream(64 * 1024)
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                val allowed = minOf(read, maxBytes - total)
                if (allowed <= 0) break
                out.write(buffer, 0, allowed)
                total += allowed
                if (total >= maxBytes) break
            }
            return out.toByteArray()
        }
    }

    private fun charsetOf(contentType: String?): String? =
        contentType?.let { Regex("(?i)charset=([\\w-]+)").find(it)?.groupValues?.get(1) }

    private fun decode(bytes: ByteArray, charset: String?): String {
        val name = charset ?: "UTF-8"
        return runCatching { String(bytes, charset(name)) }.getOrElse { String(bytes, Charsets.UTF_8) }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 12_000
        private const val READ_TIMEOUT_MS = 18_000
    }
}
