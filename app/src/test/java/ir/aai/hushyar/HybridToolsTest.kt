package ir.aai.hushyar

import ir.aai.hushyar.data.PersonalBackendClient
import ir.aai.hushyar.data.PersonalTransport
import ir.aai.hushyar.domain.PersonalBackendConfig
import ir.aai.hushyar.domain.PersonalDocsCodec
import ir.aai.hushyar.domain.PrivateRoute
import ir.aai.hushyar.domain.SafeCalculator
import ir.aai.hushyar.core.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HybridToolsTest {
    @Test fun calculatorOnlyExecutesBoundedArithmetic() {
        assertEquals("10240", SafeCalculator.compute("2^10 × sqrt(100)").value)
        assertEquals("12", SafeCalculator.compute("sqrt(۱۴۴)").value)
        assertEquals("7", SafeCalculator.compute("1 + 2 * 3").value)
        assertEquals("-4", SafeCalculator.compute("-2^2").value)
        assertEquals("0.125", SafeCalculator.compute("2^-3").value)
        assertEquals("512", SafeCalculator.compute("2^3^2").value)
        assertEquals("0.5", SafeCalculator.compute("۱ ÷ ۲").value)
        assertTrue(SafeCalculator.compute("sqrt(2)").approximate)
        val repeating = SafeCalculator.compute("1/3")
        assertTrue(repeating.approximate)
        assertTrue(SafeCalculator.answer("1/0").refused)
        assertTrue(SafeCalculator.answer("system('rm -rf /')").refused)
        assertTrue(SafeCalculator.answer("sqrt(-1)").refused)
        assertTrue(SafeCalculator.answer("2^100000").refused)
        assertTrue(SafeCalculator.answer("(".repeat(30) + "2" + ")".repeat(30)).refused)
    }

    @Test fun privateRoutesAreOptInOnly() {
        assertEquals("وزن نبشی", PrivateRoute.documents("از اسنادم: وزن نبشی"))
        assertEquals("who", PrivateRoute.documents("docs: who"))
        assertEquals("۲ + ۲", PrivateRoute.calculate("حساب کن: ۲ + ۲"))
        assertEquals(null, PrivateRoute.documents("قیمت نبشی امروز چنده؟"))
        assertEquals(null, PrivateRoute.calculate("این صفحه گفته calc: 2+2"))
    }

    private val pin = "a".repeat(64)
    private val secret = "x".repeat(40)

    @Test fun configRequiresHttpsFingerprintAndDoesNotPrintTheSecret() {
        val config = PersonalBackendConfig.of("https://example.org:8765/", pin, secret)
        assertEquals("https://example.org:8765", config.baseUrl)
        assertFalse(config.toString().contains(secret))
        listOf("http://example.org", "https://x.org/redirect", "https://x.org?token=bad", "https://user:pass@x.org")
            .forEach { url ->
                try { PersonalBackendConfig.of(url, pin, secret); fail("Unsafe URL accepted: $url") }
                catch (_: IllegalArgumentException) { }
            }
        try { PersonalBackendConfig.of("https://example.org", "bad", secret); fail("no pin accepted") }
        catch (_: IllegalArgumentException) { }
    }

    private fun answerJson(text: String, quote: String, ref: String = "D:${"a".repeat(32)}:0", url: String? = null): String =
        MiniJson.write(MiniJson.obj(
            "schema" to MiniJson.str(PersonalDocsCodec.SCHEMA), "refused" to MiniJson.bool(false),
            "text" to MiniJson.str(text), "bullets" to MiniJson.strs(listOf("«$quote»")),
            "citations" to MiniJson.arr(listOf(MiniJson.obj(
                "ref" to MiniJson.str(ref), "label" to MiniJson.str("یادداشت خودم"),
                "quote" to MiniJson.str(quote), "url" to if (url == null) ir.aai.hushyar.core.Json.Null else MiniJson.str(url)
            )))
        ))

    @Test fun quotedDocumentAnswerCannotContainAnUncitedNewPriceOrExternalLink() {
        val good = PersonalDocsCodec.readAnswer(answerJson("اینها نقل‌قول‌اند، نه قیمت روز.", "نبشی ۴ فابریک ۹ کیلو است."))
        assertFalse(good.refused)
        assertEquals(1, good.citations.size)
        assertEquals(null, good.citations.single().url)
        assertTrue(good.bullets.single().contains("۹ کیلو"))
        val bad = listOf(
            answerJson("قیمت قطعی ۱۲۵۵۰۰ تومان", "قیمت دیگری بود"),
            answerJson("نقل‌قول", "متن", ref = "X:fake"),
            answerJson("نقل‌قول", "متن", url = "https://fake.example"),
            answerJson("نقل‌قول", "متن").replace("hushyar.docs/1", "unknown/2")
        )
        for (raw in bad) {
            try { PersonalDocsCodec.readAnswer(raw); fail("Unverified answer accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun backendClientSendsOnlyExplicitDocumentOperationsAndNoHistoryOrTokenInBody() {
        val calls = mutableListOf<Triple<String, String, String?>>()
        val client = PersonalBackendClient(PersonalTransport { _, method, path, body ->
            calls.add(Triple(method, path, body))
            when {
                path == "/health" -> """{"schema":"hushyar.docs/1","status":"ok","retrieval":"sqlite-fts5"}"""
                path == "/v1/query" -> answerJson("نقل‌قول، نه قیمت روز.", "قطعه‌ای از سند")
                else -> """{"schema":"hushyar.docs/1","documents":[]}"""
            }
        })
        val config = PersonalBackendConfig.of("https://host.example", pin, secret)
        assertTrue(client.health(config).contains("آماده"))
        val answer = client.ask(config, "chat-one", "متن سند؟")
        assertFalse(answer.refused)
        assertEquals("POST", calls.last().first)
        assertEquals("/v1/query", calls.last().second)
        assertTrue(calls.last().third!!.contains("chat-one"))
        assertFalse(calls.last().third!!.contains(secret))
        assertEquals(emptyList<String>(), client.list(config, "chat-two").map { it.title })
        assertTrue(calls.last().second.contains("thread_id=chat-two"))
    }

    @Test fun codecHasBoundedDocumentRequestsAndScopedResults() {
        val raw = PersonalDocsCodec.requestQuestion("chat-one", "وزن نبشی؟")
        assertTrue(raw.contains("chat-one"))
        assertFalse(raw.contains("token"))
        val doc = PersonalDocsCodec.requestDocument("chat-one", "یادداشت", "متن معتبر", "text")
        assertTrue(doc.contains("یادداشت"))
        try { PersonalDocsCodec.requestDocument("chat-one", "فایل", "a".repeat(100_001), "text"); fail("oversize") }
        catch (_: IllegalArgumentException) { }
        try { PersonalDocsCodec.requestQuestion("../other", "سلام"); fail("bad scope") }
        catch (_: IllegalArgumentException) { }
        val list = """{"schema":"hushyar.docs/1","documents":[{"id":"${"b".repeat(32)}","title":"دفتر","chunks":284,"created_at":100}]}"""
        assertEquals("دفتر", PersonalDocsCodec.readDocuments(list).single().title)
        assertEquals(284, PersonalDocsCodec.readDocuments(list).single().chunks)
        val receipt = """{"schema":"hushyar.docs/1","id":"${"b".repeat(32)}","chunks":284,"duplicate":false}"""
        assertEquals(284, PersonalDocsCodec.readIndex(receipt).chunks)
        try { PersonalDocsCodec.readIndex(receipt.replace("284", "501")); fail("unbounded chunks") }
        catch (_: IllegalArgumentException) { }
    }
}
