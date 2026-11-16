package ir.aai.hushyar.core

import java.util.UUID

object Ids {
    fun new(prefix: String): String = "$prefix-" + UUID.randomUUID().toString().replace("-", "").take(12)

    /** Deterministic id for content-addressed rows (web-harvested listings, knowledge drafts). */
    fun hash(seed: String): String = java.lang.Long.toHexString(Similarity.fnv1a64(seed))
}
