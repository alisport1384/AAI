package ir.aai.hushyar.data

import ir.aai.hushyar.domain.CaseStore
import ir.aai.hushyar.domain.PlanProgress
import ir.aai.hushyar.domain.ResearchPlan
import ir.aai.hushyar.domain.ProactiveBudget
import ir.aai.hushyar.domain.ProactiveOutcome
import ir.aai.hushyar.domain.ProactiveResearcher

/**
 * Turns the pure plan machinery ([ProactiveResearcher]) into something the app can run while the
 * user is not asking anything: it loads the current plan, the saved cursor and what was learned
 * before, runs one bounded slice, and writes every result back to SQLite.
 *
 * The whole loop is idempotent on purpose. A scheduled run may be delayed, skipped or killed
 * halfway (Android makes no promise about periodic work), so the only thing that must survive is
 * the cursor in `plan_state`: the next run simply continues from the first layer that is not DONE.
 */
class ProactiveRunner(
    private val repos: Repos,
    private val tools: AndroidTools
) {

    /** One bounded slice of the plan. Returns null when there is nothing to do yet. */
    fun tick(budget: ProactiveBudget? = null): ProactiveOutcome? {
        val plan = tools.researchPlan()
        if (plan.layers.isEmpty()) return null
        val progress = tools.planProgress()
        if (plan.next(progress.statuses()) == null) {
            repos.log("research", "system", "پلان کامل است", "goal=${plan.goal}")
            return null
        }
        val outcome = run(plan, progress, budget ?: tools.proactiveBudget())
        persist(outcome, "tick")
        return outcome
    }

    /**
     * Right after answering a question: spend a light budget on the same question, so the next
     * similar question is answered faster (and better) from what was stored now.
     */
    fun afterAnswer(question: String, langTag: String = "fa"): ProactiveOutcome? {
        if (question.isBlank()) return null
        val plan = tools.researchPlan()
        val progress = tools.planProgress()
        val outcome = runCatching {
            researcher().deepen(
                question = question,
                plan = plan,
                progress = progress,
                langTag = langTag,
                pack = repos.activePack(),
                cases = repos.casesAll(),
                playbook = repos.playbookAll(),
                domains = repos.domainsAll(),
                budget = ProactiveBudget.LIGHT
            )
        }.getOrNull() ?: return null
        persist(outcome, "deepen")
        return outcome
    }

    private fun run(plan: ResearchPlan, progress: PlanProgress, budget: ProactiveBudget): ProactiveOutcome =
        researcher().run(
            plan = plan,
            progress = progress,
            budget = budget,
            langTag = "fa",
            pack = repos.activePack(),
            cases = repos.casesAll(),
            playbook = repos.playbookAll(),
            domains = repos.domainsAll()
        )

    private fun researcher(): ProactiveResearcher = ProactiveResearcher(
        search = { query, lang -> tools.webSearch(query, lang) },
        fetch = { url, lang -> tools.fetchPage(url, lang) },
        now = { System.currentTimeMillis() },
        // Claims that pass the subject gate are written to the real store, not just reported.
        store = { item -> tools.knowledgeStore(item) }
    )

    private fun persist(outcome: ProactiveOutcome, mode: String) {
        repos.caseUpsert(outcome.cases)
        repos.playbookUpsert(outcome.playbook)
        repos.domainsUpsert(outcome.domains)
        repos.planProgressSave(outcome.progressAfter)
        repos.log(
            "research", "system",
            if (mode == "deepen") "پژوهش پس از پاسخ" else "کاوش خودکار پلان",
            "layers=${outcome.ranLayers.joinToString(",")} queries=${outcome.queries.size} " +
                "pages=${outcome.pages.size} learned=${outcome.learned.size} stop=${outcome.stop.name} " +
                "next=${outcome.nextLayer?.id ?: "-"}"
        )
    }

    /** "چه دانستم / چه کم است" — the honest report the user reads instead of asking a question. */
    fun report(): String {
        val plan = tools.researchPlan()
        val progress = tools.planProgress()
        val cases = repos.casesAll()
        return buildString {
            appendLine("هدف: ${plan.goal}")
            plan.describe(progress.statuses()).forEach { appendLine(it) }
            appendLine("پیشرفت: ${(plan.ratio(progress.statuses()) * 100).toInt()}%")
            appendLine(CaseStore.summary(cases, System.currentTimeMillis()))
        }.trim()
    }
}
