package ir.aai.hushyar.domain

import ir.aai.hushyar.core.Lang
import ir.aai.hushyar.core.LangDetect
import ir.aai.hushyar.core.Lexicon
import ir.aai.hushyar.core.Ranking
import ir.aai.hushyar.core.Similarity
import ir.aai.hushyar.core.Text

/**
 * The memory subsystem: classification, de-duplication, consolidation and ranking.
 * Everything is local, deterministic and inspectable — the user can read, edit and delete
 * every row, which is what makes "learning" trustworthy instead of magical.
 */
object MemoryBank {

    const val CAT_PREFERENCE = "ترجیح"
    const val CAT_BUDGET = "بودجه"
    const val CAT_PRODUCT = "کالا"
    const val CAT_SELLER = "فروشنده"
    const val CAT_PLACE = "مکان"
    const val CAT_RULE = "قاعده"
    const val CAT_GOAL = "هدف"
    const val CAT_LESSON = "درس"
    const val CAT_EXCHANGE = "گفتگو"
    const val CAT_FACT = "دانسته"
    const val CAT_GENERAL = "عمومی"

    val categories = listOf(
        CAT_PREFERENCE, CAT_BUDGET, CAT_PRODUCT, CAT_SELLER, CAT_PLACE,
        CAT_RULE, CAT_GOAL, CAT_LESSON, CAT_EXCHANGE, CAT_FACT, CAT_GENERAL
    )

    private val rules: List<Pair<String, Regex>> = listOf(
        CAT_BUDGET to Regex("(?i)(بودجه|سقف|حداکثر|تا ?\\d|بیشتر از ?\\d|تومان|میلیون|budget|max|under|cheap|ارزان)"),
        CAT_PREFERENCE to Regex("(?i)(دوست دارم|ترجیح|بهتره|بهتر است|ترجیح میدم|prefer|favorite|like|dislike|بدم میاد)"),
        CAT_PRODUCT to Regex("(?i)(گوشی|لپ ?تاپ|هدفون|کالا|محصول|مدل|برند|phone|laptop|model|brand|product)"),
        CAT_SELLER to Regex("(?i)(فروشنده|فروشگاه|مغازه|دیجی|seller|vendor|shop|store)"),
        CAT_PLACE to Regex("(?i)(شهر|تهران|کرج|مشهد|اصفهان|شیراز|تبریز|شهرستان|ارسال به|city|location)"),
        CAT_RULE to Regex("(?i)(قاعده|قانون|همیشه|هرگز|نباید|باید|از این به بعد|بدون|rule|always|never|must)"),
        CAT_GOAL to Regex("(?i)(هدف|می‌خوام|میخوام|قصد|برنامه|خرید|goal|plan|want to)"),
        CAT_LESSON to Regex("(?i)(یاد گرفتم|درس|نتیجه|اشتباه|lesson|learned)"),
        CAT_FACT to Regex("(?i)(بر اساس|منبع|source|according|استناد)")
    )

    fun classify(text: String, pack: MarketPack? = null): String {
        val normalized = Text.normalize(text)
        if (normalized.isBlank()) return CAT_GENERAL
        val scores = LinkedHashMap<String, Int>()
        for ((category, regex) in rules) {
            val hits = regex.findAll(normalized).count()
            if (hits > 0) scores[category] = (scores[category] ?: 0) + hits
        }
        pack?.let { p ->
            val packTokens = packTerms(p)
            if (packTokens.any { normalized.contains(it) }) scores[CAT_PRODUCT] = (scores[CAT_PRODUCT] ?: 0) + 2
        }
        if (normalized.contains("?")) scores[CAT_EXCHANGE] = (scores[CAT_EXCHANGE] ?: 0) + 1
        return scores.maxByOrNull { it.value }?.key ?: CAT_GENERAL
    }

    /** Tags = lexical core of the sentence plus bilingual expansions (bounded). */
    fun tags(text: String, pack: MarketPack? = null): List<String> {
        val tokens = Text.contentTokens(text).filter { it.length > 2 }.distinct()
        val expanded = Lexicon.expand(tokens.take(12))
        val packT = pack?.let { p -> Text.contentTokens(packTerms(p).joinToString(" ")).toSet() } ?: emptySet()
        val fromPack = tokens.filter { it in packT }
        return (tokens.take(8) + fromPack + expanded).distinct().take(14)
    }

    fun importance(text: String, explicit: Boolean, pack: MarketPack?): Double {
        val category = classify(text, pack)
        var score = when (category) {
            CAT_RULE -> 0.9
            CAT_PREFERENCE -> 0.8
            CAT_BUDGET -> 0.8
            CAT_GOAL -> 0.7
            CAT_LESSON -> 0.7
            CAT_SELLER, CAT_PLACE -> 0.6
            CAT_FACT -> 0.6
            CAT_EXCHANGE -> 0.4
            else -> 0.5
        }
        if (explicit) score += 0.15
        if (text.any { it.isDigit() }) score += 0.05
        return score.coerceIn(0.0, 1.0)
    }

    fun normalizeText(text: String): String = Text.normalize(text).replace(Regex("\\s+"), " ").trim()

    /** Near-duplicate detection via 64-bit simhash (Hamming distance). */
    fun duplicateOf(candidate: MemoryItem, existing: List<MemoryItem>, maxDistance: Int = 6): MemoryItem? {
        val candidateHash = candidate.simhash
        val candidateText = normalizeText(candidate.text)
        return existing.firstOrNull { item ->
            if (item.id == candidate.id) return@firstOrNull false
            val sameText = normalizeText(item.text) == candidateText
            sameText || Similarity.hamming(item.simhash, candidateHash) <= maxDistance
        }
    }

    data class Consolidation(
        val kept: List<MemoryItem>,
        val merged: List<MemoryItem>,
        val droppedIds: List<String>
    )

    /**
     * Merges near-duplicates, keeping the highest-importance copy and summing usage.
     * Bounded to O(n^2) over a capped window: correct for on-device sizes (thousands).
     */
    fun consolidate(items: List<MemoryItem>, maxDistance: Int = 6, window: Int = 600): Consolidation {
        val ordered = items.sortedWith(compareByDescending<MemoryItem> { it.importance }.thenByDescending { it.createdAt })
        val kept = mutableListOf<MemoryItem>()
        val merged = mutableListOf<MemoryItem>()
        val dropped = mutableListOf<String>()
        for (item in ordered.take(window)) {
            val twin = kept.firstOrNull { Similarity.hamming(it.simhash, item.simhash) <= maxDistance && it.category == item.category }
            if (twin == null) {
                kept.add(item)
                continue
            }
            val survivor = if (item.text.length > twin.text.length) item else twin
            val other = if (survivor === item) twin else item
            val updated = survivor.copy(
                importance = maxOf(survivor.importance, other.importance),
                useCount = survivor.useCount + other.useCount,
                lastUsedAt = maxOf(survivor.lastUsedAt, other.lastUsedAt),
                tags = (survivor.tags + other.tags).distinct().take(14)
            )
            val idx = kept.indexOf(twin)
            if (idx >= 0) kept[idx] = updated
            else kept.add(updated)
            merged.add(updated)
            dropped.add(other.id)
        }
        kept.addAll(ordered.drop(window))
        return Consolidation(kept, merged, dropped.distinct())
    }

    /**
     * Hybrid ranking: BM25 over tags+text, containment for exact phrase hits, recency decay
     * and importance. Weights are explicit so the behaviour can be defended and tested.
     */
    fun rank(query: String, items: List<MemoryItem>, now: Long, limit: Int = 5): List<MemoryHit> {
        if (items.isEmpty()) return emptyList()
        val corpus = items.map { docTokens(it) }
        val idf = Ranking.idf(corpus)
        val queryTokens = Text.contentTokens(query).ifEmpty { Text.tokens(query) }
        val expanded = Lexicon.expand(queryTokens)
        val queryNorm = normalizeText(query)
        val scored = items.mapIndexed { index, item ->
            val bm = Ranking.bm25(expanded, corpus[index], idf)
            val phrase = if (queryNorm.isNotBlank() && normalizeText(item.text).contains(queryNorm)) 1.0 else 0.0
            val containment = Similarity.containment(expanded, corpus[index].toSet())
            val rec = Ranking.recency(if (item.lastUsedAt > 0) item.lastUsedAt else item.createdAt, now)
            val score = 0.55 * bm + 0.9 * phrase + 0.35 * containment + 0.15 * rec + 0.25 * item.importance
            MemoryHit(item, score) to index
        }
        return scored.sortedWith(compareByDescending<Pair<MemoryHit, Int>> { it.first.score }.thenBy { it.second })
            .map { it.first }
            .filter { it.score > 0.0 }
            .take(limit)
    }

    fun docTokens(item: MemoryItem): List<String> =
        (Text.contentTokens(item.text) + item.tags.flatMap { Text.contentTokens(it) } + Text.contentTokens(item.category))
            .distinct()

    private fun packTerms(pack: MarketPack): List<String> =
        pack.attributes.flatMap { listOf(it.label, it.id) + it.synonyms.take(2) } +
            pack.intents.flatMap { it.keywords.take(3) }

    /**
     * Active learning: the memory worth keeping after an answer is the *user preference*
     * (durable) and, only for confidently cited facts, a factual note.
     */
    fun learnableFrom(question: String, answer: Answer, pack: MarketPack?): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val durable = answer.intent.startsWith("pref") || classify(question, pack) in
            setOf(CAT_PREFERENCE, CAT_BUDGET, CAT_RULE, CAT_GOAL)
        if (durable && !answer.refused) out.add("preference" to question.take(400))
        if (!answer.refused && answer.confidence >= 0.7 && answer.citations.isNotEmpty()) {
            val claim = answer.bullets.firstOrNull()?.takeIf { it.length in 20..300 }
            if (claim != null) out.add("fact" to claim)
        }
        return out
    }

    /** Short bilingual summary of the memory store, used by the ask screen header. */
    fun summary(items: List<MemoryItem>, lang: Lang = Lang.FA): String {
        if (items.isEmpty()) return if (lang == Lang.EN) "Memory is empty." else "حافظه خالی است."
        val byCategory = items.groupingBy { it.category }.eachCount().entries.sortedByDescending { it.value }
        return if (lang == Lang.EN) {
            buildString {
                append("${items.size} memories: ")
                append(byCategory.joinToString(", ") { "${it.key}×${it.value}" })
            }
        } else {
            buildString {
                append("${Text.num(items.size.toLong())} یادسپاری: ")
                append(byCategory.joinToString("، ") { "${it.key}×${Text.num(it.value.toLong())}" })
            }
        }
    }
}
