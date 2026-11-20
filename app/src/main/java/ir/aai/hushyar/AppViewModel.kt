package ir.aai.hushyar

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ir.aai.hushyar.core.Gguf
import ir.aai.hushyar.core.GgufHeader
import ir.aai.hushyar.core.GgufRead
import ir.aai.hushyar.core.Ids
import ir.aai.hushyar.core.MemInfo
import ir.aai.hushyar.core.PriceParser
import ir.aai.hushyar.core.Text
import ir.aai.hushyar.data.AndroidTools
import ir.aai.hushyar.data.BackupService
import ir.aai.hushyar.data.CsvImporter
import ir.aai.hushyar.data.HttpModelFetcher
import ir.aai.hushyar.data.HttpUrlTransport
import ir.aai.hushyar.data.InstallResult
import ir.aai.hushyar.data.LocalEngine
import ir.aai.hushyar.data.ExportResult
import ir.aai.hushyar.data.ImportResult
import ir.aai.hushyar.data.ModelInstaller
import ir.aai.hushyar.data.ModelTransfer
import ir.aai.hushyar.data.ModelStore
import ir.aai.hushyar.data.ProactiveRunner
import ir.aai.hushyar.data.PersonalBackendClient
import ir.aai.hushyar.data.Repos
import ir.aai.hushyar.data.Store
import ir.aai.hushyar.domain.Agent
import ir.aai.hushyar.domain.AgentStep
import ir.aai.hushyar.domain.AnalysisResult
import ir.aai.hushyar.domain.Analyzer
import ir.aai.hushyar.domain.Answer
import ir.aai.hushyar.domain.ChatThread
import ir.aai.hushyar.domain.ChatTurn
import ir.aai.hushyar.domain.ConversationContext
import ir.aai.hushyar.domain.ConversationResolution
import ir.aai.hushyar.domain.PurchaseComposer
import ir.aai.hushyar.domain.PersonalBackendConfig
import ir.aai.hushyar.domain.PersonalDocument
import ir.aai.hushyar.domain.PrivateRoute
import ir.aai.hushyar.domain.SafeCalculator
import ir.aai.hushyar.domain.AgentOutcome
import ir.aai.hushyar.domain.AttributeExtractor
import ir.aai.hushyar.domain.Catalog
import ir.aai.hushyar.domain.DeviceMemory
import ir.aai.hushyar.domain.EngineDiagnosis
import ir.aai.hushyar.domain.FitVerdict
import ir.aai.hushyar.domain.ModelFit
import ir.aai.hushyar.domain.Interview
import ir.aai.hushyar.domain.Listing
import ir.aai.hushyar.domain.MarketPack
import ir.aai.hushyar.domain.Markdown
import ir.aai.hushyar.domain.MemoryBank
import ir.aai.hushyar.domain.MemoryItem
import ir.aai.hushyar.domain.KnowledgeBank
import ir.aai.hushyar.domain.KnowledgeItem
import ir.aai.hushyar.domain.AgentBriefing
import ir.aai.hushyar.domain.AgentDecision
import ir.aai.hushyar.domain.UrlIngest
import ir.aai.hushyar.domain.Growth
import ir.aai.hushyar.domain.LocalAction
import ir.aai.hushyar.domain.LocalBrain
import ir.aai.hushyar.domain.LocalPlanner
import ir.aai.hushyar.domain.LocalChatter
import ir.aai.hushyar.domain.LocalGeneration
import ir.aai.hushyar.domain.LocalModelUi
import ir.aai.hushyar.domain.LocalPhraser
import ir.aai.hushyar.domain.LogEvent
import ir.aai.hushyar.domain.ModelCatalog
import ir.aai.hushyar.domain.ModelPhase
import ir.aai.hushyar.domain.StudyBudget
import ir.aai.hushyar.domain.StudyTrigger
import ir.aai.hushyar.domain.TemplatePhraser
import ir.aai.hushyar.domain.replyLine
import ir.aai.hushyar.domain.PackBuildResult
import ir.aai.hushyar.domain.PackCodec
import ir.aai.hushyar.domain.ProductGroup
import ir.aai.hushyar.domain.QaEngine
import ir.aai.hushyar.domain.QuestionReader
import ir.aai.hushyar.domain.AskType
import ir.aai.hushyar.domain.SearchCore
import ir.aai.hushyar.domain.EngineProbe
import ir.aai.hushyar.domain.Engines
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
    var chatThreads by mutableStateOf<List<ChatThread>>(emptyList())
        private set
    var chatTurns by mutableStateOf<List<ChatTurn>>(emptyList())
        private set
    var selectedChatId by mutableStateOf<String?>(null)
        private set
    var chatBusy by mutableStateOf(true)
        private set
    /** Optional owner-run service; settings are private, token is Keystore-encrypted and not backed up. */
    var personalBackendUrl by mutableStateOf(store.personalBackend.location().first)
        private set
    var personalBackendPin by mutableStateOf(store.personalBackend.location().second)
        private set
    var personalBackendConfigured by mutableStateOf(store.personalBackend.enabled())
        private set
    var personalDocuments by mutableStateOf<List<PersonalDocument>>(emptyList())
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
    var totalKnowledgeCount by mutableStateOf(0)
        private set
    var researchDepth by mutableStateOf("balanced")
        private set

    /** The plan the assistant works through on its own (v0.5), and how far it got. */
    var planLines by mutableStateOf<List<String>>(emptyList())
        private set
    var planRatio by mutableStateOf(0.0)
        private set
    var planGoal by mutableStateOf("")
        private set
    var caseSummary by mutableStateOf("")
        private set
    var caseCount by mutableStateOf(0)
        private set
    var proactiveRunning by mutableStateOf(false)
        private set
    var lastProactive by mutableStateOf<String?>(null)
        private set

    /** v0.6: the goal the user stated, and what every search engine is doing right now. */
    var goals by mutableStateOf<List<String>>(emptyList())
        private set
    var probes by mutableStateOf<List<Triple<String, Boolean, Int>>>(emptyList())
        private set
    var probing by mutableStateOf(false)
        private set
    var engineCount by mutableStateOf(0)
        private set

    /** v0.7: the on-device core. Absent by default; the app is complete without it. */
    var localModel by mutableStateOf(LocalModelUi(
        specId = repos.preference(KEY_MODEL_ID) ?: ModelCatalog.recommended.id,
        usingForAnswers = repos.preference(KEY_MODEL_USE) == "1"
    ))
        private set
    /** وقتی روشن است، محاسبه و سوال مستقیم مستقیماً به مدل lokal می‌رود (بدون جستجوب وب). */
    var directModel by mutableStateOf(repos.preference(KEY_DIRECT_MODEL) == "1")
        private set
    var localProof by mutableStateOf<String?>(null)
        private set

    /** v0.9: what the brain decided before any work was done, and how much the app now knows. */
    var lastDecision by mutableStateOf<AgentDecision?>(null)
        private set
    var growth by mutableStateOf(Growth.Report())
        private set

    /** v0.8.1: the honest answer to "why did the engine not load on this device". */
    var engineReport by mutableStateOf<String?>(null)
        private set
    var engineVerdict by mutableStateOf(EngineDiagnosis.Verdict.UNKNOWN)
        private set

    /** v0.8: deep study state — what was asked, what came back, layer by layer. */
    var studyRunning by mutableStateOf(false)
        private set
    var studySummary by mutableStateOf<String?>(null)
        private set
    var studyLayers by mutableStateOf<List<Triple<String, Int, Int>>>(emptyList())
        private set
    var studyGaps by mutableStateOf<List<String>>(emptyList())
        private set
    var studyEngineLine by mutableStateOf<String?>(null)
        private set

    /** Copyable diagnosis of the last model load attempt (v0.11.2). */
    var modelReport by mutableStateOf<String?>(null)
        private set

    // ---------- v0.12: صفحهٔ بک‌اند — هدف، محیط، پرامپت کلی، منابع، لاگ ----------

    var goalText by mutableStateOf("")
        private set
    var environmentText by mutableStateOf("")
        private set
    var directiveText by mutableStateOf("")
        private set
    var sources by mutableStateOf<List<Engines.UserSource>>(emptyList())
        private set
    /** v0.12: one row per engine from the on-device sweep (the v0.6 `probes` triple stays as is). */
    var engineProbes by mutableStateOf<List<EngineProbe>>(emptyList())
        private set
    var sweeping by mutableStateOf(false)
        private set
    @Suppress("PropertyName")
    var logging by mutableStateOf(false)
        private set
    var logLines by mutableStateOf<List<ir.aai.hushyar.domain.AuditEntry>>(emptyList())
        private set

    private val modelStore = ModelStore(store.modelsDir())
    private val installer = ModelInstaller(modelStore, HttpModelFetcher())
    private val transfer = ModelTransfer(modelStore)
    // v0.11.2: when the native engine says "load failed", this hook adds the *measured* reason —
    // the real GGUF header and the real memory numbers of this phone.
    private val engine = LocalEngine().also { e -> e.diagnosisFor = { spec, file -> modelFailureReport(spec, file) } }
    @Volatile private var downloading = false
    val isDownloading: Boolean get() = downloading

    private val qa = QaEngine()
    /**
     * v0.12 — منابع جستجو: پیش‌فرض‌های خودمان + هر منبعی که کاربر در بک‌اند اضافه کند.
     * منابع کاربر با اولویت پرسیده می‌شوند (خودش می‌داند کدام سایت برای کارش معتبر است).
     */
    private fun userSources(): List<Engines.UserSource> =
        runCatching { Engines.SourcesCodec.decode(repos.preference(KEY_SOURCES)) }.getOrDefault(emptyList())

    @Volatile private var sourceEpoch = 0

    private val search = SearchCore(HttpUrlTransport(), engines = Engines.all)
    private val privateBackend = PersonalBackendClient()
    private val tools = AndroidTools(repos, store, search)
    private val proactive = ProactiveRunner(repos, tools)

    init {
        refresh()
        refreshLocal()
        reloadBackend()
        reloadChats()
        // Auto-seed IronPin offline knowledge on first launch (idempotent)
        if (ir.aai.hushyar.domain.IronSeed.needsSeed(repos)) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { ir.aai.hushyar.domain.IronSeed.seed(repos) }
                withContext(Dispatchers.Main) { refreshSecondary() }
            }
        }
    }

    /** Reads the backend page's settings (goal / environment / global prompt / sources / logging). */
    fun reloadBackend() {
        goalText = repos.preference(KEY_GOAL).orEmpty()
        environmentText = repos.preference(KEY_ENV).orEmpty()
        directiveText = repos.preference(KEY_DIRECTIVE).orEmpty()
        sources = userSources()
        repoSearch()
        logging = repos.loggingEnabled()
        logLines = if (logging) runCatching { repos.audits(120) }.getOrDefault(emptyList()) else emptyList()
    }

    fun saveBackend(goal: String, environment: String, directive: String) {
        repos.putPreference(KEY_GOAL, goal.trim())
        repos.putPreference(KEY_ENV, environment.trim())
        repos.putPreference(KEY_DIRECTIVE, directive.trim())
        goalText = goal.trim()
        environmentText = environment.trim()
        directiveText = directive.trim()
        repos.audit("user", "backend.save", "goal", goal.trim().take(120))
        notice = "هدف، محیط و پرامپت کلی ذخیره شد."
    }

    /** Connecting is optional and explicit; neither chat nor documents are uploaded automatically. */
    fun savePersonalBackend(url: String, pin: String, token: String): String? {
        val config = try { PersonalBackendConfig.of(url, pin, token) }
            catch (error: IllegalArgumentException) { return error.message ?: "نشانی یا کلید معتبر نیست" }
        if (loading || chatBusy) return "برای پایان کار جاری صبر کن"
        io({ store.personalBackend.save(config); Unit }) {
            personalBackendUrl = config.baseUrl
            personalBackendPin = config.pinSha256
            personalBackendConfigured = true
            personalDocuments = emptyList()
            notice = "بک‌اند شخصی روی همین دستگاه ثبت شد؛ با «آزمون اتصال» گواهی و کلید را بررسی کن. گفتگوهای عادی همچنان روی دستگاه‌اند."
        }
        return null
    }

    fun disconnectPersonalBackend() {
        if (loading || chatBusy) return
        io({ store.personalBackend.clear(); Unit }) {
            personalBackendUrl = ""
            personalBackendPin = ""
            personalBackendConfigured = false
            personalDocuments = emptyList()
            notice = "اتصال و کلید از گوشی حذف شدند. اسنادِ بارگذاری‌شده روی سرور شخصی پاک نشده‌اند."
        }
    }

    fun testPersonalBackend() {
        if (!personalBackendConfigured || loading || chatBusy) return
        io({ privateBackend.health(store.personalBackend.read() ?: error("ابتدا بک‌اند شخصی را ثبت کن")) }) {
            notice = it
        }
    }

    /** Document requests and uploads are scoped to the *selected* conversation. */
    fun refreshPersonalDocuments() {
        val threadId = selectedChatId
        if (!personalBackendConfigured || threadId == null) {
            personalDocuments = emptyList()
            return
        }
        if (loading || chatBusy) return
        io({ privateBackend.list(store.personalBackend.read() ?: error("بک‌اند تنظیم نشده"), threadId) }) {
            if (selectedChatId == threadId) personalDocuments = it
        }
    }

    fun indexPersonalDocument(uri: android.net.Uri) {
        val threadId = selectedChatId
        if (threadId == null) { notice = "اول یک گفت‌وگو بساز؛ سند فقط به همان گفتگو تعلق می‌گیرد."; return }
        if (!personalBackendConfigured) { notice = "اول بک‌اند شخصی را ثبت کن."; return }
        if (loading || chatBusy) { notice = "تا پایان کار جاری صبر کن."; return }
        io({
            val selected = store.selectedText(uri)
            selected.title to privateBackend.index(store.personalBackend.read() ?: error("بک‌اند تنظیم نشده"),
                threadId, selected.title, selected.body, selected.kind)
        }) { (name, result) ->
            notice = if (result.duplicate) "«$name» قبلاً در همین گفتگو نمایه شده بود."
                else "«$name» در ${Text.num(result.chunks.toLong())} بخش روی سرور شخصی نمایه شد."
            refreshPersonalDocuments()
        }
    }

    fun removePersonalDocument(id: String) {
        val threadId = selectedChatId ?: return
        if (!personalBackendConfigured || loading || chatBusy) return
        io({ privateBackend.delete(store.personalBackend.read() ?: error("بک‌اند تنظیم نشده"), threadId, id) }) { deleted ->
            notice = if (deleted) "سند از بک‌اند شخصی حذف شد." else "سند در این گفتگو پیدا نشد."
            refreshPersonalDocuments()
        }
    }

    fun purgePersonalThread() {
        val threadId = selectedChatId ?: return
        if (!personalBackendConfigured || loading || chatBusy) return
        io({ privateBackend.purgeThread(store.personalBackend.read() ?: error("بک‌اند تنظیم نشده"), threadId) }) { count ->
            personalDocuments = emptyList()
            notice = "${Text.num(count.toLong())} سند همین گفتگو از سرور شخصی حذف شد؛ گفتگوهای دیگر دست‌نخورده‌اند."
        }
    }

    /** The one string the local model sees before its own rules (v0.12). */
    fun directive(): String = listOf(
        goalText.takeIf { it.isNotBlank() }?.let { "هدف من: $it" },
        environmentText.takeIf { it.isNotBlank() }?.let { "محیط کار من: $it" },
        directiveText.takeIf { it.isNotBlank() }
    ).filterNotNull().joinToString("\n")

    fun addSource(title: String, template: String, format: String): String? {
        val source = Engines.UserSource(
            title = title.trim(),
            template = template.trim(),
            format = runCatching { ir.aai.hushyar.domain.SerpFormat.valueOf(format.uppercase()) }
                .getOrDefault(ir.aai.hushyar.domain.SerpFormat.HTML)
        )
        if (!source.valid()) {
            return "منبع پذیرفته نشد: عنوان لازم است، نشانی باید با https:// شروع شود و {q} داشته باشد."
        }
        val next = (sources + source).distinctBy { it.id }
        persistSources(next)
        return null
    }

    fun removeSource(id: String) {
        persistSources(sources.filterNot { it.id == id })
    }

    fun toggleSource(id: String) {
        persistSources(sources.map { if (it.id == id) it.copy(enabled = !it.enabled) else it })
    }

    private fun persistSources(next: List<Engines.UserSource>) {
        repos.putPreference(KEY_SOURCES, Engines.SourcesCodec.encode(next))
        sources = next
        sourceEpoch++
        repoSearch()
        repos.audit("user", "sources.save", "${next.size}", next.joinToString("،") { it.title })
    }

    /** Pushes the user's sources into the one search core the agent already holds. */
    private fun repoSearch() {
        search.extraEngines = sources.filter { it.valid() }.map { it.asEngine() }
    }

    /** Asks every engine one question and records who answered — on *this* network. */
    fun sweepEngines() {
        if (sweeping) return
        sweeping = true
        viewModelScope.launch {
            val rows = withContext(Dispatchers.IO) {
                runCatching { search.probe("قیمت تیشرت نخی", "fa", 5) }.getOrDefault(emptyList())
            }
            engineProbes = rows
            sweeping = false
            val ok = rows.count { it.ok }
            notice = "پویش موتورها: $ok از ${rows.size} موتور روی این شبکه پاسخ دادند."
        }
    }

    fun toggleLogging(on: Boolean) {
        repos.setLogging(on)
        logging = on
        logLines = if (on) runCatching { repos.audits(120) }.getOrDefault(emptyList()) else emptyList()
        notice = if (on) {
            "لاگ روشن شد؛ رویدادهای تازه ذخیره می‌شوند."
        } else {
            "لاگ خاموش است؛ هیچ رویدادی ذخیره نمی‌شود."
        }
    }

    fun clearLog() {
        repos.clearAudit()
        logLines = emptyList()
        notice = "لاگ پاک شد."
    }

    fun refreshLog() {
        logLines = if (logging) runCatching { repos.audits(120) }.getOrDefault(emptyList()) else emptyList()
    }

    fun clearNotice() {
        notice = null
    }

    // ---------- v0.11.2: measuring instead of guessing why a model will not load ----------

    /**
     * عددهای همین گوشی، در همین لحظه. حافظه از `/proc/meminfo` خوانده می‌شود (بدون Context)،
     * بیتی‌بودن پردازه از `Process.is64Bit()`، و فضای آزاد از خود پوشهٔ مدل‌ها.
     */
    fun deviceMemory(): DeviceMemory {
        val mem = MemInfo.snapshot()
        val is64 = runCatching { android.os.Process.is64Bit() }.getOrDefault(true)
        return DeviceMemory(
            totalBytes = mem.totalBytes,
            availBytes = mem.availableBytes,
            // We do not read Android's lowMemory flag (it needs a Context we deliberately do not
            // hold); the same information is derived from the numbers we do have, and said so.
            lowMemory = mem.totalBytes > 0 && mem.availableBytes * 8 < mem.totalBytes,
            is64Bit = is64,
            freeDiskBytes = runCatching { modelStore.freeSpace() }.getOrDefault(0L),
            abis = runCatching { android.os.Build.SUPPORTED_ABIS?.toList().orEmpty() }.getOrDefault(emptyList())
        )
    }

    /** Header of the file that is actually on disk — or why it is not a GGUF we can read. */
    fun installedHeader(): Pair<GgufHeader?, String> {
        val spec = localModel.spec
        return when (val read = Gguf.read(modelStore.file(spec))) {
            is GgufRead.Ok -> read.header to read.header.describe()
            is GgufRead.Bad -> null to read.reason
        }
    }

    private fun modelFailureReport(spec: ir.aai.hushyar.domain.ModelSpec, file: java.io.File): String {
        val header = (Gguf.read(file) as? GgufRead.Ok)?.header
        return ModelFit.failureReport(deviceMemory(), spec, header)
    }

    /** «این گوشی تا چه حجمی را بارگذاری می‌کند» — از اندازه‌های همین لحظه. */
    fun capacityLine(): String = ModelFit.capacityLine(deviceMemory())

    /** The strongest model in the catalogue that this phone can actually load. */
    fun bestModelId(): String? = ModelFit.bestFor(deviceMemory(), ModelCatalog.all)?.id

    /** One line for the model list, shown *before* the user spends a gigabyte of data. */
    fun fitAdvisory(id: String): String? {
        val spec = runCatching { ModelCatalog.find(id) }.getOrNull() ?: return null
        val installed = runCatching { modelStore.installed(spec) }.getOrDefault(false)
        return ModelFit.advisory(deviceMemory(), spec, installed)
    }

    /** The full copyable report: file, device, verdict. No hidden reasoning. */
    fun diagnoseModel() {
        val spec = localModel.spec
        val (header, fileLine) = installedHeader()
        val device = deviceMemory()
        val verdict = ModelFit.verdict(device, spec.bytes, localModel.installed, header)
        val text = buildString {
            appendLine("گزارش مدل (v0.11.2)")
            appendLine("مدل: ${spec.title} — ${spec.sizeLabel}")
            appendLine("فایل: ${modelStore.file(spec).absolutePath}")
            appendLine("سربرگ: $fileLine")
            appendLine(
                "دستگاه: حافظهٔ کل " + Text.num(ModelFit.mb(device.totalBytes)) + " مگابایت، " +
                    "آزاد " + Text.num(ModelFit.mb(device.availBytes)) + " مگابایت (از /proc/meminfo)، " +
                    "فضای آزاد " + Text.num(ModelFit.mb(device.freeDiskBytes)) + " مگابایت"
            )
            appendLine(
                "پردازه: " + if (device.is64Bit) "۶۴بیتی" else "۳۲بیتی" +
                    " — معماری‌های گوشی: " + device.abis.joinToString("، ").ifBlank { "نامعلوم" }
            )
            appendLine(
                "برآورد نیاز: " + Text.num(ModelFit.mb(ModelFit.estimatedNeedBytes(spec.bytes))) +
                    " مگابایت (تخمین)"
            )
            appendLine("داوری: " + ModelFit.verdictLabel(verdict))
            append(ModelFit.explain(verdict, device, spec))
            engine.lastError?.let { appendLine().appendLine("خطای آخر موتور: $it") }
        }
        modelReport = text
    }


    // ---------- v0.7: on-device model lifecycle (download · verify · load · delete) ----------

    /** Reads what is actually on disk and what the native engine actually reports. */
    fun refreshLocal() {
        val spec = localModel.spec
        val installed = modelStore.installed(spec)
        val part = if (modelStore.part(spec).isFile) modelStore.part(spec).length() else 0L
        localModel = localModel.copy(
            phase = when {
                !engine.nativeAvailable -> ModelPhase.ENGINE_ERROR
                installed -> ModelPhase.READY
                else -> ModelPhase.MISSING
            },
            downloadedBytes = if (part > 0) part else if (installed) spec.bytes else 0,
            totalBytes = spec.bytes,
            engineReady = engine.ready,
            installed = installed,
            backend = engine.backend,
            message = engine.lastError
        )
    }

    /**
     * Reads the facts the device itself reports and says which failure this is. Cheap and
     * context-free: ABIs and page size come from the framework, the library's page alignment
     * comes from a constant that a unit test keeps honest against the committed `.so`.
     */
    fun diagnoseEngine() {
        val facts = runCatching {
            val failure = ir.aai.hushyar.core.LlamaNative.loadFailure
            EngineDiagnosis.Facts(
                loadFailure = failure,
                abis = android.os.Build.SUPPORTED_ABIS?.toList().orEmpty(),
                pageSizeBytes = runCatching {
                    android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)
                }.getOrDefault(0L),
                apiLevel = android.os.Build.VERSION.SDK_INT,
                deviceModel = (android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL).trim(),
                libAlign = EngineDiagnosis.alignFor(android.os.Build.SUPPORTED_ABIS?.toList().orEmpty()),
                libPresent = !EngineDiagnosis.libraryMissingFromError(failure)
            )
        }.getOrNull()
        if (facts == null) {
            engineReport = "گزارش تشخیص ساخته نشد: اطلاعات دستگاه خوانده نشد."
            return
        }
        engineVerdict = EngineDiagnosis.verdict(facts)
        engineReport = EngineDiagnosis.report(facts, extra = engine.lastError)
    }

    fun chooseModel(id: String) {
        if (!ModelCatalog.byId.containsKey(id)) return
        repos.putPreference(KEY_MODEL_ID, id)
        engine.unload()
        localModel = localModel.copy(specId = id, downloadedBytes = 0, totalBytes = ModelCatalog.find(id).bytes)
        refreshLocal()
        notice = "مدل انتخابی: ${ModelCatalog.find(id).title}"
    }

    /** One download, resumed if it was interrupted, verified by size and SHA-256. */
    fun downloadModel() {
        if (downloading) return
        val spec = localModel.spec
        downloading = true
        localModel = localModel.copy(phase = ModelPhase.DOWNLOADING, totalBytes = spec.bytes, message = null)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                installer.install(
                    spec,
                    onProgress = { done, total ->
                        localModel = localModel.copy(downloadedBytes = done, totalBytes = if (total > 0) total else spec.bytes)
                    },
                    isCancelled = { !downloading }
                )
            }
            downloading = false
            when (result) {
                is InstallResult.Installed -> {
                    repos.audit("user", "model.install", spec.id, "${spec.bytes} bytes sha256=${spec.sha256.take(12)}")
                    val loaded = withContext(Dispatchers.IO) { engine.load(modelStore.file(spec), spec) }
                    localModel = localModel.copy(
                        phase = ModelPhase.READY,
                        downloadedBytes = spec.bytes,
                        engineReady = loaded,
                        backend = engine.backend,
                        message = if (loaded) null else engine.lastError
                    )
                    notice = if (loaded) {
                        "مدل نصب و بارگذاری شد: ${spec.title}"
                    } else {
                        "مدل دانلود و تأیید شد، ولی بارگذاری نشد: ${engine.lastError}"
                    }
                    refreshLocal()
                }
                is InstallResult.Failed -> {
                    repos.audit("system", "model.failed", spec.id, result.reason)
                    localModel = localModel.copy(phase = ModelPhase.MISSING, message = result.reason)
                    notice = result.reason
                }
                InstallResult.Cancelled -> {
                    localModel = localModel.copy(phase = ModelPhase.MISSING, message = "دانلود لغو شد.")
                    notice = "دانلود لغو شد؛ فایل ناقص برای ادامهٔ بعدی نگه داشته شد."
                }
            }
        }
    }

    fun cancelDownload() {
        downloading = false
    }

    fun deleteModel() {
        val spec = localModel.spec
        engine.unload()
        val removed = modelStore.delete(spec)
        repos.audit("user", "model.delete", spec.id, "removed=$removed")
        localModel = localModel.copy(downloadedBytes = 0, phase = ModelPhase.MISSING, engineReady = false, message = null)
        refreshLocal()
        notice = if (removed) "فایل مدل از دستگاه حذف شد." else "فایلی برای حذف نبود."
    }

    /**
     * Writes the model file the user already downloaded to a destination they choose
     * (Downloads, Drive, SD card). One download, forever — this is the copy that survives
     * an uninstall or moves to another phone.
     */
    fun saveModelToFile(uri: android.net.Uri) {
        val spec = localModel.spec
        io({ transfer.export(spec, { store.openOutput(uri) }) }) { result ->
            when (result) {
                is ExportResult.Exported -> {
                    repos.audit("user", "model.export", spec.id, "${result.bytes} bytes")
                    notice = "فایل مدل ذخیره شد (${result.bytes / (1024 * 1024)} مگابایت). " +
                        "بعداً با «بازیابی از فایل» همان فایل را برگردان؛ لازم نیست دوباره دانلود شود."
                }
                is ExportResult.Failed -> notice = result.reason
                ExportResult.Cancelled -> notice = "ذخیرهٔ فایل لغو شد."
            }
        }
    }

    /**
     * Brings back a previously saved GGUF. The SHA-256 decides which model it is, so the file
     * can be any of the catalogue entries — the selection follows the file.
     */
    fun restoreModelFromFile(uri: android.net.Uri) {
        io({
            transfer.import(
                openInput = { store.openInput(uri) },
                onProgress = { done, total ->
                    localModel = localModel.copy(
                        phase = ModelPhase.DOWNLOADING,
                        downloadedBytes = done,
                        totalBytes = if (total > 0) total else localModel.totalBytes
                    )
                }
            )
        }) { result ->
            when (result) {
                is ImportResult.Installed -> {
                    repos.putPreference(KEY_MODEL_ID, result.spec.id)
                    engine.unload()
                    repos.audit("user", "model.import", result.spec.id, "sha256 verified")
                    localModel = localModel.copy(specId = result.spec.id, downloadedBytes = result.spec.bytes)
                    val loaded = engine.load(modelStore.file(result.spec), result.spec)
                    localModel = localModel.copy(
                        engineReady = loaded,
                        backend = engine.backend,
                        message = if (loaded) null else engine.lastError
                    )
                    refreshLocal()
                    notice = if (loaded) {
                        "مدل «${result.spec.title}» از فایل برگردانده و بارگذاری شد؛ دانلودی لازم نبود."
                    } else {
                        "فایل تأیید شد ولی بارگذاری نشد: ${engine.lastError}"
                    }
                }
                is ImportResult.Failed -> {
                    localModel = localModel.copy(phase = ModelPhase.MISSING, message = result.reason)
                    notice = result.reason
                }
                ImportResult.Cancelled -> {
                    refreshLocal()
                    notice = "بازیابی فایل لغو شد."
                }
            }
        }
    }

    /**
     * «یاد بگیر» — searches every engine, harvests what it finds, layers it, and keeps asking
     * until a whole round adds nothing. The knowledge it stores answers later questions through
     * the normal knowledge gate, so this is a one-time cost per topic.
     */
    fun studyTopic(topic: String, deep: Boolean = false) {
        val clean = topic.trim()
        if (clean.length < 3) {
            notice = "موضوع خیلی کوتاه است؛ یک نام یا عبارت بنویس."
            return
        }
        if (studyRunning) return
        studyRunning = true
        val langTag = if (clean.any { it in '\u0600'..'\u06FF' }) "fa" else "en"
        viewModelScope.launch {
            val brain = if (localModel.usingForAnswers && engine.ready) LocalBrain(engine, localModel.spec) else null
            val budget = StudyBudget.byName(if (deep) "deep" else repos.researchDepth())
            val outcome = withContext(Dispatchers.IO) {
                runCatching { tools.deepStudy(clean, langTag, budget, brain) }
            }
            studyRunning = false
            outcome.onFailure { notice = "یادگیری ناموفق: ${it.message ?: "خطای نامشخص"}" }
            outcome.onSuccess { result ->
                studySummary = result.summary
                studyLayers = result.facets.map { facet ->
                    val count = result.learned.count { it.layer == facet.layer }
                    val sources = result.learned.filter { it.layer == facet.layer }.map { it.domain }.distinct().size
                    Triple(facet.layer, count, sources)
                }
                studyGaps = result.gaps
                val answered = result.engines.count { it.value > 0 }
                studyEngineLine = "موتورهای پاسخ‌دهنده: $answered" +
                    if (result.failures.isEmpty()) "" else " — بی‌نتیجه: " +
                        result.failures.entries.take(6).joinToString("، ") { "${it.key}: ${it.value.take(40)}" }
                notice = result.summary
                refreshSecondary()
            }
        }
    }

    /** Loads the verified file into RAM now (first load of the session costs a few seconds). */
    fun loadModelNow() {
        val spec = localModel.spec
        io({ engine.load(modelStore.file(spec), spec) }) { loaded ->
            localModel = localModel.copy(
                engineReady = loaded,
                phase = if (loaded) ModelPhase.READY else localModel.phase,
                backend = engine.backend,
                message = if (loaded) null else engine.lastError
            )
            notice = if (loaded) "مدل در حافظه بارگذاری شد." else "بارگذاری نشد: ${engine.lastError}"
        }
    }

    fun setLocalAnswers(enabled: Boolean) {
        repos.putPreference(KEY_MODEL_USE, if (enabled) "1" else "0")
        localModel = localModel.copy(usingForAnswers = enabled)
        if (enabled && !engine.ready && modelStore.installed(localModel.spec)) loadModelNow()
        notice = if (enabled) {
            "بازنویسی پاسخ‌ها با مدل محلی روشن شد (شاهدها و اعداد از قبل همان‌اند)."
        } else {
            "بازنویسی پاسخ‌ها با قالب قطعی برگشت."
        }
    }

    /** وقتی روشن است، محاسبه و سوال مستقیم مستقیماً به مدل lokal می‌رود (بدون جستجوب وب). */
    fun toggleDirectModel(enabled: Boolean) {
        repos.putPreference(KEY_DIRECT_MODEL, if (enabled) "1" else "0")
        directModel = enabled
        notice = if (enabled) {
            "محاسبه و سوال مستقیم مستقیماً به مدل می‌رود (بدون جستجوب)."
        } else {
            "محاسبه و سوال مستقیم به جستجوب برمی‌گردد."
        }
    }

    /** Runs the model once on a harmless prompt: the only proof that it works *on this device*. */
    fun testLocalEngine() {
        val spec = localModel.spec
        io({
            if (!engine.ready) {
                if (!engine.load(modelStore.file(spec), spec)) return@io LocalGeneration(error = engine.lastError)
            }
            engine.generate(ir.aai.hushyar.domain.LocalPrompt.selfTest(spec), 48)
        }) { result ->
            localProof = when {
                result.ok -> "پاسخ مدل (${result.millis}ms): ${result.text}"
                else -> "موتور جواب نداد: ${result.error}"
            }
            localModel = localModel.copy(engineReady = engine.ready, backend = engine.backend, lastTest = localProof)
            refreshLocal()
        }
    }

    /** Free chat with the downloaded model. Labelled as model text; never stored as knowledge. */
    fun askLocal(question: String) {
        io({
            LocalChatter(engine, localModel.spec).reply(question)
        }) { reply ->
            localProof = reply.replyLine()
            localModel = localModel.copy(lastTest = localProof)
        }
    }

    // ---------- IronPin offline database ----------
    fun seedIronKnowledge() {
        io({ ir.aai.hushyar.domain.IronSeed.seed(repos) }) { count ->
            refreshSecondary()
            notice = "دیتابیس آفلاین بارگذاری شد: $count نکته به دانش اضافه شد."
        }
    }
    fun reseedIronKnowledge() {
        io({ ir.aai.hushyar.domain.IronSeed.reseed(repos) }) { count ->
            refreshSecondary()
            notice = "دیتابیس بازنصب شد: $count نکته."
        }
    }

    // ---------- Qwen اسکنر آنلاین: هر ۲۴ ساعت دقیقِ روشِ Qwen ----------
    fun qwenSyncStatus(): String = ir.aai.hushyar.domain.QwenSync.statusLine(repos)
    fun qwenVerifyFromBundle(context: android.content.Context): String {
        return try {
            val json = context.assets.open("qwen_batch_bb9e0626.json").bufferedReader().readText()
            ir.aai.hushyar.domain.QwenSync.verify(json).report
        } catch (e: Exception) { "فایل باندل یافت نشد: ${e.message}" }
    }
    fun syncQwenNow(context: android.content.Context) {
        if (loading) return
        io({
            val json = try { context.assets.open("qwen_batch_bb9e0626.json").bufferedReader().readText() } catch (_: Exception) { null }
            if (json != null && ir.aai.hushyar.domain.QwenSync.needsSeed(repos)) {
                ir.aai.hushyar.domain.QwenSync.importFromAssetsJson(json, repos)
            }
            // همگام‌سازیِ همه (بَچ 242 + اسکن بزرگ 4092) — با transport بزرگ 6MB
            val res = ir.aai.hushyar.domain.QwenSync.fetchAllAndImport(repos, ir.aai.hushyar.domain.QwenSync.largeTransport())
            res.getOrThrow()
        }) { r ->
            refreshSecondary()
            notice = "همگام‌سازی Qwen: ${r.totalItemsInBatch} نکته کل (${r.imported} تازه، ${r.refreshed} به‌روز). آخرین بَچ ${r.batchId}. ${r.verification.report}"
        }
    }
    fun syncQwenFromAssets(context: android.content.Context) {
        io({
            val json = context.assets.open("qwen_batch_bb9e0626.json").bufferedReader().readText()
            ir.aai.hushyar.domain.QwenSync.importFromAssetsJson(json, repos)
        }) { r ->
            refreshSecondary()
            notice = "دانشِ اسکنر از باندل اضافه شد: ${r.totalItemsInBatch} نکته. ${r.verification.report}"
        }
    }
    fun loggingSummary(): String = if (logging) "روشن — ${logLines.size} رویداد" else "خاموش"
    fun exportLogMarkdown() {
        io({
            val md = ir.aai.hushyar.domain.Markdown.fullBrain(
                items = repos.memoryAll(2000), events = repos.events(500),
                audits = repos.auditsAll(500), packs = repos.packs(),
                listingCount = repos.listings().size, generatedAt = System.currentTimeMillis(),
                knowledge = repos.knowledgeAll(2000)
            )
            val file = store.writeLog("hushyar-log-${store.stamp()}", md)
            repos.log("log", "user", "خروجی لاگ .md", file.name)
            file.name to md
        }) { (name, md) -> lastMarkdown = md; notice = "لاگ به Markdown ذخیره شد: $name" }
    }
    // exportModelToUri is same as saveModelToFile — kept for SettingsScreen compatibility
    fun testModel(prompt: String) {
        if (!engine.ready) { notice = "مدل بارگذاری نشده — اول «بارگذاری در RAM» را بزن."; return }
        io({ engine.generate(prompt, 128) }) { gen ->
            localProof = if (gen.ok) "تست: ${gen.text}" else "خطا: ${gen.error}"
            localModel = localModel.copy(lastTest = localProof)
            notice = localProof ?: ""
        }
    }
    // Expose for SettingsScreen
    val modelOptions: List<ir.aai.hushyar.domain.ModelSpec> get() = ir.aai.hushyar.domain.ModelCatalog.all
    val localTestResult: String? get() = localProof

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

    private data class ChatSnapshot(
        val threads: List<ChatThread>, val selected: String?, val turns: List<ChatTurn>
    )

    private fun chatSnapshot(preferredId: String? = null): ChatSnapshot {
        val threads = repos.chatThreads()
        val selected = preferredId?.takeIf { id -> threads.any { it.id == id } }
            ?: repos.chatSelectedId()?.takeIf { id -> threads.any { it.id == id } }
            ?: threads.firstOrNull()?.id
        if (selected != null && selected != repos.chatSelectedId()) repos.chatSelect(selected)
        return ChatSnapshot(threads, selected, selected?.let(repos::chatTurns).orEmpty())
    }

    private fun showChat(snapshot: ChatSnapshot, keepDiagnostics: Boolean = false) {
        if (selectedChatId != snapshot.selected) personalDocuments = emptyList()
        chatThreads = snapshot.threads
        selectedChatId = snapshot.selected
        chatTurns = snapshot.turns
        lastAnswer = snapshot.turns.lastOrNull { it.answer != null }?.answer
        if (!keepDiagnostics) {
            steps = emptyList()
            lastDecision = null
        }
    }

    /** Reopens the last selected conversation on startup and after a restore. */
    fun reloadChats() {
        chatBusy = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { chatSnapshot() } }
            result.onSuccess { showChat(it) }.onFailure { notice = it.message ?: "خواندن گفت‌وگوها ناموفق بود" }
            chatBusy = false
        }
    }

    private fun manageChat(successNotice: String? = null, action: () -> Unit) {
        if (chatBusy || loading) return
        chatBusy = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { action(); chatSnapshot() } }
            result.onSuccess {
                showChat(it)
                if (successNotice != null) notice = successNotice
            }.onFailure { notice = it.message ?: "تغییر گفت‌وگو ناموفق بود" }
            chatBusy = false
        }
    }

    fun newChat() = manageChat { repos.chatCreate() }

    fun selectChat(id: String) {
        if (id == selectedChatId || chatThreads.none { it.id == id }) return
        manageChat { repos.chatSelect(id) }
    }

    fun renameChat(id: String, title: String) = manageChat("عنوان گفت‌وگو تغییر کرد.") {
        repos.chatRename(id, title)
    }

    fun deleteChat(id: String) = manageChat("گفت‌وگو و پیام‌هایش از این گوشی پاک شدند.") {
        repos.chatDelete(id)
    }

    private data class ChatStart(val threadId: String, val turn: ChatTurn, val history: List<ChatTurn>, val snapshot: ChatSnapshot)

    /** The user turn is committed before any model or web work; each thread has its own context. */
    fun ask(question: String): Boolean {
        val text = question.trim()
        if (text.isEmpty() || chatBusy || loading) return false
        chatBusy = true
        loading = true
        steps = emptyList()
        lastDecision = null
        val chosenId = selectedChatId
        viewModelScope.launch {
            val started = withContext(Dispatchers.IO) { runCatching {
                val threadId = chosenId?.takeIf { id -> repos.chatThreads().any { it.id == id } }
                    ?: repos.chatCreate().id
                // Even a failed prior user message is still the previous question
                // for explicit recall. Planning uses answered turns only.
                val history = repos.chatTurns(threadId)
                val turn = repos.chatAppend(threadId, text)
                ChatStart(threadId, turn, history, chatSnapshot(threadId))
            } }
            if (started.isFailure) {
                notice = started.exceptionOrNull()?.message ?: "ذخیرهٔ پیام ناموفق بود"
                loading = false
                chatBusy = false
                return@launch
            }
            val start = started.getOrThrow()
            showChat(start.snapshot)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val generator = if (localModel.usingForAnswers && engine.ready) engine else null
                    // When directModel is on, skip web search/grounding and ask the local model directly.
                    // Explicit commands (حساب کن: / از اسناد من:) still keep their own path.
                    val directAnswer = if (directModel && PrivateRoute.calculate(text) == null &&
                        PrivateRoute.documents(text) == null && engine.ready) {
                        val gen = ir.aai.hushyar.domain.LocalChatter(engine, localModel.spec)
                            .reply(text, "fa", start.history)
                        if (gen.ok) {
                            ir.aai.hushyar.domain.Answer(
                                intent = "chat/direct",
                                text = gen.text!!,
                                confidence = 0.7,
                                trace = listOf("مدل محلی — بدون جستجوب وب")
                            )
                        } else null
                    } else null
                    // Explicit tools must not be rewritten into another product or sent to a model.
                    val resolution = if (PrivateRoute.calculate(text) != null || PrivateRoute.documents(text) != null)
                        ConversationResolution(text)
                    else ConversationContext.resolve(text, start.threadId, start.history, generator, localModel.spec)
                        .copy(directAnswer = directAnswer ?: null)
                    val purchase = if (resolution.directAnswer == null &&
                        PrivateRoute.calculate(text) == null && PrivateRoute.documents(text) == null)
                        PurchaseComposer.plan(text, resolution.effectiveQuestion, start.threadId,
                            start.history, resolution.usedHistory) else null
                    val outcome = resolution.directAnswer?.let { AgentOutcome(it, emptyList(), emptyList()) }
                        ?: if (purchase != null) {
                            // A user-supplied per-unit rate is a hypothetical worksheet, not a
                            // reason to fetch and silently replace it with a market quote.
                            val base = if (purchase.parts.userRate != null) AgentOutcome(
                                Answer("purchase.user-input", "محاسبه از نرخ اعلامی شما؛ بدون جستجوی قیمت"),
                                emptyList(), emptyList()
                            ) else answerQuestion(purchase.priceQuestion, start.history,
                                resolution.forceFresh, start.threadId)
                            val rate = if (resolution.forceFresh) null else PurchaseComposer.storedRate(
                                purchase.priceQuestion, repos.knowledgeAll(800), System.currentTimeMillis())
                            val composed = PurchaseComposer.answer(purchase, rate)
                            base.copy(answer = composed.copy(trace = composed.trace + base.answer.trace.take(4)),
                                steps = base.steps + AgentStep(base.steps.size + 1,
                                    "جمع شرطیِ قیمت، مالیات و حمل از داده‌های نوع‌دار و فرض‌های صریح",
                                    "purchase.compose", emptyMap(),
                                    if (composed.refused) "مولفه‌ها ناقص‌اند" else "همهٔ مولفه‌های لازم مشخص‌اند"))
                        } else answerQuestion(resolution.effectiveQuestion, start.history,
                            resolution.forceFresh, start.threadId)
                    val answer = if (resolution.usedHistory) outcome.answer.copy(
                        trace = listOf(resolution.note) + outcome.answer.trace
                    ) else outcome.answer
                    repos.chatComplete(start.turn.id, resolution.effectiveQuestion, answer)
                    outcome.copy(answer = answer) to resolution
                }.onFailure { error ->
                    runCatching { repos.chatFail(start.turn.id, error.message ?: "پاسخ به پایان نرسید؛ دوباره تلاش کنید.") }
                }
            }
            // The saved answer (or a failed user turn) is loaded from the same database that survives
            // an APK update. Never show an unsaved answer as though it were durable.
            val saved = withContext(Dispatchers.IO) { runCatching { chatSnapshot(start.threadId) } }
            saved.onSuccess { showChat(it, keepDiagnostics = result.isSuccess) }
                .onFailure { notice = "خواندن پیام‌های ذخیره‌شده ناموفق بود: ${it.message}" }
            loading = false
            chatBusy = false
            result.onSuccess { (outcome, _) ->
                steps = outcome.steps
                growth = Growth.report(repos.knowledgeAll(800), tools.goalsNow(), System.currentTimeMillis())
                refreshSecondary()
            }.onFailure { notice = "پاسخ به پایان نرسید؛ پیام روی گوشی ماند: ${it.message ?: "خطای نامشخص"}" }
        }
        return true
    }

    /**
     * The agent loop: data commands, local catalog, memory and web research, then learning.
     * It receives the *resolved* question, before decision and evidence gathering.
     */
    private fun answerQuestion(question: String, prior: List<ChatTurn>, forceFresh: Boolean, threadId: String): AgentOutcome {
            PrivateRoute.calculate(question)?.let { expression ->
                val answer = SafeCalculator.answer(expression)
                return AgentOutcome(answer, listOf(AgentStep(1, "محاسبهٔ محدود روی همین دستگاه", "calculator",
                    emptyMap(), if (answer.refused) "عبارت رد شد" else "فقط عبارت حسابی اجرا شد")), emptyList())
            }
            PrivateRoute.documents(question)?.let { query ->
                val answer = when {
                    query.isBlank() -> Answer("personal.docs", "پس از «از اسنادم:» پرسش را بنویس.",
                        refused = true, missing = listOf("پرسش دربارهٔ سند"))
                    !personalBackendConfigured -> Answer("personal.docs",
                        "بک‌اند شخصی تنظیم نشده است؛ گفتگوهای معمولی همچنان روی همین گوشی کار می‌کنند.",
                        refused = true, missing = listOf("اتصال اختیاری به بک‌اند شخصی"))
                    else -> runCatching {
                        val config = store.personalBackend.read() ?: error("اتصال شخصی خاموش است")
                        privateBackend.ask(config, threadId, query)
                    }.getOrElse { Answer("personal.docs",
                        "سرویس اسناد شخصی در دسترس نیست یا گواهی/کلید آن معتبر نیست؛ " +
                            "از پاسخ‌های قدیمی یا مدلِ بدون شاهد به‌جای سند استفاده نکردم.",
                        refused = true, missing = listOf("اتصال و شاهد در اسناد شخصی"),
                        trace = listOf("درخواست مستند شکست خورد؛ پاسخ حدسی یا کش‌شده نمایش داده نشد.")) }
                }
                return AgentOutcome(answer, listOf(AgentStep(1, "جستجو در اسناد همین گفتگو روی بک‌اند شخصی", "personal.docs",
                    emptyMap(), if (answer.refused) "شاهد کافی نیست یا سرور در دسترس نیست" else "نقل‌قول‌ها با شناسهٔ سند ارائه شدند")),
                    emptyList())
            }
            // v0.11: «من فروشگاه تی‌شرت و شلوار هستم» یک پرسش نیست، یک معرفی است. برنامه از آن
            // بستهٔ دانش می‌سازد و ذخیره می‌کند؛ از آن به بعد همان مسیر بی‌حوزه جواب می‌دهد.
            val setupText = ir.aai.hushyar.domain.DomainPacks.DomainSetup.describe(question)
            if (setupText != null) {
                repos.putPreference(ir.aai.hushyar.domain.AnyDomainBrain.CUSTOM_DOMAIN_KEY, setupText)
                val built = ir.aai.hushyar.domain.DomainPacks.fromDescription(setupText)
                val entities = built?.entities?.joinToString("، ") { it.label }.orEmpty()
                val attrs = built?.entities?.firstOrNull()?.attributes?.joinToString("، ") { it.label }.orEmpty()
                return ir.aai.hushyar.domain.AgentOutcome(
                    answer = ir.aai.hushyar.domain.Answer(
                        intent = "domain.setup",
                        text = "بازار تو ثبت شد: ${built?.name ?: setupText.take(40)}",
                        bullets = listOf(
                            "کالاها: $entities",
                            if (attrs.isNotBlank()) "ویژگی‌هایی که دنبالشان می‌گردم: $attrs" else "",
                            "از این به بعد قیمت و ویژگی این کالاها را می‌پرسم؛ هر چه پیدا کنم در دانش ذخیره می‌شود و دفعهٔ بعد بدون جستجو جواب می‌دهم.",
                            "برای عوض کردنش کافی است دوباره بگویی «من فروشگاه … هستم»."
                        ).filter { it.isNotBlank() },
                        confidence = 0.9,
                        lang = "fa",
                        trace = listOf("domain.setup → بستهٔ دانش از توضیح کاربر ساخته و ذخیره شد")
                    ),
                    steps = listOf(
                        ir.aai.hushyar.domain.AgentStep(
                            1, "معرفی بازار را خواندم و بستهٔ دانش ساختم", "domain.setup",
                            mapOf("custom" to "1"), "کالاها: $entities"
                        )
                    ),
                    actions = listOf("preference.write:${ir.aai.hushyar.domain.AnyDomainBrain.CUSTOM_DOMAIN_KEY}")
                )
            }

            // v0.9: the local model is the brain. It is told the market, the standing goals and how
            // much is already stored, then chooses one action; the code below only carries it out.
            // Without a model — or when the model answers with something unusable — the same
            // decision comes from LocalPlanner.heuristic, and `fromModel` records which one it was.
            val langForPlan = if (question.any { it in '\u0600'..'\u06FF' }) "fa" else "en"
            val known = repos.knowledgeAll(800)
            val goals = tools.goalsNow()
            val briefing = AgentBriefing(
                request = question,
                lang = langForPlan,
                goals = goals,
                marketName = pack?.name.orEmpty(),
                marketSells = pack?.categories?.take(3)?.joinToString("، ").orEmpty(),
                knownFacts = known.size,
                knownDomains = known.map { it.domain }.filter { it.isNotBlank() }.distinct().size,
                memoryHit = repos.knowledgeAnswerable(question, now = System.currentTimeMillis(), limit = 3).isNotEmpty(),
                topFacts = repos.knowledgeAnswerable(question, now = System.currentTimeMillis(), limit = 3)
                    .map { it.claim },
                marketAttributes = pack?.attributes?.map { it.label }.orEmpty(),
                gaps = known.isEmpty().let { if (it) listOf("چیزی ذخیره نشده") else emptyList() },
                dialogue = prior.takeLast(4).map { "کاربر: ${it.question.take(70)}؛ پاسخ: ${it.answer?.text?.take(80).orEmpty()}" }
            )
            // کاربر خودش نشانی داده است: *پیش* از مدل و پویش/یادگیریِ خودکار همان صفحه‌ها را
            // باز می‌کنیم. مدل کوچک نه قیمت را استخراج می‌کند و نه حق ساختن عدد دارد.
            val handedSources = UrlIngest.hasLinks(question) && UrlIngest.asksPrice(question)
            val planned = if (handedSources) {
                AgentDecision(LocalAction.RESEARCH, question, "اول نشانی‌های داخل پرسش را باز کن", false)
            } else if (forceFresh) {
                AgentDecision(LocalAction.RESEARCH, question, "درخواست بررسی تازه؛ پاسخ ذخیره‌شده کافی نیست", false)
            } else LocalPlanner.decide(
                request = question,
                lang = langForPlan,
                briefing = briefing,
                generator = if (localModel.usingForAnswers && engine.ready) engine else null,
                spec = localModel.spec
            )
            // A small model can mistakenly call a price/number question "chat". Never let a
            // knowledge request take the ungrounded greeting shortcut.
            val knowledgeQuestion = QuestionReader.read(question).ask in setOf(
                AskType.PRICE, AskType.AMOUNT, AskType.DATE, AskType.COMPARISON
            ) || UrlIngest.hasLinks(question)
            val decision = if (planned.action == LocalAction.GREET && knowledgeQuestion)
                AgentDecision(LocalAction.RESEARCH, question, "پرسش دانشی به شاهد نیاز دارد؛ گفت‌وگوی بی‌منبع کافی نیست", false)
            else planned
            lastDecision = decision
            repos.log("brain", "local", "${decision.action.name} → ${decision.reason}", decision.query.take(120))

            if (decision.action == LocalAction.GREET) {
                // Pure talk only. With no opted-in model (or a generation failure), use a clearly
                // labelled local greeting instead of sending a greeting to web search.
                val chat = if (localModel.usingForAnswers && engine.ready)
                    runCatching { LocalChatter(engine, localModel.spec).reply(question, langForPlan, prior) }.getOrNull()
                else null
                val text = chat?.text?.trim()?.takeIf { it.isNotBlank() }
                val template = when {
                    Text.normalize(question).contains("ممنون") || Text.normalize(question).contains("مرسی") ->
                        "خواهش می‌کنم. اگر سؤال بعدی به همین موضوع مربوط است، ادامه بده."
                    Text.normalize(question).contains("خداحافظ") -> "خدانگهدار! این گفت‌وگو روی گوشی‌ات می‌ماند."
                    else -> "سلام! سؤالت را بپرس؛ برای عدد و قیمت فقط با شاهد پاسخ می‌دهم."
                }
                return AgentOutcome(
                    answer = Answer(
                        intent = if (text != null) "chat/local" else "chat/template",
                        text = text ?: template,
                        lang = "fa",
                        confidence = 0.2,
                        trace = listOf(if (text != null) "گفت‌وگوی مدل محلی؛ شاهد وب ندارد"
                            else "پاسخ قالبی محلی؛ مدل فعال نبود یا پاسخی نداد؛ شاهد وب ندارد")
                    ),
                    steps = listOf(ir.aai.hushyar.domain.AgentStep(
                        1, "گفت‌وگوی ساده — ${decision.reason}",
                        if (text != null) "brain/local-model" else "brain/rules",
                        mapOf("action" to decision.action.name), "بدون جستجو، بدون شاهد"
                    )), actions = emptyList()
                )
            }
            when (decision.action) {
                LocalAction.STUDY -> {
                    val topic = decision.query.ifBlank { question }
                    val langTag = if (topic.any { it in '\u0600'..'\u06FF' }) "fa" else "en"
                    val brain = if (localModel.usingForAnswers && engine.ready) LocalBrain(engine, localModel.spec) else null
                    runCatching { tools.deepStudy(topic, langTag, StudyBudget.byName(repos.researchDepth()), brain) }
                }
                else -> Unit
            }

            // v0.8: a knowledge request ("یاد بگیر…", "همه‌چیز درباره…") runs the full study loop
            // first — every engine, layered — so the answer below is built from fresh material
            // that also stays for later questions.
            if (!handedSources && !forceFresh && StudyTrigger.wantsStudy(question)) {
                val topic = StudyTrigger.topicOf(question)
                val langTag = if (topic.any { it in '\u0600'..'\u06FF' }) "fa" else "en"
                val brain = if (localModel.usingForAnswers && engine.ready) LocalBrain(engine, localModel.spec) else null
                runCatching { tools.deepStudy(topic, langTag, StudyBudget.byName(repos.researchDepth()), brain) }
            }
            // The model, when installed and switched on, only re-phrases grounded drafts.
            // Same evidence, same citations, same refusals — different sentence.
            val spec = localModel.spec
            val phraserFactory: (String) -> ir.aai.hushyar.domain.Phraser = { lang ->
                if (localModel.usingForAnswers && engine.ready) {
                    LocalPhraser(lang, engine, spec, directive = directive())
                } else {
                    TemplatePhraser(lang)
                }
            }
            // v0.11: بستهٔ دانشی که از حرف خودِ کاربر ساخته شده، و مدل محلی در نقش یادگیرنده —
            // نه فقط بازنویس جمله.
            val customDomain = runCatching {
                repos.preference(ir.aai.hushyar.domain.AnyDomainBrain.CUSTOM_DOMAIN_KEY)
                    ?.let { ir.aai.hushyar.domain.DomainPacks.fromDescription(it) }
            }.getOrNull()
            val learningGenerator = if (localModel.usingForAnswers && engine.ready) engine else null
            val agent = Agent(
                tools, phraserFactory,
                customDomain = customDomain,
                generator = learningGenerator,
                modelSpec = spec
            )
            // "Answer from what we already know" means exactly that: the web is not touched. If the
            // stored knowledge cannot carry it, the agent refuses honestly and we then allow one
            // web pass — recorded, not hidden.
            var outcome = agent.run(question, allowWeb = decision.action != LocalAction.ANSWER_MEMORY, forceFresh = forceFresh)
            var corrected = false
            if (outcome.answer.refused && decision.action == LocalAction.ANSWER_MEMORY) {
                outcome = agent.run(question, allowWeb = true)
                corrected = true
            }
            val brainStep = ir.aai.hushyar.domain.AgentStep(
                index = 0,
                thought = "هستهٔ محلی: ${decision.action.fa} — ${decision.reason}" +
                    (if (corrected) " (دانش کافی نبود، یک بار جستجو مجاز شد)" else ""),
                tool = if (decision.fromModel) "brain/local-model" else "brain/rules",
                args = mapOf("action" to decision.action.name, "query" to decision.query.take(120)),
                observation = briefing.prompt().take(400)
            )
            outcome = outcome.copy(steps = listOf(brainStep) + outcome.steps.mapIndexed { i, s -> s.copy(index = i + 1) })
            // لاگ واقعاً پیش‌فرض خاموش است: نه فقط جدول audit، بلکه فایل QA و جدول event هم
            // خودبه‌خود نوشته نمی‌شوند. خروجی صریحِ کاربر همچنان با دکمهٔ خودش ساخته می‌شود.
            if (repos.loggingEnabled()) {
                val transcript = Markdown.answer(question, outcome.answer, outcome.steps, System.currentTimeMillis())
                val file = store.writeLog("qa-${store.stamp()}", transcript)
                repos.audit("agent", "qa.answer", outcome.answer.intent, "refused=${outcome.answer.refused} md=${file.name}")
            }
            // v0.5: learn from the question we just answered — a light pass on the same question, so
            // the next similar one is faster (a case hit costs zero network rounds).
            // v0.10: پاسخ‌های «کالا/قیمت» از جدول و محاسبه آمده‌اند و مقدارشان تاریخ دارد؛
            // فرستادنشان به پروندهٔ پاسخ‌ها باعث می‌شد سؤال بعدی قیمت کهنه را از پرونده بخواند.
            val isMaterialAnswer = outcome.answer.intent.startsWith("material.")
            if (!outcome.answer.refused && !isMaterialAnswer && !handedSources) {
                runCatching { proactive.afterAnswer(question) }
            }
            return outcome
    }

    private fun refreshSecondary() {
        io({
            val plan = tools.researchPlan()
            val progress = tools.planProgress()
            val cases = repos.casesAll()
            val goalsNow = tools.goalsNow()
            Secondary(
                memory = repos.memoryAll(300),
                events = repos.events(200),
                backups = store.listBackups().map { it.name },
                knowledge = repos.knowledgeAll(2000),
                totalKnowledge = repos.countKnowledge(),
                depth = repos.researchDepth(),
                planLines = plan.describe(progress.statuses()),
                planRatio = plan.ratio(progress.statuses()),
                planGoal = plan.goal,
                caseSummary = ir.aai.hushyar.domain.CaseStore.summary(cases, System.currentTimeMillis()),
                caseCount = cases.size,
                goals = goalsNow,
                probes = tools.lastProbe(),
                engineCount = ir.aai.hushyar.domain.Engines.all.size
            )
        }) {
            growth = Growth.report(it.knowledge, it.goals, System.currentTimeMillis())
            apply(it)
        }
    }

    private data class Secondary(
        val memory: List<MemoryItem>,
        val events: List<LogEvent>,
        val backups: List<String>,
        val knowledge: List<KnowledgeItem>,
        val totalKnowledge: Int = 0,
        val depth: String,
        val planLines: List<String> = emptyList(),
        val planRatio: Double = 0.0,
        val planGoal: String = "",
        val caseSummary: String = "",
        val caseCount: Int = 0,
        val goals: List<String> = emptyList(),
        val probes: List<Triple<String, Boolean, Int>> = emptyList(),
        val engineCount: Int = 0
    )

    private fun apply(snapshot: Secondary) {
        memories = snapshot.memory
        events = snapshot.events
        backupFiles = snapshot.backups
        knowledge = snapshot.knowledge
        totalKnowledgeCount = snapshot.totalKnowledge
        researchDepth = snapshot.depth
        goals = snapshot.goals
        probes = snapshot.probes
        engineCount = snapshot.engineCount
        planLines = snapshot.planLines
        planRatio = snapshot.planRatio
        planGoal = snapshot.planGoal
        caseSummary = snapshot.caseSummary
        caseCount = snapshot.caseCount
        dataFiles = store.inventory()
    }

    fun refreshData() = refreshSecondary()

    /**
     * Register the goal in the user's own words. From here the plan exists even with no market pack,
     * and the scheduled job continues it without being asked.
     */
    fun setGoal(text: String) {
        val clean = text.trim()
        if (clean.length < 3) {
            notice = "هدف خیلی کوتاه است؛ یک جمله بنویس."
            return
        }
        io({ tools.setGoal(clean) }) { plan ->
            notice = "هدف ثبت شد؛ ${plan.layers.size} لایه برای کاوش ساخته شد."
            refreshSecondary()
        }
    }

    /** Ask every known engine one question and show which ones actually answered. */
    fun testEngines() {
        if (probing) return
        probing = true
        val query = goals.firstOrNull() ?: chatTurns.lastOrNull { it.answer != null }?.question ?: "قیمت گوشی"
        io({ tools.probeEngines(query) }) { result ->
            probing = false
            probes = result.map { Triple(it.id, it.ok, it.hits) }
            val ok = result.count { it.ok }
            notice = "آزمون موتورها: $ok از ${result.size} موتور جواب دادند."
            refreshSecondary()
        }
    }

    /**
     * One bounded slice of autonomous exploration, on demand. Same code path the scheduler uses, so
     * what the button does and what happens in the background are the same thing.
     */
    fun exploreNow() {
        if (proactiveRunning) return
        proactiveRunning = true
        io({ proactive.tick() }) { outcome ->
            proactiveRunning = false
            lastProactive = when {
                outcome == null -> "پلان تازه‌ای برای کاوش نیست (یا کامل شده)."
                else -> "لایه‌های اجراشده: ${outcome.ranLayers.joinToString("، ").ifBlank { "—" }}؛ " +
                    "پرسش ${outcome.queries.size}، صفحه ${outcome.pages.size}، آموخته ${outcome.learned.size}؛ " +
                    "توقف: ${outcome.stop}"
            }
            notice = lastProactive
            refreshSecondary()
        }
    }

    fun closeProactiveNotice() {
        lastProactive = null
    }

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
            notice = "بک‌آپ ساخته شد: ${result.file.name}\n${BackupService.summary(result.info)}\nبرای نگه‌داشتن بیرون از اپ، دکمهٔ «ذخیره بیرون از اپ» را بزن."
            lastMarkdown = result.markdown
            refreshSecondary()
        }
    }

    /** A private backup dies with an uninstall. Let the user save the ZIP outside the app. */
    fun exportBackupToUri(name: String, uri: android.net.Uri) {
        io({
            val file = store.backupDir.listFiles()?.firstOrNull { it.name == name && it.extension == "zip" }
                ?: throw IllegalArgumentException("فایل پشتیبان پیدا نشد")
            val output = store.openOutput(uri) ?: throw IllegalStateException("محل ذخیره باز نشد")
            val copied = output.use { stream -> file.inputStream().use { it.copyTo(stream) } }
            require(copied == file.length()) { "کپی پشتیبان ناقص بود" }
            copied
        }) { copied ->
            notice = "پشتیبان بیرون از اپ ذخیره شد (${ir.aai.hushyar.core.Text.num(copied)} بایت). پیش از حذف اپ سالم‌بودن فایل را بررسی کن."
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
        if (chatBusy) { notice = "تا پایان پاسخ جاری برای بازیابی صبر کن."; return }
        io({ BackupService.restore(repos, json) }) { info ->
            notice = "بازیابی انجام شد.\n${BackupService.summary(info)}"
            reloadChats()
            refreshSecondary()
            refresh()
        }
    }

    fun restoreFromText(text: String) {
        if (text.isBlank()) { notice = "متن پشتیبان خالی است."; return }
        restore(text)
    }

    fun restoreBackupFile(name: String) {
        if (chatBusy) { notice = "تا پایان پاسخ جاری برای بازیابی صبر کن."; return }
        io({
            val file = store.backupDir.listFiles()?.firstOrNull { it.name == name }
                ?: throw IllegalArgumentException("فایل پیدا نشد: $name")
            BackupService.restoreFromFile(repos, store, file)
        }) { info ->
            notice = "بازیابی از $name انجام شد.\n${BackupService.summary(info)}"
            reloadChats()
            refreshSecondary()
            refresh()
        }
    }

    fun restoreFromUri(uri: android.net.Uri) {
        if (chatBusy) { notice = "تا پایان پاسخ جاری برای بازیابی صبر کن."; return }
        io({ BackupService.restoreFromUri(repos, store, uri) }) { info ->
            notice = "بازیابی از فایل انجام شد.\n${BackupService.summary(info)}"
            reloadChats()
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
        if (chatBusy || loading) { notice = "تا پایان پاسخ جاری برای پاک‌سازی صبر کن."; return }
        io({ repos.resetEverything(); store.personalBackend.clear(); Unit }) {
            personalBackendUrl = ""
            personalBackendPin = ""
            personalBackendConfigured = false
            personalDocuments = emptyList()
            pack = null
            manifest = null
            listings = emptyList()
            products = emptyList()
            answers = emptyMap()
            chatThreads = emptyList()
            chatTurns = emptyList()
            selectedChatId = null
            lastAnswer = null
            lastDecision = null
            steps = emptyList()
            analysis = null
            notice = "داده‌های روی این گوشی و اتصال بک‌اند پاک شد؛ اسنادِ سرور شخصی جدا هستند و خودکار حذف نشده‌اند."
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

        /** Preference keys: one model is remembered across restarts, plus the on/off switch. */
        const val KEY_MODEL_ID = "local_model_id"
        const val KEY_MODEL_USE = "local_model_use"
        /** v0.13: when on, calculation / direct questions go straight to the local model (no web search). */
        const val KEY_DIRECT_MODEL = "direct_model"

        /** v0.12: the backend page's own settings. */
        const val KEY_GOAL = "backend.goal"
        const val KEY_ENV = "backend.environment"
        const val KEY_DIRECTIVE = "backend.directive"
        const val KEY_SOURCES = "search.sources"

        fun factory(repos: Repos, store: Store): ViewModelProvider.Factory = AppViewModelFactory(repos, store)
    }
}
