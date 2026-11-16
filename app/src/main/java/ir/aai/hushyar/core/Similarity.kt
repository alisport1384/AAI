package ir.aai.hushyar.core

import kotlin.math.abs

object Similarity {

    fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.count { it in b }
        val union = a.size + b.size - inter
        return if (union == 0) 0.0 else inter.toDouble() / union
    }

    fun containment(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.count { it in b }
        return inter.toDouble() / minOf(a.size, b.size)
    }

    fun combined(a: Set<String>, b: Set<String>): Double =
        0.6 * jaccard(a, b) + 0.4 * containment(a, b)

    /** 64-bit SimHash over tokens; near-duplicates have small Hamming distance. */
    fun simhash(tokens: List<String>): Long {
        if (tokens.isEmpty()) return 0L
        val vector = IntArray(64)
        for (token in tokens) {
            val h = fnv1a64(token)
            for (bit in 0 until 64) {
                val set = (h ushr bit) and 1L == 1L
                vector[bit] += if (set) 1 else -1
            }
        }
        var out = 0L
        for (bit in 0 until 64) if (vector[bit] > 0) out = out or (1L shl bit)
        return out
    }

    fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    fun fnv1a64(s: String): Long {
        var hash = -3750763034362895579L // 14695981039346656037 unsigned
        for (c in s) {
            hash = hash xor c.code.toLong()
            hash *= 1099511628211L
        }
        return hash
    }

    fun median(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
    }

    /** Linear-interpolated percentile, p in [0,1]. */
    fun percentile(values: List<Long>, p: Double): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val clamped = p.coerceIn(0.0, 1.0)
        val pos = clamped * (sorted.size - 1)
        val lower = kotlin.math.floor(pos).toInt()
        val upper = kotlin.math.ceil(pos).toInt()
        if (lower == upper) return sorted[lower].toDouble()
        val weight = pos - lower
        return sorted[lower] * (1 - weight) + sorted[upper] * weight
    }

    /** Robust z-score based on median absolute deviation. */
    fun madZ(values: List<Long>, value: Long): Double {
        val med = median(values) ?: return 0.0
        if (values.size < 3) return 0.0
        val deviations = values.map { abs(it - med) }
        val mad = median(deviations) ?: return 0.0
        if (mad == 0L) return if (value == med) 0.0 else Double.MAX_VALUE
        return 0.6745 * (value - med) / mad
    }
}
