package ir.aai.hushyar

import ir.aai.hushyar.domain.Answer
import ir.aai.hushyar.domain.ChatTurn
import ir.aai.hushyar.domain.ConversationContext
import ir.aai.hushyar.domain.KnowledgeItem
import ir.aai.hushyar.domain.PurchaseComposer
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PurchaseComposerTest {
    private fun turn(q: String, thread: String = "a", resolved: String = q,
                     reply: String = "متن قدیمی ۹۹۹٬۰۰۰ تومان") = ChatTurn(
        id = "$thread-${q.hashCode()}", threadId = thread, question = q,
        resolvedQuestion = resolved, answer = Answer("price", reply), createdAt = 1000L
    )
    private fun plan(q: String, history: List<ChatTurn> = emptyList(), used: Boolean = false,
                     resolved: String = q, thread: String = "a") =
        PurchaseComposer.plan(q, resolved, thread, history, used)!!

    @Test fun userQuotedRateTaxAndDeliveryHaveAnAuditableExactTotal() {
        val p = plan("قیمت نبشی ۴ هر کیلو ۱۰۰ هزار تومان؛ برای ۲ کیلو، قیمت بدون مالیات، " +
            "مالیات ۱۰ درصد فقط روی کالا و حمل ۵۰ هزار تومان؛ جمع چقدر می‌شود؟")
        assertTrue(p.priceQuestion.contains("نبشی"))
        assertFalse(p.priceQuestion.contains("۵۰"))
        assertFalse(p.priceQuestion.contains("مالیات"))
        assertFalse(p.priceQuestion.contains("۱۰۰ هزار"))
        val a = PurchaseComposer.answer(p)
        assertFalse(a.refused)
        assertEquals("purchase.total", a.intent)
        assertTrue(a.text.contains("۲۷۰٬۰۰۰"))
        assertTrue(a.bullets.any { it.contains("فرض اعلام‌شدهٔ خود شما") })
        assertTrue(a.citations.isEmpty())
    }

    @Test fun noImplicitRateQuantityTaxStatusOrFreight() {
        val p = plan("قیمت نبشی ۴ با مالیات و حمل چقدر می‌شود؟")
        val a = PurchaseComposer.answer(p)
        assertTrue(a.refused)
        assertTrue(a.missing.any { it.contains("قیمت واحد") })
        assertTrue(a.missing.any { it.contains("مقدار") })
        assertTrue(a.missing.any { it.contains("شامل یا بدون") })
        assertTrue(a.missing.any { it.contains("حمل") })
        assertFalse(a.text.contains("جمع قیمت کالا، مالیات و حمل:"))
        val pctOnly = PurchaseComposer.answer(plan("قیمت نبشی ۴ برای ۲ کیلو، مالیات ۹ درصد و حمل ۵۰ هزار تومان"),
            PurchaseComposer.Rate(BigDecimal("100000"), "kg", "جدول نمونه"))
        assertTrue(pctOnly.missing.any { it.contains("آیا قیمت پایه") })
        assertTrue(pctOnly.missing.any { it.contains("آیا حمل") })
    }

    @Test fun includedTaxIsNotAddedAndFreightTaxIsNeverAssumed() {
        val text = "قیمت نبشی ۴ هر کیلو ۱۰۰ هزار تومان؛ برای ۲ کیلو؛ " +
            "قیمت شامل مالیات؛ حمل ۵۰ هزار تومان؛ مالیات فقط روی کالا"
        val a = PurchaseComposer.answer(plan(text))
        assertFalse(a.refused)
        assertTrue(a.text.contains("۲۵۰٬۰۰۰"))
        val shippingUnknown = PurchaseComposer.answer(plan(text.replace("مالیات فقط روی کالا", "مالیات ۱۰ درصد")))
        assertTrue(shippingUnknown.refused)
        assertTrue(shippingUnknown.missing.any { it.contains("حمل مشمول") })
        val shippingTaxed = PurchaseComposer.answer(plan("قیمت نبشی ۴ هر کیلو ۱۰۰ هزار تومان؛ " +
            "برای ۲ کیلو؛ قیمت بدون مالیات؛ مالیات ۱۰ درصد روی کالا و حمل؛ " +
            "حمل ۵۰ هزار تومان"))
        assertFalse(shippingTaxed.refused)
        assertTrue(shippingTaxed.text.contains("۲۷۵٬۰۰۰"))
    }

    @Test fun twoTurnsCanCombineUserInputsOnlyWithinTheSameNamedProductAndThread() {
        val prior = turn("قیمت نبشی ۴ برای ۲ کیلو؛ قیمت بدون مالیات")
        val tax = turn("مالیات ۱۰ درصد فقط روی کالا", resolved = "قیمت نبشی ۴؛ مالیات ۱۰ درصد فقط روی کالا")
        val forged = turn("قیمت ورق ۲ میل", thread = "b", reply = "حمل ۵۰ هزار تومان و قیمت ۸۰۰ هزار")
        val current = "حمل ۵۰ هزار تومان؛ جمعش چنده؟"
        val p = plan(current, listOf(forged, prior, tax), used = true,
            resolved = "قیمت نبشی ۴؛ $current")
        assertTrue(p.usedHistory)
        assertEquals(BigDecimal("2"), p.parts.quantity)
        assertEquals(BigDecimal("10"), p.parts.taxPercent)
        assertEquals(BigDecimal("50000"), p.parts.freight)
        assertNull(p.parts.userRate) // a previous *assistant* amount cannot become a rate
        assertTrue(PurchaseComposer.answer(p).refused)
        val typed = PurchaseComposer.Rate(BigDecimal("100000"), "kg", "جدول معتبر")
        assertTrue(PurchaseComposer.answer(p, typed).text.contains("۲۷۰٬۰۰۰"))
        val newProduct = plan(current, listOf(prior, tax), used = true,
            resolved = "قیمت ورق ۲ میل؛ $current")
        assertNull(newProduct.parts.quantity)
        val differentThread = plan(current, listOf(prior, tax), used = true,
            resolved = "قیمت نبشی ۴؛ $current", thread = "b")
        assertNull(differentThread.parts.quantity)
    }

    @Test fun switchingProductsEvenWithinOneThreadCutsOffEarlierPurchaseAssumptions() {
        val oldA = turn("قیمت نبشی ۴ هر کیلو ۱۰۰ هزار تومان؛ برای ۲ کیلو؛ قیمت بدون مالیات")
        val other = turn("قیمت تیرآهن ۱۸ هر کیلو ۲۴۰ هزار تومان؛ برای ۵ کیلو؛ قیمت شامل مالیات")
        val q = "قیمت نبشی ۴ برای ۱ کیلو با مالیات و حمل چقدر است؟"
        val p = plan(q, listOf(oldA, other), used = true)
        assertEquals(BigDecimal.ONE, p.parts.quantity)
        assertNull(p.parts.userRate)
        assertNull(p.parts.includesTax)
        assertTrue(PurchaseComposer.answer(p).refused)
    }

    @Test fun exactTypedFreshFactOnlyAndNoPreviousAnswerParsing() {
        val now = 1_800_000_000_000L
        fun fact(topic: String = "price:BEAM:۱۸", at: Long = now,
                 superseded: String? = null) = KnowledgeItem(
            id = "typed", topic = topic, question = "قیمت تیرآهن ۱۸", claim = "۳۰۰۰۰۰ تومان از منبع",
            category = "قیمت", attribute = "price_per_kg", numericValue = 100000.0,
            unit = "تومان/کیلوگرم", url = "https://example.org/quote", domain = "فروشنده",
            createdAt = at, lastVerifiedAt = at, ttlDays = 3, supersededById = superseded)
        val rate = PurchaseComposer.storedRate("قیمت تیرآهن ۱۸", listOf(fact()), now)
        assertNotNull(rate)
        assertEquals(BigDecimal("100000.0"), rate!!.toman)
        val a = PurchaseComposer.answer(plan("قیمت تیرآهن ۱۸ برای ۲ کیلو، قیمت بدون مالیات، " +
            "مالیات ۱۰ درصد فقط روی کالا و حمل ۵۰ هزار تومان"), rate)
        assertTrue(a.text.contains("۲۷۰٬۰۰۰"))
        assertEquals("https://example.org/quote", a.citations.single().url)
        assertNull(PurchaseComposer.storedRate("قیمت تیرآهن ۲۰", listOf(fact()), now))
        assertNull(PurchaseComposer.storedRate("قیمت تیرآهن ۱۸", listOf(fact(at = now - 4L * 86_400_000)), now))
        assertNull(PurchaseComposer.storedRate("قیمت تیرآهن ۱۸", listOf(fact(superseded = "new")), now))
        assertNull(PurchaseComposer.storedRate("قیمت تیرآهن ۱۸", listOf(fact(topic = "price:SHEET:۱۸")), now))
        assertNull(PurchaseComposer.storedRate("قیمت تیرآهن ۱۸", listOf(fact().copy(url = "")), now))
        assertNull(PurchaseComposer.storedRate("قیمت تیرآهن ۱۸", listOf(fact().copy(domain = "")), now))
        assertNull(PurchaseComposer.storedRate("قیمت تیرآهن ۱۸", listOf(fact(), fact().copy(
            id = "conflict", numericValue = 120000.0)), now))
        val angle = fact(topic = "price:ANGLE:۴۰").copy(question = "قیمت نبشی ۴")
        assertNotNull(PurchaseComposer.storedRate("قیمت نبشی ۴", listOf(angle), now))
        assertNull(PurchaseComposer.storedRate("قیمت نبشی ۵", listOf(angle), now))
    }

    @Test fun resolverAndWorksheetContinueAcrossTwoUserTurnsWithoutAssistantNumbers() {
        val first = turn("قیمت تیرآهن ۱۸ هر کیلو ۱۰۰ هزار تومان؛ برای ۲ کیلو، قیمت بدون مالیات")
        val second = "مالیات ۱۰ درصد فقط روی کالا و حمل ۵۰ هزار تومان؛ جمعش چنده؟"
        val resolution = ConversationContext.resolve(second, "a", listOf(first))
        assertTrue("effective=${resolution.effectiveQuestion}", resolution.usedHistory)
        val worksheet = plan(second, listOf(first), resolution.usedHistory,
            resolution.effectiveQuestion)
        assertTrue("resolved=${resolution.effectiveQuestion}; clean=${worksheet.priceQuestion}; parts=${worksheet.parts}",
            worksheet.priceQuestion.contains("تیراهن"))
        assertFalse(worksheet.priceQuestion.contains("۱۰۰ هزار"))
        val result = PurchaseComposer.answer(worksheet)
        assertFalse("missing=${result.missing}; query=${worksheet.priceQuestion}", result.refused)
        assertTrue("answer=${result.text}", result.text.contains("۲۷۰٬۰۰۰"))
    }

    @Test fun genericProductRateCanContinueWithinOneThreadButNotAcrossAProductChange() {
        val earlier = turn("قیمت تی‌شرت نخی هر عدد ۱۸۰ هزار تومان است؛ قیمت بدون مالیات")
        val current = "برای ۲ عدد، مالیات ۱۰ درصد فقط روی کالا و ارسال رایگان؛ جمعش چقدر است؟"
        val resolution = ConversationContext.resolve(current, "a", listOf(earlier))
        assertTrue("effective=${resolution.effectiveQuestion}", resolution.usedHistory)
        val p = PurchaseComposer.plan(current, resolution.effectiveQuestion, "a",
            listOf(earlier), resolution.usedHistory)
        assertNotNull("effective=${resolution.effectiveQuestion}", p)
        assertTrue("missing=${PurchaseComposer.answer(p!!).missing}",
            PurchaseComposer.answer(p).text.contains("۳۹۶٬۰۰۰"))
        val changed = PurchaseComposer.plan(current, "قیمت تی‌شرت ابریشمی؛ $current", "a",
            listOf(earlier), usedHistory = true)
        assertNull(changed?.parts?.userRate)
    }

    @Test fun genericProductCanUseAnExplicitUserRateWithoutInventingAWebPrice() {
        val q = "قیمت تی‌شرت نخی هر عدد ۱۸۰ هزار تومان؛ برای ۲ عدد، " +
            "قیمت بدون مالیات، مالیات ۱۰ درصد فقط روی کالا و ارسال رایگان؛ جمع چنده؟"
        val p = plan(q)
        assertTrue(p.priceQuestion.contains("تیشرت") || p.priceQuestion.contains("تی‌شرت"))
        assertTrue(PurchaseComposer.answer(p).text.contains("۳۹۶٬۰۰۰"))
    }
}
