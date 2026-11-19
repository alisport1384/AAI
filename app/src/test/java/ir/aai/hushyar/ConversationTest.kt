package ir.aai.hushyar

import ir.aai.hushyar.domain.Answer
import ir.aai.hushyar.domain.AskType
import ir.aai.hushyar.domain.ChatAnswerCodec
import ir.aai.hushyar.domain.ChatTurn
import ir.aai.hushyar.domain.Citation
import ir.aai.hushyar.domain.ConversationContext
import ir.aai.hushyar.domain.Grounding
import ir.aai.hushyar.domain.LocalGeneration
import ir.aai.hushyar.domain.LocalGenerator
import ir.aai.hushyar.domain.QuestionReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTest {
    private val source = Citation("P1", "جدول فروشنده", "https://example.org/price")
    private fun turn(
        thread: String = "a", text: String = "قیمت نبشی ۴ فابریک ۹ کیلو چنده؟",
        resolved: String = text, answer: Answer? = Answer(
            "material.price", "پاسخ قبلی؛ قیمت روز تأیید نشده", citations = listOf(source),
            bullets = listOf("محاسبه از سطر جدول"), confidence = 0.7
        )
    ) = ChatTurn("id-$thread-${text.hashCode()}", thread, text, resolvedQuestion = resolved, answer = answer,
        createdAt = 1000L, finishedAt = if (answer != null) 2000L else 0L)

    private class Rewriter(val response: String) : LocalGenerator {
        override val ready = true
        var prompt: String = ""
        override fun generate(prompt: String, maxTokens: Int): LocalGeneration {
            this.prompt = prompt
            return LocalGeneration(text = response)
        }
        override fun cancel() = Unit
    }

    @Test
    fun ellipticalPriceFollowUpCarriesSubjectAndNewWeightButNotOldWeight() {
        val result = ConversationContext.resolve("برای ۷/۵ کیلو چطور؟", "a", listOf(turn()))
        assertTrue(result.usedHistory)
        assertTrue(result.effectiveQuestion.contains("نبشی"))
        assertTrue("effective=${result.effectiveQuestion}; numbers=${Grounding.numbers(result.effectiveQuestion)}",
            Grounding.numbers(result.effectiveQuestion).contains("4"))
        assertTrue(result.effectiveQuestion.contains("۷/۵"))
        assertFalse(result.effectiveQuestion.contains("9 کیلو"))
        assertEquals(AskType.PRICE, QuestionReader.read(result.effectiveQuestion).ask)
    }

    @Test
    fun changingTheProductDoesNotCarryThePreviousProductOrSize() {
        val first = turn(text = "قیمت نبشی ۴ چند است؟")
        val result = ConversationContext.resolve("برای ورق ۲ میل چطور؟", "a", listOf(first))
        assertTrue(result.effectiveQuestion.contains("ورق"))
        assertFalse(result.effectiveQuestion.contains("نبشی"))
        assertFalse(result.effectiveQuestion.contains("۴"))
        assertEquals(AskType.PRICE, QuestionReader.read(result.effectiveQuestion).ask)
        val standalone = ConversationContext.resolve("قیمت ورق ۲ میل چنده؟", "a", listOf(first))
        assertFalse(standalone.usedHistory)
        assertEquals("قیمت ورق ۲ میل چنده؟", standalone.effectiveQuestion)
    }

    @Test
    fun nothingFromAnotherThreadBecomesAReferentEvenWithMixedInput() {
        val other = turn(thread = "b", text = "قیمت ورق ۲ میل چنده؟")
        val result = ConversationContext.resolve("قیمتش چنده؟", "a", listOf(other))
        assertEquals("conversation.clarify", result.directAnswer?.intent)
        assertFalse(result.effectiveQuestion.contains("ورق"))
        val own = turn(thread = "a", text = "قیمت تی‌شرت نخی چنده؟")
        val next = ConversationContext.resolve("قیمتش چنده؟", "a", listOf(other, own))
        assertTrue(next.effectiveQuestion.contains("تیشرت") || next.effectiveQuestion.contains("تی‌شرت"))
        assertFalse(next.effectiveQuestion.contains("ورق"))
    }

    @Test
    fun sourceFollowUpRepeatsOnlyThePreviousAnswerCitationsAndNeverRefetches() {
        val result = ConversationContext.resolve("منبعش کجاست؟", "a", listOf(turn()))
        assertTrue(result.usedHistory)
        assertEquals("conversation.source", result.directAnswer?.intent)
        assertEquals(listOf(source), result.directAnswer?.citations)
        assertFalse(result.directAnswer!!.refused)
        assertTrue(result.directAnswer!!.text.contains("دوباره بررسی نشده"))
        assertFalse(result.forceFresh)
    }

    @Test
    fun lackOfCitationsProducesNoImaginarySource() {
        val result = ConversationContext.resolve("منبعش کجاست؟", "a", listOf(turn(
            answer = Answer("chat/local", "فقط متن مدل", citations = emptyList())
        )))
        assertTrue(result.directAnswer!!.refused)
        assertTrue(result.directAnswer!!.citations.isEmpty())
    }

    @Test
    fun aFollowUpAfterSourceAnswerStillFindsTheLastRealSubject() {
        val first = turn()
        val provenance = ConversationContext.resolve("منبعش کجاست؟", "a", listOf(first))
        val second = turn(text = "منبعش کجاست؟", resolved = provenance.effectiveQuestion,
            answer = provenance.directAnswer)
        val next = ConversationContext.resolve("برای ۸ کیلو چطور؟", "a", listOf(first, second))
        assertTrue(next.effectiveQuestion.contains("نبشی"))
        assertTrue(next.effectiveQuestion.contains("۸"))
        assertEquals(AskType.PRICE, QuestionReader.read(next.effectiveQuestion).ask)
    }

    @Test
    fun updateFollowUpForcesFreshEvidenceAndKeepsTheExactSourceUrl() {
        val result = ConversationContext.resolve("الان به‌روزش کن", "a", listOf(turn()))
        assertTrue(result.forceFresh)
        assertTrue("effective=${result.effectiveQuestion}", result.effectiveQuestion.contains("نبشی"))
        assertTrue(result.effectiveQuestion.contains(source.url!!))
        assertTrue(result.effectiveQuestion.contains("قیمت"))
        val withoutSource = ConversationContext.resolve("الان به‌روزش کن", "a", listOf(
            turn(answer = Answer("material.price", "قبلاً پیدا نشد", refused = true))
        ))
        assertTrue(withoutSource.forceFresh)
        assertTrue(withoutSource.effectiveQuestion.startsWith("تحقیق کن دربارهٔ"))
    }

    @Test
    fun ambiguousOrUnresolvedPronounsAskInsteadOfGuessing() {
        assertEquals("conversation.clarify",
            ConversationContext.resolve("اون یکی چطور؟", "a", listOf(turn())).directAnswer?.intent)
        assertEquals("conversation.clarify",
            ConversationContext.resolve("قیمتش چنده؟", "a", emptyList()).directAnswer?.intent)
        assertEquals("conversation.clarify",
            ConversationContext.resolve("قیمتش چنده؟", "a", listOf(turn(answer = null))).directAnswer?.intent)
    }

    @Test
    fun clarificationCanBeCompletedWithAProductName() {
        val unresolved = ConversationContext.resolve("قیمتش چنده؟", "a", emptyList())
        val clarified = turn(text = "قیمتش چنده؟", answer = unresolved.directAnswer)
        val result = ConversationContext.resolve("نبشی ۴", "a", listOf(clarified))
        assertTrue(result.effectiveQuestion.contains("نبشی ۴"))
        assertEquals("effective=${result.effectiveQuestion}", AskType.PRICE, QuestionReader.read(result.effectiveQuestion).ask)
    }

    @Test
    fun modelMayRewriteOnlyWithOldQuestionNumbersNotOldAnswerNumbers() {
        val prior = turn(answer = Answer("material.price", "قیمت ۱۲۵۵۰۰ تومان بود", citations = listOf(source)))
        val invented = Rewriter("قیمت نبشی ۴ برای ۸ کیلو ۱۲۵۵۰۰ تومان است؟")
        val result = ConversationContext.resolve("برای ۸ کیلو چطور؟", "a", listOf(prior), invented)
        assertTrue(invented.prompt.contains("۱۲۵۵۰۰")) // context is available, but is NOT evidence
        assertFalse(result.effectiveQuestion.contains("۱۲۵۵۰۰"))
        assertTrue(result.note.contains("قاعده"))
        val safe = Rewriter("قیمت نبشی ۴ برای ۸ کیلو چقدر است؟")
        val accepted = ConversationContext.resolve("برای ۸ کیلو چطور؟", "a", listOf(prior), safe)
        assertEquals("قیمت نبشی ۴ برای ۸ کیلو چقدر است؟", accepted.effectiveQuestion)
        assertTrue(accepted.note.contains("مدل"))
    }

    @Test
    fun modelSeesOnlyTurnsFromTheSelectedThread() {
        val rewriter = Rewriter("قیمت ورق ۲ چند است؟")
        val own = turn(text = "قیمت نبشی ۴ چند است؟")
        val other = turn(thread = "b", text = "قیمت ورق ۲ چند است؟")
        val result = ConversationContext.resolve("قیمتش؟", "a", listOf(other, own), rewriter)
        assertFalse(rewriter.prompt.contains("ورق"))
        assertFalse(result.effectiveQuestion.contains("ورق"))
    }

    @Test
    fun comparisonCanJoinTwoExplicitEarlierSubjectsWithoutReusingTheirPrices() {
        val a = turn(text = "قیمت نبشی ۴ چنده؟", answer = Answer("price", "قیمت قدیمی ۱۲۵۵۰۰ تومان"))
        val b = turn(text = "قیمت نبشی ۵ چنده؟", answer = Answer("price", "قیمت قدیمی ۱۵۰۰۰۰ تومان"))
        val comparison = ConversationContext.resolve("کدومش به‌صرفه‌تره؟", "a", listOf(a, b))
        assertTrue(comparison.usedHistory)
        assertEquals(AskType.COMPARISON, QuestionReader.read(comparison.effectiveQuestion).ask)
        assertTrue(comparison.effectiveQuestion.contains("4"))
        assertTrue(comparison.effectiveQuestion.contains("5"))
        assertFalse(comparison.effectiveQuestion.contains("۱۲۵۵۰۰"))
        assertFalse(comparison.effectiveQuestion.contains("۱۵۰۰۰۰"))
        val other = ConversationContext.resolve("اون یکی چطور؟", "a", listOf(a, b))
        assertTrue(other.effectiveQuestion.contains("4"))
        assertFalse(other.effectiveQuestion.contains("5"))
    }

    @Test
    fun sameLinkFollowUpReopensTheOldUserSuppliedUrlRatherThanInventingOne() {
        val previous = turn(text = "قیمت نبشی ۴ از https://example.org/price چنده؟",
            answer = Answer("price", "صفحه قیمت نداشت", refused = true))
        val next = ConversationContext.resolve("قیمت همون لینک چنده؟", "a", listOf(previous))
        assertTrue(next.forceFresh)
        assertTrue(next.effectiveQuestion.contains("نبشی"))
        assertTrue(next.effectiveQuestion.contains("https://example.org/price"))
        val missingLink = ConversationContext.resolve("قیمت همون لینک چنده؟", "a", listOf(
            turn(answer = Answer("price", "منبعی نبود", refused = true, citations = emptyList()))
        ))
        assertTrue(missingLink.directAnswer!!.refused)
        assertTrue(missingLink.directAnswer!!.text.contains("لینکی"))
    }

    @Test
    fun askingTheWeightDoesNotTurnTheEarlierUserConstraintIntoEvidence() {
        val result = ConversationContext.resolve("وزنش چنده؟", "a", listOf(turn()))
        assertTrue(result.usedHistory)
        assertTrue(result.effectiveQuestion.startsWith("وزن نبشی 4"))
        assertFalse(result.effectiveQuestion.contains("9 کیلو"))
        assertFalse(result.effectiveQuestion.startsWith("قیمت"))
    }

    @Test
    fun compoundConditionKeepsTheOriginalProductAndNewNumbersWithoutOldAnswerAmounts() {
        val prior = turn(answer = Answer("price", "قیمت قبلی ۱۲۵۵۰۰ تومان", citations = listOf(source)))
        val now = "اگر ۸ کیلو باشد و دو شاخه بخواهم چقدر می‌شود؟"
        val result = ConversationContext.resolve(now, "a", listOf(prior))
        assertTrue(result.effectiveQuestion.contains("نبشی"))
        assertTrue(result.effectiveQuestion.contains("۸"))
        assertTrue(result.effectiveQuestion.contains("دو"))
        assertFalse(result.effectiveQuestion.contains("۱۲۵۵۰۰"))
        assertEquals(AskType.PRICE, QuestionReader.read(result.effectiveQuestion).ask)
    }

    @Test
    fun explicitRecallReadsOnlyThisThreadsPreviousUserQuestionEvenIfItFailed() {
        val other = turn(thread = "b", text = "قیمت ورق ۲؟")
        val own = turn(thread = "a", text = "قیمت نبشی ۴ چنده؟")
        val failed = turn(thread = "a", text = "برای دو کیلو چطور؟", answer = null)
        val recall = ConversationContext.resolve("سؤال قبلی من چه بود؟", "a", listOf(other, own, failed))
        assertEquals("conversation.recall", recall.directAnswer?.intent)
        assertTrue(recall.directAnswer!!.text.contains("برای دو کیلو چطور؟"))
        assertFalse(recall.directAnswer!!.text.contains("ورق"))
        assertFalse(recall.directAnswer!!.text.contains("۱۲۵۵۰۰"))
        val empty = ConversationContext.resolve("سؤال قبلی من چه بود؟", "a", listOf(other))
        assertTrue(empty.directAnswer!!.refused)
        assertFalse(empty.usedHistory)
    }

    @Test
    fun discussionAndFinanceReferencesKeepTheSubjectWithoutReplayingEarlierAmounts() {
        val first = turn(text = "قیمت نبشی ۴ برای ۲ کیلو چنده؟",
            answer = Answer("material.price", "نرخ قدیمی ۱۲۵۵۰۰ تومان", citations = listOf(source)))
        val finance = ConversationContext.resolve(
            "مالیات ۹ درصد و حمل ۵۰ هزار تومان را به قیمتش اضافه کن", "a", listOf(first))
        assertTrue(finance.effectiveQuestion.contains("نبشی"))
        assertTrue(finance.effectiveQuestion.contains("۹"))
        assertFalse(finance.effectiveQuestion.contains("۱۲۵۵۰۰"))
        val discussion = ConversationContext.resolve("درباره‌اش بیشتر توضیح بده", "a", listOf(first))
        assertTrue(discussion.usedHistory)
        assertTrue(discussion.effectiveQuestion.contains("نبشی"))
    }

    @Test
    fun codecRetainsWholeAnswerIncludingCitationsAndRefusal() {
        val answer = Answer("price", "پیدا نشد", bullets = listOf("دلیل ۱"), citations = listOf(source),
            confidence = 0.25, missing = listOf("واحد قیمت"), refused = true, lang = "fa",
            trace = listOf("گام"), actions = listOf("none"), learned = listOf("نکته"))
        assertEquals(answer, ChatAnswerCodec.decode(ChatAnswerCodec.encode(answer)))
    }
}
