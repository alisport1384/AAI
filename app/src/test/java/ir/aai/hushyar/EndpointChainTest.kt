package ir.aai.hushyar

import ir.aai.hushyar.core.HttpRequest
import ir.aai.hushyar.core.HttpResponse
import ir.aai.hushyar.core.HttpTransport
import ir.aai.hushyar.domain.EngineProbe
import ir.aai.hushyar.domain.Engines
import ir.aai.hushyar.domain.SearchCore
import ir.aai.hushyar.domain.SerpOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The endpoint chain, tested against a fake transport that behaves like the measured internet:
 * a consent shell on the HTML route, a real answer on the RSS route. This is the promise of
 * «راهی پیدا کن که موتورها جواب بدهند» expressed as an executable expectation.
 */
class EndpointChainTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("serp/$name")!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    /**
     * Bing's real RSS feed, in the same shape as the captured one, but with article URLs: the
     * captured product-query feeds mostly carry brand homepages, which the guard deliberately
     * drops (a search result must point at a page, not at a shop's front door).
     */
    private fun rssFeed(): String = """
        <?xml version="1.0" encoding="utf-8" ?>
        <rss version="2.0"><channel><title>Bing: headphones</title>
        <item><title>Sony WH-1000XM5 review: still the noise-cancelling king</title>
        <link>https://www.theverge.com/reviews/sony-wh-1000xm5</link>
        <description>Long-term test of the Sony WH-1000XM5 and its battery life.</description></item>
        <item><title>Sony WH-1000XM5 price and availability in Europe</title>
        <link>https://www.whathifi.com/news/sony-wh-1000xm5-price</link>
        <description>Street price and bundle options.</description></item>
        <item><title>How to reset the Sony WH-1000XM5 headphones</title>
        <link>https://www.sony.com/electronics/support/articles/reset-xm5</link>
        <description>Official reset instructions.</description></item>
        </channel></rss>
    """.trimIndent()

    /** Routes requests by URL substring; anything unmatched is a connection error. */
    private class FakeTransport(private val routes: List<Pair<String, HttpResponse>>) : HttpTransport {
        val asked = mutableListOf<String>()

        override fun get(request: HttpRequest): HttpResponse {
            asked.add(request.url)
            val route = routes.firstOrNull { request.url.contains(it.first) } ?: return HttpResponse(
                request.url, 0, "", null, "unreachable"
            )
            return route.second.copy(url = request.url)
        }
    }

    private fun core(transport: HttpTransport, engines: List<ir.aai.hushyar.domain.EngineSpec>) =
        SearchCore(
            http = transport,
            engines = engines,
            pacing = 0L,
            sleep = {},
            maxCallMillis = 5_000,
            defaultEngines = engines.size,
            clock = { 0L }
        )

    @Test
    fun bingFallsBackToRssWhenTheHtmlPageIsAConsentShell() {
        val bing = Engines.byId("bing")!!
        val shell = "<html><body>Before you continue to Bing, we use cookies.</body></html>"
        val transport = FakeTransport(
            listOf(
                "format=rss" to HttpResponse("", 200, rssFeed(), "application/rss+xml"),
                "bing.com/search" to HttpResponse("", 200, shell, "text/html")
            )
        )
        val report = core(transport, listOf(bing)).search("هدفون سونی", "fa")

        assertTrue("the RSS route must be tried", transport.asked.any { it.contains("format=rss") })
        assertTrue("the RSS route must produce results", report.hits.isNotEmpty())
        assertTrue("the report must say which route answered: ${report.notes}", report.notes.containsKey("bing"))
        assertTrue(report.notes.getValue("bing").contains("RSS"))
        assertTrue(report.failures.isEmpty())
    }

    @Test
    fun aBlockedHtmlRouteFallsThroughToTheWorkingEndpoint() {
        val bing = Engines.byId("bing")!!
        val transport = FakeTransport(
            listOf(
                "format=rss" to HttpResponse("", 200, rssFeed(), "application/rss+xml"),
                "bing.com/search" to HttpResponse("", 403, "forbidden", "text/html")
            )
        )
        val report = core(transport, listOf(bing)).search("هدفون سونی", "fa")
        assertTrue("a 403 on one route must not end the engine", report.hits.isNotEmpty())
    }

    @Test
    fun theChainIsWalkedInOrderSoTheCheapestRouteWins() {
        val bing = Engines.byId("bing")!!
        val html = """
            <html><body>
            <a href="https://shop.test/headphones/sony-wh-1000xm5-price">قیمت هدفون سونی در فروشگاه</a>
            <a href="https://news.test/review/sony">بررسی کامل هدفون سونی</a>
            </body></html>
        """.trimIndent()
        val transport = FakeTransport(
            listOf(
                "bing.com/search" to HttpResponse("", 200, html, "text/html"),
                "format=rss" to HttpResponse("", 200, rssFeed(), "application/rss+xml")
            )
        )
        val report = core(transport, listOf(bing)).search("هدفون سونی", "fa")
        assertTrue("failures=${report.failures}", report.hits.isNotEmpty())
        assertFalse("HTML worked, so RSS must not be asked", transport.asked.any { it.contains("format=rss") })
        assertTrue("no note is expected when the plain route answered", report.notes.isEmpty())
    }

    @Test
    fun everyEndpointFailingIsReportedAsItsRealOutcome() {
        val bing = Engines.byId("bing")!!
        val transport = FakeTransport(
            listOf("bing.com/search" to HttpResponse("", 429, "slow down", "text/html"))
        )
        val report = core(transport, listOf(bing)).search("هدفون سونی", "fa")
        val why = report.failures["bing"].orEmpty()
        assertTrue("the failure must be labelled, not swallowed: $why", why.isNotBlank())
        assertTrue(
            "a 429 must be named as rate limiting: $why",
            why.contains("محدود") || why.contains("۴۲۹") || why.contains("429") || why.contains("بیش از حد")
        )
    }

    @Test
    fun aTransportExceptionIsNotAnEmptyResultSet() {
        val bing = Engines.byId("bing")!!
        val report = core(FakeTransport(emptyList()), listOf(bing)).search("هدفون سونی", "fa")
        val why = report.failures["bing"].orEmpty()
        assertTrue(why.contains("شبکه") || why.contains("unreachable") || why.contains("خطای"))
        assertTrue(report.hits.isEmpty())
    }

    @Test
    fun probeNamesTheOutcomeForEveryEngineItAsked() {
        val bing = Engines.byId("bing")!!
        val google = Engines.byId("google")!!
        val transport = FakeTransport(
            listOf(
                "format=rss" to HttpResponse("", 200, rssFeed(), "application/rss+xml"),
                "bing.com/search" to HttpResponse("", 200, fixture("google-js-shell.html"), "text/html"),
                "google.com/search" to HttpResponse("", 200, fixture("google-js-shell.html"), "text/html")
            )
        )
        val probes: List<EngineProbe> = core(transport, listOf(bing, google)).probe("هدفون سونی", "fa")
        assertEquals(2, probes.size)
        val bingProbe = probes.first { it.id == "bing" }
        val googleProbe = probes.first { it.id == "google" }
        assertTrue("Bing answered", bingProbe.hits > 0)
        assertEquals(SerpOutcome.RESULTS, bingProbe.outcome)
        assertEquals("a JS shell must not be called 'no results'", SerpOutcome.JS_REQUIRED, googleProbe.outcome)
        assertTrue(googleProbe.detail.isNotBlank())
    }

    @Test
    fun searchAllAsksEveryEngineNotJustTheCheapEight() {
        val transport = FakeTransport(
            listOf("bing.com/search" to HttpResponse("", 403, "forbidden", "text/html"))
        )
        val report = core(transport, Engines.all).searchAll("هدفون سونی", "fa", limitPerEngine = 5)
        val asked = transport.asked.map { it.substringAfter("://").substringBefore('/') }.distinct()
        assertTrue("studying a topic must sweep the catalogue (${asked.size} hosts)", asked.size >= 15)
        assertTrue("every asked engine must be accounted for", report.failures.isNotEmpty())
    }
}
