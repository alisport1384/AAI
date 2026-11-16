package ir.aai.hushyar

import ir.aai.hushyar.core.Text
import ir.aai.hushyar.domain.AnswerDraft
import ir.aai.hushyar.domain.Catalog
import ir.aai.hushyar.domain.Critic
import ir.aai.hushyar.domain.Fact
import ir.aai.hushyar.domain.Interview
import ir.aai.hushyar.domain.Listing
import ir.aai.hushyar.domain.MarketPack
import ir.aai.hushyar.domain.QaEngine
import ir.aai.hushyar.domain.Resolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QaTest {

    private fun pack(): MarketPack = Interview.build(
        mapOf(
            "market_name" to "بازار تست",
            "attributes" to "حافظه، رم، گارانتی",
            "currency_unit" to "تومان"
        ),
        1_000L
    ).pack

    private fun listing(id: String, title: String, price: Long?, at: Long): Listing = Listing(
        id = id,
        title = title,
        titleNorm = Text.normalize(title),
        priceRaw = price?.toString() ?: "",
        priceIrt = price,
        priceNote = "مستقیم",
        source = "تست",
        attrs = mapOf("attr_1" to "256", "attr_2" to "8", "attr_3" to "شرکتی"),
        createdAt = at
    )

    private fun catalog(pack: MarketPack, listings: List<Listing>): Catalog =
        Catalog(pack, listings, Resolver.group(listings, pack.priceRules))

    private val samsung = "گوشی سامسونگ گلکسی S24 حافظه 256 رم 8"
    private val iphone = "آیفون 13 اپل حافظه 128"

    @Test
    fun priceQuestionIsAnsweredWithCitations() {
        val p = pack()
        val cat = catalog(
            p,
            listOf(
                listing("l1", samsung, 50_000_000, 1_000),
                listing("l2", "$samsung گارانتی", 55_000_000, 2_000)
            )
        )
        val answer = QaEngine().ask("قیمت گوشی سامسونگ گلکسی s24 چقدر است؟", cat)
        assertEquals("price.check", answer.intent)
        assertFalse(answer.refused)
        assertTrue(answer.citations.isNotEmpty())
        assertTrue(answer.bullets.any { it.contains("میانه") })
        assertTrue(answer.confidence > 0.5)
    }

    @Test
    fun unknownProductIsRefusedInsteadOfGuessed() {
        val p = pack()
        val cat = catalog(p, listOf(listing("l1", samsung, 50_000_000, 1_000)))
        val answer = QaEngine().ask("قیمت پراید ۱۳۱ چقدر است؟", cat)
        assertTrue(answer.refused)
        assertTrue(answer.missing.isNotEmpty())
        assertTrue(answer.citations.isEmpty())
        assertEquals(0.0, answer.confidence, 0.0001)
    }

    @Test
    fun emptyCatalogRefusesEverything() {
        val p = pack()
        val answer = QaEngine().ask("قیمت گوشی سامسونگ گلکسی s24 چقدر است؟", catalog(p, emptyList()))
        assertTrue(answer.refused)
    }

    @Test
    fun comparisonNeedsTwoKnownProducts() {
        val p = pack()
        val cat = catalog(
            p,
            listOf(
                listing("l1", samsung, 50_000_000, 1_000),
                listing("l2", iphone, 40_000_000, 2_000)
            )
        )
        val ok = QaEngine().ask("گوشی سامسونگ گلکسی s24 را با آیفون 13 مقایسه کن", cat)
        assertEquals("compare", ok.intent)
        assertFalse(ok.refused)
        assertTrue(ok.bullets.isNotEmpty())

        val notEnough = QaEngine().ask("گوشی سامسونگ گلکسی s24 را مقایسه کن", cat)
        assertTrue(notEnough.refused)
    }

    @Test
    fun historyNeedsAtLeastTwoPoints() {
        val p = pack()
        val single = catalog(p, listOf(listing("l1", samsung, 50_000_000, 1_000)))
        assertTrue(QaEngine().ask("روند گوشی سامسونگ گلکسی s24 چطور بوده؟", single).refused)

        val double = catalog(
            p,
            listOf(
                listing("l1", samsung, 50_000_000, 1_000),
                listing("l2", samsung, 55_000_000, 2_000)
            )
        )
        val answer = QaEngine().ask("روند گوشی سامسونگ گلکسی s24 چطور بوده؟", double)
        assertEquals("price.history", answer.intent)
        assertFalse(answer.refused)
        assertTrue(answer.citations.size >= 2)
    }

    @Test
    fun specsComeFromStoredAttributes() {
        val p = pack()
        val cat = catalog(p, listOf(listing("l1", samsung, 50_000_000, 1_000)))
        val answer = QaEngine().ask("مشخصات گوشی سامسونگ گلکسی s24 چیست؟", cat)
        assertEquals("spec.lookup", answer.intent)
        assertTrue(answer.bullets.any { it.contains("حافظه") })
    }

    @Test
    fun criticDropsNumbersWithoutEvidence() {
        val draft = AnswerDraft(
            intent = "price.check",
            headline = "قیمت:",
            facts = listOf(Fact("قیمت", "۱۲٬۰۰۰٬۰۰۰")),
            citations = emptyList(),
            missing = emptyList(),
            confidence = 0.8,
            refused = false,
            lang = "fa"
        )
        val stripped = Critic.stripUnsupported(draft)
        assertTrue(stripped.refused)
        assertTrue(stripped.facts.isEmpty())
    }

    @Test
    fun criticKeepsSupportedNumbers() {
        val draft = AnswerDraft(
            intent = "price.check",
            headline = "قیمت:",
            facts = listOf(Fact("قیمت", "۱۲٬۰۰۰٬۰۰۰")),
            citations = listOf(ir.aai.hushyar.domain.Citation("L:1", "تست")),
            missing = emptyList(),
            confidence = 0.8,
            refused = false,
            lang = "fa"
        )
        val kept = Critic.stripUnsupported(draft)
        assertFalse(kept.refused)
        assertEquals(1, kept.facts.size)
    }
}
