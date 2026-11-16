package ir.aai.hushyar

import ir.aai.hushyar.domain.GapKind
import ir.aai.hushyar.domain.Interview
import ir.aai.hushyar.domain.KnowledgeBank
import ir.aai.hushyar.domain.KnowledgeDecision
import ir.aai.hushyar.domain.KnowledgeItem
import ir.aai.hushyar.domain.MarketPack
import ir.aai.hushyar.domain.MemoryBank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeTest {

    private val day = 86_400_000L
    private val now = 1_700_000_000_000L

    private fun pack(): MarketPack = Interview.build(
        mapOf(
            "market_name" to "بازار دانش",
            "attributes" to "حافظه، رم، گارانتی",
            "currency_unit" to "تومان"
        ),
        1_000L
    ).pack

    private fun item(
        id: String,
        claim: String,
        topic: String = "قیمت-گوشی-سامسونگ",
        domain: String = "example.com",
        verifiedAt: Long = now,
        confidence: Double = 0.6,
        numeric: Double? = null,
        unit: String? = null,
        attribute: String? = null,
        superseded: String? = null
    ) = KnowledgeItem(
        id = id,
        topic = topic,
        question = "قیمت گوشی سامسونگ چقدر است؟",
        claim = claim,
        category = MemoryBank.CAT_FACT,
        attribute = attribute,
        numericValue = numeric,
        unit = unit,
        url = "https://$domain/x",
        domain = domain,
        confidence = confidence,
        createdAt = verifiedAt,
        lastVerifiedAt = verifiedAt,
        supersededById = superseded
    )

    @Test
    fun topicKeyIgnoresQuestionWordsAndIsStable() {
        val a = KnowledgeBank.topicKey("قیمت گوشی سامسونگ چقدر است؟")
        val b = KnowledgeBank.topicKey("قیمت گوشی سامسونگ")
        assertEquals(a, b)
        assertFalse(a.contains("چقدر"))
        assertTrue(KnowledgeBank.topicKey("what is the price of a samsung phone").contains("price"))
    }

    @Test
    fun numericExtractionUnderstandsPersianPrices() {
        val price = KnowledgeBank.numeric("قیمت این گوشی ۵۰ میلیون تومان است")
        assertNotNull(price)
        assertEquals(50_000_000.0, price!!.first, 1.0)
        assertEquals("تومان", price.second)
        val english = KnowledgeBank.numeric("about 1.5 million toman")
        assertNotNull(english)
        assertEquals(1_500_000.0, english!!.first, 1.0)
        assertEquals(null, KnowledgeBank.numeric("این گوشی خوب است"))
    }

    @Test
    fun claimsAreCategorisedAndAttributesDetected() {
        val p = pack()
        val category = KnowledgeBank.categorize("قیمت این محصول ۵۰ میلیون تومان است", p)
        assertTrue(category in setOf(MemoryBank.CAT_BUDGET, MemoryBank.CAT_FACT, MemoryBank.CAT_PRODUCT))
        assertNotNull(KnowledgeBank.attributeOf("حافظه این گوشی ۲۵۶ گیگابایت است", p))
    }

    @Test
    fun firstObservationIsNew() {
        val merge = KnowledgeBank.merge(emptyList(), item("k1", "قیمت گوشی ۵۰ میلیون تومان است", numeric = 50_000_000.0, unit = "تومان"))
        assertEquals(KnowledgeDecision.NEW, merge.decision)
        assertEquals(1, merge.items.size)
    }

    @Test
    fun repeatingTheSameClaimRefreshesAndCountsSources() {
        val existing = listOf(item("k1", "قیمت گوشی سامسونگ ۵۰ میلیون تومان است", numeric = 50_000_000.0, unit = "تومان"))
        val again = item("k2", "قیمت گوشی سامسونگ ۵۰ میلیون تومان است", domain = "other.com", verifiedAt = now + day)
        val merge = KnowledgeBank.merge(existing, again)
        assertEquals(KnowledgeDecision.REFRESH, merge.decision)
        assertEquals(1, merge.items.size)
        assertEquals(2, merge.items.first().sourceCount)
        assertEquals(now + day, merge.items.first().lastVerifiedAt)
    }

    @Test
    fun changedNumberCorrectsAndKeepsHistory() {
        val existing = listOf(item("k1", "قیمت گوشی سامسونگ ۵۰ میلیون تومان است", numeric = 50_000_000.0, unit = "تومان"))
        val fresh = item("k3", "قیمت گوشی سامسونگ ۶۵ میلیون تومان است", domain = "shop.ir", verifiedAt = now + day, numeric = 65_000_000.0, unit = "تومان")
        val merge = KnowledgeBank.merge(existing, fresh)
        assertEquals(KnowledgeDecision.CORRECT, merge.decision)
        assertEquals("k3", merge.touchedId)
        assertEquals("k1", merge.supersededId)
        val old = merge.items.first { it.id == "k1" }
        assertEquals("k3", old.supersededById)
        assertEquals(2, merge.items.size)
        val active = merge.items.filter { it.fresh }
        assertEquals(1, active.size)
        assertEquals(65_000_000.0, active.first().numericValue!!, 1.0)
    }

    @Test
    fun smallDifferencesWithinToleranceAreNotCorrections() {
        val existing = listOf(item("k1", "قیمت گوشی ۵۰ میلیون تومان است", numeric = 50_000_000.0, unit = "تومان"))
        val close = item("k2", "قیمت گوشی ۵۲ میلیون تومان است", numeric = 52_000_000.0, unit = "تومان", verifiedAt = now)
        val decision = KnowledgeBank.decide(existing, close)
        assertTrue(decision == KnowledgeDecision.REFRESH || decision == KnowledgeDecision.NEW)
    }

    @Test
    fun disagreeingTextIsRecordedAsConflict() {
        val existing = listOf(item("k1", "این گوشی گارانتی رسمی هجده ماهه دارد"))
        val other = item("k2", "این گوشی هیچ گارانتی رسمی ندارد و فروشنده مسئولیتی نمی‌پذیرد", domain = "blog.ir")
        val merge = KnowledgeBank.merge(existing, other)
        assertEquals(KnowledgeDecision.CONFLICT, merge.decision)
        assertEquals(2, merge.items.size)
    }

    @Test
    fun lowerConfidenceDuplicateIsIgnored() {
        val existing = listOf(item("k1", "قیمت گوشی سامسونگ ۵۰ میلیون تومان است", confidence = 0.8, numeric = 50_000_000.0, unit = "تومان"))
        val weak = item("k9", "قیمت گوشی سامسونگ ۹۰ میلیون تومان است", confidence = 0.5, numeric = 90_000_000.0, unit = "تومان")
        assertEquals(KnowledgeDecision.IGNORE, KnowledgeBank.decide(existing, weak))
    }

    @Test
    fun stalenessUsesLastVerification() {
        val old = item("k1", "قیمت گوشی ۵۰ میلیون تومان است", verifiedAt = now - 60 * day)
        val recent = item("k2", "قیمت گوشی ۵۰ میلیون تومان است", verifiedAt = now - 2 * day)
        assertTrue(KnowledgeBank.isStale(old, now))
        assertFalse(KnowledgeBank.isStale(recent, now))
        assertEquals(1, KnowledgeBank.staleItems(listOf(old, recent), now).size)
        val supersededOld = item("k3", "قیمت گوشی ۴۰ میلیون تومان است", verifiedAt = now - 90 * day, superseded = "k4")
        assertEquals(0, KnowledgeBank.staleItems(listOf(supersededOld), now).size)
    }

    @Test
    fun coverageRewardsRelevanceNumberAndSecondSource() {
        val p = pack()
        val question = "قیمت گوشی سامسونگ گلکسی s24 چقدر است؟"
        val single = listOf(
            item("k1", "قیمت گوشی سامسونگ گلکسی s24 حدود ۵۰ میلیون تومان است و گارانتی دارد", numeric = 50_000_000.0, unit = "تومان")
        )
        val withSecond = single + item(
            "k2", "قیمت گوشی سامسونگ گلکسی s24 در فروشگاه دیگر ۵۱ میلیون تومان است",
            domain = "shop.ir", numeric = 51_000_000.0, unit = "تومان"
        )
        val weak = listOf(item("k3", "این صفحه دربارهٔ تاریخچهٔ شرکت‌ها است"))
        val c1 = KnowledgeBank.coverage(question, single, now, p)
        val c2 = KnowledgeBank.coverage(question, withSecond, now, p)
        val c3 = KnowledgeBank.coverage(question, weak, now, p)
        assertTrue(c1 > 0.4)
        assertTrue(c2 > c1)
        assertTrue(c3 < c1)
        assertEquals(0.0, KnowledgeBank.coverage(question, emptyList(), now, p), 0.0001)
    }

    @Test
    fun gapsPointAtTheMissingNumber() {
        val p = pack()
        val question = "قیمت گوشی سامسونگ چقدر است؟"
        val noNumber = listOf(item("k1", "گوشی سامسونگ صفحهٔ نمایش بزرگی دارد"))
        val gaps = KnowledgeBank.gaps(question, noNumber, now, p)
        assertTrue(gaps.any { it.kind == GapKind.NUMERIC })
        val withNumber = listOf(item("k2", "قیمت گوشی سامسونگ ۵۰ میلیون تومان است", numeric = 50_000_000.0, unit = "تومان"))
        val gaps2 = KnowledgeBank.gaps(question, withNumber, now, p)
        assertTrue(gaps2.none { it.kind == GapKind.NUMERIC })
    }

    @Test
    fun answerablePrefersFreshRelevantItems() {
        val question = "قیمت گوشی سامسونگ چقدر است؟"
        val items = listOf(
            item("k1", "قیمت گوشی سامسونگ ۵۰ میلیون تومان است", verifiedAt = now),
            item("k2", "قیمت گوشی سامسونگ ۵۰ میلیون تومان است", topic = "هوا-و-آب", verifiedAt = now),
            item("k3", "قیمت گوشی سامسونگ ۴۰ میلیون تومان بود", verifiedAt = now - 120 * day)
        )
        val hits = KnowledgeBank.answerable(question, items, now, 3)
        assertTrue(hits.isNotEmpty())
        assertEquals("k1", hits.first().id)
        assertTrue(hits.none { it.id == "k3" })
    }

    @Test
    fun summaryMentionsCountsAndStaleItems() {
        val items = listOf(
            item("k1", "قیمت گوشی ۵۰ میلیون تومان است", verifiedAt = now),
            item("k2", "قیمت گوشی ۵۰ میلیون تومان است", verifiedAt = now - 100 * day, topic = "موضوع-دیگر")
        )
        val summary = KnowledgeBank.summary(items, now)
        assertTrue(summary.contains("دانسته"))
        assertTrue(summary.contains("کهنه"))
    }
}
