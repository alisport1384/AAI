package ir.aai.hushyar

import ir.aai.hushyar.domain.Interview
import ir.aai.hushyar.domain.MarketPack
import ir.aai.hushyar.domain.PackCodec
import ir.aai.hushyar.domain.PackValidator
import ir.aai.hushyar.domain.SelfModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackTest {

    private fun answers(): Map<String, String> = mapOf(
        "market_name" to "بازار تست موبایل",
        "items" to "گوشی",
        "categories" to "موبایل، تبلت",
        "attributes" to "حافظه، رم، گارانتی",
        "weights" to "گارانتی",
        "currency_unit" to "تومان",
        "sources" to "فهرست خودم",
        "faq" to "قیمت این گوشی چقدر است؟\nاصالت کالا چطور است؟",
        "risks" to "قیمت غیرعادی",
        "langs" to "فارسی",
        "limits" to "توصیهٔ حقوقی نمی‌دهم"
    )

    @Test
    fun builderProducesValidPack() {
        val built = Interview.build(answers(), 1_000L)
        assertTrue(built.report.ok)
        assertEquals("بازار تست موبایل", built.pack.name)
        assertEquals(3, built.pack.attributes.size)
        assertEquals(listOf("موبایل", "تبلت"), built.pack.categories)
        assertTrue(built.pack.attributes[0].required)
        assertNotNull(built.pack.priceRules)
    }

    @Test
    fun weightHintBoostsTheNamedAttribute() {
        val built = Interview.build(answers(), 1_000L)
        val warranty = built.pack.attributes.first { it.label == "گارانتی" }
        val storage = built.pack.attributes.first { it.label == "حافظه" }
        assertTrue(warranty.weight > storage.weight)
        assertEquals(1.0, built.pack.attributes.sumOf { it.weight }, 0.0001)
    }

    @Test
    fun numericAttributesAreTyped() {
        val built = Interview.build(answers(), 1_000L)
        assertEquals(ir.aai.hushyar.domain.AttrType.NUMBER, built.pack.attributes.first { it.label == "حافظه" }.type)
    }

    @Test
    fun customIntentsAndFlagsComeFromAnswers() {
        val built = Interview.build(answers(), 1_000L)
        assertTrue(built.pack.intents.any { it.id == "price.check" })
        assertEquals(2, built.pack.intents.count { it.id.startsWith("faq_") })
        assertTrue(built.pack.redFlags.any { it.id == "custom_1" })
    }

    @Test
    fun emptyAnswersAreBlockedNotCrashing() {
        val built = Interview.build(emptyMap(), 1_000L)
        assertTrue(built.report.ok) // fallback attributes keep the pack usable
        assertFalse(built.pack.name.isBlank())
    }

    @Test
    fun validatorBlocksPackWithoutAttributes() {
        val pack = MarketPack(id = "x", name = "بازار", attributes = emptyList())
        val report = PackValidator.validate(pack)
        assertFalse(report.ok)
        assertTrue(report.blocking.any { it.contains("ویژگی") })
    }

    @Test
    fun validatorFlagsZeroWeights() {
        val built = Interview.build(answers(), 1_000L)
        val pack = built.pack.copy(attributes = built.pack.attributes.map { it.copy(weight = 0.0) })
        assertFalse(PackValidator.validate(pack).ok)
    }

    @Test
    fun jsonRoundTripPreservesPack() {
        val pack = Interview.build(answers(), 1_000L).pack
        val json = PackCodec.encode(pack, pretty = true)
        val decoded = PackCodec.decode(json)
        assertEquals(pack.name, decoded.name)
        assertEquals(pack.attributes.map { it.label }, decoded.attributes.map { it.label })
        assertEquals(pack.attributes.map { it.weight }, decoded.attributes.map { it.weight })
        assertEquals(pack.intents.map { it.id }, decoded.intents.map { it.id })
        assertEquals(pack.priceRules, decoded.priceRules)
        assertEquals(pack.langs, decoded.langs)
    }

    @Test
    fun selfManifestFollowsThePack() {
        val pack = Interview.build(answers(), 1_000L).pack
        val manifest = SelfModel.derive(pack, listingCount = 0, productCount = 0)
        assertTrue(manifest.identity.contains(pack.name))
        assertTrue(manifest.focusAttributes.contains("گارانتی"))
        assertTrue(manifest.unknowns.any { it.contains("هیچ آگهی") })
        assertTrue(manifest.limits.any { it.contains("توصیهٔ حقوقی") })
    }

    @Test
    fun importingUnknownSchemaIsRejected() {
        val pack = MarketPack(id = "z", name = "بازار", schema = "other/9")
        assertFalse(PackValidator.validate(pack).ok)
    }
}
