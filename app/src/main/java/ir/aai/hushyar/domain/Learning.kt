package ir.aai.hushyar.domain

import ir.aai.hushyar.core.Similarity
import ir.aai.hushyar.core.Text

/**
 * What the assistant keeps from every answered question (v0.5, second request):
 * *"after answering, it must research so that next time a similar question is answered better and
 * faster"*.
 *
 * Four separate layers, each with its own failure mode handled:
 *
 *  1. [QaCase]    — the answered question itself: reused only for the *same* kind of question about
 *                   the *same* subject while it is still fresh, so the second time costs zero requests.
 *  2. [Playbook]  — which query actually produced the answer, so the next attempt starts there
 *                   (fewer rounds = faster, and fewer wasted requests).
 *  3. [DomainStat]— which sites answered before for this topic; a tie-break, never a hard gate.
 *  4. [StudyDepth]— if the same topic was refused before, the next attempt escalates the budget
 *                   instead of repeating the same shallow search (better, not just faster).
 *
 * Why the guard rails: caching answers is only safe for stable, context-free questions; a cache hit
 * that silently serves a stale or off-topic answer is worse than a miss (see RESEARCH-v5.md §4).
 */
data class QaCase(
    val id: String,
    val topicKey: String,
    /** AskType name, so a price answer is never reused for a date question. */
    val ask: String,
    val subject: List<String>,
    val question: String,
    val answer: String,
    val url: String,
    val domain: String,
    val capturedAt: Long,
    val lastVerifiedAt: Long,
    val ttlDays: Int = 14,
    val hits: Int = 0
) {
    fun fresh(now: Long): Boolean =
        lastVerifiedAt > 0 && now - lastVerifiedAt <= ttlDays.toLong() * 86_400_000L
}

object CaseStore {

    const val SUBJECT_OVERLAP = 0.66
    const val DEFAULT_TTL_DAYS = 14

    fun id(question: String, ask: AskType): String =
        "c-" + Similarity.fnv1a64(Text.normalize(question) + "|" + ask.name).toString(16)

    /**
     * Reuse is allowed only when *all* of these hold: same ask type, same topic, the same subject,
     * still fresh, and the stored answer still has the shape the question asks for. Anything else is
     * a miss — and a miss costs one search, while a wrong hit costs trust.
     */
    fun find(
        cases: List<QaCase>,
        model: QuestionModel,
        question: String,
        now: Long,
        pack: MarketPack? = null
    ): QaCase? {
        val ask = model.ask.name
        val topic = KnowledgeBank.topicKey(question)
        return cases.asSequence()
            .filter { it.ask == ask }
            .filter { it.fresh(now) }
            .filter { AnswerShapes.answers(model.ask, it.answer, pack) }
            .filter { it.topicKey == topic || Similarity.containment(
                Text.contentTokens(it.topicKey).toSet(),
                Text.contentTokens(topic).toSet()
            ) >= 0.75 }
            .filter { subjectOverlap(model.subject, it.subject) >= SUBJECT_OVERLAP }
            .maxByOrNull { it.hits * 0.01 + (1.0 - age(now, it)) }
    }

    private fun age(now: Long, case: QaCase): Double =
        ((now - case.lastVerifiedAt).toDouble() / (case.ttlDays.toLong() * 86_400_000L)).coerceIn(0.0, 1.0)

    private fun subjectOverlap(asked: List<String>, stored: List<String>): Double {
        if (asked.isEmpty() || stored.isEmpty()) return 0.0
        val hits = asked.count { token -> stored.any { it == token || (it.length >= 4 && token.length >= 4 && (it.startsWith(token) || token.startsWith(it))) } }
        return hits.toDouble() / asked.size
    }

    /** Records an answered question. The same question overwrites the older case instead of duplicating. */
    fun learn(
        existing: List<QaCase>,
        model: QuestionModel,
        question: String,
        answer: String,
        source: Evidence?,
        now: Long,
        ttlDays: Int = DEFAULT_TTL_DAYS
    ): List<QaCase> {
        if (answer.isBlank()) return existing
        val case = QaCase(
            id = id(question, model.ask),
            topicKey = KnowledgeBank.topicKey(question),
            ask = model.ask.name,
            subject = model.subject,
            question = question.take(300),
            answer = answer.take(400),
            url = source?.page?.url ?: "",
            domain = source?.page?.url?.let { KnowledgeBank.draftHost(it) } ?: "",
            capturedAt = existing.firstOrNull { it.id == id(question, model.ask) }?.capturedAt ?: now,
            lastVerifiedAt = now,
            ttlDays = ttlDays,
            hits = existing.firstOrNull { it.id == id(question, model.ask) }?.hits ?: 1
        )
        return (existing.filter { it.id != case.id } + case).sortedByDescending { it.lastVerifiedAt }.take(400)
    }

    /** Fresh data arrived for this question: extend its life and its hit count. */
    fun touch(cases: List<QaCase>, id: String, now: Long, answer: String? = null): List<QaCase> =
        cases.map {
            if (it.id != id) it
            else it.copy(
                lastVerifiedAt = now,
                hits = it.hits + 1,
                answer = answer?.take(400) ?: it.answer
            )
        }

    fun stale(cases: List<QaCase>, now: Long): List<QaCase> = cases.filter { !it.fresh(now) }

    fun summary(cases: List<QaCase>, now: Long, langTag: String = "fa"): String {
        if (cases.isEmpty()) return if (langTag == "en") "no answered questions stored yet" else "هنوز پرونده‌ای ذخیره نشده"
        val fresh = cases.count { it.fresh(now) }
        return if (langTag == "en") "${cases.size} cases ($fresh fresh)"
        else "${Text.num(cases.size.toLong())} پرونده (${Text.num(fresh.toLong())} تازه)"
    }
}

/** One (topic, query) pair and how often it worked. */
data class PlayEntry(
    val topicKey: String,
    val query: String,
    val wins: Int = 0,
    val losses: Int = 0,
    val lastWinAt: Long = 0L
) {
    /** Laplace-smoothed success, so one lucky early win does not dominate forever. */
    val score: Double get() = (wins + 1.0) / (wins + losses + 2.0)
}

object Playbook {

    fun entry(entries: List<PlayEntry>, topicKey: String, query: String): PlayEntry? =
        entries.firstOrNull { it.topicKey == topicKey && it.query == Text.normalize(query).trim() }

    /** Proven queries first, then the untried ones, then the ones that already failed. */
    fun order(topicKey: String, queries: List<String>, entries: List<PlayEntry>): List<String> {
        val normalized = queries.map { Text.normalize(it).trim() }.distinct()
        return normalized.sortedWith(
            compareByDescending<String> { query ->
                val e = entry(entries, topicKey, query)
                when {
                    e == null -> 0.5               // untried: worth one attempt
                    e.wins == 0 -> 0.1             // tried and failed: last
                    else -> 1.0 + e.score          // proven
                }
            }
        )
    }

    fun record(
        entries: List<PlayEntry>,
        topicKey: String,
        query: String,
        won: Boolean,
        now: Long
    ): List<PlayEntry> {
        val key = Text.normalize(query).trim()
        if (key.isBlank()) return entries
        val existing = entry(entries, topicKey, key)
        val updated = if (existing == null) {
            PlayEntry(topicKey, key, wins = if (won) 1 else 0, losses = if (won) 0 else 1, lastWinAt = if (won) now else 0L)
        } else {
            existing.copy(
                wins = existing.wins + if (won) 1 else 0,
                losses = existing.losses + if (won) 0 else 1,
                lastWinAt = if (won) now else existing.lastWinAt
            )
        }
        return (entries.filterNot { it.topicKey == topicKey && it.query == key } + updated)
            .sortedByDescending { it.lastWinAt }
            .take(600)
    }

    fun best(topicKey: String, entries: List<PlayEntry>, limit: Int = 3): List<PlayEntry> =
        entries.filter { it.topicKey == topicKey && it.wins > 0 }
            .sortedByDescending { it.score }
            .take(limit)

    /** How many times this topic ended in a refusal — the signal that the next try must go deeper. */
    fun refusals(topicKey: String, entries: List<PlayEntry>): Int =
        entries.filter { it.topicKey == topicKey }.sumOf { it.losses }
}

/** Which domains actually answered before, per topic. A tie-break on ranking, never a filter. */
data class DomainStat(val domain: String, val wins: Int = 0, val losses: Int = 0) {

    val credibility: Double get() = (wins + 1.0) / (wins + losses + 2.0)

    /** 0.80 … 1.15 — enough to break ties, not enough to override the answer-shape gate. */
    val factor: Double get() = (0.80 + 0.70 * credibility).coerceIn(0.80, 1.15)
}

object DomainTrust {

    fun factor(stats: List<DomainStat>, domain: String): Double =
        stats.firstOrNull { it.domain == domain }?.factor ?: 1.0

    fun record(stats: List<DomainStat>, domain: String, won: Boolean): List<DomainStat> {
        if (domain.isBlank()) return stats
        val existing = stats.firstOrNull { it.domain == domain }
        val updated = if (existing == null) {
            DomainStat(domain, wins = if (won) 1 else 0, losses = if (won) 0 else 1)
        } else {
            existing.copy(
                wins = existing.wins + if (won) 1 else 0,
                losses = existing.losses + if (won) 0 else 1
            )
        }
        return (stats.filterNot { it.domain == domain } + updated).take(300)
    }
}

/** Escalation: the same topic that was refused before is researched with a bigger budget. */
object StudyDepth {

    fun escalate(base: ResearchBudget, refusals: Int): ResearchBudget = when {
        refusals <= 0 -> base
        refusals == 1 && base.maxRounds < ResearchBudget.BALANCED.maxRounds -> ResearchBudget.BALANCED
        base.maxRounds < ResearchBudget.DEEP.maxRounds -> ResearchBudget.DEEP
        else -> base
    }

    fun label(budget: ResearchBudget): String = when {
        budget.maxRounds >= ResearchBudget.DEEP.maxRounds -> "عمیق"
        budget.maxRounds >= ResearchBudget.BALANCED.maxRounds -> "متعادل"
        else -> "سریع"
    }
}
