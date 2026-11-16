package ir.aai.hushyar

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ir.aai.hushyar.core.Ids
import ir.aai.hushyar.core.PriceParser
import ir.aai.hushyar.core.Text
import ir.aai.hushyar.data.AndroidTools
import ir.aai.hushyar.data.BackupService
import ir.aai.hushyar.data.CsvImporter
import ir.aai.hushyar.data.HttpUrlTransport
import ir.aai.hushyar.data.Repos
import ir.aai.hushyar.data.Store
import ir.aai.hushyar.domain.Agent
import ir.aai.hushyar.domain.AgentStep
import ir.aai.hushyar.domain.AnalysisResult
import ir.aai.hushyar.domain.Analyzer
import ir.aai.hushyar.domain.Answer
import ir.aai.hushyar.domain.AttributeExtractor
import ir.aai.hushyar.domain.Catalog
import ir.aai.hushyar.domain.Interview
import ir.aai.hushyar.domain.Listing
import ir.aai.hushyar.domain.MarketPack
import ir.aai.hushyar.domain.Markdown
import ir.aai.hushyar.domain.MemoryBank
import ir.aai.hushyar.domain.MemoryItem
import ir.aai.hushyar.domain.KnowledgeBank
import ir.aai.hushyar.domain.KnowledgeItem
import ir.aai.hushyar.domain.LogEvent
import ir.aai.hushyar.domain.PackBuildResult
import ir.aai.hushyar.domain.PackCodec
import ir.aai.hushyar.domain.ProductGroup
import ir.aai.hushyar.domain.QaEngine
import ir.aai.hushyar.domain.SearchCore
import ir.aai.hushyar.domain.Resolver
import ir.aai.hushyar.domain.SelfManifest
import ir.aai.hushyar.domain.SelfModel
import ir.aai.hushyar.domain.ValidationReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppViewModel(
    private val repos: Repos,
    private val store: Store
) : ViewModel() {

    var pack by mutableStateOf<MarketPack?>(null)
        private set
    var manifest by mutableStateOf<SelfManifest?>(null)
        private set
    var listings by mutableStateOf<List<Listing>>(emptyList())
        private set
    var products by mutableStateOf<List<ProductGroup>>(emptyList())
        private set
    var answers by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var packList by mutableStateOf<List<Triple<String, String, Int>>>(emptyList())
        private set
    var lastBuilt by mutableStateOf<PackBuildResult?>(null)
        private set
    var analysis by mutableStateOf<AnalysisResult?>(null)
        private set
    var lastAnswer by mutableStateOf<Answer?>(null)
        private set
    var qaHistory by mutableStateOf<List<Pair<String, Answer>>>(emptyList())
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var steps by mutableStateOf<List<AgentStep>>(emptyList())
        private set
    var memories by mutableStateOf<List<MemoryItem>>(emptyList())
        private set
    var events by mutableStateOf<List<LogEvent>>(emptyList())
        private set
    var backupFiles by mutableStateOf<List<String>>(emptyList())
        private set
    var dataFiles by mutableStateOf<List<String>>(emptyList())
        private set
    var lastMarkdown by mutableStateOf<String?>(null)
        private set
    var knowledge by mutableStateOf<List<KnowledgeItem>>(emptyList())
        private set
    var researchDepth by mutableStateOf("balanced")
        private set

    private val qa = QaEngine()
    private val search = SearchCore(HttpUrlTransport())
    private val tools = AndroidTools(repos, store, search)

    init {
        refresh()
    }

    fun clearNotice() {
        notice = null
    }

    private fun <T> io(block: () -> T, then: (T) -> Unit) {
        loading = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(block) }
            loading = false
            result.onSuccess(then).onFailure { notice = it.message ?: "خطای نامشخص" }
        }
    }

    fun refresh() {
        io({
            val active = repos.activePack()
            val ls = repos.listings()
            val prods = if (active != null) Resolver.group(ls, active.priceRules) else emptyList()
            Snapshot(
                pack = active,
                listings = ls,
                products = prods,
                answers = repos.answers(),
                packs = repos.packs(),
                manifest = active?.let { SelfModel.derive(it, ls.size, prods.size) }
            )
        }) { snap ->
            pack = snap.pack
            listings = snap.listings
            products = snap.products
            answers = snap.answers
            packList = snap.packs
            manifest = snap.manifest
            analysis = analysis?.takeIf { a -> snap.products.any { it.id == a.product.id } }
        }
    }

    fun saveAnswer(key: String, value: String) {
        io({ repos.saveAnswer(key, value); repos.answers() }) { answers = it }
    }

    fun buildMarket() {
        io({
            val built = Interview.build(repos.answers(), System.currentTimeMillis())
            if (built.report.ok) {
                repos.savePack(built.pack, makeActive = true)
                repos.audit("user", "pack.build", built.pack.id, "نسخهٔ ${built.pack.version}")
            }
            built
        }) { built ->
            lastBuilt = built
            notice = if (built.report.ok) "بازار «${built.pack.name}» ساخته شد." else "بازار ساخته نشد: " + built.report.blocking.joinToString(" | ")
            refresh()
        }
    }

    fun saveAnswersAndBuild(all: Map<String, String>) {
        io({
            all.forEach { (k, v) -> repos.saveAnswer(k, v) }
            val built = Interview.build(repos.answers(), System.currentTimeMillis())
            if (built.report.ok) {
                repos.savePack(built.pack, makeActive = true)
                repos.audit("user", "pack.build", built.pack.id, "نسخهٔ ${built.pack.version}")
            }
            built
        }) { built ->
            lastBuilt = built
            notice = if (built.report.ok) "بازار «${built.pack.name}» ساخته شد." else "بازار ساخته نشد: " + built.report.blocking.joinToString(" | ")
            refresh()
        }
    }

    fun importPack(json: String) {
        io({
            val imported = PackCodec.decode(json)
            val report: ValidationReport = ir.aai.hushyar.domain.PackValidator.validate(imported)
            if (report.ok) {
                repos.savePack(imported, makeActive = true)
                repos.audit("user", "pack.import", imported.id, "وارد کردن پک")
            }
            report
        }) { report ->
            notice = if (report.ok) "پک وارد شد." else "پک نامعتبر: " + report.blocking.joinToString(" | ")
            refresh()
        }
    }

    fun exportPack(): String? {
        val current = pack ?: return null
        return PackCodec.encode(current, pretty = true)
    }

    fun activatePack(id: String) {
        io({ repos.activatePack(id); repos.audit("user", "pack.activate", id, "") }) {
            notice = "بازار فعال تغییر کرد."
            refresh()
        }
    }

    fun addListing(title: String, priceRaw: String, source: String, manual: Map<String, String>) {
        val current = pack ?: run { notice = "اول بازار را معرفی کنید."; return }
        if (title.isBlank()) { notice = "عنوان آگهی خالی است."; return }
        io({
            val parsed = PriceParser.parse(priceRaw, current.priceRules)
            val base = Listing(
                id = Ids.new("l"),
                title = title.trim(),
                titleNorm = Text.normalize(title),
                priceRaw = priceRaw.trim(),
                priceIrt = parsed.irt,
                priceNote = parsed.note,
                source = source.ifBlank { "ورود دستی" },
                url = null,
                seller = null,
                city = null,
                attrs = manual.filterValues { it.isNotBlank() },
                createdAt = System.currentTimeMillis()
            )
            val extracted = AttributeExtractor.extract(base, current)
            val listing = base.copy(attrs = extracted)
            repos.insertListing(listing)
            repos.audit("user", "listing.add", listing.id, listing.title)
            repos.answers()
            Unit
        }) {
            notice = "آگهی ثبت شد."
            refresh()
        }
    }

    fun importCsv(text: String) {
        val current = pack ?: run { notice = "اول بازار را معرفی کنید."; return }
        io({
            val result = CsvImporter.parse(text, current, System.currentTimeMillis())
            repos.insertListings(result.listings)
            repos.audit("user", "listing.import", "csv", "افزوده: ${result.listings.size}")
            result
        }) { result ->
            notice = buildString {
                append("${result.listings.size} آگهی وارد شد")
                if (result.skipped > 0) append("، ${result.skipped} سطر رد شد")
                if (result.errors.isNotEmpty()) append(". ").append(result.errors.take(2).joinToString(" "))
            }
            refresh()
        }
    }

    fun deleteListing(id: String) {
        io({ repos.deleteListing(id); repos.audit("user", "listing.delete", id, ""); Unit }) {
            notice = "آگهی حذف شد."
            refresh()
        }
    }

    fun analyze(product: ProductGroup) {
        val current = pack ?: return
        analysis = Analyzer.analyze(product, current)
    }

    fun closeAnalysis() {
        analysis = null
    }

    /**
     * The agent loop: data commands, local catalog, memory and web research, then learning.
     * A market pack is helpful but no longer required — memory, backup and web work without one.
     */
    fun ask(question: String) {
        if (question.isBlank()) return
        steps = emptyList()
        io({
            val agent = Agent(tools)
            val outcome = agent.run(question, allowWeb = true)
            val transcript = Markdown.answer(question, outcome.answer, outcome.steps, System.currentTimeMillis())
            val file = store.writeLog("qa-${store.stamp()}", transcript)
            repos.audit("agent", "qa.answer", outcome.answer.intent, "refused=${outcome.answer.refused} md=${file.name}")
            outcome
        }) { outcome ->
            lastAnswer = outcome.answer
            steps = outcome.steps
            qaHistory = (listOf(question to outcome.answer) + qaHistory).take(20)
            refreshSecondary()
        }
    }

    private fun refreshSecondary() {
        io({
            Secondary(
                memory = repos.memoryAll(300),
                events = repos.events(200),
                backups = store.listBackups().map { it.name },
                knowledge = repos.knowledgeAll(300),
                depth = repos.researchDepth()
            )
        }) { apply(it) }
    }

    private data class Secondary(
        val memory: List<MemoryItem>,
        val events: List<LogEvent>,
        val backups: List<String>,
        val knowledge: List<KnowledgeItem>,
        val depth: String
    )

    private fun apply(snapshot: Secondary) {
        memories = snapshot.memory
        events = snapshot.events
        backupFiles = snapshot.backups
        knowledge = snapshot.knowledge
        researchDepth = snapshot.depth
        dataFiles = store.inventory()
    }

    fun refreshData() = refreshSecondary()

    // ---------- data control: edit and delete individual records ----------

    fun updateMemory(item: MemoryItem, newText: String, category: String) {
        io({
            repos.updateMemory(item.copy(text = newText.trim(), category = category))
            repos.log("memory", "user", "ویرایش دستی یادسپاری", item.id)
            Unit
        }) {
            notice = "یادسپاری ویرایش شد."
            refreshSecondary()
        }
    }

    fun deleteMemory(id: String) {
        io({ repos.deleteMemory(id); Unit }) {
            notice = "یادسپاری حذف شد."
            refreshSecondary()
        }
    }

    fun editListing(listing: Listing, newTitle: String, newPrice: String) {
        val current = pack
        io({
            val parsed = if (current != null) PriceParser.parse(newPrice, current.priceRules) else null
            val updated = listing.copy(
                title = newTitle.trim().ifBlank { listing.title },
                titleNorm = Text.normalize(newTitle.trim().ifBlank { listing.title }),
                priceRaw = newPrice.trim().ifBlank { listing.priceRaw },
                priceIrt = parsed?.irt ?: listing.priceIrt,
                priceNote = parsed?.note ?: listing.priceNote
            )
            repos.updateListing(updated)
            Unit
        }) {
            notice = "آگهی ویرایش شد."
            refresh()
        }
    }

    // ---------- memory, backup, logs ----------

    // ---------- knowledge store ----------

    fun deleteKnowledge(id: String) {
        io({ repos.deleteKnowledge(id); Unit }) {
            notice = "دانسته حذف شد."
            refreshSecondary()
        }
    }

    fun updateKnowledge(item: KnowledgeItem, claim: String, category: String) {
        io({
            repos.updateKnowledge(item.copy(claim = claim.trim().ifBlank { item.claim }, category = category))
            repos.log("knowledge", "user", "ویرایش دستی دانسته", item.id)
            Unit
        }) {
            notice = "دانسته ویرایش شد."
            refreshSecondary()
        }
    }

    fun chooseResearchDepth(name: String) {
        io({ repos.setResearchDepth(name); Unit }) {
            researchDepth = name
            notice = when (name) {
                "quick" -> "عمق پژوهش: سریع (۲ دور، تا ۴ صفحه)"
                "deep" -> "عمق پژوهش: عمیق (۵ دور، تا ۱۴ صفحه)"
                else -> "عمق پژوهش: متعادل (۳ دور، تا ۸ صفحه)"
            }
        }
    }

    /** Re-research everything that went stale ("تا زمانی که لازم است جستجو کن"). */
    fun refreshKnowledge() = ask("بروزرسانی کن")

    fun knowledgeSummary(): String = KnowledgeBank.summary(knowledge, System.currentTimeMillis())

    fun consolidateMemory() {
        io({
            val all = repos.memoryAll(2000)
            val result = MemoryBank.consolidate(all)
            for (item in result.merged) repos.updateMemory(item)
            for (id in result.droppedIds) repos.deleteMemory(id)
            repos.log("memory", "user", "یکپارچه‌سازی حافظه", "ادغام=${result.merged.size} حذف=${result.droppedIds.size}")
            result
        }) { result ->
            notice = "ادغام شد: ${result.merged.size} رکورد، حذف تکراری: ${result.droppedIds.size}"
            refreshSecondary()
        }
    }

    fun backup() {
        io({ BackupService.createDetailed(repos, store, BuildConfig.VERSION_NAME) }) { result ->
            notice = "بک‌آپ ساخته شد: ${result.file.name}\n${BackupService.summary(result.info)}"
            lastMarkdown = result.markdown
            refreshSecondary()
        }
    }

    fun exportMarkdown() {
        io({
            val md = Markdown.fullBrain(
                items = repos.memoryAll(2000),
                events = repos.events(500),
                audits = repos.auditsAll(500),
                packs = repos.packs(),
                listingCount = repos.listings().size,
                generatedAt = System.currentTimeMillis(),
                knowledge = repos.knowledgeAll(2000)
            )
            val file = store.writeLog("hushyar-brain-${store.stamp()}", md)
            repos.log("log", "user", "خروجی دفتر هوش", file.name)
            md to file.name
        }) { (md, name) ->
            lastMarkdown = md
            notice = "دفتر هوش به Markdown ذخیره شد: $name"
            refreshSecondary()
        }
    }

    fun restore(json: String) {
        io({ BackupService.restore(repos, json) }) { info ->
            notice = "بازیابی انجام شد.\n${BackupService.summary(info)}"
            refreshSecondary()
            refresh()
        }
    }

    fun restoreFromText(text: String) {
        if (text.isBlank()) { notice = "متن پشتیبان خالی است."; return }
        restore(text)
    }

    fun restoreBackupFile(name: String) {
        io({
            val file = store.backupDir.listFiles()?.firstOrNull { it.name == name }
                ?: throw IllegalArgumentException("فایل پیدا نشد: $name")
            BackupService.restoreFromFile(repos, store, file)
        }) { info ->
            notice = "بازیابی از $name انجام شد.\n${BackupService.summary(info)}"
            refreshSecondary()
            refresh()
        }
    }

    fun restoreFromUri(uri: android.net.Uri) {
        io({ BackupService.restoreFromUri(repos, store, uri) }) { info ->
            notice = "بازیابی از فایل انجام شد.\n${BackupService.summary(info)}"
            refreshSecondary()
            refresh()
        }
    }

    fun inspectBackupText(text: String) {
        io({ BackupService.inspect(text) }) { info ->
            notice = BackupService.summary(info)
        }
    }

    fun readBackupPreview(name: String) {
        io({
            val file = store.backupDir.listFiles()?.firstOrNull { it.name == name }
                ?: throw IllegalArgumentException("فایل پیدا نشد")
            if (file.extension == "json") file.readText(Charsets.UTF_8) else store.readBackup(file)
        }) { json ->
            notice = BackupService.summary(BackupService.inspect(json))
        }
    }

    fun clearSteps() {
        steps = emptyList()
    }

    fun resetAll() {
        io({ repos.resetEverything(); Unit }) {
            pack = null
            manifest = null
            listings = emptyList()
            products = emptyList()
            answers = emptyMap()
            lastAnswer = null
            qaHistory = emptyList()
            analysis = null
            notice = "همه‌چیز پاک شد."
        }
    }

    fun interviewQuestions() = Interview.questions

    private data class Snapshot(
        val pack: MarketPack?,
        val listings: List<Listing>,
        val products: List<ProductGroup>,
        val answers: Map<String, String>,
        val packs: List<Triple<String, String, Int>>,
        val manifest: SelfManifest?
    )

    companion object {
        fun factory(repos: Repos, store: Store): ViewModelProvider.Factory = AppViewModelFactory(repos, store)
    }
}
