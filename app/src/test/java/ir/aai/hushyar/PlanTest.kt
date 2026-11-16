package ir.aai.hushyar

import ir.aai.hushyar.domain.LayerStatus
import ir.aai.hushyar.domain.Listing
import ir.aai.hushyar.domain.MarketPack
import ir.aai.hushyar.domain.AttributeSpec
import ir.aai.hushyar.domain.PlanBuilder
import ir.aai.hushyar.domain.ProductGroup
import ir.aai.hushyar.domain.ResearchPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.5, first request: the assistant plans the whole body first and then layers details.
 * These tests pin the *order* and the *dependency lock* — the two things that make "coarse first"
 * more than a slogan.
 */
class PlanTest {

    private val pack = MarketPack(
        id = "p-test",
        name = "لپ‌تاپ",
        attributes = listOf(AttributeSpec("cpu", "پردازنده"), AttributeSpec("ram", "رم"))
    )

    private fun product(name: String) = ProductGroup(
        id = "g-$name",
        canonicalName = name,
        listings = listOf(
            Listing(
                id = "l-$name",
                title = name,
                titleNorm = name,
                priceRaw = "30000000",
                priceIrt = 30_000_000,
                priceNote = "",
                source = "test",
                createdAt = 0L
            )
        ),
        attrs = emptyMap()
    )

    private val products = listOf(product("لنوو"), product("ایسوس"), product("ایسر"))

    private fun plan(questions: List<String> = listOf("رم لنوو چند گیگ است و برای کار روزمره کافی است")): ResearchPlan =
        PlanBuilder.build(pack, products, questions, "fa", now = 1_700_000_000_000L)

    @Test
    fun layersGoFromTheBigBodyToTheFinestDetail() {
        val p = plan()
        val depths = p.layers.map { it.depth }
        assertEquals(depths.sorted(), depths)
        assertEquals("market", p.layers.first().id)
        assertTrue(p.layers.any { it.id == "attributes" })
        assertTrue(p.layers.any { it.id == "comparison" })
    }

    @Test
    fun theProductLayerCannotStartBeforeTheMarketLayerIsDone() {
        val p = plan()
        val nothingDone = PlanBuilder.emptyProgress(p)
        assertEquals("market", p.next(nothingDone)?.id)

        // Market done → the products layer unlocks, the deeper ones still do not.
        val marketDone = nothingDone + ("market" to LayerStatus.DONE)
        assertEquals("products", p.next(marketDone)?.id)
        assertTrue(p.unlocked(marketDone).none { it.id == "attributes" })
    }

    @Test
    fun aPartialLayerStaysTheNextThingToDo() {
        val p = plan()
        val progress = PlanBuilder.emptyProgress(p) + ("market" to LayerStatus.PARTIAL)
        assertEquals("market", p.next(progress)?.id)
        assertEquals(0, p.done(progress))
    }

    @Test
    fun finishingEveryLayerFinishesThePlan() {
        val p = plan()
        val all = p.layers.associate { it.id to LayerStatus.DONE }
        assertNull(p.next(all))
        assertEquals(1.0, p.ratio(all), 1e-9)
    }

    @Test
    fun ratioCountsDoneLayersOnly() {
        val p = plan()
        val half = p.layers.take(p.layers.size / 2).associate { it.id to LayerStatus.DONE } +
            p.layers.drop(p.layers.size / 2).associate { it.id to LayerStatus.PENDING }
        assertEquals(p.done(half).toDouble() / p.layers.size, p.ratio(half), 1e-9)
    }

    @Test
    fun questionsTheUserAskedBecomeTheirOwnDossierLayer() {
        val p = plan()
        val dossier = p.layer("dossier")
        assertNotNull(dossier)
        assertEquals(1, dossier!!.queries.size)
        assertTrue(dossier.queries.first().contains("رم لنوو"))
    }

    @Test
    fun aPlanWithoutProductsIsSmallButNotEmpty() {
        val p = PlanBuilder.build(pack, emptyList(), emptyList(), "fa", 1L)
        assertEquals(listOf("market"), p.layers.map { it.id })
        assertTrue(p.layers.first().queries.isNotEmpty())
    }

    @Test
    fun describeReportsHonestStatePerLayer() {
        val p = plan()
        val progress = PlanBuilder.emptyProgress(p) + ("market" to LayerStatus.DONE)
        val lines = p.describe(progress)
        assertEquals(p.layers.size, lines.size)
        assertTrue(lines.first().contains("انجام شد"))
        assertTrue(lines.drop(1).any { it.contains("در نوبت") })
    }
}
