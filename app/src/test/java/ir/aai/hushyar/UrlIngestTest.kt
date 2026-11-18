package ir.aai.hushyar

import ir.aai.hushyar.domain.Agent
import ir.aai.hushyar.domain.SearchCore
import ir.aai.hushyar.domain.UrlIngest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.13 — «قیمت نبشی ۴ رو از منابعی که خودم دادم پیدا کن» → «قیمت در دسترس نبود».
 *
 * ریشه: پرسش چهار نشانی داشت و برنامه هیچ‌کدام را باز نکرد؛ مسیر پژوهش همیشه «جستجو» بود.
 * این تست‌ها با **شکل HTML واقعی** منابعِ داده‌شده نوشته شده‌اند؛ متن‌های بلند کوتاه شده‌اند:
 *
 *  * `t.me/s/ahanalathoseini` — پست‌ها با رقم فارسی و بدون کلمهٔ «تومان»:
 *    `✅ نبشی4فابریک (9کیلو) ۱۲۵۵۰۰` و `نبشی4نرمال 2تا3متر تماس`
 *  * `t.me/s/ahanresan` — قیمت با «ریال» و ایموجی: `💰 660,000 ریال`
 *  * `ahanmelal.com` — صفحهٔ اصلی فقط پیوندِ صفحهٔ قیمت را دارد؛ در صفحهٔ بعد جدول CSS است.
 *  * `asreahan.com` — قیمتِ قابل خواندن در HTML اولیهٔ صفحهٔ اصلی دیده نشد.
 */
class UrlIngestTest {

    private val question =
        "قیمت نبشی ۴ رو از منابعی که خودم دادم پیدا کن\n" +
            "https://ahanmelal.com\nhttps://asreahan.com\n" +
            "https://t.me/s/ahanresan\nhttps://t.me/s/ahanalathoseini"

    // ------------------------------------------------------------ finding the sources

    @Test
    fun allFourLinksInTheQuestionAreFoundInOrder() {
        val links = UrlIngest.links(question)
        assertEquals(4, links.size)
        assertEquals("https://ahanmelal.com", links[0])
        assertEquals("https://t.me/s/ahanalathoseini", links[3])
        assertTrue(UrlIngest.hasLinks(question))
        assertFalse(UrlIngest.hasLinks("قیمت نبشی ۴ چنده؟"))
    }

    @Test
    fun aLinkFollowedByCommaOrParenthesisIsCleanedButKept() {
        val links = UrlIngest.links("ببین https://t.me/s/ahanresan، و https://asreahan.com).")
        assertEquals(listOf("https://t.me/s/ahanresan", "https://asreahan.com"), links)
    }

    @Test
    fun theProductWordSeparatesFromTheInstruction() {
        val keywords = UrlIngest.keywords(question)
        assertTrue("«نبشی» is the product: $keywords", keywords.contains("نبشی"))
        assertFalse("«منابعی» is not a product: $keywords", keywords.contains("منابعی"))
        assertFalse("«دادم» is not a product: $keywords", keywords.contains("دادم"))
        assertEquals(listOf("4"), UrlIngest.sizes(question))
    }

    @Test
    fun bareDomainsAndChannelAddressesAreAcceptedAsUserSources() {
        val links = UrlIngest.links("قیمت نبشی ۴: ahanmelal.com، asreahan.com و t.me/s/ahanalathoseini")
        assertEquals(
            listOf("https://ahanmelal.com", "https://asreahan.com", "https://t.me/s/ahanalathoseini"),
            links
        )
    }

    @Test
    fun theLinkPathIsNotMistakenForAProductOrSize() {
        val q = "قیمت نبشی4 رو بگو https://example.com/price/5/today?page=6"
        assertEquals(listOf("نبشی"), UrlIngest.keywords(q))
        assertEquals(listOf("4"), UrlIngest.sizes(q))
        assertFalse(UrlIngest.asksPrice("صفحهٔ https://example.com/price/5 دربارهٔ چی هست؟"))
    }

    // ------------------------------------------------------------ telegram posts

    /** Exactly the shape Telegram serves for `t.me/s/<channel>`. */
    private val telegramHtml = """
        <html><body><section class="tgme_channel_info">نبشی ناودانی حسینی</section>
        <div class="tgme_widget_message_wrap"><div class="tgme_widget_message">
          <div class="tgme_widget_message_text js-message_text" dir="auto">#نبشی_فابریک<br/>
            ✅ نبشی4فابریک&nbsp;(7/5کیلو)&nbsp;&nbsp;&nbsp;تماس<br/>
            ✅ نبشی4 فابریک&nbsp;&nbsp;(8/5کیلو)&nbsp;&nbsp;&nbsp;۱۲۶۵۰۰<br/>
            ✅ نبشی4فابریک&nbsp;(9کیلو)&nbsp;&nbsp;&nbsp;۱۲۵۵۰۰<br/>
            ✅ نبشی5فابریک&nbsp;(17کیلو)&nbsp;&nbsp;&nbsp;۱۲۳۰۰۰<br/>
            📞 05136903007</div>
          <div class="tgme_widget_message_footer compact js-message_footer">بازدید ۱۱۱۱</div>
        </div></div>
        <div class="tgme_widget_message_wrap"><div class="tgme_widget_message">
          <div class="tgme_widget_message_text js-message_text" dir="auto">#نبشی_نرمال<br/>
            نبشی4نرمال&nbsp;2تا3متر&nbsp;&nbsp;&nbsp;تماس<br/>
            نبشی4نرمال&nbsp;3تا6متر&nbsp;&nbsp;&nbsp;تماس<br/>
            نبشی5نرمال&nbsp;1تا3متر&nbsp;&nbsp;&nbsp;۱۰۷۰۰۰</div></div></div>
        </body></html>
    """.trimIndent()

    @Test
    fun telegramIsReadPostByPost() {
        val docs = UrlIngest.documents(telegramHtml, "https://t.me/s/ahanalathoseini")
        assertEquals("each post is its own document", 2, docs.size)
        assertTrue(docs[0].contains("نبشی4فابریک"))
        assertTrue(docs[1].contains("نبشی4نرمال"))
    }

    @Test
    fun everySizeFourLineOfTheChannelIsFoundWithItsPriceOrItsContact() {
        val offers = UrlIngest.documents(telegramHtml, "https://t.me/s/ahanalathoseini")
            .flatMap { UrlIngest.offers(it, listOf("نبشی"), listOf("4"), "https://t.me/s/ahanalathoseini") }

        val priced = offers.filter { it.status == UrlIngest.OfferStatus.PRICED }
        val contact = offers.filter { it.status == UrlIngest.OfferStatus.CONTACT_ONLY }

        val amounts = priced.mapNotNull { it.amountToman }.toSet()
        assertTrue("126,500 and 125,500 must be read: $amounts", amounts.containsAll(setOf(126_500L, 125_500L)))
        assertTrue("per-kilo unit must survive: $priced", priced.all { it.unit == "کیلو" || it.unit == "کیلوگرم" })
        assertEquals("two normal-size lines and one light prefab line say «تماس»", 3, contact.size)
        assertEquals(2, contact.count { it.line.contains("نرمال") })
        assertTrue(contact.all { it.amountToman == null })
        assertTrue("«نبشی 5» must not answer a «نبشی 4» question",
            offers.none { it.line.contains("نبشی5") })
    }

    @Test
    fun aContactLineNeverInheritsTheNextLinesPrice() {
        val text = "نبشی4فابریک (7/5کیلو) تماس\nنبشی4 فابریک (8/5کیلو) ۱۲۶۵۰۰"
        val offers = UrlIngest.offers(text, listOf("نبشی"), listOf("4"), "https://t.me/s/x")
        val first = offers.first { it.line.contains("7/5") }
        assertEquals(UrlIngest.OfferStatus.CONTACT_ONLY, first.status)
        assertEquals(null, first.amountToman)
    }

    @Test
    fun aDifferentSizeWithWeightFourCannotBeMistakenForSizeFour() {
        val text = "نبشی5فابریک (4کیلو) ۱۰۷۰۰۰\nنبشی4فابریک (8/5کیلو) ۱۲۶۵۰۰"
        val found = UrlIngest.offers(text, listOf("نبشی"), listOf("4"), "https://t.me/s/x")
        assertEquals(1, found.size)
        assertTrue(found.first().line.contains("نبشی4"))
        assertEquals(126_500L, found.first().amountToman)
    }

    @Test
    fun aDifferentProductsPriceOnTheNextLineMustNotBleedAcross() {
        val text = "نبشی4فابریک (8کیلو)\nنبشی5فابریک (9کیلو) ۱۰۷۰۰۰"
        val found = UrlIngest.offers(text, listOf("نبشی"), listOf("4"), "https://t.me/s/x")
        assertEquals(1, found.size)
        assertEquals(UrlIngest.OfferStatus.NO_NUMBER, found.first().status)
        assertEquals(null, found.first().amountToman)
    }

    @Test
    fun aTickerThatPrintsOnlyADecreaseIsNotAProductPrice() {
        val text = "نبشی 4 نستا: 5000 تومان کاهش"
        val found = UrlIngest.offers(text, listOf("نبشی"), listOf("4"), "https://ahanmelal.com")
        assertEquals(1, found.size)
        assertEquals(UrlIngest.OfferStatus.NO_NUMBER, found.first().status)
    }

    @Test
    fun aPhoneNumberIsNotAProductPrice() {
        val text = "نبشی4فابریک (7/5کیلو)\n📞05136903007"
        val found = UrlIngest.offers(text, listOf("نبشی"), listOf("4"), "https://t.me/s/x")
        assertEquals(1, found.size)
        assertEquals(UrlIngest.OfferStatus.NO_NUMBER, found.first().status)
    }

    // ------------------------------------------------------------ ahanresan shapes

    private val ahanresanPost = """
        🟦 قیمت ورق فولاد مبارکه — ابعاد ۶۰۰×۱۵۰ سانتی‌متر
        🔹 ورق 2 میل — 600×150
        💰 660,000 ریال
        🔹 تیرآهن 14 اصفهان — وزن 155 کیلو
        💰 96,000,000 ریال
    """.trimIndent()

    @Test
    fun aPriceOnTheFollowingLineBelongsToTheProductAboveIt() {
        val offers = UrlIngest.offers(ahanresanPost, listOf("ورق"), listOf("2"), "https://t.me/s/ahanresan")
        assertEquals(1, offers.size)
        val offer = offers.first()
        assertEquals(UrlIngest.OfferStatus.PRICED, offer.status)
        // 660,000 Rial = 66,000 Toman — the conversion is printed, never guessed silently.
        assertEquals(66_000L, offer.amountToman)
        assertEquals("ریال", offer.currencyWord)
        assertTrue(offer.line.contains("660,000"))
    }

    @Test
    fun aProductWhosePriceIsMissingIsReportedAsSuchNotSkipped() {
        val text = "🔹 نبشی 4 — 6 متر\n❗️ اتمام موجودی"
        val offers = UrlIngest.offers(text, listOf("نبشی"), listOf("4"), "https://example.com")
        assertEquals(1, offers.size)
        assertEquals(UrlIngest.OfferStatus.CONTACT_ONLY, offers.first().status)
    }

    // ------------------------------------------------------------ a page with no price at all

    @Test
    fun aHomepageWithoutPricesProducesNoOfferAndTheReasonIsCountable() {
        val html = """
            <html><head><title>آهن ملل | قیمت آهن</title></head><body>
            <nav><a href="/price/">قیمت میلگرد</a> <a href="/price/nabshi">قیمت نبشی</a></nav>
            <h1>خرید آهن آلات</h1>
            <p>قیمت‌ها به‌صورت لحظه‌ای در جدول زیر به‌روزرسانی می‌شوند.</p>
            </body></html>
        """.trimIndent()
        val docs = UrlIngest.documents(html, "https://ahanmelal.com")
        assertEquals(1, docs.size)
        val offers = UrlIngest.offers(docs.first(), listOf("نبشی"), listOf("4"), "https://ahanmelal.com")
        assertTrue("no size-4 price line may be invented", offers.none { it.status == UrlIngest.OfferStatus.PRICED })
        assertTrue(docs.first().isNotBlank())
    }

    /** CSS-grid price table of a site whose homepage only links to the price subpage. */
    private val angleGrid = """
        <html><body>
        <div class="tr gridHeader">
          <div class=th>نوع نبشی</div><div class=th>ضخامت (mm)</div>
          <div class=th>طول (m)</div><div class=th>نوسان</div><div class=th>قیمت</div>
        </div>
        <div class="tr trbody tr-row">
          <div class=td>نبشی سایز 4</div><div class=td>2</div><div class=td>6</div>
          <div class="bluecolor td"><span>ثابت</span></div>
          <div data-price="132700" data-price2="120636" class="td price priceText">120,636</div>
        </div>
        <div class="tr trbody tr-row">
          <div class=td>نبشی سایز 5</div><div class=td>4</div><div class=td>6</div>
          <div class="bluecolor td"><span>ثابت</span></div>
          <div data-price="117700" data-price2="107000" class="td price priceText">107,000</div>
        </div>
        </body></html>
    """.trimIndent()

    @Test
    fun aCssGridIsReadRowByRowUsingVisiblePriceNotHiddenTaxAttributes() {
        val rows = UrlIngest.documents(angleGrid, "https://ahanmelal.com/steel-angle-price")
        assertEquals(2, rows.size)
        assertTrue(rows.first().contains("ضخامت (mm): 2"))
        assertTrue(rows.first().contains("قیمت: 120,636"))
        val offers = rows.flatMap {
            UrlIngest.offers(it, listOf("نبشی"), listOf("4"), "https://ahanmelal.com/steel-angle-price")
        }
        assertEquals(1, offers.size)
        assertEquals(120_636L, offers.first().amountToman)
        assertEquals(null, offers.first().currencyWord)
        assertFalse("hidden value and next row must not be mistaken for the price",
            offers.first().line.contains("132700") || offers.first().line.contains("107,000"))
    }

    @Test
    fun aHomepageProductLinkIsFollowedAndCitedWithoutUsingASearchEngine() {
        val home = """
            <html><body><a href="/steel-angle-price">قیمت نبشی</a>
            <p>نبشی 4 نستا: 5000 تومان کاهش</p></body></html>
        """.trimIndent()
        val tools = sourceTools(mapOf(
            "https://ahanmelal.com" to home,
            "https://ahanmelal.com/steel-angle-price" to angleGrid
        ))
        val answer = Agent(tools).run("قیمت نبشی ۴؟ ahanmelal.com")
        assertFalse(answer.answer.refused)
        assertTrue(answer.answer.bullets.joinToString().contains("۱۲۰٬۶۳۶"))
        assertFalse(answer.answer.bullets.joinToString().contains("۵٬۰۰۰"))
        assertTrue(answer.answer.citations.any { it.url == "https://ahanmelal.com/steel-angle-price" })
        assertTrue(answer.steps.any { it.tool == "sources.read" &&
            it.args["url"] == "https://ahanmelal.com/steel-angle-price" })
        assertTrue(tools.searchedQueries.isEmpty())
    }

    // ------------------------------------------------------------ the agent path

    @Test
    fun theAgentAnswersFromTheUsersOwnSourcesWithoutSearching() {
        val tools = sourceTools(
            mapOf(
                "https://t.me/s/ahanalathoseini" to telegramHtml,
                "https://ahanmelal.com" to "<html><body><p>قیمت‌ها با جاوااسکریپت</p></body></html>"
            )
        )
        val outcome = Agent(tools).run(
            "قیمت نبشی ۴ رو از منابعی که خودم دادم پیدا کن https://ahanmelal.com https://t.me/s/ahanalathoseini"
        )
        assertFalse("an answer exists, so this must not be a refusal", outcome.answer.refused)
        val text = (listOf(outcome.answer.text) + outcome.answer.bullets).joinToString("\n")
        assertTrue("a real price from the channel: $text", text.contains("۱۲۶٬۵۰۰"))
        assertTrue("the source is named", outcome.answer.citations.any { it.url == "https://t.me/s/ahanalathoseini" })
        assertTrue("no web search may run when the user gave sources", tools.searchedQueries.isEmpty())
        assertTrue("the fetched pages are logged as steps",
            outcome.steps.any { it.tool == "sources.read" })
    }

    @Test
    fun theExactFourSourceScenarioOpensAllFourAndThenTheRelevantInternalPage() {
        val angleUrl = "https://ahanmelal.com/steel-angle-price"
        val home = "<html><body><a href=\"/steel-angle-price\">قیمت نبشی</a>" +
            "<p>نبشی 4 نستا: 5000 تومان کاهش</p></body></html>"
        val tools = sourceTools(mapOf(
            "https://ahanmelal.com" to home,
            "https://asreahan.com" to "<html><script>window.__DATA__ = {}</script><title>عصر آهن</title></html>",
            "https://t.me/s/ahanresan" to ahanresanPost,
            "https://t.me/s/ahanalathoseini" to telegramHtml,
            angleUrl to angleGrid
        ))
        val result = Agent(tools).run(question)
        val opened = result.steps.filter { it.tool == "sources.read" }.mapNotNull { it.args["url"] }
        assertEquals(UrlIngest.links(question), opened.take(4))
        assertEquals(angleUrl, opened.last())
        assertFalse(result.answer.refused)
        val text = result.answer.bullets.joinToString("\n")
        assertTrue(text.contains("۱۲۶٬۵۰۰"))
        assertTrue(text.contains("۱۲۰٬۶۳۶"))
        assertFalse("the other size's price may not be the answer", text.contains("۱۰۷٬۰۰۰"))
        assertTrue(result.answer.citations.any { it.url == angleUrl })
        assertTrue(result.answer.citations.any { it.url == "https://t.me/s/ahanalathoseini" })
        assertTrue(tools.searchedQueries.isEmpty())
    }

    @Test
    fun aFoundNumberIsStoredPerVariantAndReusedWithoutMixingOtherSizes() {
        val url = "https://t.me/s/ahanalathoseini"
        val tools = sourceTools(mapOf(url to telegramHtml))
        Agent(tools).run("قیمت نبشی ۴ از $url")
        assertEquals(2, tools.knowledge.count { it.category == UrlIngest.SOURCE_PRICE_CATEGORY })
        assertTrue(tools.knowledge.all { it.ttlDays == 3 })

        Agent(tools).run("قیمت نبشی ۵ از $url")
        val reused = Agent(tools).run("قیمت نبشی ۴")
        assertEquals("knowledge.source-price", reused.answer.intent)
        assertTrue(reused.answer.bullets.joinToString().contains("۱۲۶٬۵۰۰"))
        assertFalse("size five must not answer size four", reused.answer.bullets.joinToString().contains("۱۲۳٬۰۰۰"))
        assertTrue("source attribution survives memory", reused.answer.citations.any { it.url == url })
        assertTrue("reusing the local bank costs no web search", tools.searchedQueries.isEmpty())
    }

    @Test
    fun aSourceThatPrintsNoCurrencyDoesNotBecomeATomanClaim() {
        val url = "https://t.me/s/ahanalathoseini"
        val tools = sourceTools(mapOf(url to telegramHtml))
        val result = Agent(tools).run("قیمت نبشی ۴ از $url")
        assertTrue(result.answer.text.contains("واحد پول در سطر ذکر نشده") ||
            result.answer.bullets.any { it.contains("واحد پول در سطر ذکر نشده") })
        assertFalse(result.answer.text.contains("۱۲۶٬۵۰۰ تومان"))
    }

    @Test
    fun whenNobodyPrintsAPriceTheAnswerSaysWhatEachSourceDid() {
        val tools = sourceTools(
            mapOf(
                "https://ahanmelal.com" to "<html><body><h1>آهن ملل</h1><p>فهرست محصولات</p></body></html>"
            ),
            setOf("https://asreahan.com")
        )
        val outcome = Agent(tools).run("قیمت نبشی ۴ چنده؟ https://ahanmelal.com https://asreahan.com")
        assertTrue(outcome.answer.refused)
        val text = outcome.answer.bullets.joinToString("\n")
        assertTrue("the working source is reported with its line count: $text", text.contains("ahanmelal.com"))
        assertTrue("the unreachable source is named, not hidden: $text", text.contains("asreahan.com"))
        assertTrue("it says plainly that no number was invented: $text", text.contains("پیدا نشد"))
    }
}

/** Builds the tools double with the pages the user handed over; no engine is reachable. */
private fun sourceTools(pages: Map<String, String>, fail: Set<String> = emptySet()): FakeTools {
    val noNetwork = SearchCore(
        http = object : ir.aai.hushyar.core.HttpTransport {
            override fun get(request: ir.aai.hushyar.core.HttpRequest) =
                ir.aai.hushyar.core.HttpResponse(request.url, 599, "")
        },
        engines = emptyList(),
        pacing = 0L,
        sleep = {}
    )
    val pack = ir.aai.hushyar.domain.Interview.build(
        mapOf(
            "market_name" to "بازار آهن",
            "attributes" to "سایز",
            "currency_unit" to "تومان"
        ),
        1_700_000_000_000L
    ).pack
    return FakeTools(pack, noNetwork, servedPages = pages, failedUrls = fail)
}
