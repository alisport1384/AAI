package ir.aai.hushyar

import ir.aai.hushyar.data.PersonalBackendClient
import ir.aai.hushyar.domain.PersonalBackendConfig
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Security
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real JVM TLS sockets; an Android-device connection still needs the manual check in TESTING.md. */
class PinnedHttpsTransportTest {
    private val password = "test-only-keystore-password-0123456789"
    private val bearer = "test-only-secret-0123456789abcdef123456"

    private fun withServer(san: String, check: (Int, String) -> Unit) {
        // Other Robolectric tests install a JVM Conscrypt provider globally. This
        // Android-specific provider breaks JDK 17's JSSE EC initialization on the JVM.
        // Temporarily remove it for this pure-JVM socket test; restore its exact position.
        val conscrypt = Security.getProviders().mapIndexedNotNull { index, provider ->
            if (provider.javaClass.name.contains("conscrypt", ignoreCase = true) ||
                provider.name.contains("conscrypt", ignoreCase = true)) provider to index + 1 else null
        }
        conscrypt.forEach { (provider, _) -> Security.removeProvider(provider.name) }
        val dir = Files.createTempDirectory("hushyar-tls-").toFile()
        try {
            val keystore = File(dir, "tls.p12")
            val keytool = File(System.getProperty("java.home"), "bin/keytool").absolutePath
            val process = ProcessBuilder(keytool, "-genkeypair", "-alias", "tls", "-keyalg", "RSA",
                "-keysize", "2048", "-validity", "1", "-dname", "CN=hushyar-test",
                "-ext", "SAN=$san", "-storetype", "PKCS12", "-keystore", keystore.path,
                "-storepass", password, "-keypass", password, "-noprompt")
                .redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(output, 0, process.waitFor())
            val keys = KeyStore.getInstance("PKCS12").apply {
                keystore.inputStream().use { load(it, password.toCharArray()) }
            }
            val pin = MessageDigest.getInstance("SHA-256").digest(keys.getCertificate("tls").encoded)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(keys, password.toCharArray())
            }
            // Robolectric may install Conscrypt globally. On JDK 17 its test-only server
            // socket needs reflective access to java.net; SunJSSE avoids that dependency.
            val tls = SSLContext.getInstance("TLS", "SunJSSE")
                .apply { init(managers.keyManagers, null, SecureRandom()) }
            val server = tls.serverSocketFactory.createServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
            val worker = Thread({
                while (!server.isClosed) {
                    try {
                        (server.accept() as SSLSocket).use { peer ->
                            peer.soTimeout = 5_000
                            val input = peer.inputStream.bufferedReader(Charsets.UTF_8)
                            if (input.readLine()?.startsWith("GET /health ") != true) return@use
                            var authorized = false
                            while (true) {
                                val header = input.readLine() ?: break
                                if (header.isEmpty()) break
                                if (header.equals("Authorization: Bearer $bearer", ignoreCase = true)) authorized = true
                            }
                            val response = if (authorized)
                                """{"schema":"hushyar.docs/1","status":"ok","retrieval":"sqlite-fts5"}"""
                                else """{"error":{"code":"UNAUTHORIZED"}}"""
                            val bytes = response.toByteArray(Charsets.UTF_8)
                            val headers = "HTTP/1.1 ${if (authorized) "200 OK" else "401 Unauthorized"}\r\n" +
                                "Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                            peer.outputStream.write(headers.toByteArray(Charsets.US_ASCII) + bytes)
                            peer.outputStream.flush()
                        }
                    } catch (_: IOException) {
                        // Client rejection at TLS handshake is expected for bad pin / host.
                    }
                }
            }, "hushyar-tls-test").apply { isDaemon = true; start() }
            try {
                check(server.localPort, pin)
            } finally {
                server.close()
                worker.join(6_000)
            }
        } finally {
            dir.deleteRecursively()
            conscrypt.forEach { (provider, position) -> Security.insertProviderAt(provider, position) }
        }
    }

    @Test(timeout = 25_000) fun correctPinAllowsSelfSignedTlsButWrongPinFailsClosed() = withServer("ip:127.0.0.1") { port, pin ->
        val client = PersonalBackendClient()
        val valid = PersonalBackendConfig.of("https://127.0.0.1:$port", pin, bearer)
        assertTrue(client.health(valid).contains("آماده"))
        val bad = PersonalBackendConfig.of("https://127.0.0.1:$port", "f".repeat(64), bearer)
        val failure = runCatching { client.health(bad) }.exceptionOrNull()
        assertTrue("Wrong fingerprint must fail during TLS (got $failure)", failure is SSLException)
    }

    @Test(timeout = 25_000) fun certificateWithDnsSanOnlyCannotImpersonateAnIpHost() = withServer("dns:localhost") { port, pin ->
        val client = PersonalBackendClient()
        val config = PersonalBackendConfig.of("https://127.0.0.1:$port", pin, bearer)
        val failure = runCatching { client.health(config) }.exceptionOrNull()
        assertTrue("Pinned certificate with wrong SAN must reject the host (got $failure)",
            failure is SSLException || (failure is IOException &&
                failure.message.orEmpty().contains("hostname wrong", ignoreCase = true)))
    }
}
