package ir.aai.hushyar.domain

import ir.aai.hushyar.core.Html
import ir.aai.hushyar.core.HttpDefaults
import ir.aai.hushyar.core.HttpRequest
import ir.aai.hushyar.core.HttpTransport
import ir.aai.hushyar.core.Lang
import ir.aai.hushyar.core.LangDetect
import ir.aai.hushyar.core.Lexicon
import ir.aai.hushyar.core.Ranking
import ir.aai.hushyar.core.Text

/**
 * One engine adapter is nothing more than a URL template plus the hosts that belong to
 * the engine itself. Parsing is engine-agnostic and lives in [SerpParser]; when an engine
 * changes its markup the adapter survives and only the generic filter is adjusted.
 */
enum class EngineKind { GENERAL, WIKI, META, PERSIAN, SPECIALIST }

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
    val note: String = ""
) {
    fun url(query: String, langTag: String): String {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val hl = if (langTag == "fa") "fa" else "en"
        return template.replace("{q}", encoded).replace("{hl}", hl)
    }
}

/**
 * The engine catalogue: as many public, key-less, account-less HTML endpoints as we can honestly
 * track. Nothing here needs an API key, and nothing here bypasses a block: when an engine answers
 * with 403/429 we simply stop asking it for a while.
 */
object Engines {

    val all: List<EngineSpec> = listOf(
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
            listOf("mojeek.com"), friction = 1, note = "ایندکس مستقل، HTML ساده"
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
            listOf("bing.com", "microsoft.com", "msn.com"), friction = 2
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
            "google", "Google", "https://www.google.com/search?num=10&hl={hl}&q={q}",
            listOf("google.com", "google.co", "gstatic.com", "googleusercontent.com"),
            friction = 3, needsJs = true, note = "اغلب صفحهٔ رضایت/کپچا می‌دهد"
        ),
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
            listOf("qwant.com"), friction = 3, needsJs = true
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

    fun parse(engine: EngineSpec, html: String, limit: Int = 8): List<WebHit> {
        val out = LinkedHashMap<String, WebHit>()
        for (link in Html.links(html, engine.url("x", "fa"), limit = 120)) {
            if (out.size >= limit) break
            if (!accept(engine, link.url, link.text)) continue
            val canonical = canonical(link.url)
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

/** Result of one engine being asked one question, used by the self-test screen. */
data class EngineProbe(
    val id: String,
    val name: String,
    val friction: Int,
    val ok: Boolean,
    val hits: Int,
    val detail: String,
    val millis: Long
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
        enoughHits: Int = 12
    ): SearchReport {
        val clean = Text.normalize(query).trim()
        if (clean.isEmpty()) return SearchReport(clean, emptyList())
        val started = clock()
        val perEngine = LinkedHashMap<String, List<WebHit>>()
        val errors = LinkedHashMap<String, String>()
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
            val response = try {
                http.get(HttpRequest(engine.url(clean, langTag), HttpDefaults.headers(langTag)))
            } catch (t: Throwable) {
                errors[engine.id] = "خطای شبکه: ${t.message ?: t::class.java.simpleName}"
                failures[engine.id] = (failures[engine.id] ?: 0) + 1
                continue
            }
            if (!response.ok) {
                errors[engine.id] = "HTTP ${response.status}"
                failures[engine.id] = (failures[engine.id] ?: 0) + 1
                if (response.status == 403 || response.status == 429 || response.status == 503) {
                    cooling[engine.id] = clock() + COOLDOWN_MS
                }
                continue
            }
            val hits = try {
                SerpParser.parse(engine, response.body, limitPerEngine)
            } catch (t: Throwable) {
                errors[engine.id] = "خطای تجزیه: ${t.message ?: "?"}"
                emptyList()
            }
            if (hits.isEmpty()) {
                errors[engine.id] = errors[engine.id] ?: "بدون نتیجهٔ قابل تجزیه"
                failures[engine.id] = (failures[engine.id] ?: 0) + 1
            } else {
                failures[engine.id] = 0
                perEngine[engine.id] = hits
                collected += hits.size
            }
        }
        val merged = fuse(perEngine)
        val report = SearchReport(clean, merged, perEngine.mapValues { it.value.size }, errors)
        if (merged.size >= 3) return report

        // Reflection step: a thin result set usually means the query was too narrow or in the
        // wrong language. Retry once with bilingual expansion — but never recurse further, and
        // never when every engine failed (that is a network problem, not a query problem).
        val usable = report.perEngine.isNotEmpty()
        val expanded = expandQuery(clean)
        if (usable && expanded != clean && clock() - started < maxCallMillis) {
            val second = search(expanded, langTag, limitPerEngine, maxEngines, enoughHits)
            val combined = fuseResults(listOf(merged, second.hits))
            return report.copy(
                query = expanded,
                hits = combined,
                perEngine = report.perEngine + second.perEngine.mapKeys { "retry:${it.key}" },
                failures = report.failures + second.failures.mapKeys { "retry:${it.key}" }
            )
        }
        return report
    }

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
            val started = clock()
            val response = try {
                http.get(HttpRequest(engine.url(clean, langTag), HttpDefaults.headers(langTag)))
            } catch (t: Throwable) {
                out.add(
                    EngineProbe(
                        engine.id, engine.name, engine.friction, false, 0,
                        t.message ?: t::class.java.simpleName, clock() - started
                    )
                )
                continue
            }
            if (!response.ok) {
                out.add(EngineProbe(engine.id, engine.name, engine.friction, false, 0, "HTTP ${response.status}", clock() - started))
                continue
            }
            val hits = runCatching { SerpParser.parse(engine, response.body, limitPerEngine) }.getOrDefault(emptyList())
            out.add(
                EngineProbe(
                    engine.id, engine.name, engine.friction, hits.isNotEmpty(), hits.size,
                    if (hits.isEmpty()) "پاسخ آمد ولی نتیجهٔ قابل تجزیه نداشت" else "ok",
                    clock() - started
                )
            )
            if (hits.isNotEmpty()) {
                failures[engine.id] = 0
                cooling.remove(engine.id)
            }
        }
        return out
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

    companion object {

        /** An engine that said 403/429 rests for this long, then gets another honest try. */
        const val COOLDOWN_MS = 10 * 60 * 1000L

        /**
         * Reciprocal-rank fusion: an engine that cannot be gamed by one lucky page wins,
         * because agreement across engines outranks depth inside a single engine.
         */
        fun fuse(perEngine: Map<String, List<WebHit>>): List<WebHit> {
            if (perEngine.isEmpty()) return emptyList()
            val byUrl = LinkedHashMap<String, MutableList<WebHit>>()
            for ((_, hits) in perEngine) for (hit in hits) {
                byUrl.getOrPut(hit.url) { mutableListOf() }.add(hit)
            }
            val lists = perEngine.values.map { hits -> hits.map { it.url } }
            val fused = Ranking.rrf(lists)
            val order = fused.map { it.first }.filter { byUrl.containsKey(it) }
            val leftovers = byUrl.keys.filter { it !in order }
            return (order + leftovers).map { url ->
                val group = byUrl.getValue(url)
                val best = group.minByOrNull { it.rank } ?: group.first()
                best.copy(snippet = group.map { it.snippet }.firstOrNull { it.length > 40 } ?: best.snippet)
            }
        }

        fun fuseResults(lists: List<List<WebHit>>): List<WebHit> {
            val byUrl = LinkedHashMap<String, WebHit>()
            for (list in lists) for (hit in list) {
                val existing = byUrl[hit.url]
                if (existing == null || existing.snippet.length < hit.snippet.length) byUrl[hit.url] = hit
            }
            val order = lists.flatten().map { it.url }.distinct()
            return order.mapNotNull { byUrl[it] }
        }
    }
}
