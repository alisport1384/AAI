package ir.aai.hushyar.domain

import ir.aai.hushyar.core.PriceParser
import ir.aai.hushyar.core.PriceRules
import ir.aai.hushyar.core.Similarity
import ir.aai.hushyar.core.Text

/**
 * Deterministic analysis chain (DESIGN §8–§9):
 * attribute extraction -> listing resolution -> price statistics -> value score -> red flags.
 * Every value carries its source, and anything unresolved is reported as "missing" instead of guessed.
 */
object AttributeExtractor {

    fun extract(listing: Listing, pack: MarketPack): Map<String, String> {
        val out = LinkedHashMap<String, String>(listing.attrs)
        val normTitle = Text.normalize(listing.title)
        for (attr in pack.attributes) {
            if (out.containsKey(attr.id)) continue
            val keys = buildList {
                add(attr.label)
                addAll(attr.synonyms)
            }.map { Text.normalize(it) }.filter { it.isNotBlank() }
            for (key in keys) {
                val value = when (attr.type) {
                    AttrType.NUMBER -> extractNumber(normTitle, key)
                    else -> extractEnumOrWord(normTitle, key)
                }
                if (value != null) { out[attr.id] = value; break }
            }
        }
        return out
    }

    private fun extractNumber(text: String, key: String): String? {
        val idx = text.indexOf(key)
        if (idx < 0) return null
        val tail = text.substring(idx + key.length).trim()
        val m = Regex("^[^0-9]{0,6}([0-9]+(\\.[0-9]+)?)").find(tail)
        return m?.groupValues?.get(1)
    }

    private fun extractEnumOrWord(text: String, key: String): String? {
        val idx = text.indexOf(key)
        if (idx < 0) return null
        val tail = text.substring(idx + key.length).trim()
        if (tail.isEmpty()) return null
        val word = tail.split(Regex("[\\s,،;؛:()\\-]+")).firstOrNull { it.isNotBlank() } ?: return null
        return if (word.length <= 24) word else null
    }
}

object Resolver {

    const val MERGE_THRESHOLD = 0.82
    const val REVIEW_THRESHOLD = 0.60

    fun group(listings: List<Listing>, rule: PriceRules): List<ProductGroup> {
        val groups = mutableListOf<MutableList<Listing>>()
        for (listing in listings.sortedBy { it.titleNorm }) {
            val tokens = Text.contentTokens(listing.title).toSet()
            var placed = false
            for (group in groups) {
                val reference = group.first()
                val refTokens = Text.contentTokens(reference.title).toSet()
                val score = Similarity.combined(tokens, refTokens)
                val numericConflict = numericConflict(listing, reference)
                if (score >= MERGE_THRESHOLD && !numericConflict) {
                    group.add(listing)
                    placed = true
                    break
                }
            }
            if (!placed) groups.add(mutableListOf(listing))
        }
        return groups.map { members ->
            val canonical = canonicalName(members)
            ProductGroup(
                id = "p-" + Similarity.fnv1a64(canonical + members.size).toString(),
                canonicalName = canonical,
                listings = members,
                attrs = mergeAttributes(members)
            )
        }
    }

    private fun numericConflict(a: Listing, b: Listing): Boolean {
        for ((id, av) in a.attrs) {
            val bv = b.attrs[id] ?: continue
            val an = av.toDoubleOrNull() ?: continue
            val bn = bv.toDoubleOrNull() ?: continue
            if (an <= 0 || bn <= 0) continue
            val diff = kotlin.math.abs(an - bn) / maxOf(an, bn)
            if (diff > 0.25) return true
        }
        return false
    }

    private fun canonicalName(members: List<Listing>): String {
        if (members.size == 1) return members.first().title.trim()
        val tokenCounts = mutableMapOf<String, Int>()
        for (m in members) for (t in Text.contentTokens(m.title).distinct()) {
            tokenCounts[t] = (tokenCounts[t] ?: 0) + 1
        }
        // keep the most frequent words, but display them in the order of the shortest title
        val shortest = members.minByOrNull { it.title.length }?.title ?: members.first().title
        val kept = Text.tokens(shortest).filter { (tokenCounts[Text.normalize(it)] ?: 0) >= 1 }
        val rebuilt = kept.joinToString(" ").ifBlank { shortest }
        return rebuilt.trim()
    }

    private fun mergeAttributes(members: List<Listing>): Map<String, String> {
        val counts = mutableMapOf<String, MutableMap<String, Int>>()
        for (m in members) for ((k, v) in m.attrs) {
            counts.getOrPut(k) { mutableMapOf() }.merge(v, 1, Int::plus)
        }
        return counts.mapValues { (_, values) -> values.maxByOrNull { it.value }?.key.orEmpty() }
    }

    fun findInQuestion(question: String, products: List<ProductGroup>): ProductGroup? {
        val qTokens = Text.contentTokens(question).toSet()
        if (qTokens.isEmpty()) return null
        return products
            .map { p -> p to Similarity.containment(Text.contentTokens(p.canonicalName).toSet(), qTokens) }
            .filter { it.second > 0.0 }
            .maxByOrNull { it.second }
            ?.takeIf { it.second >= 0.40 }
            ?.first
    }

    fun findManyInQuestion(question: String, products: List<ProductGroup>): List<ProductGroup> {
        val qTokens = Text.contentTokens(question).toSet()
        if (qTokens.isEmpty()) return emptyList()
        return products
            .map { p -> p to Similarity.containment(Text.contentTokens(p.canonicalName).toSet(), qTokens) }
            .filter { it.second >= 0.40 }
            .sortedByDescending { it.second }
            .take(3)
            .map { it.first }
    }
}

object PriceStatsCalculator {
    fun stats(prices: List<Long>, reference: Long?): PriceStats? {
        if (prices.isEmpty()) return null
        val min = prices.min()
        val max = prices.max()
        val median = Similarity.median(prices) ?: return null
        // A percentile from a single observation is meaningless: report "unknown" instead of a fake 1.0.
        val percentile = if (reference != null && prices.size >= 2) {
            val below = prices.count { it <= reference }
            (below.toDouble() / prices.size).coerceIn(0.0, 1.0)
        } else null
        return PriceStats(count = prices.size, min = min, median = median, max = max, percentile = percentile)
    }
}

object Scorer {

    fun attributeScore(product: ProductGroup, pack: MarketPack): Double {
        var weighted = 0.0
        var totalWeight = 0.0
        for (attr in pack.attributes) {
            val weight = attr.weight.coerceAtLeast(0.0)
            if (weight == 0.0) continue
            totalWeight += weight
            val value = product.attrs[attr.id]
            val filled = !value.isNullOrBlank()
            val presence = if (filled) 1.0 else 0.0
            val requiredBonus = if (attr.required && !filled) 0.0 else presence
            weighted += weight * requiredBonus
        }
        return if (totalWeight == 0.0) 0.0 else weighted / totalWeight
    }

    fun valueScore(product: ProductGroup, pack: MarketPack, stats: PriceStats?): Double {
        val attrScore = attributeScore(product, pack)
        val priceComponent = when (val p = stats?.percentile) {
            null -> 0.5               // unknown position: neutral, and reported as missing
            else -> 1.0 - p
        }
        return (0.6 * attrScore + 0.4 * priceComponent).coerceIn(0.0, 1.0)
    }

    fun verdict(score: Double): String = when {
        score >= 0.70 -> "ارزش بالا"
        score >= 0.45 -> "متوسط"
        else -> "گران"
    }

    fun priceBand(percentile: Double?, rules: PriceRules): String = when {
        percentile == null -> "نامعلوم"
        percentile <= rules.goodBand -> "ارزان"
        percentile <= rules.fairBand -> "متعارف"
        percentile <= rules.highBand -> "بالا"
        else -> "پرت"
    }
}

object RedFlagEngine {

    fun evaluate(
        product: ProductGroup,
        pack: MarketPack,
        stats: PriceStats?,
        referencePrice: Long?
    ): List<RedFlag> {
        val flags = mutableListOf<RedFlag>()
        val prices = product.prices
        if (prices.size >= 3 && referencePrice != null && stats != null) {
            val p05 = Similarity.percentile(prices, 0.05) ?: 0.0
            if (referencePrice < p05) flags.add(flag(pack, "price_too_low", "قیمت این آگهی به‌شکل غیرعادی پایین‌تر از بازهٔ همین کالاست؛ اصالت و کامل‌بودن را بررسی کنید."))
            val z = Similarity.madZ(prices, referencePrice)
            if (kotlin.math.abs(z) >= pack.priceRules.madZ) {
                flags.add(flag(pack, "price_outlier", "قیمت با میانهٔ این کالا فاصلهٔ زیادی دارد (z=${"%.1f".format(z)})."))
            }
        }
        for (attr in pack.attributes) {
            if (!attr.required) continue
            val missingEverywhere = product.listings.all { it.attrs[attr.id].isNullOrBlank() }
            if (missingEverywhere) {
                flags.add(flag(pack, "missing_required_attr", "ویژگی ضروری «${attr.label}» در هیچ آگهی این کالا نیامده است."))
            }
        }
        val conflicts = conflictingAttributes(product)
        if (conflicts.isNotEmpty()) {
            flags.add(flag(pack, "spec_conflict", "مغایرت مشخصات در: " + conflicts.joinToString("، ")))
        }
        pack.redFlags.filter { it.id.startsWith("custom_") }.forEach { spec ->
            if (spec.text.isNotBlank()) flags.add(RedFlag(spec.id, spec.severity, spec.text))
        }
        return flags.distinctBy { it.id + it.text }
    }

    private fun flag(pack: MarketPack, id: String, fallback: String): RedFlag {
        val spec = pack.redFlags.firstOrNull { it.id == id }
        return RedFlag(id, spec?.severity ?: "medium", spec?.text?.takeIf { it.isNotBlank() } ?: fallback)
    }

    private fun conflictingAttributes(product: ProductGroup): List<String> {
        val byAttr = mutableMapOf<String, MutableSet<String>>()
        for (listing in product.listings) for ((k, v) in listing.attrs) {
            if (v.isNotBlank()) byAttr.getOrPut(k) { mutableSetOf() }.add(Text.normalize(v))
        }
        return byAttr.filterValues { it.size > 1 }.keys.toList()
    }
}

object Analyzer {

    fun analyze(product: ProductGroup, pack: MarketPack): AnalysisResult {
        val reference = product.listings.maxByOrNull { it.createdAt }?.priceIrt
        val stats = PriceStatsCalculator.stats(product.prices, reference)
        val score = Scorer.valueScore(product, pack, stats)
        val findings = pack.attributes.map { attr ->
            val value = product.attrs[attr.id]
            AttributeFinding(
                id = attr.id,
                label = attr.label,
                value = value?.takeIf { it.isNotBlank() },
                sourceLabel = if (value.isNullOrBlank()) "بدون داده" else "آگهی‌ها",
                confidence = if (value.isNullOrBlank()) 0.0 else 0.8
            )
        }
        val missing = pack.attributes.filter { product.attrs[it.id].isNullOrBlank() }.map { it.label }
        val citations = product.listings.take(5).map { listing ->
            Citation(ref = "L:${listing.id.take(8)}", label = "${listing.source} • ${Text.numEn(listing.priceIrt ?: 0)} تومان", url = listing.url)
        }
        val notes = product.listings
            .filter { it.priceIrt == null }
            .map { "قیمت «${it.title.take(24)}…» نامعلوم است و در آمار حساب نشد." }

        return AnalysisResult(
            product = product,
            findings = findings,
            priceStats = stats,
            referencePrice = reference,
            valueScore = score,
            verdict = Scorer.verdict(score),
            priceBand = Scorer.priceBand(stats?.percentile, pack.priceRules),
            flags = RedFlagEngine.evaluate(product, pack, stats, reference),
            missing = missing,
            citations = citations,
            notes = notes
        )
    }

    fun priceOf(raw: String, pack: MarketPack) = PriceParser.parse(raw, pack.priceRules)
}
