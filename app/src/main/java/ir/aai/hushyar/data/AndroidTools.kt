package ir.aai.hushyar.data

import android.net.Uri
import ir.aai.hushyar.BuildConfig
import ir.aai.hushyar.core.Jalali
import ir.aai.hushyar.core.Text
import ir.aai.hushyar.domain.AgentTools
import ir.aai.hushyar.domain.Backup
import ir.aai.hushyar.domain.Catalog
import ir.aai.hushyar.domain.CaseStore
import ir.aai.hushyar.domain.DomainStat
import ir.aai.hushyar.domain.DomainTrust
import ir.aai.hushyar.domain.Evidence
import ir.aai.hushyar.domain.PlanBuilder
import ir.aai.hushyar.domain.PlanProgress
import ir.aai.hushyar.domain.PlayEntry
import ir.aai.hushyar.domain.Playbook
import ir.aai.hushyar.domain.ProactiveBudget
import ir.aai.hushyar.domain.QaCase
import ir.aai.hushyar.domain.QuestionModel
import ir.aai.hushyar.domain.ResearchPlan
import ir.aai.hushyar.domain.ExtractedPage
import ir.aai.hushyar.domain.KnowledgeItem
import ir.aai.hushyar.domain.KnowledgeResult
import ir.aai.hushyar.domain.Listing
import ir.aai.hushyar.domain.LogEvent
import ir.aai.hushyar.domain.MarketPack
import ir.aai.hushyar.domain.Markdown
import ir.aai.hushyar.domain.MemoryBank
import ir.aai.hushyar.domain.MemoryHit
import ir.aai.hushyar.domain.MemoryItem
import ir.aai.hushyar.domain.ResearchBudget
import ir.aai.hushyar.domain.Resolver
import ir.aai.hushyar.domain.SearchCore
import ir.aai.hushyar.domain.SearchReport
import java.io.File

/**
 * Concrete wiring of the agent's tool port onto SQLite + files + HTTP. Kept thin on purpose:
 * all policy lives in `domain/`, so the app layer stays replaceable and testable.
 */
class AndroidTools(
    private val repos: Repos,
    private val store: Store,
    private val search: SearchCore,
    private val appVersion: String = BuildConfig.VERSION_NAME
) : AgentTools {

    private var lastBackup: File? = null

    override fun now(): Long = System.currentTimeMillis()

    override fun catalog(): Catalog {
        val pack = repos.activePack() ?: EMPTY_PACK
        val listings = repos.listings()
        val products = if (pack.id == EMPTY_PACK.id) emptyList() else Resolver.group(listings, pack.priceRules)
        return Catalog(pack, listings, products)
    }

    override fun memoryWrite(kind: String, text: String, source: String): MemoryItem {
        val pack = repos.activePack()
        val category = MemoryBank.classify(text, pack)
        val tags = MemoryBank.tags(text, pack)
        val importance = MemoryBank.importance(text, explicit = source == "user", pack = pack)
        val probe = MemoryItem(
            id = "probe",
            kind = kind,
            text = text,
            createdAt = now(),
            category = category,
            tags = tags,
            importance = importance,
            source = source
        )
        val twin = MemoryBank.duplicateOf(probe, repos.memoryAll(600))
        if (twin != null) {
            val merged = twin.copy(
                importance = maxOf(twin.importance, importance),
                tags = (twin.tags + tags).distinct().take(14),
                useCount = twin.useCount + 1,
                lastUsedAt = now()
            )
            repos.updateMemory(merged)
            repos.log("memory", "agent", "ادغام با یادسپاری موجود", merged.id)
            return merged
        }
        val written = repos.addMemory(kind, text, category, tags, importance, source)
        repos.log("memory", "agent", "یادسپاری جدید: $category", text.take(200))
        return written
    }

    override fun memorySearch(query: String, limit: Int): List<MemoryHit> =
        MemoryBank.rank(query, repos.memoryAll(800), now(), limit)

    override fun memoryAll(limit: Int): List<MemoryItem> = repos.memoryAll(limit)

    override fun memoryDelete(id: String): Boolean {
        val removed = repos.deleteMemory(id)
        if (removed) repos.log("memory", "agent", "حذف یادسپاری", id)
        return removed
    }

    override fun memoryTouch(ids: List<String>) = repos.touchMemory(ids)

    // ---------- knowledge store ----------

    override fun knowledgeAnswerable(question: String, limit: Int): List<KnowledgeItem> =
        repos.knowledgeAnswerable(question, now(), limit)

    override fun knowledgeAll(limit: Int): List<KnowledgeItem> = repos.knowledgeAll(limit)

    override fun knowledgeStale(limit: Int): List<KnowledgeItem> = repos.knowledgeStale(now(), limit)

    override fun knowledgeStore(item: KnowledgeItem): KnowledgeResult = repos.knowledgeUpsert(item)

    override fun knowledgeDelete(id: String): Boolean = repos.deleteKnowledge(id)

    override fun insertListings(listings: List<Listing>): Int = repos.insertListings(listings)

    override fun researchBudget(): ResearchBudget = repos.researchBudget()

    // ---- v0.5: learning from answered questions ----

    override fun caseFind(model: QuestionModel, question: String): QaCase? =
        repos.caseFind(model, question, now())

    override fun caseLearn(model: QuestionModel, question: String, answer: String, source: Evidence?) {
        val existing = repos.casesAll(400)
        val updated = CaseStore.learn(existing, model, question, answer, source, now())
        repos.caseUpsert(updated)
        repos.log("learn", "agent", "پروندهٔ پاسخ ذخیره شد", question.take(160))
    }

    override fun caseTouch(id: String) {
        val existing = repos.casesAll(400)
        repos.caseUpsert(CaseStore.touch(existing, id, now()))
    }

    override fun casesAll(limit: Int): List<QaCase> = repos.casesAll(limit)

    override fun playbookRecord(topicKey: String, query: String, won: Boolean) {
        repos.playbookUpsert(Playbook.record(repos.playbookAll(), topicKey, query, won, now()))
    }

    override fun playbookBest(topicKey: String, limit: Int): List<PlayEntry> =
        Playbook.best(topicKey, repos.playbookAll(), limit)

    override fun playbookRefusals(topicKey: String): Int = Playbook.refusals(topicKey, repos.playbookAll())

    override fun domainFactor(domain: String): Double = DomainTrust.factor(repos.domainsAll(), domain)

    override fun domainRecord(domain: String, won: Boolean) {
        repos.domainsUpsert(DomainTrust.record(repos.domainsAll(), domain, won))
    }

    // ---- v0.5: the plan that runs without a question ----

    override fun researchPlan(): ResearchPlan {
        val pack = repos.activePack() ?: EMPTY_PACK
        val catalog = catalog()
        val questions = repos.events(200)
            .filter { event -> event.kind == "answer" }
            .map { event -> event.detail.substringAfter("q=").substringBefore(" |") }
        return PlanBuilder.build(pack, catalog.products, questions.filter { it.isNotBlank() }, "fa", now())
    }

    override fun planProgress(): PlanProgress {
        val plan = researchPlan()
        val saved = repos.planProgress(plan.goal)
        val merged = plan.layers.associate { layer -> layer.id to (saved.states[layer.id] ?: ir.aai.hushyar.domain.LayerState(layer.id)) }
        return PlanProgress(plan.goal, merged)
    }

    override fun planProgressSave(progress: PlanProgress) = repos.planProgressSave(progress)

    override fun proactiveBudget(): ProactiveBudget =
        when (repos.researchDepth()) {
            "quick" -> ProactiveBudget.LIGHT
            "deep" -> ProactiveBudget.DEEP
            else -> ProactiveBudget.NORMAL
        }

    override fun webSearch(query: String, langTag: String): SearchReport {
        val report = search.search(query, langTag)
        repos.log(
            "web", "agent", "جستجو: $query",
            "نتیجه=${report.hits.size} موتور=${report.perEngine.size} خطا=${report.failures.size}"
        )
        return report
    }

    override fun fetchPage(url: String, langTag: String): ExtractedPage? {
        val page = search.fetchPage(url, langTag)
        repos.log(
            "web", "agent",
            if (page == null) "واکشی ناموفق" else "واکشی صفحه (${page.trust})",
            url
        )
        return page
    }

    override fun logAppend(kind: String, actor: String, message: String, detail: String) =
        repos.log(kind, actor, message, detail)

    override fun logRecent(limit: Int): List<LogEvent> = repos.events(limit)

    override fun backupNow(): String = BackupService.create(repos, store, appVersion).first.absolutePath

    override fun exportLogMarkdown(): String {
        val md = Markdown.log(repos.events(500))
        val file = store.writeLog("hushyar-log-${store.stamp()}", md)
        repos.log("log", "user", "خروجی Markdown لاگ", file.absolutePath)
        return md
    }

    fun lastBackupFile(): File? = lastBackup

    companion object {
        private val EMPTY_PACK = MarketPack(id = "__none__", name = "بازار تعریف نشده", version = 0)
    }
}

/**
 * Backup + restore as app-level operations. The snapshot format itself is pure domain code
 * ([Backup]); this object only knows about files and the database.
 */
object BackupService {

    data class Result(val file: File, val info: Backup.Info, val markdown: String)

    fun create(repos: Repos, store: Store, appVersion: String, now: Long = System.currentTimeMillis()): Pair<File, Backup.Info> {
        val result = createDetailed(repos, store, appVersion, now)
        return result.file to result.info
    }

    fun createDetailed(repos: Repos, store: Store, appVersion: String, now: Long = System.currentTimeMillis()): Result {
        val snapshot = repos.snapshot()
        val json = Backup.encode(snapshot, appVersion, now)
        val info = Backup.inspect(json)
        val stamp = store.stamp(now)
        val logMd = Markdown.log(snapshot.logs)
        val memoryMd = Markdown.memory(snapshot.memory)
        val brain = Markdown.fullBrain(
            items = snapshot.memory,
            events = snapshot.logs,
            audits = snapshot.audits,
            packs = snapshot.packs.map { Triple(it.id, it.name, it.version) },
            listingCount = snapshot.listings.size,
            generatedAt = now,
            knowledge = snapshot.knowledge
        )
        val file = store.writeBundle(stamp, json, brain, logMd, memoryMd)
        repos.log(
            "backup", "user", "بک‌آپ کامل ساخته شد",
            "${file.name} | پک=${snapshot.packs.size} آگهی=${snapshot.listings.size} حافظه=${snapshot.memory.size} دانش=${snapshot.knowledge.size} | ${Jalali.format(now)}"
        )
        repos.audit("user", "backup.create", file.name, "sha256=${info.sha256.take(16)}")
        return Result(file, info, brain)
    }

    fun inspect(json: String): Backup.Info = Backup.inspect(json)

    /** Verifies the checksum, then replaces the database in one transaction. */
    fun restore(repos: Repos, json: String): Backup.Info {
        val info = Backup.inspect(json)
        val snapshot = Backup.decode(json)
        repos.restore(snapshot)
        repos.log(
            "backup", "user", "بازیابی انجام شد",
            "پک=${snapshot.packs.size} آگهی=${snapshot.listings.size} حافظه=${snapshot.memory.size} دانش=${snapshot.knowledge.size} | نسخهٔ فایل=${info.appVersion}"
        )
        return info
    }

    fun restoreFromFile(repos: Repos, store: Store, file: File): Backup.Info = restore(repos, store.readBackup(file))

    fun restoreFromUri(repos: Repos, store: Store, uri: Uri): Backup.Info = restore(repos, store.readBackup(uri))

    fun summary(info: Backup.Info): String = buildString {
        append("پشتیبان نسخهٔ ").append(info.appVersion)
        append(" · ").append(Jalali.format(info.createdAt))
        append(" · ")
        append(info.counts.entries.joinToString("، ") { "${it.key}=${Text.num(it.value.toLong())}" })
        append(" · sha256=").append(info.sha256.take(12))
    }
}
