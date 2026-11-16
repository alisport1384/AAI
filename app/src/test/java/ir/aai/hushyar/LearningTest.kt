package ir.aai.hushyar

import ir.aai.hushyar.domain.CaseStore
import ir.aai.hushyar.domain.DomainStat
import ir.aai.hushyar.domain.DomainTrust
import ir.aai.hushyar.domain.Evidence
import ir.aai.hushyar.domain.ExtractedPage
import ir.aai.hushyar.domain.PlayEntry
import ir.aai.hushyar.domain.Playbook
import ir.aai.hushyar.domain.QaCase
import ir.aai.hushyar.domain.QuestionReader
import ir.aai.hushyar.domain.ResearchBudget
import ir.aai.hushyar.domain.StudyDepth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.5, second request: learn from every answered question so the next similar one is faster —
 * *without* ever serving something stale, off-subject or of the wrong answer shape.
 *
 * Each test below is one of those guard rails. Speed is only acceptable when these hold.
 */
class LearningTest {

    private val now = 1_700_000_000_000L
    private val day = 86_400_000L

    private val priceQuestion = "قیمت لپ‌تاپ لنوو چقدر است"
    private val priceAnswer = "قیمت لپ‌تاپ لنوو ۳۰ میلیون تومان است"

    private val model = QuestionReader.read(priceQuestion, "fa")

    private fun evidence(url: String = "https://shop.example.org/lenovo") = Evidence(
        sentence = priceAnswer,
        page = ExtractedPage(url = url, title = "لنوو", text = priceAnswer),
        score = 0.8
    )

    private fun learned(): List<QaCase> =
        CaseStore.learn(emptyList(), model, priceQuestion, priceAnswer, evidence(), now)

    @Test
    fun anAnsweredQuestionCanBeReusedForTheSameQuestion() {
        val cases = learned()
        val hit = CaseStore.find(cases, model, priceQuestion, now + day, null)
        assertNotNull(hit)
        assertEquals(priceAnswer, hit!!.answer)
        assertEquals("shop.example.org", hit.domain)
    }

    @Test
    fun theSameQuestionIsNotDuplicatedInTheStore() {
        val once = learned()
        val twice = CaseStore.learn(once, model, priceQuestion, priceAnswer, evidence(), now + day / 2)
        assertEquals(1, twice.size)
        assertEquals(now + day / 2, twice.first().lastVerifiedAt)
    }

    @Test
    fun aStaleCaseIsNeverReused() {
        val cases = learned()
        val late = now + (CaseStore.DEFAULT_TTL_DAYS + 1) * day
        assertNull(CaseStore.find(cases, model, priceQuestion, late, null))
        assertEquals(1, CaseStore.stale(cases, late).size)
    }

    @Test
    fun aDifferentAnswerShapeIsNeverReused() {
        // Same subject, same topic — but a "when" question must not be answered by a price case.
        val dateModel = QuestionReader.read("قیمت لپ‌تاپ لنوو کِی اعلام شد", "fa")
        assertTrue(dateModel.ask != model.ask || dateModel.subject != model.subject)
        val cases = learned()
        val other = QuestionReader.read("تاریخ عرضه لپ‌تاپ لنوو چه زمانی بود", "fa")
        assertNull(CaseStore.find(cases, other, "تاریخ عرضه لپ‌تاپ لنوو چه زمانی بود", now + day, null))
    }

    @Test
    fun aDifferentSubjectIsNeverReused() {
        val cases = learned()
        val other = "قیمت لپ‌تاپ ایسوس چقدر است"
        assertNull(CaseStore.find(cases, QuestionReader.read(other, "fa"), other, now + day, null))
    }

    @Test
    fun touchingACaseExtendsItsLifeAndItsHits() {
        val cases = learned()
        val id = cases.first().id
        val touched = CaseStore.touch(cases, id, now + 10 * day)
        val hit = CaseStore.find(touched, model, priceQuestion, now + 20 * day, null)
        assertNotNull(hit)
        assertEquals(2, hit!!.hits)
    }

    @Test
    fun aCaseWithoutEvidenceIsNotStored() {
        assertEquals(0, CaseStore.learn(emptyList(), model, priceQuestion, "  ", null, now).size)
    }

    @Test
    fun summaryIsHonestAboutEmptiness() {
        assertTrue(CaseStore.summary(emptyList(), now).contains("پرونده‌ای"))
        assertTrue(CaseStore.summary(learned(), now).contains("۱"))
    }

    // ---------- playbook: start the next attempt with the query that worked ----------

    @Test
    fun provenQueriesComeFirstAndFailedOnesLast() {
        val topic = "لپ تاپ"
        var entries = Playbook.record(emptyList(), topic, "قیمت لپ تاپ لنوو", won = true, now = now)
        entries = Playbook.record(entries, topic, "لپ تاپ لنوو مشخصات", won = false, now = now)
        val order = Playbook.order(topic, listOf("لپ تاپ لنوو مشخصات", "قیمت لپ تاپ لنوو", "لپ تاپ لنوو نظرات"), entries)
        assertEquals("قیمت لپ تاپ لنوو", order.first())
        assertEquals("لپ تاپ لنوو مشخصات", order.last())
    }

    @Test
    fun refusalsGrowWithLossesAndBestKeepsOnlyWinners() {
        val topic = "لپ تاپ"
        var entries = Playbook.record(emptyList(), topic, "q1", won = false, now = now)
        entries = Playbook.record(entries, topic, "q2", won = true, now = now)
        assertEquals(1, Playbook.refusals(topic, entries))
        assertEquals(listOf("q2"), Playbook.best(topic, entries).map(PlayEntry::query))
    }

    // ---------- source trust: a tie-break, never a gate ----------

    @Test
    fun aDomainThatAnsweredBeforeGetsASmallBoostOnly() {
        var stats: List<DomainStat> = emptyList()
        for (i in 0 until 5) stats = DomainTrust.record(stats, "good.example", won = true)
        stats = DomainTrust.record(stats, "bad.example", won = false)
        val good = DomainTrust.factor(stats, "good.example")
        val bad = DomainTrust.factor(stats, "bad.example")
        assertTrue(good > bad)
        assertTrue("boost must stay a tie-break", good <= 1.15)
        assertTrue("penalty must stay a tie-break", bad >= 0.80)
        assertEquals(1.0, DomainTrust.factor(stats, "never-seen.example"), 1e-9)
    }

    // ---------- refusal history → deeper next time, not the same shallow search ----------

    @Test
    fun aTopicRefusedBeforeIsResearchedDeeper() {
        val base = ResearchBudget.QUICK
        assertEquals(base, StudyDepth.escalate(base, 0))
        assertEquals(ResearchBudget.BALANCED, StudyDepth.escalate(base, 1))
        assertEquals(ResearchBudget.DEEP, StudyDepth.escalate(base, 2))
        assertEquals(ResearchBudget.DEEP, StudyDepth.escalate(ResearchBudget.DEEP, 9))
    }
}
