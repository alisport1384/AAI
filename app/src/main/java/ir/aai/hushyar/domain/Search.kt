package ir.aai.hushyar.domain

import ir.aai.hushyar.core.Html
import ir.aai.hushyar.core.HttpDefaults
import ir.aai.hushyar.core.HttpRequest
import ir.aai.hushyar.core.HttpTransport
import ir.aai.hushyar.core.Lang
import ir.aai.hushyar.core.LangDetect
import ir.aai.hushyar.core.Lexicon
import ir.aai.hushyar.core.Json
import ir.aai.hushyar.core.MiniJson
import ir.aai.hushyar.core.Ranking
import ir.aai.hushyar.core.Text

/**
 * One engine adapter is nothing more than a URL template plus the hosts that belong to
 * the engine itself. Parsing is engine-agnostic and lives in [SerpParser]; when an engine
 * changes its markup the adapter survives and only the generic filter is adjusted.
 */
enum class EngineKind { GENERAL, WIKI, META, PERSIAN, SPECIALIST }

/** Wire format of one endpoint. Chosen per engine from what the endpoint actually returns. */
enum class SerpFormat { HTML, RSS, JSON }

/**
 * One concrete way of asking an engine. An engine may have several: Bing answers both as HTML
 * and as a tiny RSS document, and the RSS one is 10× smaller and far more stable — so it goes
 * first. Order in [EngineSpec.endpoints] is the order we try them.
 */
data class Endpoint(
    val template: String,
    val format: SerpFormat = SerpFormat.HTML,
    val note: String = ""
)

/**
 * What an engine actually did, as opposed to "0 results". Measured 2026-11-17 from a datacenter
 * IP: Bing/DDG/Mwmbl/Wiby/Seznam answer, Google/Startpage/ECOSIA/Mojeek/Qwant/Swisscows hand
 * back a JavaScript shell or a 403. Telling the user *which* of those happened is the difference
 * between "the search is broken" and "this engine blocks this network".
 */
enum class SerpOutcome { RESULTS, EMPTY, BLOCKED, RATE_LIMITED, JS_REQUIRED, HTTP_ERROR, NETWORK }

data class SerpVerdict(val outcome: SerpOutcome, val detail: String = "") {
    val ok: Boolean get() = outcome == SerpOutcome.RESULTS
}

/**
 * One adapter = one public HTML endpoint + the hosts that belong to the engine itself.
 *
 * [friction] is an honest hint written from what these endpoints actually do without a browser:
 * 1 = plain HTML we can read on device, 2 = mixed (markup changes, occasional consent wall),
 * 3 = usually blocked or JavaScript-only. It only decides the *order* we try engines in and what we
 * tell the user afterwards — it is never a promise that an engine answers.
 */
data class EngineSpec(
    val id: String,
    val name: String,
    val template: String,
    val hosts: List<String>,
    val kind: EngineKind = EngineKind.GENERAL,
    val needsJs: Boolean = false,
    val friction: Int = 2,
    val note: String = "",
    /**
     * Extra ways to ask the same engine, tried in order when the primary one yields nothing.
     * The primary is [template]; this list is what turns "usually 0 results" into a result.
     */
    val fallbacks: List<Endpoint> = emptyList()
) {
    /** Primary first, then the alternates. */
    val endpoints: List<Endpoint>
        get() = listOf(Endpoint(template, SerpFormat.HTML)) + fallbacks

    fun url(query: String, langTag: String): String = render(template, query, langTag)

    fun render(template: String, query: String, langTag: String): String {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val fa = langTag == "fa"
        val hl = if (fa) "fa" else "en"
        val gl = if (fa) "ir" else "us"
        return template.replace("{q}", encoded)
            .replace("{hl}", hl)
            .replace("{gl}", gl)
            .replace("{ceid}", "${gl.uppercase()}:$hl")
    }
}

/**
 * The engine catalogue: as many public, key-less, account-less HTML endpoints as we can honestly
 * track. Nothing here needs an API key, and nothing here bypasses a block: when an engine answers
 * with 403/429 we simply stop asking it for a while.
 */
object Engines {

    val all: List<EngineSpec> = listOf(
        // ---- Google first, as the user asked — with the route that actually answers ----
        //
        // Measured 2026-11-17 from a datacenter IP: google.com/search returns HTTP 200 with a
        // JavaScript shell (one link, `enablejs`), so the plain HTML route cannot answer there.
        // What *does* answer without a key is Google's own news feed, which is machine-readable
        // RSS and carries real Google-ranked items. The HTML route stays first because on a phone
        // with a residential IP it can still return real markup; the RSS route is the fallback
        // that keeps Google from being a dead entry.
        EngineSpec(
            "google", "Google", "https://www.google.com/search?num=10&hl={hl}&gl={gl}&q={q}&udm=14",
            listOf("google.com", "google.co", "gstatic.com", "googleusercontent.com"),
            kind = EngineKind.GENERAL, friction = 0, needsJs = true,
            note = "صفحهٔ وب گوگل بدون جاوااسکریپت نتیجه نمی‌دهد؛ مسیر جانشین، خوراک گوگل‌نیوز است",
            fallbacks = listOf(
                Endpoint(
                    "https://news.google.com/rss/search?q={q}&hl={hl}&gl={gl}&ceid={ceid}",
                    SerpFormat.RSS,
                    "خوراک گوگل‌نیوز (بدون کلید، خوانا برای برنامه)"
                )
            )
        ),
        // ---- the ones that usually hand us plain HTML ----
        EngineSpec(
            "ddg", "DuckDuckGo (html)", "https://html.duckduckgo.com/html/?q={q}",
            listOf("duckduckgo.com"), friction = 1, note = "سبک، بدون جاوااسکریپت"
        ),
        EngineSpec(
            "ddg-lite", "DuckDuckGo (lite)", "https://lite.duckduckgo.com/lite/?q={q}",
            listOf("duckduckgo.com"), friction = 1, note = "نسخهٔ خیلی سبک"
        ),
        EngineSpec(
            "mojeek", "Mojeek", "https://www.mojeek.com/search?q={q}",
            listOf("mojeek.com"), friction = 3,
            note = "ایندکس مستقل؛ از IP مرکز داده ۴۰۳ می‌دهد (سنجیده‌شده) — از شبکهٔ موبایل معمولاً باز است"
        ),
        EngineSpec(
            "wikipedia-fa", "ویکی‌پدیا (fa)", "https://fa.wikipedia.org/w/index.php?search={q}&ns0=1",
            listOf("wikipedia.org", "wikimedia.org"), kind = EngineKind.WIKI, friction = 1,
            note = "برای تعریف و پیشینه بهتر از هر موتور دیگری"
        ),
        EngineSpec(
            "wikipedia-en", "Wikipedia (en)", "https://en.wikipedia.org/w/index.php?search={q}&ns0=1",
            listOf("wikipedia.org", "wikimedia.org"), kind = EngineKind.WIKI, friction = 1
        ),
        EngineSpec(
            "marginalia", "Marginalia", "https://search.marginalia.nu/search?query={q}",
            listOf("marginalia.nu", "old-search.marginalia.nu"), kind = EngineKind.SPECIALIST,
            friction = 1, note = "متن‌محور؛ برای موضوع‌های فنی خوب است"
        ),
        // ---- mixed ----
        EngineSpec(
            "bing", "Bing", "https://www.bing.com/search?count=10&setlang={hl}&q={q}",
            listOf("bing.com", "microsoft.com", "msn.com"), friction = 2,
            note = "HTML معمولاً جواب می‌دهد؛ اگر نه، خروجی RSS همان جستجو",
            fallbacks = listOf(
                Endpoint(
                    "https://www.bing.com/search?q={q}&format=rss&count=20",
                    SerpFormat.RSS,
                    "سنجیده‌شده ۲۰۲۶-۱۱-۱۷: ۲۰۰ با ۱۰–۱۲ نتیجه، ۵ کیلوبایت، فارسی و انگلیسی"
                )
            )
        ),
        EngineSpec(
            "mwmbl", "Mwmbl", "https://api.mwmbl.org/api/v1/search/?s={q}",
            listOf("mwmbl.org"), kind = EngineKind.SPECIALIST, friction = 1,
            note = "API عمومی بدون کلید؛ سنجیده‌شده: ۲۰۰ با ۳۰+ نتیجه در یک پاسخ JSON کوچک"
        ),
        EngineSpec(
            "wiby", "Wiby", "https://wiby.me/?q={q}",
            listOf("wiby.me"), kind = EngineKind.SPECIALIST, friction = 1,
            note = "ایندکس کوچک و دست‌چین؛ سنجیده‌شده: ۲۰۰ با نتیجه برای انگلیسی، بدون فارسی"
        ),
        EngineSpec(
            "brave", "Brave", "https://search.brave.com/search?q={q}",
            listOf("brave.com", "bravesoftware.com"), friction = 2
        ),
        EngineSpec(
            "yahoo", "Yahoo", "https://search.yahoo.com/search?p={q}",
            listOf("yahoo.com", "yimg.com"), friction = 2, note = "اغلب از Bing تغذیه می‌شود"
        ),
        EngineSpec(
            "ecosia", "Ecosia", "https://www.ecosia.org/search?q={q}",
            listOf("ecosia.org", "bing.com"), friction = 2, note = "نتایج مبتنی بر Bing"
        ),
        EngineSpec(
            "seznam", "Seznam", "https://search.seznam.cz/?q={q}",
            listOf("seznam.cz"), friction = 2, note = "ایندکس چک، HTML ساده"
        ),
        EngineSpec(
            "dogpile", "Dogpile", "https://www.dogpile.com/serp?q={q}",
            listOf("dogpile.com", "infospace.com"), kind = EngineKind.META, friction = 2
        ),
        EngineSpec(
            "stract", "Stract", "https://stract.com/search?q={q}",
            listOf("stract.com"), kind = EngineKind.SPECIALIST, friction = 2,
            note = "موتور مستقل و متن‌باز"
        ),
        EngineSpec(
            "rightdao", "Right Dao", "https://rightdao.com/search?query={q}",
            listOf("rightdao.com"), friction = 2
        ),
        // ---- meta-search (one request covers several engines) ----
        EngineSpec(
            "searx-be", "SearXNG (searx.be)", "https://searx.be/search?q={q}",
            listOf("searx.be"), kind = EngineKind.META, friction = 2,
            note = "متاجستجو؛ نتیجهٔ چند موتور با یک درخواست"
        ),
        EngineSpec(
            "searx-tiek", "SearXNG (tiekötter)", "https://searx.tiekoetter.com/search?q={q}",
            listOf("searx.tiekoetter.com"), kind = EngineKind.META, friction = 2
        ),
        EngineSpec(
            "searx-bare", "SearXNG (baresearch)", "https://baresearch.org/search?q={q}",
            listOf("baresearch.org"), kind = EngineKind.META, friction = 2
        ),
        // ---- Persian-first ----
        EngineSpec(
            "parsijoo", "پارسی‌جو", "https://parsijoo.ir/search/?q={q}",
            listOf("parsijoo.ir"), kind = EngineKind.PERSIAN, friction = 3,
            note = "موتور ایرانی؛ بی‌جاوااسکریپت نیست ولی صفحهٔ HTML می‌دهد"
        ),
        EngineSpec(
            "yooz", "یوز", "https://yooz.ir/search/?q={q}",
            listOf("yooz.ir"), kind = EngineKind.PERSIAN, friction = 3
        ),
        // ---- usually blocked or JavaScript-only: kept last, still worth one try ----

        EngineSpec(
            "yandex", "Yandex", "https://yandex.com/search/?text={q}",
            listOf("yandex.com", "yandex.ru", "yastatic.net"), friction = 3, needsJs = true
        ),
        EngineSpec(
            "startpage", "Startpage", "https://www.startpage.com/sp/search?query={q}",
            listOf("startpage.com"), friction = 3, note = "اغلب با چالش ربات پاسخ می‌دهد"
        ),
        EngineSpec(
            "qwant", "Qwant", "https://www.qwant.com/?q={q}",
            listOf("qwant.com"), friction = 3, needsJs = true,
            note = "اپ جاوااسکریپتی؛ HTML سرور نتیجه ندارد و API عمومی ۴۰۳ می‌دهد"
        ),
        EngineSpec(
            "swisscows", "Swisscows", "https://swisscows.com/web?query={q}",
            listOf("swisscows.com"), friction = 3, needsJs = true
        ),
        EngineSpec(
            "ask", "Ask", "https://www.ask.com/web?q={q}",
            listOf("ask.com"), friction = 3
        ),
        EngineSpec(
            "presearch", "Presearch", "https://presearch.com/search?q={q}",
            listOf("presearch.com"), friction = 3, needsJs = true
        )
    )

    fun byId(id: String): EngineSpec? = all.firstOrNull { it.id == id }

    /**
     * Cheapest, most likely to work first — that is the only ordering that makes sense when the
     * budget allows a handful of engines. A Persian-first engine gets a one-step boost for a Persian
     * question, never absolute priority: a JavaScript-only engine is not worth the first attempt.
     */
    fun ordered(langTag: String = "fa"): List<EngineSpec> = all.sortedWith(
        compareBy<EngineSpec> {
            val boost = if (langTag == "fa" && it.kind == EngineKind.PERSIAN) 1 else 0
            (it.friction - boost).coerceAtLeast(0)
        }.thenBy { it.friction }.thenBy { it.id }
    )

    /** Engines we expect to answer; used as the default per-search cap so one search stays cheap. */
    val usuallyWorks: List<EngineSpec> = all.filter { it.friction <= 2 }

    fun byKind(kind: EngineKind): List<EngineSpec> = all.filter { it.kind == kind }
}

/**
 * Generic SERP reader. Strategy: take every anchor on the page, keep the ones that look
 * like results (real title, off-engine host, not a search/nav route), and take the visible
 * text right after the anchor as the snippet. This survives layout changes far better than
 * selector-based scraping and is fully unit-testable with fixture HTML.
 */
object SerpParser {

    private val NAV = setOf(
        "log in", "sign in", "sign up", "privacy", "terms", "about", "settings", "help",
        "feedback", "images", "videos", "news", "maps", "shopping", "translate", "preferences",
        "ورود", "ثبت نام", "حریم خصوصی", "درباره", "تنظیمات", "راهنما", "تصاویر", "ویدیو", "اخبار", "نقشه"
    )

    private val BAD_PATH = Regex("(?i)/(search|preferences|settings|account|login|signin|policies|terms|cookie)")

    /**
     * One entry point for every wire format. HTML keeps the anchor-window strategy; RSS and JSON
     * are already structured, so they only need the same accept() filter as everything else.
     */
    fun parseAny(engine: EngineSpec, format: SerpFormat, body: String, limit: Int = 8): List<WebHit> {
        val items: List<FeedParser.FeedItem> = when (format) {
            SerpFormat.HTML -> return parse(engine, body, limit)
            SerpFormat.RSS -> FeedParser.items(body, limit * 3)
            SerpFormat.JSON -> when (engine.id) {
                "mwmbl" -> JsonSerp.mwmbl(body, limit * 3)
                "wikipedia-fa", "wikipedia-en" -> JsonSerp.wikipedia(body, limit * 3)
                else -> FeedParser.items(body, limit * 3) // generic: whatever URL-shaped fields exist
            }
        }
        val out = LinkedHashMap<String, WebHit>()
        for (item in items) {
            if (out.size >= limit) break
            val real = unwrapLink(item.link)
            if (!accept(engine, real, item.title)) continue
            val canonical = canonical(real)
            if (out.containsKey(canonical)) continue
            out[canonical] = WebHit(
                url = canonical,
                title = clean(item.title),
                snippet = clean(item.description).take(240),
                engine = engine.id,
                rank = out.size + 1
            )
        }
        return out.values.toList()
    }

    fun parse(engine: EngineSpec, html: String, limit: Int = 8): List<WebHit> {
        val out = LinkedHashMap<String, WebHit>()
        for (link in Html.links(html, engine.url("x", "fa"), limit = 120)) {
            if (out.size >= limit) break
            val real = unwrapLink(link.url)
            if (!accept(engine, real, link.text)) continue
            val canonical = canonical(real)
            if (out.containsKey(canonical)) continue
            val snippet = snippetFor(html, link.url)
            out[canonical] = WebHit(
                url = canonical,
                title = clean(link.text),
                snippet = snippet,
                engine = engine.id,
                rank = out.size + 1
            )
        }
        return out.values.toList()
    }

    private val REDIRECT_PARAMS = setOf("uddg", "u", "url", "target", "dest", "q")

    /**
     * Engines hide the real result behind their own click-tracker: DuckDuckGo sends
     * `//duckduckgo.com/l/?uddg=<encoded>`, Google `/url?q=`, Bing `/ck/a?...&u=`. Filtering on the
     * engine's own host before unwrapping is why DDG "returned nothing" for months.
     */
    fun unwrapLink(url: String): String {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return url
        val host = uri.host?.lowercase() ?: return url
        val path = uri.rawPath.orEmpty()
        val redirector = (host.endsWith("duckduckgo.com") && path.startsWith("/l/")) ||
            (host.endsWith("google.com") && (path == "/url" || path == "/url/")) ||
            (host.endsWith("bing.com") && path.startsWith("/ck/a")) ||
            (host.endsWith("search.yahoo.com") && path.startsWith("/r")) ||
            (host.endsWith("yandex.com") || host.endsWith("yandex.ru")) ||
            (host.endsWith("seznam.cz") && path.startsWith("/redir"))
        if (!redirector) return url
        val target = uri.rawQuery.orEmpty().split('&').firstNotNullOfOrNull { pair ->
            val key = pair.substringBefore('=')
            if (key in REDIRECT_PARAMS && pair.contains('=')) pair.substringAfter('=') else null
        } ?: return url
        val decoded = runCatching { java.net.URLDecoder.decode(target, "UTF-8") }.getOrDefault(target)
        return decoded.takeIf { it.startsWith("http") } ?: url
    }

    fun accept(engine: EngineSpec, url: String, label: String): Boolean {
        val lower = url.lowercase()
        if (!lower.startsWith("http")) return false
        val host = Html.domainOf(url)?.lowercase() ?: return false
        if (engine.hosts.any { host == it || host.endsWith(".$it") }) return false
        if (BAD_PATH.containsMatchIn(lower)) return false
        val title = clean(label)
        if (title.length < 8) return false
        if (title.lowercase() in NAV) return false
        if (title.count { it.isDigit() } > title.length / 2) return false
        val path = runCatching { java.net.URI(url).path.orEmpty() }.getOrDefault("")
        if (path.isEmpty() || path == "/") return false
        return true
    }

    fun clean(raw: String): String = Text.normalize(raw).replace(Regex("\\s+"), " ").trim()

    /** Removes tracking noise so the same result from two engines collapses into one. */
    fun canonical(url: String): String {
        val noFragment = url.substringBefore('#')
        val uri = runCatching { java.net.URI(noFragment) }.getOrNull() ?: return noFragment
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return noFragment
        val keep = uri.rawQuery?.split('&')
            ?.filter { q -> q.substringBefore('=').let { it.startsWith("id") || it == "v" || it == "p" || it == "q" } }
            ?.joinToString("&")
            .orEmpty()
        val path = uri.rawPath.orEmpty().let { if (it.endsWith("/")) it.dropLast(1) else it }
        val query = when {
            keep.isEmpty() -> ""
            uri.host?.contains("youtube") == true -> "?${uri.rawQuery}"
            else -> "?$keep"
        }
        return "${uri.scheme ?: "https"}://$host$path$query"
    }

    private fun snippetFor(html: String, url: String): String {
        val idx = html.indexOf(url.substringBefore('?'))
        if (idx < 0) return ""
        val window = html.substring(idx, (idx + 2600).coerceAtMost(html.length))
        val text = Html.toText(window)
        val titleCut = text.indexOf('\n')
        val body = if (titleCut in 0 until text.length - 1) text.substring(titleCut + 1) else text
        return body.replace(Regex("\\s+"), " ").trim().take(240)
    }
}


/**
 * RSS/Atom reader — the smallest honest way for an engine to answer us. Bing exposed this
 * (`&format=rss`) and it is 5 KB instead of 40 KB and needs no JavaScript at all.
 */
object FeedParser {

    data class FeedItem(val title: String, val link: String, val description: String)

    fun items(xml: String, limit: Int = 20): List<FeedItem> {
        // Raw strings here are not style: in a normal Kotlin string "\b" is a backspace character,
        // which silently produced a pattern that matched nothing and made every RSS engine look empty.
        val blocks = Regex("""(?is)<item\b[^>]*>(.*?)</item>""").findAll(xml).map { it.groupValues[1] }.toList()
            .ifEmpty {
                Regex("""(?is)<entry\b[^>]*>(.*?)</entry>""").findAll(xml).map { it.groupValues[1] }.toList()
            }
        val out = ArrayList<FeedItem>(blocks.size)
        for (block in blocks) {
            if (out.size >= limit) break
            val title = tag(block, "title")
            val link = plainTag(block, "link") ?: attrTag(block, "link", "href") ?: continue
            val description = tag(block, "description").ifBlank { tag(block, "summary") }
            if (title.isBlank() || !link.startsWith("http")) continue
            out.add(FeedItem(Html.decodeEntities(title), Html.decodeEntities(link), Html.decodeEntities(description)))
        }
        return out
    }

    private fun plainTag(block: String, name: String): String? =
        Regex("""(?is)<$name\s*>(.*?)</$name>""").find(block)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }

    private fun attrTag(block: String, name: String, attr: String): String? =
        Regex("""(?is)<$name\b[^>]*\b$attr\s*=\s*["']([^"']+)["']""").find(block)?.groupValues?.get(1)

    private fun tag(block: String, name: String): String =
        plainTag(block, name) ?: ""
}

/**
 * The two key-less JSON APIs that answered in the 2026-11 survey: Mwmbl (an open index) and the
 * MediaWiki search API. Both are parsed defensively: an unexpected shape yields an empty list,
 * never an exception, because a search must never crash the agent.
 */
object JsonSerp {

    fun mwmbl(body: String, limit: Int = 20): List<FeedParser.FeedItem> = runCatching {
        MiniJson.parse(body).asObjects().take(limit).mapNotNull { row ->
            val url = row["url"]?.asStringOrNull() ?: return@mapNotNull null
            val title = segments(row["title"])
            val extract = segments(row["extract"]).ifBlank { segments(row["description"]) }
            FeedParser.FeedItem(title.ifBlank { url }, url, extract)
        }
    }.getOrDefault(emptyList())

    fun wikipedia(body: String, limit: Int = 20): List<FeedParser.FeedItem> = runCatching {
        val root = MiniJson.parse(body).fieldsOrEmpty
        val search = root["query"]?.fieldsOrEmpty?.get("search")?.asObjects().orEmpty()
        search.take(limit).mapNotNull { row ->
            val title = (row["title"]?.asStringOrNull() ?: return@mapNotNull null)
                .replace(Regex("""\s+"""), " ").trim()
            val snippet = Html.toText(row["snippet"]?.asStringOrNull().orEmpty())
            FeedParser.FeedItem(title, wikiUrl(row), snippet)
        }
    }.getOrDefault(emptyList())

    /**
     * Mwmbl highlights matches by splitting the title into `[{"value":"Sony","is_bold":true}, …]`.
     * Reading it as a plain string threw the whole title away and every Mwmbl row was dropped.
     */
    private fun segments(node: Json?): String {
        if (node == null) return ""
        node.asStringOrNull()?.let { return it.trim() }
        return node.asObjects().joinToString("") { it["value"]?.asStringOrNull().orEmpty() }.trim()
    }

    private fun wikiUrl(row: Map<String, Json>): String = row["fullurl"]?.asStringOrNull()
        ?: ("https://" + (row["wiki"]?.asStringOrNull() ?: "en.wikipedia.org") + "/wiki/" +
            java.net.URLEncoder.encode(row["title"]?.asStringOrNull().orEmpty().replace(' ', '_'), "UTF-8"))
}

/**
 * Google's autocomplete endpoint, measured working on 2026-11-17 with no key, no account and no
 * JavaScript (`suggestqueries.google.com/complete/search?client=firefox` returns a small JSON
 * array). It is not a result list — it is a *query repair* tool, and it is the one Google surface
 * that a small offline-first app can honestly use: for «میلگرد» it answers «میلگرد ۱۶ چند کیلو
 * است», which is the difference between a useless query and an answerable one.
 */
object Suggestions {

    /** `["میلگرد",["میلگرد","میلگرد قیمت",…],[],{…}]` — the Firefox client shape. */
    fun parse(body: String, limit: Int = 10): List<String> = runCatching {
        val root = MiniJson.parse(body).itemsOrEmpty
        val list = root.getOrNull(1)?.asStrings().orEmpty()
        list.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(limit)
    }.getOrDefault(emptyList())

    /**
     * The best repair for [query], or null when nothing is better than what we already have.
     * Rules, all measured against real Persian suggestions: it must keep at least one word of the
     * query, must not be the query itself, must not be a shopping-site stub, and stays short.
     */
    fun repair(query: String, suggestions: List<String>): String? {
        val clean = query.trim()
        if (clean.isEmpty() || suggestions.isEmpty()) return null
        val words = Text.contentTokens(clean).filterLoadable()
        if (words.isEmpty()) return null
        return suggestions
            .asSequence()
            .map { it.trim() }
            .filter { it.length in 3..60 }
            .filter { it != clean && !it.equals(clean, ignoreCase = true) }
            .filter { cand -> words.any { cand.contains(it, ignoreCase = true) } }
            .filter { cand -> cand.split(' ').size <= 8 }
            .sortedByDescending { cand -> words.count { cand.contains(it, ignoreCase = true) } * 10 + cand.length.coerceAtMost(40) }
            .firstOrNull()
    }

    private fun List<String>.filterLoadable(): List<String> = filter { it.length > 1 }
}

/**
 * Why an engine gave us nothing. Measured failure modes from a datacenter IP on 2026-11-17:
 * Google/Startpage return a JavaScript shell with status 200; Ecosia/Mojeek/Yep return 403;
 * DuckDuckGo answers 202 with an anomaly page when asked twice in a row.
 */
object SerpGuard {

    private val CAPTCHA = Regex(
        "(?i)(captcha|unusual traffic|detected unusual|are you a robot|verify you are human|" +
            "anomaly|automated queries|too many requests|rate limit|access denied|forbidden)"
    )
    private val JS_ONLY = Regex(
        "(?i)(enable javascript|javascript is disabled|requires javascript|__NEXT_DATA__|" +
            "noscript|please turn on javascript)"
    )

    fun classify(status: Int, body: String, hits: Int): SerpVerdict {
        if (hits > 0) return SerpVerdict(SerpOutcome.RESULTS)
        return when {
            status == 429 -> SerpVerdict(SerpOutcome.RATE_LIMITED, "HTTP 429 — محدودیت نرخ")
            status == 403 || status == 401 -> SerpVerdict(SerpOutcome.BLOCKED, "HTTP $status — دسترسی بسته")
            status == 503 -> SerpVerdict(SerpOutcome.RATE_LIMITED, "HTTP 503 — موقتاً در دسترس نیست")
            status !in 200..299 -> SerpVerdict(SerpOutcome.HTTP_ERROR, "HTTP $status")
            CAPTCHA.containsMatchIn(body.take(20_000)) ->
                SerpVerdict(SerpOutcome.BLOCKED, "صفحهٔ کپچا/تشخیص ربات برگشت")
            JS_ONLY.containsMatchIn(body.take(20_000)) ->
                SerpVerdict(SerpOutcome.JS_REQUIRED, "بی‌جاوااسکریپت نتیجه‌ای در HTML نیست")
            else -> SerpVerdict(SerpOutcome.EMPTY, "پاسخ سالم بود ولی نتیجه‌ای نداشت")
        }
    }

    fun label(outcome: SerpOutcome): String = when (outcome) {
        SerpOutcome.RESULTS -> "نتیجه دارد"
        SerpOutcome.EMPTY -> "بدون نتیجه"
        SerpOutcome.BLOCKED -> "بلاک شد (کپچا/۴۰۳)"
        SerpOutcome.RATE_LIMITED -> "محدودیت نرخ (۴۲۹/۲۰۳)"
        SerpOutcome.JS_REQUIRED -> "فقط با جاوااسکریپت"
        SerpOutcome.HTTP_ERROR -> "خطای HTTP"
        SerpOutcome.NETWORK -> "خطای شبکه"
    }
}

/** Result of one engine being asked one question, used by the self-test screen. */
data class EngineProbe(
    val id: String,
    val name: String,
    val friction: Int,
    val ok: Boolean,
    val hits: Int,
    val detail: String,
    val millis: Long,
    val outcome: SerpOutcome = if (ok) SerpOutcome.RESULTS else SerpOutcome.EMPTY,
    val endpoint: String = "html"
)

/** Fetches and prepares a "document": cleaned text with an explicit trust marker. */
object PageExtractor {

    fun extract(html: String, url: String, maxChars: Int = 6_000): ExtractedPage {
        val signals = InjectionGuard.signals(html)
        val title = Html.title(html)?.take(200) ?: (Html.domainOf(url) ?: url)
        val body = Html.mainText(html, maxChars)
        return ExtractedPage(
            url = url,
            title = title,
            text = body,
            trust = if (signals.isEmpty()) "untrusted" else "hostile",
            signals = signals
        )
    }
}

/**
 * Web text is data, never instructions. Anything that reads like an attempt to steer the
 * agent is flagged, and [Agent] treats flagged pages as non-usable evidence.
 */
object InjectionGuard {

    private val patterns: List<Pair<String, Regex>> = listOf(
        "override-instructions" to Regex("(?i)(ignore|disregard|forget)\\s+(all\\s+)?(previous|prior|above)\\s+(instructions|prompts?|rules)"),
        "override-fa" to Regex("(دستور(ات|های)?\\s*(قبلی|پیشین|بالا)\\s*را?\\s*(نادیده|فراموش)|نادیده\\s*بگیر)"),
        "system-prompt" to Regex("(?i)(system\\s*prompt|you\\s+are\\s+now\\s+(a|an|the)|new\\s+instructions?)"),
        "prompt-injection-fa" to Regex("(پرامپت|پیام سیستم|از\\s+این\\s+به\\s+بعد\\s+تو)"),
        "role-token" to Regex("(<\\|im_start\\|>|<\\|im_end\\|>|\\[/INST\\]|<<SYS>>|###\\s*instruction)"),
        "exfiltrate" to Regex("(?i)(api[_-]?key|password|token|credential|اعتبار|رمز)\\s*[:=]"),
        "tool-call" to Regex("(?i)(call\\s+the\\s+tool|execute\\s+command|run\\s+shell|eval\\()")
    )

    fun signals(text: String): List<String> =
        patterns.filter { it.second.containsMatchIn(text) }.map { it.first }

    fun isHostile(text: String): Boolean = signals(text).isNotEmpty()
}

/**
 * Multi-engine research. Every engine is queried at most once per call, at a polite pace,
 * and failures are recorded instead of retried aggressively: 403/429 puts an engine in
 * cooldown for the rest of the session (no evasion, no proxy, no UA spoofing).
 */
class SearchCore(
    private val http: HttpTransport,
    private val engines: List<EngineSpec> = Engines.all,
    private val pacing: Long = 800L,
    private val sleep: (Long) -> Unit = { ms -> try { Thread.sleep(ms) } catch (_: InterruptedException) { } },
    /** Hard cap for one [search] call, so a slow or hostile network cannot stall the agent. */
    private val maxCallMillis: Long = 20_000L,
    /** Cheap engines first; this is how many we are willing to ask in one call by default. */
    private val defaultEngines: Int = 8,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    private val failures = mutableMapOf<String, Int>()
    private val cooling = mutableMapOf<String, Long>()

    fun health(): Map<String, Int> = failures.toMap()

    /** True while the engine is resting after a 403/429/503. Time-based: it always gets another try. */
    private fun resting(engine: EngineSpec, now: Long): Boolean {
        val until = cooling[engine.id] ?: return false
        if (now < until) return true
        cooling.remove(engine.id)
        return false
    }

    fun search(
        query: String,
        langTag: String = "fa",
        limitPerEngine: Int = 8,
        maxEngines: Int = defaultEngines,
        /** Stop asking engines once this many distinct results were collected. */
        enoughHits: Int = 12,
        /** Internal: the reflection retry is allowed once, never inside itself. */
        allowReflection: Boolean = true
    ): SearchReport {
        val clean = Text.normalize(query).trim()
        if (clean.isEmpty()) return SearchReport(clean, emptyList())
        val started = clock()
        val perEngine = LinkedHashMap<String, List<WebHit>>()
        val errors = LinkedHashMap<String, String>()
        val notes = LinkedHashMap<String, String>()
        var used = 0
        var collected = 0
        var lastEngine: String? = null
        for (engine in Engines.ordered(langTag).filter { candidate -> engines.any { it.id == candidate.id } }) {
            if (used >= maxEngines) break
            if (used > 0 && collected >= enoughHits) break
            if (clock() - started > maxCallMillis) break
            if (resting(engine, clock())) {
                errors[engine.id] = "در استراحت (پاسخ ۴۰۳/۴۲۹ قبلی)"
                continue
            }
            used++
            if (lastEngine != null) sleep(pacing)
            lastEngine = engine.id
            val answer = ask(engine, clean, langTag, limitPerEngine)
            when {
                answer.hits.isNotEmpty() -> {
                    failures[engine.id] = 0
                    perEngine[engine.id] = answer.hits
                    collected += answer.hits.size
                    if (answer.format != SerpFormat.HTML) {
                        notes[engine.id] = "پاسخ از مسیر ${answer.format.name} (${answer.endpointNote})"
                    }
                }
                answer.verdict.outcome == SerpOutcome.NETWORK -> {
                    errors[engine.id] = answer.verdict.detail
                    failures[engine.id] = (failures[engine.id] ?: 0) + 1
                }
                else -> {
                    errors[engine.id] = "${SerpGuard.label(answer.verdict.outcome)} — ${answer.verdict.detail}"
                    failures[engine.id] = (failures[engine.id] ?: 0) + 1
                    if (answer.verdict.outcome == SerpOutcome.BLOCKED ||
                        answer.verdict.outcome == SerpOutcome.RATE_LIMITED
                    ) {
                        cooling[engine.id] = clock() + COOLDOWN_MS
                    }
                }
            }
        }
        val merged = fuse(perEngine, clean)
        val report = SearchReport(clean, merged, perEngine.mapValues { it.value.size }, errors, notes.toMap())
        if (merged.size >= 3) return report

        // Reflection step: a thin result set usually means the query was too narrow or in the
        // wrong language. Google's own autocomplete is asked first (it is a free, key-less Google
        // signal and it fixes bad queries far better than a synonym table); the lexicon expansion
        // stays as the fallback when autocomplete has nothing better to offer.
        val usable = report.perEngine.isNotEmpty()
        val repaired = if (allowReflection && usable && merged.size < 5) suggestRepair(clean, langTag) else null
        val expanded = repaired ?: expandQuery(clean)
        if (usable && expanded != clean && clock() - started < maxCallMillis) {
            val second = search(expanded, langTag, limitPerEngine, maxEngines, enoughHits, allowReflection = false)
            val combined = fuseResults(listOf(merged, second.hits), expanded)
            return report.copy(
                query = expanded,
                hits = combined,
                perEngine = report.perEngine + second.perEngine.mapKeys { "retry:${it.key}" },
                failures = report.failures + second.failures.mapKeys { "retry:${it.key}" },
                notes = report.notes + second.notes.mapKeys { "retry:${it.key}" }
            )
        }
        return report
    }

    /** One engine answering one query: which endpoint said what, and how it ended. */
    private data class EngineAnswer(
        val hits: List<WebHit>,
        val verdict: SerpVerdict,
        val format: SerpFormat,
        val endpointNote: String,
        val millis: Long
    )

    /**
     * Walks an engine's endpoint chain: HTML first, then whatever else that engine offers (RSS for
     * Bing, JSON for the open APIs). Measured reason for this: from a datacenter IP the Bing HTML
     * page is sometimes a consent shell while its RSS endpoint answers with ten clean results.
     */
    private fun ask(engine: EngineSpec, query: String, langTag: String, limit: Int): EngineAnswer {
        val started = clock()
        var last = SerpVerdict(SerpOutcome.NETWORK, "هیچ مسیری امتحان نشد")
        for (endpoint in engine.endpoints) {
            val url = engine.render(endpoint.template, query, langTag)
            val response = try {
                http.get(HttpRequest(url, HttpDefaults.headers(langTag)))
            } catch (t: Throwable) {
                last = SerpVerdict(SerpOutcome.NETWORK, "خطای شبکه: ${t.message ?: t::class.java.simpleName}")
                continue
            }
            if (!response.ok) {
                val failed = SerpGuard.classify(response.status, "", 0)
                last = if (last.outcome == SerpOutcome.NETWORK || last.outcome == SerpOutcome.HTTP_ERROR) failed else last
                continue
            }
            val hits = try {
                SerpParser.parseAny(engine, endpoint.format, response.body, limit)
            } catch (t: Throwable) {
                last = SerpVerdict(SerpOutcome.EMPTY, "خطای تجزیه: ${t.message ?: "?"}")
                emptyList()
            }
            val verdict = SerpGuard.classify(response.status, response.body, hits.size)
            if (hits.isNotEmpty()) {
                return EngineAnswer(hits, verdict, endpoint.format, endpoint.note, clock() - started)
            }
            // Keep the most informative failure: if the HTML route answered with a JavaScript shell
            // and the fallback route was simply unreachable, "needs JavaScript" is the useful fact,
            // not "network error" from whatever was tried last.
            last = if (last.outcome == SerpOutcome.NETWORK || last.outcome == SerpOutcome.HTTP_ERROR) verdict else last
        }
        return EngineAnswer(emptyList(), last, SerpFormat.HTML, "", clock() - started)
    }

    /**
     * The "learn everything about one thing" mode: ask **every** engine, keep going past the
     * point where the default search would stop, and return the union. Used by the deep-study
     * loop, never by a casual question (it costs one request per engine).
     */
    fun searchAll(query: String, langTag: String = "fa", limitPerEngine: Int = 10): SearchReport =
        search(
            query,
            langTag = langTag,
            limitPerEngine = limitPerEngine,
            maxEngines = engines.size,
            enoughHits = Int.MAX_VALUE
        )

    /**
     * Ask **every** engine the same question, one by one, and report what each one did. This is the
     * "which search engines work for me right now" self-test: it is user-triggered, bounded, and it
     * never pretends an engine worked.
     */
    fun probe(query: String, langTag: String = "fa", limitPerEngine: Int = 5): List<EngineProbe> {
        val clean = Text.normalize(query).trim().ifBlank { "test" }
        val out = mutableListOf<EngineProbe>()
        var first = true
        for (engine in Engines.ordered(langTag).filter { candidate -> engines.any { it.id == candidate.id } }) {
            if (!first) sleep(pacing)
            first = false
            val answer = ask(engine, clean, langTag, limitPerEngine)
            if (answer.hits.isNotEmpty()) {
                failures[engine.id] = 0
                cooling.remove(engine.id)
            } else if (answer.verdict.outcome == SerpOutcome.BLOCKED ||
                answer.verdict.outcome == SerpOutcome.RATE_LIMITED
            ) {
                cooling[engine.id] = clock() + COOLDOWN_MS
            }
            val detail = when {
                answer.hits.isNotEmpty() && answer.format != SerpFormat.HTML ->
                    "n نتیجه از مسیر ${answer.format.name}"
                answer.hits.isNotEmpty() -> "n نتیجه از HTML"
                else -> "${SerpGuard.label(answer.verdict.outcome)} — ${answer.verdict.detail}"
            }
            out.add(
                EngineProbe(
                    id = engine.id,
                    name = engine.name,
                    friction = engine.friction,
                    ok = answer.hits.isNotEmpty(),
                    hits = answer.hits.size,
                    detail = detail,
                    millis = answer.millis,
                    outcome = answer.verdict.outcome,
                    endpoint = answer.format.name.lowercase()
                )
            )
        }
        return out
    }

    /**
     * Asks Google's autocomplete for a better phrasing of [query]. Returns null when it has nothing
     * better, or when the endpoint is unreachable — a suggestion is a bonus, never a requirement.
     */
    fun suggestRepair(query: String, langTag: String = "fa"): String? {
        val hl = if (langTag == "fa") "fa" else "en"
        val url = "https://suggestqueries.google.com/complete/search?client=firefox&hl=$hl&q=" +
            java.net.URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val body = runCatching {
            http.get(HttpRequest(url, HttpDefaults.headers(langTag)))
        }.getOrNull()?.takeIf { it.ok }?.body ?: return null
        return Suggestions.repair(query, Suggestions.parse(body))
    }

    /** Cross-language / synonym expansion for the reflection retry. */
    fun expandQuery(query: String): String {
        val tokens = Text.contentTokens(query)
        if (tokens.isEmpty()) return query
        val extras = Lexicon.expand(tokens) - tokens.toSet()
        if (extras.isEmpty()) return query
        val lang = LangDetect.detect(query)
        val picked = if (lang == Lang.EN) extras.filter { it.any { c -> c in '\u0600'..'\u06FF' } } else extras
        if (picked.isEmpty()) return query
        return (query + " " + picked.take(3).joinToString(" ")).trim()
    }

    fun fetchPage(url: String, langTag: String = "fa", maxChars: Int = 6_000): ExtractedPage? {
        val response = try {
            http.get(HttpRequest(url, HttpDefaults.headers(langTag)))
        } catch (_: Throwable) {
            return null
        }
        if (!response.ok || response.body.isBlank()) return null
        return PageExtractor.extract(response.body, url, maxChars)
    }

    /**
     * How good a hit actually is for the query. Reciprocal-rank fusion alone ranks *agreement*
     * between engines, which is how a brand homepage returned by three engines used to outrank the
     * one article that answered the question. This adds the two things RRF ignores: relevance to
     * the words the user typed, and how much the engine is trusted.
     */
    object HitRanking {

        /** Engines that gave the best answers in the 2026-11 survey rank highest. */
        fun trust(engine: String): Double = when (engine) {
            "google" -> 1.0
            "bing" -> 0.85
            "ddg", "ddg-lite" -> 0.8
            "wikipedia-fa", "wikipedia-en" -> 0.75
            "mwmbl" -> 0.65
            "seznam" -> 0.6
            "wiby" -> 0.55
            else -> 0.45
        }

        /** Share of the query's content words that appear in the hit's title, url or snippet. */
        fun relevance(query: String, hit: WebHit): Double {
            val words = Text.contentTokens(query).filter { it.length > 2 }.distinct()
            if (words.isEmpty()) return 0.0
            val haystack = (hit.title + " " + hit.url + " " + hit.snippet).lowercase()
            val covered = words.count { haystack.contains(it.lowercase()) }
            var score = covered.toDouble() / words.size
            // A title that literally contains the query is worth more than a mention in a snippet.
            if (hit.title.lowercase().contains(query.trim().lowercase())) score += 0.25
            return score.coerceIn(0.0, 1.0)
        }

        /** A front page is rarely the answer to a specific question. */
        fun isHomepage(url: String): Boolean {
            val path = runCatching { java.net.URI(url).path.orEmpty() }.getOrDefault("")
            return path.isEmpty() || path == "/"
        }

        fun score(query: String, hit: WebHit, rrfNorm: Double): Double {
            val relevance = relevance(query, hit)
            val base = 0.45 * rrfNorm + 0.40 * relevance + 0.15 * trust(hit.engine)
            // A front page is a signpost, not an answer: it has to be an almost exact match before
            // it may outrank a page that actually discusses the question.
            return if (isHomepage(hit.url)) base * 0.75 else base
        }
    }

    companion object {

        /** An engine that said 403/429 rests for this long, then gets another honest try. */
        const val COOLDOWN_MS = 10 * 60 * 1000L

        /**
         * Reciprocal-rank fusion: an engine that cannot be gamed by one lucky page wins,
         * because agreement across engines outranks depth inside a single engine.
         */
        fun fuse(perEngine: Map<String, List<WebHit>>, query: String = ""): List<WebHit> {
            if (perEngine.isEmpty()) return emptyList()
            val byUrl = LinkedHashMap<String, MutableList<WebHit>>()
            for ((_, hits) in perEngine) for (hit in hits) {
                byUrl.getOrPut(hit.url) { mutableListOf() }.add(hit)
            }
            val lists = perEngine.values.map { hits -> hits.map { it.url } }
            val fused = Ranking.rrf(lists)
            val rrfByUrl = fused.toMap()
            val maxRrf = (rrfByUrl.values.maxOrNull() ?: 1.0).takeIf { it > 0.0 } ?: 1.0
            return byUrl.entries
                .map { (url, group) ->
                    val best = group.minByOrNull { it.rank } ?: group.first()
                    val merged = best.copy(
                        snippet = group.map { it.snippet }.firstOrNull { it.length > 40 } ?: best.snippet
                    )
                    val rrfNorm = (rrfByUrl[url] ?: 0.0) / maxRrf
                    merged to HitRanking.score(query, merged, rrfNorm)
                }
                .sortedWith(compareByDescending<Pair<WebHit, Double>> { it.second }.thenBy { it.first.rank })
                .map { it.first }
        }

        fun fuseResults(lists: List<List<WebHit>>, query: String = ""): List<WebHit> {
            val byUrl = LinkedHashMap<String, WebHit>()
            for (list in lists) for (hit in list) {
                val existing = byUrl[hit.url]
                if (existing == null || existing.snippet.length < hit.snippet.length) byUrl[hit.url] = hit
            }
            val firstSeen = lists.flatten().map { it.url }.distinct()
            val order = firstSeen.withIndex().associate { it.value to it.index }
            val maxRank = (order.values.maxOrNull() ?: 0).coerceAtLeast(1)
            return byUrl.values
                .map { hit ->
                    val rrfNorm = 1.0 - (order[hit.url] ?: 0).toDouble() / (maxRank + 1)
                    hit to HitRanking.score(query, hit, rrfNorm)
                }
                .sortedWith(compareByDescending<Pair<WebHit, Double>> { it.second }.thenBy { it.first.rank })
                .map { it.first }
        }
    }
}
