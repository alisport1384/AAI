package ir.aai.hushyar.domain

import ir.aai.hushyar.core.Lang
import ir.aai.hushyar.core.LangDetect
import ir.aai.hushyar.core.Similarity
import ir.aai.hushyar.core.Text

/**
 * «هر دانشی که خواسته شد: همهٔ موتورها را بگرد، یاد بگیر، دسته‌بندی و لایه‌بندی کن، و باز هم
 * دربارهٔ همان دانش بگرد تا تمام شود.»
 *
 * This is that loop, and it is deliberately *not* a search wrapper:
 *
 * ```text
 * topic
 *  ├─ plan facets            (model if installed, template otherwise)
 *  ├─ round 1: every engine, every facet          ─┐
 *  ├─ fetch pages, harvest sentences, layer them   │ repeat until
 *  ├─ round 2: new queries per gap + expansion     │  · no new fact in a whole round
 *  └─ …                                            ┘  · all facets covered
 *                                                     · budget spent
 * ```
 *
 * Everything it learns goes through the same knowledge store the rest of the app uses, so the
 * next question about the same topic is answered from this material — that is the "use it in
 * later questions" half of the requirement, and it is not new code: `knowledgeAnswerable`
 * already reads from there.
 */
data class StudyFacet(val layer: String, val question: String, val queries: List<String> = emptyList())

data class StudyBudget(
    val rounds: Int = 3,
    val facets: Int = 6,
    val pagesPerFacet: Int = 3,
    val minFactsPerFacet: Int = 1,
    val millis: Long = 120_000
) {
    companion object {
        fun byName(name: String): StudyBudget = when (name) {
            "quick" -> StudyBudget(rounds = 1, facets = 4, pagesPerFacet = 2, millis = 45_000)
            "deep" -> StudyBudget(rounds = 5, facets = 8, pagesPerFacet = 4, minFactsPerFacet = 2, millis = 240_000)
            else -> StudyBudget()
        }
    }
}

data class StudyRound(
    val index: Int,
    val queries: List<String>,
    val enginesAnswered: Int,
    val pages: Int,
    val learned: Int,
    val newFacts: Int,
    val stop: String
)

data class StudyOutcome(
    val topic: String,
    val facets: List<StudyFacet>,
    val rounds: List<StudyRound>,
    val learned: List<KnowledgeItem>,
    val coverage: Double,
    val gaps: List<String>,
    val stop: String,
    val engines: Map<String, Int> = emptyMap(),
    val failures: Map<String, String> = emptyMap(),
    val millis: Long = 0
) {
    val summary: String
        get() = buildString {
            append("موضوع «$topic»: ${learned.size} نکته در ${rounds.size} دور")
            append("، پوشش ${(coverage * 100).toInt()}%")
            if (gaps.isNotEmpty()) append("، بی‌نتیجه: ${gaps.joinToString("، ")}")
            append(". توقف: $stop")
        }
}

/**
 * When does a question mean "go and learn this, everywhere, then answer"? The user's rule was
 * explicit: a request for knowledge must search all engines and keep going. A plain question
 * ("قیمت X چند است") stays on the cheap path — turning every question into a 26-engine crawl
 * would burn the phone's data for no reason.
 */
object StudyTrigger {

    private val CUES = listOf(
        "یاد بگیر", "یادگیری", "همه چیز", "همه‌چیز", "همهٔ اطلاعات", "کامل تحقیق", "تحقیق کن",
        "بررسی کامل", "هر چه هست", "هرچه هست", "بیشتر بدان", "مطالعه کن", "کاوش کن",
        "learn about", "research", "everything about", "deep dive", "study"
    )

    private val PREFIX = Regex(
        """(?i)^(لطفا|لطفاً|please)?\s*(یاد بگیر|تحقیق کن|کاوش کن|مطالعه کن|یادگیری|study|learn about|research)\s*(دربارهٔ|درباره|در مورد|about|on)?\s*[:：]?\s*"""
    )

    private val EVERYTHING = Regex(
        """(?i)^(همه چیز|همه‌چیز|همهٔ اطلاعات|everything about)\s*(دربارهٔ|درباره|در مورد|about)?\s*"""
    )

    fun wantsStudy(question: String): Boolean {
        val q = question.trim()
        if (q.length < 4) return false
        return CUES.any { q.contains(it, ignoreCase = true) }
    }

    /** Strips the "learn about …" wrapper so the topic itself is what gets searched. */
    fun topicOf(question: String): String {
        var q = question.trim()
        q = PREFIX.replace(q, "")
        q = EVERYTHING.replace(q, "")
        q = q.trim().trim(':', '：', '؟', '?', '.', '،')
        return q.ifBlank { question.trim() }
    }
}

/** Facet planning without a model: one question per layer of the topic's structure. */
object StudyPlanner {

    fun plan(topic: String, langTag: String, brain: LocalBrain? = null): List<StudyFacet> {
        val clean = Text.normalize(topic).trim()
        if (clean.isEmpty()) return emptyList()
        val fa = langTag != "en"
        val base = LocalBrain.LAYERS.map { layer ->
            StudyFacet(layer, question(layer, clean, fa))
        }
        // The model may add facets the template cannot imagine ("compatibility", "warranty").
        val extra = brain?.facets(clean, langTag).orEmpty()
            .filter { suggestion -> base.none { it.question.contains(suggestion) || it.layer == suggestion } }
            .take(2)
            .map { StudyFacet(LocalBrain.LAYER_SPECS, if (fa) "$clean $it" else "$clean $it") }
        return (base + extra).distinctBy { it.question }
    }

    fun question(layer: String, topic: String, fa: Boolean): String = when (layer) {
        LocalBrain.LAYER_BASICS -> if (fa) "$topic چیست" else "what is $topic"
        LocalBrain.LAYER_SPECS -> if (fa) "$topic مشخصات فنی" else "$topic specifications"
        LocalBrain.LAYER_PRICE -> if (fa) "$topic قیمت" else "$topic price"
        LocalBrain.LAYER_REVIEWS -> if (fa) "$topic بررسی و نظرات" else "$topic review"
        LocalBrain.LAYER_UPDATES -> if (fa) "$topic جدیدترین" else "$topic latest"
        LocalBrain.LAYER_SOURCES -> if (fa) "$topic سایت رسمی" else "$topic official site"
        else -> if (fa) "$topic $layer" else "$topic $layer"
    }
}

/**
 * Harvests sentences worth storing from one page. Same rule as the rest of the app: a sentence is
 * only kept when it is *about* the topic and is long enough to carry information; numeric
 * statements additionally have to carry their unit, otherwise they are noise.
 */
object FactHarvester {

    private val UNIT = Regex(
        "(تومان|تومن|ریال|دلار|یورو|درهم|میلیون|میلیارد|هزار|ماه|سال|روز|ساعت|" +
            "مگاپیکسل|میلی‌آمپر|وات|ولت|گیگاهرتز|اینچ|گرم|کیلوگرم|لیتر|درصد|" +
            "mah|wh|watt|ghz|inch|gb|tb|mb|mm|cm|kg|hz|%|usd|eur)",
        RegexOption.IGNORE_CASE
    )

    fun harvest(topic: String, page: ExtractedPage, layer: String, limit: Int = 4): List<KnowledgeItem> {
        val sentences = Extractive.sentences(page.text)
        val topicWords = topic.split(Regex("\\s+")).filter { it.length > 2 }
        val out = ArrayList<KnowledgeItem>(limit)
        for (sentence in sentences) {
            if (out.size >= limit) break
            val clean = sentence.replace(Regex("\\s+"), " ").trim().trimEnd('.', '،', '؛', '!', '?')
            if (clean.length < 30 || clean.length > 320) continue
            val aboutTopic = topicWords.isEmpty().not() &&
                (topicWords.any { clean.contains(it, ignoreCase = true) } ||
                    Similarity.jaccard(Text.tokens(clean).toSet(), Text.tokens(topic).toSet()) >= 0.06)
            if (!aboutTopic) continue
            val hasNumber = clean.any { it.isDigit() || it in '۰'..'۹' }
            if (hasNumber && !UNIT.containsMatchIn(clean)) continue
            out.add(
                KnowledgeItem(
                    id = ir.aai.hushyar.core.Ids.hash("${page.url}#${clean.take(80)}"),
                    topic = topic,
                    question = StudyPlanner.question(layer, topic, LangDetect.detect(topic) != Lang.EN),
                    claim = clean.take(240),
                    category = "study",
                    attribute = layer,
                    url = page.url,
                    domain = ir.aai.hushyar.core.Html.domainOf(page.url).orEmpty(),
                    confidence = if (hasNumber) 0.62 else 0.55,
                    createdAt = 0L,
                    lastVerifiedAt = 0L,
                    sourceCount = 1,
                    layer = layer
                )
            )
        }
        return out
    }
}

/**
 * The loop. All side effects are injected, so the whole thing is testable without a network:
 * `search` is the all-engine search, `fetch` reads a page, `store` merges into knowledge, and
 * `existing` reads what is already known about the topic.
 */
class DeepStudy(
    private val search: (String, String) -> SearchReport,
    private val fetch: (String, String) -> ExtractedPage?,
    private val store: (KnowledgeItem) -> KnowledgeResult,
    private val existing: (String) -> List<KnowledgeItem> = { emptyList() },
    private val brain: LocalBrain? = null,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    fun study(topic: String, langTag: String = "fa", budget: StudyBudget = StudyBudget()): StudyOutcome {
        val clean = Text.normalize(topic).trim()
        val started = clock()
        if (clean.isEmpty()) {
            return StudyOutcome("", emptyList(), emptyList(), emptyList(), 0.0, emptyList(), "موضوع خالی بود")
        }
        val facts = LinkedHashMap<String, KnowledgeItem>() // claim → item
        existing(clean).forEach { facts[it.claim] = it }
        val covered = LinkedHashMap<String, Int>()        // layer → facts
        val engines = LinkedHashMap<String, Int>()
        val failures = LinkedHashMap<String, String>()
        val rounds = mutableListOf<StudyRound>()
        val facets = StudyPlanner.plan(clean, langTag, brain).take(budget.facets)
        var stop = "بودجهٔ دورها تمام شد"

        for (round in 1..budget.rounds) {
            val pending = facets.filter { (covered[it.layer] ?: 0) < budget.minFactsPerFacet }
                .ifEmpty { facets.take(2) } // everything covered: still ask the two thinnest facets
            val queries = pending.flatMap { facet -> queriesFor(facet) }.distinct()
            var learned = 0
            var fresh = 0
            var pages = 0
            val answered = LinkedHashSet<String>()

            for (query in queries) {
                if (clock() - started > budget.millis) {
                    stop = "بودجهٔ زمانی تمام شد"
                    break
                }
                val report = runCatching { search(query, langTag) }.getOrElse {
                    SearchReport(query, emptyList(), emptyMap(), mapOf("search" to (it.message ?: "?")))
                }
                report.perEngine.forEach { (engine, count) -> if (count > 0) engines[engine] = (engines[engine] ?: 0) + count }
                report.failures.forEach { (engine, why) -> failures.putIfAbsent(engine, why) }
                report.perEngine.filterValues { it > 0 }.keys.forEach { answered.add(it) }

                for (hit in diverse(report.hits, budget.pagesPerFacet)) {
                    if (pages >= budget.pagesPerFacet * pending.size) break
                    pages++
                    val page = runCatching { fetch(hit.url, langTag) }.getOrNull() ?: continue
                    val layer = layerFor(hit, page, langTag)
                    for (item in FactHarvester.harvest(clean, page, layer)) {
                        learned++
                        if (facts.containsKey(item.claim)) continue
                        val result = runCatching { store(item) }.getOrNull() ?: continue
                        facts[item.claim] = item
                        covered[layer] = (covered[layer] ?: 0) + 1
                        fresh++
                        if (result.decision == KnowledgeDecision.CONFLICT) {
                            failures["contradiction"] = "تناقض ثبت شد: ${item.claim.take(60)}"
                        }
                    }
                }
            }

            rounds.add(
                StudyRound(
                    index = round,
                    queries = queries,
                    enginesAnswered = answered.size,
                    pages = pages,
                    learned = learned,
                    newFacts = fresh,
                    stop = when {
                        fresh == 0 && round > 1 -> "دور تازه چیزی اضافه نکرد"
                        else -> "ادامه"
                    }
                )
            )
            if (fresh == 0 && round > 1) {
                stop = "اشباع: دو دور پشت‌سرهم نکتهٔ تازه‌ای نداشت"
                break
            }
            if (facets.all { (covered[it.layer] ?: 0) >= budget.minFactsPerFacet }) {
                stop = "همهٔ لایه‌ها پوشش داده شد"
                break
            }
        }

        val coverage = if (facets.isEmpty()) 0.0 else
            facets.count { (covered[it.layer] ?: 0) >= budget.minFactsPerFacet }.toDouble() / facets.size
        return StudyOutcome(
            topic = clean,
            facets = facets,
            rounds = rounds,
            learned = facts.values.toList(),
            coverage = coverage,
            gaps = facets.filter { (covered[it.layer] ?: 0) < budget.minFactsPerFacet }.map { it.layer },
            stop = stop,
            engines = engines,
            failures = failures,
            millis = clock() - started
        )
    }

    /** Round 2+ widens the search: the model's variants first, then the plain facet question. */
    private fun queriesFor(facet: StudyFacet): List<String> {
        val variants = if (brain != null && brain.ready) {
            brain.queries(facet.question, facet.layer, if (LangDetect.detect(facet.question) == Lang.EN) "en" else "fa")
        } else {
            emptyList()
        }
        return (listOf(facet.question) + variants).distinct().take(3)
    }

    /** Which layer a page belongs to: the model decides when it can, the facet question otherwise. */
    private fun layerFor(hit: WebHit, page: ExtractedPage, langTag: String): String {
        val byModel = brain?.layer("${hit.title}. ${page.text.take(200)}", langTag)
        if (byModel != null) return byModel
        val title = hit.title
        return when {
            LangDetect.detect(title + " " + page.text.take(400)) != Lang.EN && title.contains("قیمت") ->
                LocalBrain.LAYER_PRICE
            title.contains("قیمت") || title.contains("price", true) -> LocalBrain.LAYER_PRICE
            title.contains("بررسی") || title.contains("review", true) -> LocalBrain.LAYER_REVIEWS
            title.contains("مشخصات") || title.contains("spec", true) -> LocalBrain.LAYER_SPECS
            else -> LocalBrain.LAYER_BASICS
        }
    }

    /** One page per domain first: five results from one shop are not five sources. */
    private fun diverse(hits: List<WebHit>, limit: Int): List<WebHit> {
        val seen = mutableSetOf<String>()
        val first = mutableListOf<WebHit>()
        val rest = mutableListOf<WebHit>()
        for (hit in hits) {
            val domain = ir.aai.hushyar.core.Html.domainOf(hit.url).orEmpty()
            if (seen.add(domain)) first.add(hit) else rest.add(hit)
        }
        return (first + rest).take(limit * 2)
    }
}
