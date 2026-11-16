package ir.aai.hushyar.domain

import ir.aai.hushyar.core.Ids
import ir.aai.hushyar.core.Lexicon
import ir.aai.hushyar.core.PriceParser
import ir.aai.hushyar.core.Text

/** How much searching is allowed for one question. Bounded on purpose: battery and data are real. */
data class ResearchBudget(
    val maxRounds: Int,
    val pagesPerRound: Int,
    val maxPages: Int,
    val maxMillis: Long,
    val targetCoverage: Double,
    val maxEvidence: Int = 4
) {
    companion object {
        val QUICK = ResearchBudget(2, 2, 4, 25_000L, 0.60)
        val BALANCED = ResearchBudget(3, 3, 8, 55_000L, 0.72)
        val DEEP = ResearchBudget(5, 3, 14, 110_000L, 0.80)

        fun byName(name: String): ResearchBudget = when (name.lowercase()) {
            "quick", "سریع" -> QUICK
            "deep", "عمیق" -> DEEP
            else -> BALANCED
        }
    }
}

enum class ResearchStop { SUFFICIENT, NO_NEW_EVIDENCE, ROUNDS, PAGES, TIME, NO_RESULTS }

data class ResearchRound(
    val index: Int,
    val query: String,
    val hits: Int,
    val fetched: Int,
    val evidence: Int,
    val coverage: Double,
    val note: String
)

data class ResearchOutcome(
    val question: String,
    val queries: List<String>,
    val rounds: List<ResearchRound>,
    val evidence: List<Evidence>,
    val pages: List<ExtractedPage>,
    val drafts: List<KnowledgeItem>,
    val coverage: Double,
    val stop: ResearchStop,
    val failures: Map<String, String> = emptyMap(),
    /** Pages intentionally not used as evidence, with the reason (hostile, short, unreachable). */
    val skipped: List<Pair<String, String>> = emptyList(),
    /** How the question was read, and whether an actual answer (not just topical text) was found. */
    val model: QuestionModel? = null,
    val answer: AnswerPlan? = null
)

/**
 * The autonomous research loop. It keeps searching while the coverage model says something is
 * missing — a number, a second independent source, a market attribute the question asked about,
 * or freshness — and stops the moment the evidence is good enough (or the budget is spent).
 *
 * Every round is recorded, so the user can see *why* it searched again.
 */
private const val MIN_USEFUL_CHARS = 120

class DeepResearcher(
    private val search: (String, String) -> SearchReport,
    private val fetch: (String, String) -> ExtractedPage?,
    private val budget: ResearchBudget = ResearchBudget.BALANCED,
    private val now: () -> Long = { System.currentTimeMillis() }
) {

    fun research(
        question: String,
        langTag: String = "fa",
        pack: MarketPack? = null,
        known: List<KnowledgeItem> = emptyList()
    ): ResearchOutcome {
        val started = now()
        // If what we already know is good enough, do not spend a single request.
        val knownCoverage = if (known.isEmpty()) 0.0 else KnowledgeBank.coverage(question, known, now(), pack)
        if (knownCoverage >= budget.targetCoverage) {
            return ResearchOutcome(
                question = question,
                queries = emptyList(),
                rounds = emptyList(),
                evidence = emptyList(),
                pages = emptyList(),
                drafts = emptyList(),
                coverage = knownCoverage,
                stop = ResearchStop.SUFFICIENT
            )
        }
        val model = QuestionReader.read(question, langTag)
        val queries = LinkedHashSet<String>()
        val pages = mutableListOf<ExtractedPage>()
        val seen = mutableSetOf<String>()
        val failures = LinkedHashMap<String, String>()
        val skipped = mutableListOf<Pair<String, String>>()
        val rounds = mutableListOf<ResearchRound>()
        var evidence: List<Evidence> = emptyList()
        var drafts: List<KnowledgeItem> = emptyList()
        var coverage = 0.0
        var plan: AnswerPlan? = null
        var stop = ResearchStop.ROUNDS
        var round = 0

        while (round < budget.maxRounds) {
            if (now() - started > budget.maxMillis) {
                stop = ResearchStop.TIME
                break
            }
            val query = nextQuery(question, langTag, pack, known + drafts, queries, round)
            if (query == null) {
                stop = ResearchStop.NO_NEW_EVIDENCE
                break
            }
            queries.add(query)
            val report = runCatching { search(query, langTag) }.getOrElse { error ->
                SearchReport(query, emptyList(), emptyMap(), mapOf("transport" to (error.message ?: "?")))
            }
            report.failures.forEach { (engine, reason) -> failures.putIfAbsent(engine, reason) }

            val fresh = report.hits.filter { it.url !in seen }.take(budget.pagesPerRound)
            var fetched = 0
            for (hit in fresh) {
                if (pages.size >= budget.maxPages) break
                if (!seen.add(hit.url)) continue   // the same URL twice in one SERP is not two sources
                val page = runCatching { fetch(hit.url, langTag) }.getOrNull()
                if (page == null) {
                    skipped.add(hit.url to "واکشی ناموفق یا خالی")
                    continue
                }
                if (page.signals.isNotEmpty()) {
                    // Web text is data, never instructions: hostile pages never become evidence.
                    skipped.add(hit.url to "کنار گذاشته شد: نشانهٔ تزریق دستور (${page.signals.joinToString(",")})")
                    continue
                }
                // Thin pages are usually junk, but a short page that states the exact answer
                // (a price listing, a one-line spec sheet) is not junk — it is the answer.
                if (page.text.length < MIN_USEFUL_CHARS &&
                    AnswerShapes.slot(model.ask, page.text, pack) == null
                ) {
                    skipped.add(hit.url to "متن کافی نداشت (${page.text.length} نویسه)")
                    continue
                }
                pages.add(page)
                fetched++
            }

            // Answer-focused selection: only sentences that name the subject survive, and the
            // question's expected answer shape decides whether the question is actually answered.
            val ranked = AnswerFocus.rank(model, pages, pack, limit = maxOf(budget.maxEvidence, 5))
            evidence = ranked.take(budget.maxEvidence).map { it.evidence }
            val currentPlan = AnswerFocus.plan(model, ranked, pages)
            plan = currentPlan
            drafts = evidence.map { item ->
                KnowledgeBank.draft(question, item, pack, confidenceFor(item, pages), now())
            }
            coverage = KnowledgeBank.coverage(question, known + drafts, now(), pack)
            rounds.add(
                ResearchRound(
                    index = round + 1,
                    query = query,
                    hits = report.hits.size,
                    fetched = fetched,
                    evidence = evidence.size,
                    coverage = coverage,
                    note = noteFor(round, report.hits.size, fetched, evidence.size, coverage, currentPlan.answered)
                )
            )

            if (pages.size >= budget.maxPages) {
                stop = ResearchStop.PAGES
                break
            }
            // "Enough" now has two parts: the coverage model is satisfied *and* the question's
            // answer shape was actually found (a numeric question is never done on topical text).
            if (coverage >= budget.targetCoverage && (currentPlan.answered || !model.numeric)) {
                stop = ResearchStop.SUFFICIENT
                break
            }
            if (fetched == 0 && round > 0) {
                stop = ResearchStop.NO_NEW_EVIDENCE
                break
            }
            round++
        }

        if (rounds.isEmpty()) {
            stop = ResearchStop.NO_RESULTS
            rounds.add(ResearchRound(1, question, 0, 0, 0, 0.0, "هیچ موتوری پاسخ نداد"))
        }
        return ResearchOutcome(
            question = question,
            queries = queries.toList(),
            rounds = rounds,
            evidence = evidence,
            pages = pages,
            drafts = drafts,
            coverage = coverage,
            stop = stop,
            failures = failures,
            skipped = skipped.toList(),
            model = model,
            answer = plan
        )
    }

    /**
     * Gap-driven reformulation: each round targets the weakest slot instead of re-asking the
     * same thing. Null means "no untried query left" → the loop must stop.
     */
    fun nextQuery(
        question: String,
        langTag: String,
        pack: MarketPack?,
        gathered: List<KnowledgeItem>,
        used: Set<String>,
        round: Int
    ): String? {
        val base = Text.normalize(question).trim().trimEnd('?', '؟', '.')
        if (round == 0) return base.ifBlank { question.trim() }

        val model = QuestionReader.read(question, langTag)
        // Gap 0: the question's answer shape is missing. Chase it before anything else.
        val noAnswerShape = gathered.none { AnswerShapes.answers(model.ask, it.claim, pack) }
        if (noAnswerShape) {
            val candidate = (base + " " + QuestionReader.gapTerm(model.ask, langTag)).replace(Regex("\\s+"), " ").trim()
            if (candidate !in used && model.keepsAnchor(candidate)) return candidate
        }

        val gaps = KnowledgeBank.gaps(question, gathered, now(), pack)
        for (gap in gaps) {
            val candidate = (base + " " + gap.queryTerm).replace(Regex("\\s+"), " ").trim()
            // Drift guard: a rewrite that loses the subject is a different question, so it is skipped.
            if (gap.queryTerm.isNotBlank() && candidate !in used && model.keepsAnchor(candidate)) return candidate
        }
        // No structural gap left: widen with bilingual expansion and pack terms.
        val tokens = Text.contentTokens(base)
        val expanded = (Lexicon.expand(tokens) - tokens.toSet()).take(3)
        val extra = (expanded + KnowledgeBank.fillers(question, pack)).distinct()
        for (term in extra) {
            val candidate = "$base $term".replace(Regex("\\s+"), " ").trim()
            if (candidate !in used && model.keepsAnchor(candidate)) return candidate
        }
        if (langTag == "fa") {
            val english = "$base review price"
            if (english !in used) return english
        }
        return null
    }

    private fun confidenceFor(evidence: Evidence, pages: List<ExtractedPage>): Double {
        val sameHost = pages.count { KnowledgeBank.draftHost(it.url) == KnowledgeBank.draftHost(evidence.page.url) }
        val base = 0.42 + 0.12 * sameHost.coerceAtMost(3) + 0.25 * evidence.score.coerceIn(0.0, 1.0)
        return base.coerceIn(0.2, 0.88)
    }

    private fun noteFor(round: Int, hits: Int, fetched: Int, evidence: Int, coverage: Double, answered: Boolean): String = when {
        hits == 0 -> "بدون نتیجه"
        fetched == 0 -> "صفحه‌های تازه‌ای نبود"
        answered && round == 0 -> "دور اول پاسخ پیدا شد"
        else -> "hits=$hits fetched=$fetched evidence=$evidence coverage=${"%.2f".format(coverage)}" +
            (if (answered) " پاسخ=یافت شد" else " پاسخ=نیامد")
    }
}

/**
 * Turns web evidence into listings (the "آگهی" section), so the ads table fills itself from
 * real pages instead of manual typing. Only sentences with an explicit price and unit become
 * listings; everything else is skipped rather than guessed.
 */
object ListingHarvester {

    data class Harvest(val listings: List<Listing>, val skipped: Int)

    fun harvest(evidence: List<Evidence>, pack: MarketPack, now: Long, limit: Int = 4): Harvest {
        val out = mutableListOf<Listing>()
        var skipped = 0
        for (item in evidence) {
            if (out.size >= limit) break
            val price = priceOf(item.sentence, pack)
            if (price == null) {
                skipped++
                continue
            }
            val title = titleOf(item)
            if (title.length < 8) {
                skipped++
                continue
            }
            val base = Listing(
                id = "web-" + Ids.hash(title + "|" + price.raw),
                title = title,
                titleNorm = Text.normalize(title),
                priceRaw = price.raw,
                priceIrt = price.irt,
                priceNote = price.note,
                source = "وب:" + KnowledgeBank.draftHost(item.page.url),
                url = item.page.url,
                seller = null,
                city = null,
                attrs = emptyMap(),
                createdAt = now
            )
            val attrs = runCatching { AttributeExtractor.extract(base, pack) }.getOrDefault(emptyMap())
            out.add(base.copy(attrs = attrs))
        }
        return Harvest(out, skipped)
    }

    private data class Price(val raw: String, val irt: Long?, val note: String)

    private fun priceOf(sentence: String, pack: MarketPack): Price? {
        val match = Regex("([۰-۹\\d][۰-۹\\d.,،٬ ]{0,18})\\s*(تومان|ریال|میلیون تومان|میلیارد تومان)").find(sentence)
            ?: return null
        val raw = match.value.trim()
        val parsed = runCatching { PriceParser.parse(raw, pack.priceRules) }.getOrNull() ?: return null
        if (parsed.irt == null) return null
        return Price(raw, parsed.irt, parsed.note)
    }

    private fun titleOf(item: Evidence): String {
        val pageTitle = item.page.title.trim()
        val candidate = pageTitle.ifBlank { item.sentence }
        return Text.normalize(candidate).take(120)
    }
}
