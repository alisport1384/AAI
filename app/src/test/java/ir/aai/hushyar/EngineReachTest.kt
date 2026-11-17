package ir.aai.hushyar

import ir.aai.hushyar.domain.Engines
import ir.aai.hushyar.domain.FeedParser
import ir.aai.hushyar.domain.JsonSerp
import ir.aai.hushyar.domain.SerpFormat
import ir.aai.hushyar.domain.SerpGuard
import ir.aai.hushyar.domain.SerpOutcome
import ir.aai.hushyar.domain.SerpParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «موتورهای جستجو را جواب‌گو کن» — tested against the *real* bodies captured on 2026-11-17
 * (`app/src/test/resources/serp/`), not against hand-written HTML.
 *
 * The point of these tests is the failure mode this release exists to fix: an engine that
 * answers 200 with a JavaScript shell used to look exactly like "no results". Now it is named.
 */
class EngineReachTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("serp/$name")!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    // ---------------------------------------------------------------- RSS (Bing)

    @Test
    fun bingRssYieldsRealResultsForAPersianQuery() {
        val bing = Engines.byId("bing")!!
        val items = FeedParser.items(fixture("bing-fa.rss"))
        assertTrue("the captured feed must contain items", items.isNotEmpty())
        assertTrue("items must carry absolute links", items.all { it.link.startsWith("http") })
        assertTrue("items must carry titles", items.all { it.title.isNotBlank() })
    }

    @Test
    fun bingHasAnRssFallbackAndItIsUsedWhenHtmlIsEmpty() {
        val bing = Engines.byId("bing")!!
        assertEquals(2, bing.endpoints.size)
        assertEquals(SerpFormat.HTML, bing.endpoints[0].format)
        assertEquals(SerpFormat.RSS, bing.endpoints[1].format)
        assertTrue("the fallback must ask for RSS", bing.endpoints[1].template.contains("format=rss"))
    }

    @Test
    fun rssParserSurvivesAshapeWeDidNotPlanFor() {
        val atom = """
            <feed><entry><title>AT&amp;T plans</title><link href="https://example.com/a"/>
            <summary>Summary text here</summary></entry></feed>
        """.trimIndent()
        val items = FeedParser.items(atom)
        assertEquals(1, items.size)
        assertEquals("https://example.com/a", items[0].link)
        assertTrue("entities must be decoded", items[0].title.contains("AT&T"))
    }

    @Test
    fun rssParserRejectsGarbageWithoutThrowing() {
        assertTrue(FeedParser.items("not xml at all").isEmpty())
        assertTrue(FeedParser.items("<rss><channel></channel></rss>").isEmpty())
    }

    // ---------------------------------------------------------------- JSON (Mwmbl)

    @Test
    fun mwmblJsonIsParsedIntoResults() {
        val items = JsonSerp.mwmbl(fixture("mwmbl.json"), 10)
        assertTrue("the captured Mwmbl answer must parse", items.size >= 5)
        assertTrue(items.all { it.link.startsWith("http") })
        assertTrue("titles must not be empty", items.filter { it.title.isNotBlank() }.size >= 5)
    }

    @Test
    fun mwmblEngineShipsItsJsonEndpoint() {
        val mwmbl = Engines.byId("mwmbl")
        assertNotNull("Mwmbl must be in the catalogue", mwmbl)
        assertTrue(mwmbl!!.template.contains("api.mwmbl.org"))
    }

    @Test
    fun wikipediaJsonShapeIsParsed() {
        val body = """
            {"query":{"search":[{"title":"Sony  WH-1000XM5","snippet":"<span>noise</span> cancelling"}]}}
        """.trimIndent()
        val items = JsonSerp.wikipedia(body)
        assertEquals(1, items.size)
        assertEquals("Sony WH-1000XM5", items[0].title)
        assertTrue(items[0].link.contains("wikipedia.org/wiki/"))
        assertFalse("html must be stripped from the snippet", items[0].description.contains("<span>"))
    }

    @Test
    fun jsonParsersNeverThrowOnRubbish() {
        assertTrue(JsonSerp.mwmbl("{not json").isEmpty())
        assertTrue(JsonSerp.wikipedia("[]").isEmpty())
    }

    // ---------------------------------------------------------------- HTML (DDG)

    @Test
    fun duckDuckGoHtmlStillParses() {
        val ddg = Engines.byId("ddg")!!
        val hits = SerpParser.parseAny(ddg, SerpFormat.HTML, fixture("ddg.html"), 8)
        assertTrue("the captured DDG page must yield results", hits.isNotEmpty())
        assertTrue("engine tag must be set", hits.all { it.engine == "ddg" })
        assertTrue("no duckduckgo.com self-links", hits.none { it.url.contains("duckduckgo.com") })
    }

    // ---------------------------------------------------------------- the actual fix

    @Test
    fun aJavaScriptShellIsNotReportedAsZeroResults() {
        val verdict = SerpGuard.classify(200, fixture("google-js-shell.html"), 0)
        assertTrue(
            "a 200 page with no results must be named, not counted as empty: $verdict",
            verdict.outcome == SerpOutcome.JS_REQUIRED || verdict.outcome == SerpOutcome.BLOCKED
        )
        assertTrue(verdict.detail.isNotBlank())
        assertFalse(verdict.ok)
    }

    @Test
    fun rateLimitingIsRecognisedFromTheStatus() {
        assertEquals(SerpOutcome.RATE_LIMITED, SerpGuard.classify(429, fixture("brave-429.html"), 0).outcome)
        assertEquals(SerpOutcome.BLOCKED, SerpGuard.classify(403, "forbidden", 0).outcome)
        assertEquals(SerpOutcome.HTTP_ERROR, SerpGuard.classify(500, "", 0).outcome)
    }

    @Test
    fun aCaptchaPageIsNamedAsABlock() {
        val body = "<html><body>Our systems have detected unusual traffic. Please solve the captcha.</body></html>"
        val verdict = SerpGuard.classify(200, body, 0)
        assertEquals(SerpOutcome.BLOCKED, verdict.outcome)
    }

    @Test
    fun resultsAlwaysWinOverAnyGuess() {
        val verdict = SerpGuard.classify(200, fixture("google-js-shell.html"), 3)
        assertEquals(SerpOutcome.RESULTS, verdict.outcome)
        assertTrue(verdict.ok)
    }

    @Test
    fun everyOutcomeHasAReadablePersianLabel() {
        SerpOutcome.entries.forEach { outcome ->
            val label = SerpGuard.label(outcome)
            assertTrue("label for $outcome must be non-empty", label.isNotBlank())
            assertTrue("label for $outcome must be Persian", label.any { it in '\u0600'..'\u06FF' })
        }
    }

    // ---------------------------------------------------------------- catalogue honesty

    @Test
    fun everyEngineAdvertisesAtLeastOneEndpointAndEveryFallbackIsHttps() {
        Engines.all.forEach { engine ->
            assertTrue("${engine.id} needs an endpoint", engine.endpoints.isNotEmpty())
            engine.endpoints.forEach { endpoint ->
                assertTrue("${engine.id} endpoint must be https", endpoint.template.startsWith("https://"))
                assertTrue("${engine.id} endpoint must take a query", endpoint.template.contains("{q}"))
            }
        }
    }

    @Test
    fun enginesThatWereMeasuredWorkingAreOrderedBeforeTheBlockedOnes() {
        val ordered = Engines.ordered("fa").map { it.id }
        val bing = ordered.indexOf("bing")
        val google = ordered.indexOf("google")
        assertTrue("Bing answered in the survey and must be tried first", bing in 0 until ordered.size)
        assertTrue("Google returned a JS shell, it must not lead", google > bing)
    }

    @Test
    fun theSurveyAddedTheEnginesThatActuallyAnswer() {
        val ids = Engines.all.map { it.id }.toSet()
        assertTrue("Mwmbl answered without a key", ids.contains("mwmbl"))
        assertTrue("Wiby answered without a key", ids.contains("wiby"))
        assertTrue("the catalogue must keep growing, not shrink", Engines.all.size >= 28)
    }
}
