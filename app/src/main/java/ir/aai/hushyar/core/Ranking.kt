package ir.aai.hushyar.core

import kotlin.math.ln
import kotlin.math.pow

/** Extra scoring primitives used by the v0.2 search + memory ranking. */
object Ranking {

    /** Reciprocal rank fusion over ranked id lists. */
    fun rrf(lists: List<List<String>>, k: Int = 60): List<Pair<String, Double>> {
        val scores = LinkedHashMap<String, Double>()
        for (list in lists) {
            list.forEachIndexed { index, id ->
                scores[id] = (scores[id] ?: 0.0) + 1.0 / (k + index + 1)
            }
        }
        return scores.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }

    /**
     * Light BM25-flavoured overlap score with optional idf weights.
     * Deterministic and cheap: good enough for on-device corpora of thousands of rows.
     */
    fun bm25(
        queryTokens: Collection<String>,
        docTokens: Collection<String>,
        idf: Map<String, Double> = emptyMap(),
        k1: Double = 1.2
    ): Double {
        if (queryTokens.isEmpty() || docTokens.isEmpty()) return 0.0
        val doc = docTokens.groupingBy { it }.eachCount()
        val avgLen = docTokens.size.toDouble().coerceAtLeast(1.0)
        var score = 0.0
        for (token in queryTokens) {
            val tf = doc[token] ?: continue
            val weight = idf[token] ?: 1.0
            val norm = tf * (k1 + 1.0) / (tf + k1 * (1.0 - 0.25 + 0.25 * docTokens.size / avgLen))
            score += weight * norm
        }
        return score
    }

    /** Inverse document frequency from a tiny corpus (smoothed). */
    fun idf(corpus: List<Collection<String>>): Map<String, Double> {
        if (corpus.isEmpty()) return emptyMap()
        val docCount = corpus.size.toDouble()
        val df = mutableMapOf<String, Int>()
        for (doc in corpus) for (token in doc.distinct()) df[token] = (df[token] ?: 0) + 1
        return df.mapValues { (_, n) -> ln(1.0 + (docCount - n + 0.5) / (n + 0.5)) }
    }

    /** Recency decay used when ordering memory hits. */
    fun recency(createdAt: Long, now: Long, halfLifeDays: Double = 30.0): Double {
        val ageDays = ((now - createdAt).coerceAtLeast(0L)) / 86_400_000.0
        return 0.5.pow(ageDays / halfLifeDays)
    }
}
