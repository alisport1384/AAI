package ir.aai.hushyar.domain

import ir.aai.hushyar.core.PriceRules

enum class AttrType { NUMBER, ENUM, TEXT }

/** One attribute the engine must look at when analysing an item (DESIGN §6). */
data class AttributeSpec(
    val id: String,
    val label: String,
    val type: AttrType = AttrType.TEXT,
    val unit: String? = null,
    val weight: Double = 0.1,
    val required: Boolean = false,
    val synonyms: List<String> = emptyList()
)

/** A question family the market cares about; keywords are matched deterministically. */
data class IntentSpec(
    val id: String,
    val label: String,
    val keywords: List<String> = emptyList()
)

data class RedFlagSpec(
    val id: String,
    val severity: String,
    val text: String
)

/**
 * The whole domain lives in this declarative bundle, so the market can be replaced
 * without touching code (this is the core answer to "من ممکنه بازار رو عوض کنم").
 */
data class MarketPack(
    val id: String,
    val name: String,
    val version: Int = 1,
    val langs: List<String> = listOf("fa"),
    val categories: List<String> = emptyList(),
    val attributes: List<AttributeSpec> = emptyList(),
    val intents: List<IntentSpec> = emptyList(),
    val redFlags: List<RedFlagSpec> = emptyList(),
    val sources: List<String> = emptyList(),
    val priceRules: PriceRules = PriceRules(),
    val refusalNote: String = "",
    val createdAt: Long = 0L,
    val schema: String = SCHEMA
) {
    companion object {
        const val SCHEMA: String = "hushyar.pack/1"
    }
}

/**
 * What the assistant knows about *itself* after the interview (the requested self-understanding).
 * Everything here is derived from the pack + stored data, never hard-coded per market.
 */
data class SelfManifest(
    val identity: String,
    val marketName: String,
    val duties: List<String>,
    val abilities: List<String>,
    val limits: List<String>,
    val unknowns: List<String>,
    val focusAttributes: List<String>,
    val intentLabels: List<String>
)

data class Listing(
    val id: String,
    val title: String,
    val titleNorm: String,
    val priceRaw: String,
    val priceIrt: Long?,
    val priceNote: String,
    val source: String,
    val url: String? = null,
    val seller: String? = null,
    val city: String? = null,
    val attrs: Map<String, String> = emptyMap(),
    val createdAt: Long = 0L
) {
    val simhash: Long
        get() = ir.aai.hushyar.core.Similarity.simhash(ir.aai.hushyar.core.Text.contentTokens(title))
}

/** Listings that the resolver considers the same item. */
data class ProductGroup(
    val id: String,
    val canonicalName: String,
    val listings: List<Listing>,
    val attrs: Map<String, String>
) {
    val prices: List<Long> get() = listings.mapNotNull { it.priceIrt }
    val lastSeen: Long get() = listings.maxOfOrNull { it.createdAt } ?: 0L
}

data class AttributeFinding(
    val id: String,
    val label: String,
    val value: String?,
    val sourceLabel: String,
    val confidence: Double
)

data class RedFlag(val id: String, val severity: String, val text: String)

data class PriceStats(
    val count: Int,
    val min: Long,
    val median: Long,
    val max: Long,
    val percentile: Double?
)

data class Citation(
    val ref: String,
    val label: String,
    val url: String? = null
)

data class AnalysisResult(
    val product: ProductGroup,
    val findings: List<AttributeFinding>,
    val priceStats: PriceStats?,
    val referencePrice: Long?,
    val valueScore: Double,
    val verdict: String,
    val priceBand: String,
    val flags: List<RedFlag>,
    val missing: List<String>,
    val citations: List<Citation>,
    val notes: List<String> = emptyList()
)

data class Answer(
    val intent: String,
    val text: String,
    val bullets: List<String> = emptyList(),
    val citations: List<Citation> = emptyList(),
    val confidence: Double = 0.0,
    val missing: List<String> = emptyList(),
    val refused: Boolean = false,
    val lang: String = "fa",
    /** How the answer was produced (agent trace, human readable). */
    val trace: List<String> = emptyList(),
    /** Data mutations performed while answering (audited, undoable at DB level). */
    val actions: List<String> = emptyList()
)

data class MemoryItem(
    val id: String,
    val kind: String,
    val text: String,
    val createdAt: Long,
    val category: String = "عمومی",
    val tags: List<String> = emptyList(),
    val importance: Double = 0.5,
    val useCount: Int = 0,
    val lastUsedAt: Long = 0L,
    val source: String = "user"
) {
    val simhash: Long get() = ir.aai.hushyar.core.Similarity.simhash(ir.aai.hushyar.core.Text.contentTokens(text))
}

/** A single web result as returned by one engine adapter. */
data class WebHit(
    val url: String,
    val title: String,
    val snippet: String,
    val engine: String,
    val rank: Int
)

data class SearchReport(
    val query: String,
    val hits: List<WebHit>,
    val perEngine: Map<String, Int> = emptyMap(),
    val failures: Map<String, String> = emptyMap()
) {
    val isEmpty: Boolean get() = hits.isEmpty()
}

/** A fetched page after cleaning and trust marking. */
data class ExtractedPage(
    val url: String,
    val title: String,
    val text: String,
    val trust: String = "untrusted",
    val signals: List<String> = emptyList()
)

/** Event log that the assistant itself can read and export as Markdown. */
data class LogEvent(
    val id: Long,
    val at: Long,
    val kind: String,
    val actor: String,
    val message: String,
    val detail: String = ""
)

data class MemoryHit(val item: MemoryItem, val score: Double)

data class AuditEntry(
    val at: Long,
    val actor: String,
    val action: String,
    val target: String,
    val detail: String
)
