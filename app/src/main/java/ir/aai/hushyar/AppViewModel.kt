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
import ir.aai.hushyar.data.CsvImporter
import ir.aai.hushyar.data.Repos
import ir.aai.hushyar.domain.AnalysisResult
import ir.aai.hushyar.domain.Analyzer
import ir.aai.hushyar.domain.Answer
import ir.aai.hushyar.domain.AttributeExtractor
import ir.aai.hushyar.domain.Catalog
import ir.aai.hushyar.domain.Interview
import ir.aai.hushyar.domain.Listing
import ir.aai.hushyar.domain.MarketPack
import ir.aai.hushyar.domain.PackBuildResult
import ir.aai.hushyar.domain.PackCodec
import ir.aai.hushyar.domain.ProductGroup
import ir.aai.hushyar.domain.QaEngine
import ir.aai.hushyar.domain.Resolver
import ir.aai.hushyar.domain.SelfManifest
import ir.aai.hushyar.domain.SelfModel
import ir.aai.hushyar.domain.ValidationReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppViewModel(private val repos: Repos) : ViewModel() {

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

    private val qa = QaEngine()

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

    fun ask(question: String) {
        val current = pack ?: run { notice = "اول بازار را معرفی کنید."; return }
        if (question.isBlank()) return
        io({
            val ls = repos.listings()
            val prods = Resolver.group(ls, current.priceRules)
            val answer = qa.ask(question, Catalog(current, ls, prods))
            repos.addMemory("episode", "پرسش: $question\nپاسخ: ${answer.text}\nامتناع: ${answer.refused}")
            repos.audit("agent", "qa.answer", answer.intent, "refused=${answer.refused}")
            answer
        }) { answer ->
            lastAnswer = answer
            qaHistory = (listOf(question to answer) + qaHistory).take(20)
        }
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
        fun factory(repos: Repos): ViewModelProvider.Factory = AppViewModelFactory(repos)
    }
}
