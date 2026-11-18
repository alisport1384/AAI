package ir.aai.hushyar.domain

import ir.aai.hushyar.core.Lang
import ir.aai.hushyar.core.NumWords
import ir.aai.hushyar.core.Similarity
import ir.aai.hushyar.core.Text

/**
 * One durable fact the assistant learned from a page. It keeps the sentence verbatim, the
 * URL it came from, when it was last verified and how many sources agreed — so a stored
 * claim is never more confident than its evidence.
 */
data class KnowledgeItem(
    val id: String,
    val topic: String,
    val question: String,
    val claim: String,
    val category: String,
    val attribute: String? = null,
    val numericValue: Double? = null,
    val unit: String? = null,
    val url: String = "",
    val domain: String = "",
    val confidence: Double = 0.5,
    val createdAt: Long = 0L,
    val lastVerifiedAt: Long = 0L,
    val ttlDays: Int = KnowledgeBank.DEFAULT_TTL_DAYS,
    val sourceCount: Int = 1,
    val supersededById: String? = null,
    /**
     * v0.8 — the layer of the topic's structure this fact belongs to (پایه/ویژگی‌ها/قیمت/…).
     * Empty for facts learned before v0.8 or by the simple question path.
     */
    val layer: String = "",
    val tags: List<String> = emptyList()
) {
    val fresh: Boolean get() = supersededById == null
}

enum class KnowledgeDecision {
    /** Nothing stored for this topic yet. */
    NEW,

    /** Same claim seen again: bump verification time and source count. */
    REFRESH,

    /** A numeric claim changed beyond tolerance: the new value wins, the old one is kept as history. */
    CORRECT,

    /** Two sources disagree on a non-numeric claim: keep both, mark the older as superseded. */
    CONFLICT,

    /** Lower-confidence duplicate of what we already know. */
    IGNORE
}

enum class GapKind { RELEVANCE, NUMERIC, MULTI_SOURCE, ATTRIBUTE, FRESHNESS }

data class Gap(val kind: GapKind, val label: String, val queryTerm: String)

/**
 * The knowledge store's brain: normalisation, categorisation, contradiction handling,
 * freshness and the coverage model that decides whether research is done or not.
 */
object KnowledgeBank {

    const val DEFAULT_TTL_DAYS = 45
    private const val NUMERIC_TOLERANCE = 0.10

    private val STOPWORDS = setOf(
        "چقدر", "چیست", "چیه", "چی", "چه", "است", "هست", "هستند", "را", "رو", "با", "از", "در",
        "به", "برای", "که", "و", "یا", "این", "آن", "هم", "ترین", "کن", "کنم", "بگو",
        "what", "is", "are", "the", "a", "an", "of", "for", "to", "in", "on", "and", "how",
        "much", "many", "does", "do", "tell", "me", "about", "please"
    )

    private val SCALES = mapOf(
        "هزار" to 1_000.0, "میلیون" to 1_000_000.0, "میلیارد" to 1_000_000_000.0,
        "thousand" to 1_000.0, "million" to 1_000_000.0, "billion" to 1_000_000_000.0
    )

    private val CURRENCIES = listOf(
        "تومان", "تومن", "ریال", "درصد", "٪", "%",
        "toman", "rial", "usd", "dollar", "euro", "gb", "mah", "watt"
    )

    private val NEGATION = Regex(
        "(?i)(ندارد|ندارند|نیست|نیستند|نمی|هیچ|بدون|هرگز|no |not |never|without|none)"
    )

    private val NUMBER_WITH_UNIT = Regex(
        "([0-9][0-9.,\\u066C\\u060C]*)\\s*(هزار|میلیون|میلیارد|thousand|million|billion)?\\s*" +
            "(تومان|تومن|ریال|درصد|٪|%|toman|rial|usd|dollar|euro|gb|mah|watt)?"
    )

    /** Stable topic key: the topical tokens of a question, so repeat questions hit the store. */
    fun topicKey(question: String): String {
        val tokens = Text.contentTokens(question).filter { it !in STOPWORDS && it.length > 1 }
        val base = if (tokens.isEmpty()) Text.tokens(question).filter { it !in STOPWORDS } else tokens
        return base.distinct().take(8).joinToString("-")
    }

    fun attributeOf(claim: String, pack: MarketPack?): String? {
        if (pack == null) return null
        val normalized = Text.normalize(claim)
        return pack.attributes.firstOrNull { attribute ->
            (listOf(attribute.label) + attribute.synonyms).any { word ->
                val key = Text.normalize(word)
                key.isNotBlank() && normalized.contains(key)
            }
        }?.id
    }

    /**
     * First number *with a unit* in a sentence, as (value, unit). The currency word wins over the
     * scale word, so "۵۰ میلیون تومان" is reported as 50,000,000 Toman and not "50 million".
     */
    fun numeric(claim: String): Pair<Double, String?>? {
        val normalized = Text.normalize(claim)
        for (match in NUMBER_WITH_UNIT.findAll(normalized)) {
            val scaleWord = match.groupValues[2]
            val currency = match.groupValues[3]
            if (scaleWord.isBlank() && currency.isBlank()) continue
            val base = match.groupValues[1]
                .replace("٬", "").replace("،", "").replace(",", "")
                .toDoubleOrNull() ?: continue
            val value = base * (SCALES[scaleWord] ?: 1.0)
            if (value <= 0) continue
            val unit = currency.ifBlank { scaleWord }
            return value to unit
        }
        return null
    }

    /** True when the claim denies something (used to detect contradicting sources). */
    fun isNegative(claim: String): Boolean = NEGATION.containsMatchIn(Text.normalize(claim))

    fun categorize(claim: String, pack: MarketPack?): String {
        val base = MemoryBank.classify(claim, pack)
        return when (base) {
            MemoryBank.CAT_GENERAL, MemoryBank.CAT_EXCHANGE -> MemoryBank.CAT_FACT
            else -> base
        }
    }

    fun tags(claim: String, pack: MarketPack?): List<String> = MemoryBank.tags(claim, pack)

    /**
     * v0.8 — a fact harvested from a page has no clock of its own. Without this stamp it went in
     * with timestamps of 0, [isStale] called it ancient the moment it was written, and the very
     * knowledge the user asked to learn was invisible to the next question.
     */
    fun stamp(item: KnowledgeItem, now: Long): KnowledgeItem = item.copy(
        createdAt = if (item.createdAt > 0) item.createdAt else now,
        lastVerifiedAt = if (item.lastVerifiedAt > 0) item.lastVerifiedAt else now
    )

    fun isStale(item: KnowledgeItem, now: Long, ttlDays: Int = item.ttlDays): Boolean {
        val reference = if (item.lastVerifiedAt > 0) item.lastVerifiedAt else item.createdAt
        if (reference <= 0) return true
        return now - reference > ttlDays.toLong() * 86_400_000L
    }

    /** A draft built from one evidence sentence; not yet merged with the store. */
    fun draft(
        question: String,
        evidence: Evidence,
        pack: MarketPack?,
        confidence: Double,
        now: Long
    ): KnowledgeItem {
        val claim = evidence.sentence
        val numeric = numeric(claim)
        return KnowledgeItem(
            id = "k-" + Similarity.fnv1a64(topicKey(question) + "|" + claim).toString(16),
            topic = topicKey(question),
            question = question.take(300),
            claim = claim.take(600),
            category = categorize(claim, pack),
            attribute = attributeOf(claim, pack),
            numericValue = numeric?.first,
            unit = numeric?.second,
            url = evidence.page.url,
            domain = draftHost(evidence.page.url),
            confidence = confidence,
            createdAt = now,
            lastVerifiedAt = now,
            tags = tags(claim, pack)
        )
    }

    /** How a new observation relates to what is already stored. */
    fun decide(existing: List<KnowledgeItem>, incoming: KnowledgeItem): KnowledgeDecision {
        val sameTopic = existing.filter { it.topic == incoming.topic && it.supersededById == null }
        if (sameTopic.isEmpty()) return KnowledgeDecision.NEW

        val comparable = sameTopic.filter { it.attribute == incoming.attribute }
        val incomingNumber = incoming.numericValue

        // 1) A number that moved is a correction even when the sentence looks identical.
        if (incomingNumber != null) {
            // A number about the same topic is comparable even when other attribute words differ:
            // "۵۰ میلیون تومان ... با گارانتی" still corrects "۳۰ میلیون تومان".
            val sameUnit = sameTopic.filter { it.numericValue != null && it.unit == incoming.unit }
            val close = sameUnit.firstOrNull { withinTolerance(it.numericValue!!, incomingNumber) }
            if (close != null) {
                return if (incoming.confidence + 0.02 < close.confidence) KnowledgeDecision.IGNORE else KnowledgeDecision.REFRESH
            }
            val clash = sameUnit.firstOrNull()
            if (clash != null) {
                return if (incoming.confidence >= clash.confidence - 0.05) KnowledgeDecision.CORRECT else KnowledgeDecision.IGNORE
            }
        }

        // 2) The same sentence again: refresh, do not duplicate.
        val sameClaim = sameTopic.firstOrNull { similarText(it.claim, incoming.claim) }
        if (sameClaim != null) {
            return if (incoming.confidence + 0.02 < sameClaim.confidence) KnowledgeDecision.IGNORE else KnowledgeDecision.REFRESH
        }

        // 3) One source asserts, the other denies, about the same subject: record the conflict.
        val polarity = comparable.firstOrNull {
            isNegative(it.claim) != isNegative(incoming.claim) &&
                Similarity.jaccard(Text.contentTokens(it.claim).toSet(), Text.contentTokens(incoming.claim).toSet()) >= 0.2
        }
        if (polarity != null) {
            return if (incoming.confidence >= polarity.confidence - 0.05) KnowledgeDecision.CONFLICT else KnowledgeDecision.IGNORE
        }

        // 4) Different, compatible claims about the same topic: keep both.
        return KnowledgeDecision.NEW
    }

    private fun withinTolerance(a: Double, b: Double): Boolean {
        val max = maxOf(kotlin.math.abs(a), kotlin.math.abs(b))
        if (max == 0.0) return true
        return kotlin.math.abs(a - b) / max <= NUMERIC_TOLERANCE
    }

    private fun similarText(a: String, b: String): Boolean =
        Similarity.hamming(Similarity.simhash(Text.contentTokens(a)), Similarity.simhash(Text.contentTokens(b))) <= 10

    data class Merge(
        val items: List<KnowledgeItem>,
        val decision: KnowledgeDecision,
        val touchedId: String?,
        val supersededId: String? = null
    )

    /**
     * Pure merge: returns the new store contents plus what happened. Calling code persists the
     * result, so the policy is testable without a database.
     */
    fun merge(existing: List<KnowledgeItem>, incoming: KnowledgeItem): Merge {
        val decision = decide(existing, incoming)
        return when (decision) {
            KnowledgeDecision.NEW -> Merge(existing + incoming, decision, incoming.id)
            KnowledgeDecision.IGNORE -> Merge(existing, decision, null)
            KnowledgeDecision.REFRESH -> {
                val target = existing.firstOrNull {
                    it.topic == incoming.topic && it.supersededById == null && it.numericValue != null &&
                        incoming.numericValue != null && it.unit == incoming.unit &&
                        withinTolerance(it.numericValue, incoming.numericValue)
                } ?: existing.first {
                    it.topic == incoming.topic && it.supersededById == null && similarText(it.claim, incoming.claim)
                }
                val updated = target.copy(
                    lastVerifiedAt = maxOf(target.lastVerifiedAt, incoming.lastVerifiedAt),
                    sourceCount = target.sourceCount + if (target.domain == incoming.domain) 0 else 1,
                    confidence = maxOf(target.confidence, incoming.confidence)
                )
                Merge(existing.map { if (it.id == target.id) updated else it }, decision, updated.id)
            }
            KnowledgeDecision.CORRECT -> {
                val old = existing.firstOrNull {
                    it.topic == incoming.topic && it.supersededById == null && it.numericValue != null &&
                        it.unit == incoming.unit && !withinTolerance(it.numericValue, incoming.numericValue ?: 0.0)
                }
                val fresh = incoming.copy(sourceCount = maxOf(incoming.sourceCount, (old?.sourceCount ?: 0) + 1))
                val items = existing.mapNotNull { item ->
                    when {
                        item.id == old?.id -> item.copy(supersededById = fresh.id)  // keep as history
                        else -> item
                    }
                }
                Merge(items + fresh, decision, fresh.id, old?.id)
            }
            KnowledgeDecision.CONFLICT -> {
                val older = existing.filter { it.topic == incoming.topic && it.supersededById == null }
                    .minByOrNull { it.lastVerifiedAt }
                val fresh = incoming.copy(confidence = (incoming.confidence * 0.9).coerceAtLeast(0.1))
                val items = existing.map { item ->
                    if (item.id == older?.id) item.copy(supersededById = fresh.id) else item
                }
                Merge(items + fresh, decision, fresh.id, older?.id)
            }
        }
    }

    /** Fresh, relevant items for a question, best first. */
    /**
     * v0.11.1 — «قیمت» موضوع نیست.
     *
     * باگ گزارش‌شده: پرسش «قیمت تی‌شرت» و بعد «قیمت شلوار». هر دو با «قیمت» شروع می‌شوند، واژهٔ
     * مشترک «قیمت» امتیاز ارتباط را بالا می‌برد و برنامه پرسش دوم را «همان پرسش» می‌دید: بدون
     * جستجو، از دانش پرسش اول جواب می‌داد. راه‌حل: واژه‌های پرسشی (قیمت، نرخ، وزن…) از «موضوع»
     * جدا می‌شوند و اگر دانش ذخیره‌شده **هیچ‌یک** از واژه‌های موضوع را نداشته باشد، ربط صفر است.
     */
    val ASK_WORDS: Set<String> = setOf(
        "قیمت", "قیمتی", "قیمتش", "نرخ", "بها", "چقدر", "چند", "چنده", "چندین",
        "وزن", "وزنش", "سایز", "اندازه", "موجودی", "جنس", "جنسش", "مدل",
        "چیه", "چیست", "چی", "هست", "است", "تومان", "تومن", "ریال", "درصد",
        "price", "cost", "costs", "weight", "size", "stock", "how", "much", "many", "what"
    )

    /** واژه‌های موضوع: آنچه پرسش *دربارهٔ* آن است، بدون واژه‌های پرسشی. */
    fun subjectTokensOf(question: String): Set<String> =
        Text.contentTokens(question).filter { it !in ASK_WORDS }.toSet()

    /**
     * آیا این دانش اصلاً دربارهٔ موضوعِ پرسش است؟ اشتراک تنها در واژه‌های پرسشی، اشتراک نیست.
     * مقایسه با پیشوند برای واژه‌های بلند («شلوار»/«شلوارک»)، مثل بقیهٔ بخش‌های برنامه.
     */
    fun mentionsSubject(question: String, item: KnowledgeItem): Boolean {
        val subject = subjectTokensOf(question)
        if (subject.isEmpty()) return true
        val hay = Text.contentTokens("${item.topic} ${item.claim} ${item.question}").toSet()
        return subject.any { token -> hay.any { word -> sameWord(token, word) } }
    }

    /** پسوندی/پیشوندی برای واژه‌های چهارحرفی و بلندتر؛ کوتاه‌ترها باید دقیق برابر باشند. */
    fun sameWord(a: String, b: String): Boolean =
        a == b || (a.length >= 4 && b.length >= 4 && (a.startsWith(b) || b.startsWith(a)))

    fun answerable(question: String, items: List<KnowledgeItem>, now: Long, limit: Int = 4): List<KnowledgeItem> {
        val key = topicKey(question)
        val queryTokens = Text.contentTokens(question).toSet()
        return items.filter { it.fresh && !isStale(it, now) && mentionsSubject(question, it) }
            // A price stored from a user-given URL must match the product *and its size*, not
            // merely share the word «نبشی». «نبشی۵ | ضخامت ۴» cannot answer «نبشی۴».
            .filter { item ->
                item.category != UrlIngest.SOURCE_PRICE_CATEGORY ||
                    UrlIngest.matches(
                        item.claim, UrlIngest.keywords(question), UrlIngest.sizes(question),
                        UrlIngest.qualifiers(question)
                    )
            }
            .map { item ->
                val topicMatch = if (item.topic == key) 1.0 else Similarity.containment(Text.contentTokens(item.topic).toSet(), queryTokens) * 0.8
                val claimRelevance = Similarity.containment(queryTokens, Text.contentTokens(item.claim).toSet())
                val freshness = 1.0 - (now - item.lastVerifiedAt).toDouble() / (item.ttlDays.toLong() * 86_400_000L)
                val score = 0.45 * topicMatch + 0.30 * claimRelevance + 0.15 * freshness.coerceIn(0.0, 1.0) + 0.10 * item.confidence
                item to score
            }
            .filter { it.second > 0.18 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    /**
     * Coverage model that drives the research loop. Four slots, explicit weights:
     * relevance 0.45 · numeric (only when the question needs it) 0.20 ·
     * source diversity 0.20 · pack attributes mentioned by the question 0.15.
     */
    fun coverage(question: String, items: List<KnowledgeItem>, now: Long, pack: MarketPack?): Double {
        if (items.isEmpty()) return 0.0
        // دانشی که موضوع پرسش را نام نمی‌برد، «پوشش» نیست؛ وگرنه واژهٔ مشترک «قیمت» پرسش دوم را
        // بی‌جستجو جواب می‌داد.
        val relevant = items.filter { mentionsSubject(question, it) }
        if (relevant.isEmpty()) return 0.0
        val queryTokens = Text.contentTokens(question).toSet()
        val relevance = relevant.maxOf { Similarity.containment(queryTokens, Text.contentTokens(it.claim).toSet()) }
            .coerceIn(0.0, 1.0)
        val needsNumber = needsNumeric(question, pack)
        val numericSlot = if (!needsNumber) 1.0 else if (relevant.any { it.numericValue != null }) 1.0 else 0.0
        val domains = relevant.map { it.domain }.filter { it.isNotBlank() }.distinct().size
        val diversity = (domains / 2.0).coerceAtMost(1.0)
        val mentioned = mentionedAttributes(question, pack)
        val attributeSlot = if (mentioned.isEmpty()) 1.0 else mentioned.count { id ->
            relevant.any { it.attribute == id }
        }.toDouble() / mentioned.size
        return 0.45 * relevance + 0.20 * numericSlot + 0.20 * diversity + 0.15 * attributeSlot
    }

    fun gaps(question: String, items: List<KnowledgeItem>, now: Long, pack: MarketPack?): List<Gap> {
        val out = mutableListOf<Gap>()
        val queryTokens = Text.contentTokens(question).toSet()
        val relevance = if (items.isEmpty()) 0.0 else items.maxOf {
            Similarity.containment(queryTokens, Text.contentTokens(it.claim).toSet())
        }
        if (relevance < 0.45) {
            out.add(Gap(GapKind.RELEVANCE, "ارتباط با پرسش", fillers(question, pack).firstOrNull() ?: "بررسی کامل"))
        }
        if (needsNumeric(question, pack) && items.none { it.numericValue != null }) {
            out.add(Gap(GapKind.NUMERIC, "عدد و قیمت", if (looksEnglish(question)) "price" else "قیمت تومان"))
        }
        if (items.map { it.domain }.filter { it.isNotBlank() }.distinct().size < 2) {
            out.add(Gap(GapKind.MULTI_SOURCE, "منبع دوم", if (looksEnglish(question)) "review comparison" else "بررسی مقایسه"))
        }
        for (id in mentionedAttributes(question, pack)) {
            if (items.none { it.attribute == id }) {
                val label = pack?.attributes?.firstOrNull { it.id == id }?.label ?: id
                out.add(Gap(GapKind.ATTRIBUTE, label, label))
            }
        }
        if (items.isNotEmpty() && items.all { isStale(it, now) }) {
            out.add(Gap(GapKind.FRESHNESS, "تازه‌سازی", if (looksEnglish(question)) "latest" else "امسال"))
        }
        return out
    }

    fun staleItems(items: List<KnowledgeItem>, now: Long): List<KnowledgeItem> =
        items.filter { it.fresh && isStale(it, now) }

    fun needsNumeric(question: String, pack: MarketPack?): Boolean {
        val q = Text.normalize(question)
        val numericWords = listOf(
            "قیمت", "چقدر", "هزینه", "نرخ", "درصد", "چند", "تومان", "بودجه", "ارزان", "گران",
            "price", "cost", "how much", "rate", "percent", "budget"
        )
        if (numericWords.any { q.contains(Text.normalize(it)) }) return true
        return mentionedAttributes(question, pack).any { id ->
            pack?.attributes?.firstOrNull { it.id == id }?.type == AttrType.NUMBER
        }
    }

    fun mentionedAttributes(question: String, pack: MarketPack?): List<String> {
        if (pack == null) return emptyList()
        val q = Text.normalize(question)
        return pack.attributes.filter { attribute ->
            (listOf(attribute.label) + attribute.synonyms).any { word ->
                val key = Text.normalize(word)
                key.length > 1 && q.contains(key)
            }
        }.map { it.id }
    }

    /** Extra search terms derived from the market pack (used when a query is too narrow). */
    fun fillers(question: String, pack: MarketPack?): List<String> {
        if (pack == null) return emptyList()
        val mentioned = mentionedAttributes(question, pack).toSet()
        return pack.attributes.filter { it.id !in mentioned }
            .sortedByDescending { it.weight }
            .take(3)
            .map { it.label }
    }

    fun summary(items: List<KnowledgeItem>, now: Long, lang: Lang = Lang.FA): String {
        val fresh = items.count { it.fresh }
        val stale = staleItems(items, now).size
        val domains = items.map { it.domain }.filter { it.isNotBlank() }.distinct().size
        return if (lang == Lang.EN) {
            "$fresh knowledge items from $domains domains, $stale need refresh."
        } else {
            "${Text.num(fresh.toLong())} دانسته از ${Text.num(domains.toLong())} دامنه؛ ${Text.num(stale.toLong())} مورد کهنه است."
        }
    }

    private fun looksEnglish(question: String): Boolean = ir.aai.hushyar.core.LangDetect.detect(question) == Lang.EN

    /** Host of a URL, used for source diversity and for the listing's `source` column. */
    fun draftHost(url: String): String =
        runCatching { java.net.URI(url).host?.removePrefix("www.") ?: "" }.getOrDefault("")
}
