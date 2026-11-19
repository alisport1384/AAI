package ir.aai.hushyar.domain

import ir.aai.hushyar.data.Repos

/**
 * Seed the offline IronPin database into Knowledge table on first launch.
 * Each KnowledgeItem is inserted via KnowledgeBank to keep decisions (NEW/REFRESH) correct.
 * Safe to call multiple times: existing claims are REFRESHed, not duplicated.
 */
object IronSeed {
    const val PREF_SEEDED = "iron_seed_v2_full" // v2: 19 خانوادهٔ کامل (با سایر + محتوای غنی‌شده لوله/بولت/…)
    const val PREF_SEEDED_LEGACY = "iron_seed_v1"

    fun needsSeed(repos: Repos): Boolean {
        // مهاجرت: اگر فقط v1 سید شده، v2 را دوباره بساز تا «سایر» و محتوای جدید بیاید
        val v2 = repos.preference(PREF_SEEDED)
        if (v2 == "1") return false
        val v1 = repos.preference(PREF_SEEDED_LEGACY)
        return v1 != "1" || v2 != "1" // v1 دارد ولی v2 ندارد => نیاز به reseed
    }

    fun seed(repos: Repos, clock: Long = System.currentTimeMillis()): Int {
        val items = IronKnowledge.toKnowledgeItems(clock)
        var inserted = 0
        for (item in items) {
            val result = repos.knowledgeUpsert(item)
            if (result.decision != KnowledgeDecision.IGNORE) inserted++
        }
        repos.putPreference(PREF_SEEDED, "1")
        repos.putPreference(PREF_SEEDED_LEGACY, "1")
        repos.log("system", "iron.seed", "inserted=$inserted v2_full", IronKnowledge.summary())
        return inserted
    }

    fun reseed(repos: Repos): Int {
        repos.putPreference(PREF_SEEDED, "0")
        return seed(repos)
    }
}
