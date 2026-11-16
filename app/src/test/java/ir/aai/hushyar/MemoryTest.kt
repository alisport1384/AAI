package ir.aai.hushyar

import ir.aai.hushyar.core.Text
import ir.aai.hushyar.domain.Interview
import ir.aai.hushyar.domain.MarketPack
import ir.aai.hushyar.domain.MemoryBank
import ir.aai.hushyar.domain.MemoryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryTest {

    private fun pack(): MarketPack = Interview.build(
        mapOf(
            "market_name" to "بازار حافظه",
            "attributes" to "حافظه، رم، گارانتی",
            "currency_unit" to "تومان"
        ),
        5_000L
    ).pack

    private fun item(id: String, text: String, at: Long = 1_000L, category: String = MemoryBank.CAT_GENERAL) = MemoryItem(
        id = id,
        kind = "note",
        text = text,
        createdAt = at,
        category = category,
        tags = MemoryBank.tags(text),
        importance = MemoryBank.importance(text, explicit = false, pack = null)
    )

    @Test
    fun categoriesAreAssignedByContent() {
        assertEquals(MemoryBank.CAT_BUDGET, MemoryBank.classify("سقف بودجه‌ام ۲۰ میلیون تومان است"))
        assertEquals(MemoryBank.CAT_RULE, MemoryBank.classify("از این به بعد هرگز بدون گارانتی خرید نکن"))
        assertEquals(MemoryBank.CAT_PREFERENCE, MemoryBank.classify("ترجیح می‌دهم گوشی سبک باشد"))
        assertEquals(MemoryBank.CAT_PRODUCT, MemoryBank.classify("گوشی سامسونگ مدل S24"))
        assertEquals(MemoryBank.CAT_PLACE, MemoryBank.classify("ارسال به شهر مشهد"))
    }

    @Test
    fun rulesAreMoreImportantThanChatter() {
        val rule = MemoryBank.importance("همیشه گارانتی رسمی بخواه", explicit = true, pack = null)
        val chatter = MemoryBank.importance("یک جملهٔ معمولی دربارهٔ هوا", explicit = false, pack = null)
        assertTrue(rule > chatter)
        assertTrue(rule <= 1.0)
    }

    @Test
    fun duplicateDetectionCatchesRephrasedNote() {
        val existing = listOf(item("m1", "سقف بودجه‌ام ۲۰ میلیون تومان است"))
        val twin = MemoryBank.duplicateOf(item("m2", "سقف بودجه‌ام ۲۰ میلیون تومان است."), existing)
        assertEquals("m1", twin?.id)
    }

    @Test
    fun differentNotesAreNotDuplicates() {
        val existing = listOf(item("m1", "سقف بودجه‌ام ۲۰ میلیون تومان است"))
        assertNull(MemoryBank.duplicateOf(item("m2", "من در شهر شیراز زندگی می‌کنم و ارسال سریع می‌خواهم"), existing))
    }

    @Test
    fun consolidationMergesTwinsAndKeepsTheStrongerOne() {
        val items = listOf(
            item("m1", "همیشه گارانتی رسمی بخواه", 1_000L, MemoryBank.CAT_RULE).copy(importance = 0.9, useCount = 2),
            item("m2", "همیشه گارانتی رسمی بخواه", 2_000L, MemoryBank.CAT_RULE).copy(importance = 0.5, useCount = 1),
            item("m3", "شهر من مشهد است", 3_000L, MemoryBank.CAT_PLACE)
        )
        val result = MemoryBank.consolidate(items)
        assertEquals(2, result.kept.size)
        assertEquals(1, result.droppedIds.size)
        val rule = result.kept.first { it.category == MemoryBank.CAT_RULE }
        assertEquals(0.9, rule.importance, 0.0001)
        assertEquals(3, rule.useCount)
    }

    @Test
    fun rankingPrefersExactTopicAndIgnoresUnrelatedRows() {
        val now = 10_000_000L
        val items = listOf(
            item("m1", "قیمت گوشی سامسونگ در بازار امروز بالا رفت", now - 1_000).copy(importance = 0.6),
            item("m2", "من عاشق چای سبز هستم", now - 1_000),
            item("m3", "سقف بودجه‌ام برای گوشی ۲۰ میلیون تومان است", now - 86_400_000L * 10).copy(importance = 0.8)
        )
        val hits = MemoryBank.rank("قیمت گوشی سامسونگ چقدر است؟", items, now, 2)
        assertTrue(hits.isNotEmpty())
        assertEquals("m1", hits.first().item.id)
        assertTrue(hits.none { it.item.id == "m2" })
    }

    @Test
    fun summaryUsesPersianDigitsAndCategories() {
        val items = listOf(item("m1", "قیمت گوشی مهم است"), item("m2", "شهر من مشهد است"))
        val summary = MemoryBank.summary(items)
        assertTrue(summary.contains("یادسپاری"))
        assertTrue(summary.contains("۲"))
    }

    @Test
    fun learnableFromKeepsDurablePreferenceOnly() {
        val pack = pack()
        val preference = ir.aai.hushyar.domain.Answer(
            intent = "price.check",
            text = "پاسخ",
            confidence = 0.8,
            refused = false,
            citations = listOf(ir.aai.hushyar.domain.Citation("W1", "example.com", "https://example.com/x"))
        )
        val learned = MemoryBank.learnableFrom("من ترجیح می‌دهم گوشی سبک باشد", preference, pack)
        assertTrue(learned.any { it.first == "preference" })
    }

    @Test
    fun emptyAndNumericInputsAreHandledWithoutCrash() {
        assertEquals(MemoryBank.CAT_GENERAL, MemoryBank.classify(""))
        assertTrue(MemoryBank.rank("", emptyList(), 1L).isEmpty())
        assertTrue(MemoryBank.tags("۱۲۳ ۴۵۶").isNotEmpty())
        assertNotEquals(MemoryBank.CAT_GENERAL, MemoryBank.classify("قیمت ۱۲ میلیون تومان"))
        assertEquals("متن", MemoryBank.normalizeText("  متن  "))
        assertTrue(Text.normalize("قیمت").isNotBlank())
    }
}
