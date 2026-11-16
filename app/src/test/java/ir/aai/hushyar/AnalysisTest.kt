package ir.aai.hushyar

import ir.aai.hushyar.core.PriceRules
import ir.aai.hushyar.core.Similarity
import ir.aai.hushyar.core.Text
import ir.aai.hushyar.domain.Analyzer
import ir.aai.hushyar.domain.AttributeExtractor
import ir.aai.hushyar.domain.Interview
import ir.aai.hushyar.domain.Listing
import ir.aai.hushyar.domain.MarketPack
import ir.aai.hushyar.domain.Resolver
import ir.aai.hushyar.domain.Scorer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisTest {

    private fun testPack(): MarketPack = Interview.build(
        mapOf(
            "market_name" to "بازار تست",
            "attributes" to "حافظه، رم، گارانتی",
            "currency_unit" to "تومان"
        ),
        1_000L
    ).pack

    private fun listing(
        id: String,
        title: String,
        price: Long?,
        warranty: String? = null,
        createdAt: Long = 1_000L
    ): Listing {
        val attrs = buildMap {
            put("attr_1", "256")
            put("attr_2", "8")
            if (warranty != null) put("attr_3", warranty)
        }
        return Listing(
            id = id,
            title = title,
            titleNorm = Text.normalize(title),
            priceRaw = price?.toString() ?: "",
            priceIrt = price,
            priceNote = "مستقیم",
            source = "تست",
            attrs = attrs,
            createdAt = createdAt
        )
    }

    @Test
    fun extractorReadsNumbersAndWordsFromTitle() {
        val pack = testPack()
        val raw = Listing(
            id = "l0",
            title = "گوشی سامسونگ حافظه ۲۵۶ رم ۸ گارانتی شرکتی",
            titleNorm = "",
            priceRaw = "",
            priceIrt = null,
            priceNote = "",
            source = "تست"
        )
        val attrs = AttributeExtractor.extract(raw, pack)
        assertEquals("256", attrs["attr_1"])
        assertEquals("8", attrs["attr_2"])
        assertEquals("شرکتی", attrs["attr_3"])
    }

    @Test
    fun manualAttributesAreNotOverwritten() {
        val pack = testPack()
        val raw = Listing(
            id = "l0",
            title = "گوشی حافظه ۵۱۲",
            titleNorm = "",
            priceRaw = "",
            priceIrt = null,
            priceNote = "",
            source = "تست",
            attrs = mapOf("attr_1" to "256")
        )
        assertEquals("256", AttributeExtractor.extract(raw, pack)["attr_1"])
    }

    @Test
    fun similarTitlesAreGroupedIntoOneProduct() {
        val pack = testPack()
        val listings = listOf(
            listing("l1", "گوشی سامسونگ گلکسی S24 حافظه 256 رم 8", 50_000_000, "شرکتی", 1_000),
            listing("l2", "گوشی سامسونگ گلکسی S24 حافظه 256 رم 8 گارانتی", 55_000_000, "محلی", 2_000)
        )
        val groups = Resolver.group(listings, pack.priceRules)
        assertEquals(1, groups.size)
        assertEquals(2, groups.first().listings.size)
    }

    @Test
    fun differentNumericSpecsAreNotMerged() {
        val pack = testPack()
        val a = listing("l1", "گوشی سامسونگ گلکسی S24 حافظه 128 رم 8", 40_000_000, "شرکتی", 1_000)
        val b = listing("l2", "گوشی سامسونگ گلکسی S24 حافظه 512 رم 8", 70_000_000, "شرکتی", 2_000)
        val groups = Resolver.group(listOf(a, b), pack.priceRules)
        assertEquals(2, groups.size)
    }

    @Test
    fun analyzerReportsStatsFlagsAndCitations() {
        val pack = testPack()
        val listings = listOf(
            listing("l1", "گوشی سامسونگ گلکسی S24 حافظه 256 رم 8", 50_000_000, "شرکتی", 1_000),
            listing("l2", "گوشی سامسونگ گلکسی S24 حافظه 256 رم 8 گارانتی", 55_000_000, "محلی", 2_000)
        )
        val product = Resolver.group(listings, pack.priceRules).first()
        val result = Analyzer.analyze(product, pack)

        assertNotNull(result.priceStats)
        assertEquals(2, result.priceStats?.count)
        assertEquals(2, result.citations.size)
        assertTrue(result.verdict in setOf("ارزش بالا", "متوسط", "گران"))
        assertTrue(result.flags.any { it.id == "spec_conflict" })
        assertEquals(0, result.missing.size) // all three attributes are present
    }

    @Test
    fun missingAttributesAreReportedNotGuessed() {
        val pack = testPack()
        val bare = Listing(
            id = "l1",
            title = "گوشی سامسونگ گلکسی S24",
            titleNorm = Text.normalize("گوشی سامسونگ گلکسی S24"),
            priceRaw = "50000000",
            priceIrt = 50_000_000,
            priceNote = "مستقیم",
            source = "تست",
            attrs = emptyMap(),
            createdAt = 1_000
        )
        val product = Resolver.group(listOf(bare), pack.priceRules).first()
        val result = Analyzer.analyze(product, pack)
        assertTrue(result.missing.contains("حافظه"))
        assertNull(result.priceStats?.percentile)
        assertTrue(result.findings.any { it.value == null })
    }

    @Test
    fun scorerAndBandsBehaveAsDocumented() {
        val rules = PriceRules()
        assertEquals("ارزان", Scorer.priceBand(0.10, rules))
        assertEquals("متعارف", Scorer.priceBand(0.40, rules))
        assertEquals("بالا", Scorer.priceBand(0.70, rules))
        assertEquals("پرت", Scorer.priceBand(0.95, rules))
        assertEquals("نامعلوم", Scorer.priceBand(null, rules))
        assertEquals("ارزش بالا", Scorer.verdict(0.9))
        assertEquals("گران", Scorer.verdict(0.2))
    }

    @Test
    fun similaritySanity() {
        assertEquals(1.0, Similarity.jaccard(setOf("a", "b"), setOf("a", "b")), 0.0001)
        assertEquals(0.0, Similarity.jaccard(setOf("a"), setOf("b")), 0.0001)
        assertEquals(1.0, Similarity.containment(setOf("a", "b"), setOf("a", "b", "c")), 0.0001)
        assertEquals(50L, Similarity.median(listOf(40L, 50L, 60L)))
        assertEquals(55L, Similarity.median(listOf(40L, 50L, 60L, 70L)))
        assertEquals(0, Similarity.hamming(Similarity.simhash(listOf("a", "b")), Similarity.simhash(listOf("a", "b"))))
    }
}
