package ir.aai.hushyar

import ir.aai.hushyar.domain.Engines
import ir.aai.hushyar.domain.SerpFormat
import ir.aai.hushyar.domain.SearchCore
import ir.aai.hushyar.domain.Suggestions
import ir.aai.hushyar.domain.WebHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «موتور جستجو اصلا خوب نیست» — this is the test for that complaint, in two halves:
 *
 *  1. Google is asked first, and it has a route that can actually answer without JavaScript.
 *  2. The ranking stops rewarding agreement between engines and starts rewarding the hit that
 *     answers the question — which is what "good results" means to a user.
 */
class SearchQualityTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("serp/$name")!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun hit(url: String, title: String, engine: String = "bing", rank: Int = 1, snippet: String = "") =
        WebHit(url = url, title = title, snippet = snippet, engine = engine, rank = rank)

    // ---------------------------------------------------------------- Google first

    @Test
    fun googleIsTheFirstEngineTried() {
        assertEquals("google", Engines.ordered("fa").first().id)
        assertEquals("google", Engines.ordered("en").first().id)
        assertEquals("Google must have the lowest friction", 0, Engines.byId("google")!!.friction)
    }

    @Test
    fun googleCarriesARouteThatCanAnswerWithoutJavaScript() {
        val google = Engines.byId("google")!!
        assertTrue("the HTML route stays first", google.endpoints.first().format == SerpFormat.HTML)
        val fallback = google.endpoints.getOrNull(1)
        assertNotNull("Google must not be a dead entry: ${google.endpoints}", fallback)
        assertEquals(SerpFormat.RSS, fallback!!.format)
        assertTrue("the fallback must be Google's own feed", fallback.template.contains("news.google.com"))
        assertTrue("the feed needs the query", fallback.template.contains("{q}"))
    }

    @Test
    fun googleTemplatesFillInLanguageAndCountry() {
        val google = Engines.byId("google")!!
        val faUrl = google.url("قیمت میلگرد", "fa")
        assertTrue(faUrl.contains("hl=fa"))
        assertTrue(faUrl.contains("gl=ir"))
        val enUrl = google.url("steel price", "en")
        assertTrue(enUrl.contains("hl=en"))
        assertTrue(enUrl.contains("gl=us"))
        val feed = google.render(google.endpoints[1].template, "قیمت میلگرد", "fa")
        assertTrue("ceid must be built from country and language: $feed", feed.contains("ceid=IR:fa"))
    }

    @Test
    fun googlesNewsFeedIsParsedIntoUsableItems() {
        val items = ir.aai.hushyar.domain.FeedParser.items(fixture("google-news-en.rss"), 5)
        assertTrue("the captured Google feed must contain items", items.isNotEmpty())
        assertTrue(items.all { it.title.isNotBlank() })
        assertTrue(items.all { it.link.startsWith("http") })
    }

    // ---------------------------------------------------------------- query repair

    @Test
    fun googlesAutocompleteIsReadAndUsedToRepairAWeakQuery() {
        val suggestions = Suggestions.parse(fixture("google-suggest.json"))
        assertTrue("the captured autocomplete answer must parse", suggestions.size >= 5)
        assertTrue(suggestions.contains("میلگرد ۱۶"))
        val repaired = Suggestions.repair("میلگرد", suggestions)
        assertNotNull("a bare word must be improved", repaired)
        assertTrue("the repair must still be about the query: $repaired", repaired!!.contains("میلگرد"))
        assertTrue("the repair must be more specific than the query", repaired.length > "میلگرد".length)
    }

    @Test
    fun aGoodQueryIsNotPunishedByAWorseSuggestion() {
        val suggestions = listOf("چیزی کاملاً بی‌ربط", "قیمت میلگرد ۱۶ امروز")
        // "قیمت میلگرد ۱۶ امروز" keeps the words; something unrelated must never win.
        val repaired = Suggestions.repair("قیمت میلگرد ۱۶", suggestions)
        assertTrue(repaired == null || repaired.contains("میلگرد"))
    }

    @Test
    fun anUnrelatedSuggestionIsRefused() {
        assertEquals(null, Suggestions.repair("قیمت میلگرد", listOf("هوای امروز تهران", "بورس کالا")))
        assertEquals(null, Suggestions.repair("قیمت میلگرد", emptyList()))
        assertEquals(null, Suggestions.repair("", listOf("چیزی")))
    }

    @Test
    fun rubbishAutocompleteBodiesDoNotThrow() {
        assertTrue(Suggestions.parse("not json").isEmpty())
        assertTrue(Suggestions.parse("[]").isEmpty())
    }

    // ---------------------------------------------------------------- the ranking

    @Test
    fun theArticleThatAnswersBeatsABrandHomepageFromABetterEngine() {
        val perEngine = mapOf(
            "google" to listOf(hit("https://www.sony.com/", "Sony", engine = "google", rank = 1)),
            "bing" to listOf(
                hit(
                    "https://www.whathifi.com/reviews/sony-wh-1000xm5",
                    "Sony WH-1000XM5 review: price, battery life and noise cancelling",
                    engine = "bing", rank = 2
                )
            )
        )
        val fused = SearchCore.fuse(perEngine, "sony wh-1000xm5 price")
        assertEquals(
            "the review answers the question; the homepage does not",
            "https://www.whathifi.com/reviews/sony-wh-1000xm5",
            fused.first().url
        )
    }

    @Test
    fun agreementBetweenEnginesStillCounts() {
        val shared = "https://shop.test/milgard-16-price"
        val perEngine = mapOf(
            "bing" to listOf(hit(shared, "قیمت میلگرد ۱۶ امروز", "bing", 1)),
            "ddg" to listOf(hit(shared, "قیمت میلگرد ۱۶ امروز", "ddg", 2)),
            "wiby" to listOf(hit("https://blog.test/unrelated", "یادداشتی دربارهٔ سفر", "wiby", 1))
        )
        val fused = SearchCore.fuse(perEngine, "قیمت میلگرد ۱۶")
        assertEquals(shared, fused.first().url)
    }

    @Test
    fun aHomepageNeedsToBeRelevantToOutrankAnArticle() {
        val perEngine = mapOf(
            "bing" to listOf(
                hit("https://shop.test/", "قیمت میلگرد ۱۶ در فروشگاه", "bing", 1),
                hit("https://news.test/milgard-16", "قیمت میلگرد ۱۶ امروز اعلام شد", "bing", 2)
            )
        )
        val fused = SearchCore.fuse(perEngine, "قیمت میلگرد ۱۶")
        assertEquals("the article wins on an equal footing", "https://news.test/milgard-16", fused.first().url)
    }

    @Test
    fun relevanceIsMeasuredFromWhatTheUserActuallyTyped() {
        val relevant = hit("https://a.test/milgard", "قیمت میلگرد ۱۶ آجدار", "bing", 1)
        val irrelevant = hit("https://b.test/car", "بازار خودرو و وام", "bing", 2)
        val q = "قیمت میلگرد ۱۶"
        assertTrue(
            SearchCore.HitRanking.relevance(q, relevant) > SearchCore.HitRanking.relevance(q, irrelevant)
        )
        assertTrue(
            "an unrelated title must not look relevant: ${SearchCore.HitRanking.relevance(q, irrelevant)}",
            SearchCore.HitRanking.relevance(q, irrelevant) < 0.5
        )
    }

    @Test
    fun trustedEnginesBreakTiesInTheirFavour() {
        assertTrue(SearchCore.HitRanking.trust("google") > SearchCore.HitRanking.trust("bing"))
        assertTrue(SearchCore.HitRanking.trust("bing") > SearchCore.HitRanking.trust("wiby"))
        assertTrue(SearchCore.HitRanking.trust("unknown-engine") > 0.0)
    }

    @Test
    fun aFrontPageIsRecognised() {
        assertTrue(SearchCore.HitRanking.isHomepage("https://shop.test"))
        assertTrue(SearchCore.HitRanking.isHomepage("https://shop.test/"))
        assertFalse(SearchCore.HitRanking.isHomepage("https://shop.test/p/1234"))
    }

    @Test
    fun rankingDoesNotLoseResultsItWasGiven() {
        val perEngine = mapOf(
            "bing" to listOf(hit("https://a.test/x", "هدفون سونی", "bing", 1)),
            "wiby" to listOf(hit("https://b.test/y", "چیز دیگری", "wiby", 1))
        )
        val fused = SearchCore.fuse(perEngine, "هدفون سونی")
        assertEquals("no hit may be dropped by ranking", 2, fused.size)
        assertEquals(2, fused.map { it.url }.distinct().size)
    }

    @Test
    fun anEmptyOrIrrelevantQueryStillReturnsTheHits() {
        val perEngine = mapOf("bing" to listOf(hit("https://a.test/x", "چیزی", "bing", 1)))
        assertEquals(1, SearchCore.fuse(perEngine, "").size)
        assertEquals(1, SearchCore.fuseResults(listOf(perEngine.getValue("bing")), "").size)
    }
}
