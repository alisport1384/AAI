package ir.aai.hushyar.domain

import ir.aai.hushyar.core.Text

/** The saved cursor of one plan layer. */
data class LayerState(
    val id: String,
    val status: LayerStatus = LayerStatus.PENDING,
    val lastRunAt: Long = 0L,
    val learned: Int = 0,
    val queriesDone: Int = 0
)

data class PlanProgress(
    val goal: String,
    val states: Map<String, LayerState> = emptyMap()
) {
    fun statuses(): Map<String, LayerStatus> = states.mapValues { it.value.status }

    fun state(id: String): LayerState = states[id] ?: LayerState(id)

    fun with(state: LayerState): PlanProgress = copy(states = states + (state.id to state))

    fun learned(): Int = states.values.sumOf { it.learned }
}

/** One automatic run is bounded on purpose: battery and mobile data are the user's, not ours. */
data class ProactiveBudget(
    val maxLayers: Int = 1,
    val maxQueries: Int = 3,
    val maxPages: Int = 5,
    val maxMillis: Long = 40_000L
) {
    companion object {
        val LIGHT = ProactiveBudget(1, 2, 3, 25_000L)
        val NORMAL = ProactiveBudget(1, 3, 5, 40_000L)
        val DEEP = ProactiveBudget(2, 6, 10, 90_000L)
    }
}

data class ProactiveOutcome(
    val plan: ResearchPlan,
    val progressBefore: PlanProgress,
    val progressAfter: PlanProgress,
    val ranLayers: List<String>,
    val queries: List<String>,
    val pages: List<ExtractedPage>,
    val learned: List<KnowledgeItem>,
    val cases: List<QaCase>,
    val playbook: List<PlayEntry>,
    val domains: List<DomainStat>,
    val stop: ResearchStop,
    /** Where the next run will continue — this is what makes it "keep going" instead of one-shot. */
    val nextLayer: PlanLayer?
) {
    val completed: Boolean get() = nextLayer == null
}

/**
 * Runs the plan without being asked anything.
 *
 * Coarse before fine: [ResearchPlan.next] only unlocks a layer once its parents are DONE, so the big
 * body is filled before details, exactly like sketching and then layering. Each run is bounded and
 * writes its cursor back, so several short runs finish a long plan instead of one long crawl.
 *
 * Every query goes through the same answer machinery as a user question (QuestionReader →
 * AnswerFocus → answer shape). What it learns feeds the knowledge store, the case store (so a repeat
 * question is free) and the playbook (so the next attempt starts with the query that worked).
 */
class ProactiveResearcher(
    private val search: (String, String) -> SearchReport,
    private val fetch: (String, String) -> ExtractedPage?,
    private val now: () -> Long = { System.currentTimeMillis() },
    /**
     * Persistence hook: whatever survives the subject gate is handed to the real knowledge store,
     * so a later question can answer from it. Without this hook the run would only *report* what it
     * learned while the database stayed empty — the exact trap this callback removes.
     */
    private val store: ((KnowledgeItem) -> KnowledgeResult)? = null
) {

    fun run(
        plan: ResearchPlan,
        progress: PlanProgress,
        budget: ProactiveBudget = ProactiveBudget.NORMAL,
        langTag: String = "fa",
        pack: MarketPack? = null,
        cases: List<QaCase> = emptyList(),
        playbook: List<PlayEntry> = emptyList(),
        domains: List<DomainStat> = emptyList()
    ): ProactiveOutcome {
        val started = now()
        var state = progress
        var entries = playbook
        var stats = domains
        var caseList = cases
        val ranLayers = mutableListOf<String>()
        val queries = mutableListOf<String>()
        val pages = mutableListOf<ExtractedPage>()
        val learned = mutableListOf<KnowledgeItem>()
        val seen = mutableSetOf<String>()
        var stop = ResearchStop.ROUNDS
        var layersRun = 0

        while (layersRun < budget.maxLayers) {
            if (now() - started > budget.maxMillis) { stop = ResearchStop.TIME; break }
            if (queries.size >= budget.maxQueries) { stop = ResearchStop.ROUNDS; break }
            val layer = plan.next(state.statuses())
            if (layer == null) { stop = ResearchStop.SUFFICIENT; break }

            val topic = KnowledgeBank.topicKey(layer.queries.firstOrNull() ?: plan.goal)
            val ordered = Playbook.order(topic, layer.queries, entries)
            var doneHere = state.state(layer.id).queriesDone
            var learnedHere = state.state(layer.id).learned
            var layerComplete = true

            for (query in ordered) {
                if (queries.size >= budget.maxQueries) { layerComplete = false; break }
                if (pages.size >= budget.maxPages) { layerComplete = false; break }
                val stamp = now()
                if (stamp - started > budget.maxMillis) { layerComplete = false; break }

                val model = QuestionReader.read(query, langTag)
                // Already answered and still fresh: skip the network entirely — this is the payoff of
                // the case store, now applied to the plan itself.
                if (CaseStore.find(caseList, model, query, stamp, pack) != null) {
                    doneHere++
                    continue
                }

                val report = runCatching { search(query, langTag) }
                    .getOrElse { SearchReport(query, emptyList(), emptyMap(), mapOf("transport" to (it.message ?: "?"))) }
                queries.add(query)

                // Pages fetched for *this* query only. Ranking the whole run's pile would let an
                // earlier query's page answer a later question — cheap, and wrong.
                val here = mutableListOf<ExtractedPage>()
                for (hit in report.hits) {
                    if (pages.size >= budget.maxPages) break
                    if (here.size >= 2) break
                    if (!seen.add(hit.url)) continue
                    val page = runCatching { fetch(hit.url, langTag) }.getOrNull() ?: continue
                    if (page.signals.isNotEmpty()) continue
                    if (page.text.length < 120 && AnswerShapes.slot(model.ask, page.text, pack) == null) continue
                    here.add(page)
                }
                pages.addAll(here)

                val ranked = AnswerFocus.rank(model, here, pack, limit = 4, trust = { DomainTrust.factor(stats, it) })
                val answerPlan = AnswerFocus.plan(model, ranked, here)
                val evidence = answerPlan.support.take(2).map { it.evidence }

                // Only claims that survive the subject gate enter the store — and they enter the real
                // store through [store] when the app wires one, not just this run's report.
                val sink = store
                for (item in evidence) {
                    val draft = KnowledgeBank.draft(query, item, pack, 0.45 + 0.3 * item.score, stamp)
                    val written = if (sink != null) runCatching { sink(draft) }.getOrNull() else null
                    // Nothing is reported as learned unless it was actually written.
                    if (sink == null || written != null) learned.add(draft)
                }

                val won = answerPlan.answered
                entries = Playbook.record(entries, topic, query, won, stamp)
                if (won) {
                    val source = answerPlan.source
                    stats = DomainTrust.record(stats, source?.page?.url?.let { KnowledgeBank.draftHost(it) } ?: "", true)
                    caseList = CaseStore.learn(caseList, model, query, answerPlan.direct!!, source, stamp)
                    learnedHere++
                } else {
                    stats = DomainTrust.record(stats, "", false)
                }
                doneHere++
            }

            state = state.with(
                LayerState(
                    id = layer.id,
                    status = if (layerComplete) LayerStatus.DONE else LayerStatus.PARTIAL,
                    lastRunAt = now(),
                    learned = learnedHere,
                    queriesDone = doneHere
                )
            )
            ranLayers.add(layer.id)
            layersRun++
            if (!layerComplete) { stop = ResearchStop.TIME; break }
        }

        if (ranLayers.isEmpty() && stop == ResearchStop.ROUNDS) stop = ResearchStop.NO_RESULTS

        return ProactiveOutcome(
            plan = plan,
            progressBefore = progress,
            progressAfter = state,
            ranLayers = ranLayers,
            queries = queries,
            pages = pages,
            learned = learned,
            cases = caseList,
            playbook = entries,
            domains = stats,
            stop = stop,
            nextLayer = plan.next(state.statuses())
        )
    }

    /** A short deepening pass used right after answering a question (same step, better next time). */
    fun deepen(
        question: String,
        plan: ResearchPlan,
        progress: PlanProgress,
        langTag: String = "fa",
        pack: MarketPack? = null,
        cases: List<QaCase> = emptyList(),
        playbook: List<PlayEntry> = emptyList(),
        domains: List<DomainStat> = emptyList(),
        budget: ProactiveBudget = ProactiveBudget.LIGHT
    ): ProactiveOutcome {
        val model = QuestionReader.read(question, langTag)
        val dossier = PlanLayer(
            id = "dossier-now",
            kind = LayerKind.DOSSIER,
            depth = 0,
            title = if (langTag == "en") "After this answer" else "پس از همین پاسخ",
            queries = listOf(Text.normalize(question).trim().trimEnd('?', '؟', '.')),
            requires = emptyList()
        )
        val focused = ResearchPlan(
            goal = plan.goal,
            layers = listOf(dossier) + plan.layers,
            createdAt = plan.createdAt
        )
        return run(
            plan = focused,
            progress = progress.copy(states = progress.states + (dossier.id to LayerState(dossier.id))),
            budget = budget,
            langTag = langTag,
            pack = pack,
            cases = cases,
            playbook = playbook,
            domains = domains
        )
    }
}
