package ir.aai.hushyar.domain

import ir.aai.hushyar.data.Repos

/**
 * Seed the offline IronPin database into Knowledge table on first launch.
 * Each KnowledgeItem is inserted via KnowledgeBank to keep decisions (NEW/REFRESH) correct.
 * Safe to call multiple times: existing claims are REFRESHed, not duplicated.
 */
object IronSeed {
    const val PREF_SEEDED = "iron_seed_v1"

    fun needsSeed(repos: Repos): Boolean = repos.preference(PREF_SEEDED) != "1"

    fun seed(repos: Repos, clock: Long = System.currentTimeMillis()): Int {
        val items = IronKnowledge.toKnowledgeItems(clock)
        var inserted = 0
        for (item in items) {
            val result = repos.knowledgeUpsert(item)
            if (result.decision != KnowledgeDecision.IGNORE) inserted++
        }
        repos.putPreference(PREF_SEEDED, "1")
        repos.log("system", "iron.seed", "inserted=$inserted", IronKnowledge.summary())
        return inserted
    }

    fun reseed(repos: Repos): Int {
        repos.putPreference(PREF_SEEDED, "0")
        return seed(repos)
    }
}
