package ir.aai.hushyar

import ir.aai.hushyar.core.HttpRequest
import ir.aai.hushyar.core.HttpResponse
import ir.aai.hushyar.core.HttpTransport
import ir.aai.hushyar.domain.EngineKind
import ir.aai.hushyar.domain.Engines
import ir.aai.hushyar.domain.SerpFormat
import ir.aai.hushyar.domain.SerpParser
import ir.aai.hushyar.domain.SearchCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.12 — «هنوز نوع جستجو کردن اشکال داره.»
 *
 * ریشه پیدا شد و سنجیده شد (۲۰۲۶-۱۱-۱۷): Bing بدون پارامتر `mkt` صفحه‌ای می‌دهد که در آن **هیچ
 * بلوک نتیجه‌ای نیست** (`b_algo` = ۰) — با هر User-Agent، از جمله همان UA صادق اپ. با
 * `mkt=fa-IR` همان درخواست ۵ تا ۱۰ نتیجهٔ واقعی می‌دهد. پارامتر `setlang` به‌تنهایی هیچ اثری
 * ندارد. تست‌های این فایل همان یک خط را، تجزیهٔ بلوکی، منابع کاربر و ترتیب پرسیدن را قفل می‌کنند.
 */
class SearchFixesTest {

    // ------------------------------------------------------------ the measured bug

    @Test
    fun bingIsAskedWithAMarketParameter() {
        val bing = Engines.byId("bing")!!
        val fa = bing.url("قیمت تیشرت", "fa")
        assertTrue("Bing must be asked with a market: $fa", fa.contains("mkt=fa-IR"))
        assertFalse("setlang alone does nothing — measured", fa.contains("setlang"))

        val en = bing.url("cotton t-shirt price", "en")
        assertTrue(en, en.contains("mkt=en-US"))
    }

    @Test
    fun everyBingRouteCarriesTheMarketParameter() {
        val bing = Engines.byId("bing")!!
        bing.endpoints.forEach { endpoint ->
            val url = bing.render(endpoint.template, "قیمت شلوار", "fa")
            assertTrue("${endpoint.template} lost mkt: $url", url.contains("mkt=fa-IR"))
        }
        assertTrue("the RSS fallback must exist", bing.endpoints.any { it.format == SerpFormat.RSS })
    }

    @Test
    fun wikipediaIsAskedThroughItsJsonApi() {
        val wiki = Engines.byId("wikipedia-fa")!!
        assertEquals(SerpFormat.JSON, wiki.endpoints.first().format)
        assertTrue(wiki.endpoints.first().template.contains("api.php"))
    }

    @Test
    fun reliableEnginesAreOrderedBeforeJavaScriptOnlyOnes() {
        val ordered = Engines.ordered("fa").map { it.id }
        val bing = ordered.indexOf("bing")
        val google = ordered.indexOf("google")
        val qwant = ordered.indexOf("qwant")
        assertTrue("Bing answers, so it must be in the first few: $ordered", bing in 0..6)
        assertTrue("a JavaScript-only engine must stay behind: $ordered", qwant > bing)
        assertTrue("Google is still asked before the long tail: $ordered", google < ordered.size - 1)
    }

    // ------------------------------------------------------------ result blocks

    private val bingFixture = """
        <html><body><ol id="b_results">
        <li class="b_algo" data-id iid=SERP.1><h2><a href="https://www.tgju.org/">قیمت طلا و سکه امروز</a></h2>
          <div class="b_caption"><p class="b_lineclamp3">قیمت هر گرم طلای ۱۸ عیار در بازار آزاد اعلام شد.</p></div></li>
        <li class="b_algo" data-id iid=SERP.2><h2><a href="https://poshak.example/shalvar">قیمت شلوار جین عمده</a></h2>
          <div class="b_caption"><p>قیمت هر عدد شلوار جین در بازار عمده ۴۵۰٬۰۰۰ تومان است.</p></div></li>
        </ol></body></html>
    """.trimIndent()

    @Test
    fun bingBlocksKeepTheTitleTheLinkAndTheSentenceTogether() {
        val blocks = SerpParser.bingBlocks(bingFixture)
        assertEquals(2, blocks.size)
        assertEquals("https://poshak.example/shalvar", blocks[1].url)
        assertTrue(blocks[1].title.contains("شلوار"))
        assertTrue("the snippet belongs to the same block: ${blocks[1].snippet}",
            blocks[1].snippet.contains("۴۵۰٬۰۰۰"))
        assertFalse(blocks[1].snippet.contains("طلای"))
    }

    @Test
    fun bingParsingProducesUsableHitsAndDropsTheEngineItself() {
        val bing = Engines.byId("bing")!!
        val hits = SerpParser.parse(bing, bingFixture, 5)
        assertEquals(2, hits.size)
        assertTrue(hits.all { it.engine == "bing" })
        assertTrue("no bing.com result links", hits.none { it.url.contains("bing.com") })
        assertEquals("https://poshak.example/shalvar", hits[1].url)
    }

    @Test
    fun aWrappedBingLinkIsUnwrappedToTheRealTarget() {
        // Bing sometimes wraps the target: /ck/a?...&u=a1<base64url>
        val wrapped = "https://www.bing.com/ck/a?!&&p=abc&u=a1aHR0cHM6Ly9wb3NoYWsuZXhhbXBsZS94&ntb=1"
        assertEquals("https://poshak.example/x", SerpParser.unwrapLink(wrapped))
    }

    // ------------------------------------------------------------ user sources

    @Test
    fun userSourcesRoundTripThroughJson() {
        val sources = listOf(
            Engines.UserSource("دیجی‌کالا", "https://www.digikala.com/search/?q={q}"),
            Engines.UserSource("My JSON", "https://api.example/search?q={q}", SerpFormat.JSON, enabled = false)
        )
        val decoded = Engines.SourcesCodec.decode(Engines.SourcesCodec.encode(sources))
        assertEquals(2, decoded.size)
        assertEquals("دیجی‌کالا", decoded[0].title)
        assertEquals(SerpFormat.JSON, decoded[1].format)
        assertFalse(decoded[1].enabled)
    }

    @Test
    fun aSourceWithoutThePlaceholderIsRefused() {
        assertFalse(Engines.UserSource("بد", "https://example.com/search", SerpFormat.HTML).valid())
        assertFalse(Engines.UserSource("http", "http://example.com/search?q={q}", SerpFormat.HTML).valid())
        assertTrue(Engines.UserSource("خوب", "https://example.com/search?q={q}", SerpFormat.HTML).valid())
        assertTrue(Engines.SourcesCodec.decode("not json").isEmpty())
    }

    private class RecordingTransport : HttpTransport {
        val urls = mutableListOf<String>()
        override fun get(request: HttpRequest): HttpResponse {
            urls += request.url
            val body = if (request.url.contains("mine.example")) {
                """<html><body><a href="https://mine.example/p/1">قیمت پارچه در بازار تهران امروز</a></body></html>"""
            } else {
                """<html><body><a href="https://other.example/p/2">قیمت پارچه در بازار شهر دیگر</a></body></html>"""
            }
            return HttpResponse(request.url, 200, body)
        }
    }

    @Test
    fun theUsersOwnSourceIsAskedFirst() {
        val transport = RecordingTransport()
        val core = SearchCore(transport, engines = Engines.all, pacing = 0L, sleep = {}, maxCallMillis = 5_000)
        core.extraEngines = listOf(
            Engines.UserSource("منبع من", "https://mine.example/search?q={q}").asEngine()
        )
        val report = core.search("قیمت پارچه", "fa", maxEngines = 1, allowReflection = false)
        assertTrue("the user's source must be tried first: ${transport.urls}", transport.urls.isNotEmpty())
        assertTrue(transport.urls.first(), transport.urls.first().contains("mine.example"))
        assertTrue(report.perEngine.containsKey("user:منبع-من") || report.hits.any { it.url.contains("mine.example") })
    }

    @Test
    fun userSourcesAreMarkedAsSuchForTheUi() {
        val engine = Engines.UserSource("فروشگاه من", "https://shop.example/s?q={q}").asEngine()
        assertEquals(EngineKind.USER, engine.kind)
        assertTrue(engine.note.contains("منبع خودت"))
    }
}
