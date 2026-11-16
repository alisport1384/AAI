package ir.aai.hushyar.domain

import ir.aai.hushyar.core.Lang
import ir.aai.hushyar.core.LangDetect
import ir.aai.hushyar.core.Lexicon
import ir.aai.hushyar.core.Similarity
import ir.aai.hushyar.core.Text

/** One observable step of the reasoning loop; shown in the UI and written to the .md log. */
data class AgentStep(
    val index: Int,
    val thought: String,
    val tool: String,
    val args: Map<String, String>,
    val observation: String
)

data class AgentOutcome(
    val answer: Answer,
    val steps: List<AgentStep>,
    val actions: List<String>,
    val pages: List<ExtractedPage> = emptyList()
)

/**
 * The only way the agent can touch the world. Each function is implemented by the app with
 * SQLite/OkHttp; tests implement it in memory, so the whole loop is testable without Android.
 */
interface AgentTools {
    fun now(): Long
    fun catalog(): Catalog
    fun memoryWrite(kind: String, text: String, source: String): MemoryItem
    fun memorySearch(query: String, limit: Int): List<MemoryHit>
    fun memoryAll(limit: Int): List<MemoryItem>
    fun memoryDelete(id: String): Boolean
    fun memoryTouch(ids: List<String>)
    fun webSearch(query: String, langTag: String): SearchReport
    fun fetchPage(url: String, langTag: String): ExtractedPage?
    fun logAppend(kind: String, actor: String, message: String, detail: String)
    fun logRecent(limit: Int): List<LogEvent>
    fun backupNow(): String
    fun exportLogMarkdown(): String
}

/**
 * Tool-using loop: parse command → act on data → or gather evidence (memory, catalog, web)
 * → verify → answer with citations → learn. Every branch is deterministic, so a failure is
 * reproducible and a refusal is explainable.
 */
class Agent(
    private val tools: AgentTools,
    private val phraserFactory: (String) -> Phraser = { TemplatePhraser(it) },
    private val maxPages: Int = 3,
    private val maxEvidence: Int = 4
) {

    private val qa = QaEngine()

    fun run(question: String, allowWeb: Boolean = true): AgentOutcome {
        val now = tools.now()
        val langEnum = LangDetect.detect(question)
        val langTag = if (langEnum == Lang.EN) "en" else "fa"
        val phraser = phraserFactory(langTag)
        val steps = mutableListOf<AgentStep>()
        val actions = mutableListOf<String>()
        val pack = tools.catalog().pack

        fun step(thought: String, tool: String, args: Map<String, String> = emptyMap(), observation: String = "") {
            steps.add(AgentStep(steps.size + 1, thought, tool, args, observation.take(600)))
        }

        step("دریافت پرسش و تشخیص زبان", "input", mapOf("lang" to langTag, "text" to question.take(120)), "زبان: $langTag")

        // ---- 1. data commands: the assistant edits the app's own data ----
        val command = CommandParser.parse(question)
        if (command != null) {
            val recorder: (String, String, Map<String, String>, String) -> Unit =
                { thought, tool, args, observation -> step(thought, tool, args, observation) }
            val outcome = runCommand(command, question, langTag, now, recorder, actions)
            if (outcome != null) {
                tools.logAppend("command", "agent", "command:${command.kind}", question.take(300))
                return AgentOutcome(outcome.copy(trace = steps.map { "${it.index}. ${it.tool} → ${it.observation.take(160)}" }, actions = actions), steps, actions)
            }
        }

        // ---- 2. local catalog first: cheap, exact, no network ----
        val localAnswer = qa.ask(question, tools.catalog(), now)
        step("پرسش از داده‌های محلی (کاتالوگ بازار)", "catalog.ask", emptyMap(),
            if (localAnswer.refused) "پاسخ محلی ممکن نبود" else "پاسخ محلی با اطمینان ${pct(localAnswer.confidence)}")
        if (!localAnswer.refused && localAnswer.confidence >= 0.6) {
            tools.logAppend("answer", "agent", "local-answer", question.take(300))
            return finish(localAnswer.copy(trace = steps.map { it.tool }), steps, actions)
        }

        // ---- 3. memory: durable user knowledge ----
        val memoryHits = tools.memorySearch(question, 6)
        step("جستجوی حافظه", "memory.search", mapOf("q" to question.take(80)), "نتایج: ${memoryHits.size}")
        val strongMemory = memoryHits.filter { it.score >= 2.0 }.take(3)
        if (strongMemory.isNotEmpty() && !needsFreshData(question)) {
            tools.memoryTouch(strongMemory.map { it.item.id })
            val draft = AnswerDraft(
                intent = "memory.recall",
                headline = if (langTag == "en") "From memory:" else "از حافظه:",
                facts = strongMemory.mapIndexed { i, hit -> Fact("M${i + 1}", hit.item.text.take(240)) },
                citations = strongMemory.mapIndexed { i, hit -> Citation("M${i + 1}", hit.item.category, null) },
                missing = emptyList(),
                confidence = 0.62,
                refused = false,
                lang = langTag
            )
            return finish(phraser.render(Critic.stripUnsupported(draft)).copy(trace = steps.map { it.tool }), steps, actions)
        }

        // ---- 4. web research ----
        if (!allowWeb) {
            val draft = refusedDraft("web.disabled", langTag, listOf(if (langTag == "en") "web search is off" else "جستجوی وب خاموش است"))
            return finish(phraser.render(draft).copy(trace = steps.map { it.tool }), steps, actions)
        }

        val query = buildQuery(question, pack)
        var report = tools.webSearch(query, langTag)
        step("جستجوی وب چند-موتوره", "web.search", mapOf("q" to query),
            "نتیجه: ${report.hits.size} از ${report.perEngine.size} موتور؛ خطا: ${report.failures.size}")
        if (report.hits.size < 3) {
            val retryQuery = buildQuery(question, pack, widen = true)
            if (retryQuery != query) {
                report = tools.webSearch(retryQuery, langTag)
                step("بازنویسی پرسش و جستجوی دوباره (بازتاب)", "web.search", mapOf("q" to retryQuery),
                    "نتیجهٔ دور دوم: ${report.hits.size}")
            }
        }
        if (report.isEmpty) {
            val reasons = report.failures.entries.take(3).joinToString("؛ ") { "${it.key}: ${it.value}" }
            tools.logAppend("refusal", "agent", "no-web-results", "$query | $reasons")
            val draft = refusedDraft(
                "web.empty", langTag,
                listOf(
                    if (langTag == "en") "no engine returned a usable result" else "هیچ موتوری نتیجهٔ قابل استفاده نداد",
                    if (reasons.isNotBlank()) (if (langTag == "en") "engine errors: $reasons" else "خطای موتورها: $reasons") else ""
                ).filter { it.isNotBlank() }
            )
            return finish(phraser.render(draft).copy(trace = steps.map { it.tool }), steps, actions)
        }

        val pages = mutableListOf<ExtractedPage>()
        for (hit in report.hits.take(maxPages)) {
            val page = tools.fetchPage(hit.url, langTag)
            when {
                page == null -> step("واکشی صفحه", "web.fetch", mapOf("url" to hit.url), "ناموفق یا خالی")
                page.signals.isNotEmpty() -> step(
                    "واکشی صفحه", "web.fetch", mapOf("url" to hit.url),
                    "کنار گذاشته شد: نشانهٔ تزریق دستور (${page.signals.joinToString(",")})"
                )
                page.text.length < 200 -> step("واکشی صفحه", "web.fetch", mapOf("url" to hit.url), "متن کافی نداشت (${page.text.length} نویسه)")
                else -> {
                    pages.add(page)
                    step("واکشی صفحه", "web.fetch", mapOf("url" to hit.url), "خوب: ${page.text.length} نویسه")
                }
            }
        }

        val evidence = Extractive.select(question, pages, maxEvidence)
        step("استخراج شاهد و خلاصه‌سازی استخراجی", "extract", mapOf("pages" to pages.size.toString()),
            "شاهد: ${evidence.size} جمله از ${pages.size} صفحه")

        val draft = if (evidence.isEmpty()) {
            refusedDraft(
                "web.no-evidence", langTag,
                listOf(if (langTag == "en") "pages had no sentence related to the question" else "صفحه‌ها جملهٔ مرتبط با پرسش نداشتند")
            )
        } else {
            val citations = evidence.mapIndexed { i, item -> Citation("W${i + 1}", hostOf(item.page.url), item.page.url) }
            val headline = if (langTag == "en") {
                "From ${pages.size} source(s): " + (evidence.first().sentence.take(120))
            } else {
                "بر پایهٔ ${Text.num(pages.size.toLong())} منبع: " + evidence.first().sentence.take(120)
            }
            AnswerDraft(
                intent = "web.research",
                headline = headline,
                facts = evidence.mapIndexed { i, item -> Fact("W${i + 1}", item.sentence.take(280)) },
                citations = citations,
                missing = emptyList(),
                confidence = confidenceOf(evidence, report, langTag),
                refused = false,
                lang = langTag
            )
        }

        val verified = Critic.stripUnsupported(draft)
        var answer = phraser.render(verified)
        if (report.failures.isNotEmpty() && !answer.refused) {
            answer = answer.copy(
                bullets = answer.bullets + (
                    if (langTag == "en") "Unreachable engines: ${report.failures.keys.joinToString(", ")}"
                    else "موتورهای در دسترس‌نبوده: ${report.failures.keys.joinToString("، ")}"
                    )
            )
        }
        answer = answer.copy(trace = steps.map { "${it.index}. ${it.tool}" }, actions = actions)

        // ---- 5. active learning: keep what is durable, log what happened ----
        val learnable = MemoryBank.learnableFrom(question, answer, pack)
        for ((kind, text) in learnable) {
            val written = tools.memoryWrite(kind, text, "agent")
            actions.add("memory.write:${written.id}")
            step("یادگیری از این گفتگو", "memory.write", mapOf("kind" to kind), written.category)
        }
        tools.logAppend("answer", "agent", "web-answer", "q=$query | sources=${pages.size} | conf=${pct(answer.confidence)}")
        return AgentOutcome(answer, steps, actions, pages)
    }

    // ---------- commands ----------

    private fun runCommand(
        command: Command,
        question: String,
        langTag: String,
        now: Long,
        step: (String, String, Map<String, String>, String) -> Unit,
        actions: MutableList<String>
    ): Answer? {
        val pack = tools.catalog().pack
        return when (command.kind) {
            CommandKind.SAVE -> {
                val payload = command.payload.ifBlank { question }
                val category = MemoryBank.classify(payload, pack)
                val written = tools.memoryWrite(command.memoryKind, payload, "user")
                actions.add("memory.write:${written.id}")
                step("ذخیرهٔ یادسپاری", "memory.write", mapOf("category" to category), "id=${written.id}")
                Answer(
                    intent = "memory.write",
                    text = if (langTag == "en") "Saved to memory (${written.category})." else "در حافظه ثبت شد (دستهٔ ${written.category}).",
                    bullets = listOf(payload.take(240)),
                    citations = listOf(Citation(written.id, "حافظه", null)),
                    confidence = 1.0,
                    lang = langTag
                )
            }
            CommandKind.FORGET -> {
                val hits = tools.memorySearch(command.payload.ifBlank { question }, 5)
                if (hits.isEmpty()) {
                    step("جستجوی حافظه برای حذف", "memory.search", emptyMap(), "چیزی پیدا نشد")
                    Answer(
                        intent = "memory.delete",
                        text = if (langTag == "en") "Nothing matched, nothing deleted." else "چیزی مطابق نبود، پس چیزی حذف نشد.",
                        confidence = 0.9,
                        lang = langTag
                    )
                } else {
                    val removed = hits.filter { tools.memoryDelete(it.item.id) }
                    actions.addAll(removed.map { "memory.delete:${it.item.id}" })
                    step("حذف یادسپاری", "memory.delete", mapOf("n" to removed.size.toString()), removed.joinToString { it.item.id })
                    Answer(
                        intent = "memory.delete",
                        text = if (langTag == "en") "Deleted ${removed.size} memory item(s)." else "${Text.num(removed.size.toLong())} یادسپاری حذف شد.",
                        bullets = removed.map { it.item.text.take(160) },
                        confidence = 1.0,
                        lang = langTag
                    )
                }
            }
            CommandKind.BACKUP -> {
                val path = tools.backupNow()
                actions.add("backup.write")
                step("ساخت بک‌آپ کامل", "backup.now", emptyMap(), path)
                Answer(
                    intent = "backup.write",
                    text = if (langTag == "en") "Backup written: $path" else "بک‌آپ ساخته شد: $path",
                    bullets = listOf(if (langTag == "en") "Includes packs, listings, memory, log and settings." else "شامل پک‌ها، آگهی‌ها، حافظه، لاگ و تنظیمات."),
                    confidence = 1.0,
                    lang = langTag
                )
            }
            CommandKind.LOG -> {
                val events = tools.logRecent(60)
                val md = tools.exportLogMarkdown()
                actions.add("log.export")
                step("خواندن لاگ و خروجی Markdown", "log.export", mapOf("n" to events.size.toString()), "md=${md.length} نویسه")
                Answer(
                    intent = "log.read",
                    text = if (langTag == "en") "Log exported as Markdown (${events.size} events)." else "لاگ به Markdown تبدیل شد (${Text.num(events.size.toLong())} رویداد).",
                    bullets = events.take(5).map { "${it.kind}: ${it.message.take(120)}" },
                    confidence = 1.0,
                    lang = langTag
                )
            }
            CommandKind.RECALL -> {
                val all = tools.memoryAll(400)
                val consolidated = MemoryBank.consolidate(all)
                actions.addAll(consolidated.droppedIds.map { "memory.merge:$it" })
                step("بازخوانی و یکپارچه‌سازی حافظه", "memory.consolidate", mapOf("n" to all.size.toString()),
                    "یکتا: ${consolidated.kept.size}، ادغام: ${consolidated.merged.size}")
                Answer(
                    intent = "memory.list",
                    text = MemoryBank.summary(consolidated.kept, if (langTag == "en") Lang.EN else Lang.FA),
                    bullets = consolidated.kept.sortedByDescending { it.importance }.take(8)
                        .map { "${it.category}: ${it.text.take(140)}" },
                    confidence = 1.0,
                    lang = langTag
                )
            }
        }
    }

    private fun needsFreshData(question: String): Boolean {
        val q = Text.normalize(question)
        return listOf("امروز", "الان", "قیمت روز", "جدیدترین", "آخرین", "today", "now", "latest", "current", "price today")
            .any { q.contains(Text.normalize(it)) }
    }

    private fun buildQuery(question: String, pack: MarketPack, widen: Boolean = false): String {
        val base = Text.normalize(question).trim()
        val tokens = Text.contentTokens(base)
        val extras = Lexicon.expand(tokens) - tokens.toSet()
        val packTerms = Lexicon.packTerms(pack.attributes.take(3).map { it.label })
        val add = if (widen) (extras.take(4) + packTerms.take(2)) else emptyList()
        return if (add.isEmpty()) base else (base + " " + add.distinct().joinToString(" ")).trim()
    }

    private fun confidenceOf(evidence: List<Evidence>, report: SearchReport, langTag: String): Double {
        if (evidence.isEmpty()) return 0.0
        val hosts = evidence.map { hostOf(it.page.url) }.distinct().size
        val base = 0.28 + 0.10 * hosts.coerceAtMost(4) + 0.28 * evidence.map { it.score }.average().coerceIn(0.0, 1.0)
        val diversity = if (report.perEngine.size >= 2) 0.06 else 0.0
        return (base + diversity).coerceIn(0.1, 0.85)
    }

    private fun refusedDraft(intent: String, langTag: String, missing: List<String>): AnswerDraft = AnswerDraft(
        intent = intent,
        headline = "",
        facts = emptyList(),
        citations = emptyList(),
        missing = missing,
        confidence = 0.0,
        refused = true,
        lang = langTag
    )

    private fun finish(answer: Answer, steps: List<AgentStep>, actions: List<String>): AgentOutcome =
        AgentOutcome(answer, steps, actions)

    private fun pct(value: Double): String = "${(value * 100).toInt()}%"

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host?.removePrefix("www.") ?: url }.getOrDefault(url).take(60)
}

// ---------- commands ----------

enum class CommandKind { SAVE, FORGET, BACKUP, LOG, RECALL }

data class Command(val kind: CommandKind, val payload: String, val memoryKind: String = "note")

/**
 * Small, explicit command grammar. Anything that is not clearly a command falls through to
 * the research path, so a user sentence is never silently treated as a database mutation.
 */
object CommandParser {

    private val save = Regex(
        "(?i)^\\s*(?:لطفا\\s*)?(?:اینو\\s+|این\\s+را\\s+)?(?:ذخیره\\s*کن|ثبت\\s*کن|به\\s*خاطر\\s*بسپار|یادت\\s*باشه|به\\s*یاد\\s*داشته\\s*باش|remember|save)\\s*[:：]?\\s*(.*)$",
        RegexOption.DOT_MATCHES_ALL
    )
    private val forget = Regex(
        "(?i)^\\s*(?:اینو\\s+)?(?:فراموش\\s*کن|پاک\\s*کن|حذف\\s*کن|forget|delete)\\s*[:：]?\\s*(.*)$",
        RegexOption.DOT_MATCHES_ALL
    )
    private val backup = Regex("(?i)(بک\\s*آپ|بکاپ|پشتیبان\\s*گیری|نسخهٔ\\s*پشتیبان|backup)")
    private val log = Regex("(?i)(لاگ|گزارش\\s*فعالیت|log|history\\s*of\\s*actions)")
    private val recall = Regex(
        "(?i)((چه|چیا)\\s*(چیز\\S*)?\\s*(یادت|در\\s*حافظه)|حافظه\\s*(را|رو)?\\s*(نشان|نشون|بگو|لیست|خالی)|^حافظه$|" +
            "what\\s+do\\s+you\\s+remember|show\\s+memory|list\\s+memory)"
    )

    fun parse(question: String): Command? {
        val original = question.trim()
        if (original.isEmpty()) return null
        // ZWNJ is meaningful in Persian text but must not break command detection, so every
        // match runs on a same-length probe (ZWNJ -> space) and the payload is cut from the
        // original string with the matched range.
        val text = original.replace('\u200c', ' ')
        save.find(text)?.let { m ->
            val payload = m.groups[1]?.let { original.substring(it.range) }.orEmpty().trim()
            if (payload.isNotEmpty()) {
                val kind = when {
                    payload.contains("ترجیح") || payload.contains("prefer") -> "preference"
                    payload.contains("قانون") || payload.contains("rule") -> "rule"
                    payload.startsWith("http") -> "link"
                    else -> "note"
                }
                return Command(CommandKind.SAVE, payload, kind)
            }
        }
        forget.find(text)?.let { m ->
            val payload = m.groups[1]?.let { original.substring(it.range) }.orEmpty().trim()
            if (payload.isNotEmpty()) return Command(CommandKind.FORGET, payload)
        }
        if (backup.containsMatchIn(text) && text.length <= 60) return Command(CommandKind.BACKUP, text)
        if (log.containsMatchIn(text) && text.length <= 40) return Command(CommandKind.LOG, text)
        if (recall.containsMatchIn(text) && text.length <= 40) return Command(CommandKind.RECALL, text)
        return null
    }
}

// ---------- extractive evidence ----------

data class Evidence(val sentence: String, val page: ExtractedPage, val score: Double)

/**
 * Extractive summarisation instead of generative paraphrase: every sentence handed to the
 * answer exists verbatim in a fetched page, so a number in the answer always has a URL.
 */
object Extractive {

    private val splitter = Regex("(?<=[.!?؟!])\\s+|\\n+")

    fun sentences(text: String): List<String> =
        text.split(splitter).map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.length in 40..400 }

    fun select(question: String, pages: List<ExtractedPage>, limit: Int): List<Evidence> {
        if (pages.isEmpty()) return emptyList()
        val queryTokens = Lexicon.expand(Text.contentTokens(question).ifEmpty { Text.tokens(question) })
        val candidates = pages.flatMap { page -> sentences(page.text).map { it to page } }
        if (candidates.isEmpty()) return emptyList()
        val scored = candidates.mapIndexed { index, (sentence, page) ->
            val tokens = Text.contentTokens(sentence)
            val overlap = Similarity.containment(queryTokens, tokens.toSet())
            val positionBonus = if (index < 40) 0.05 else 0.0
            val lengthPenalty = if (sentence.length > 260) 0.05 else 0.0
            val score = overlap + positionBonus - lengthPenalty
            Evidence(sentence, page, score) to index
        }.sortedWith(compareByDescending<Pair<Evidence, Int>> { it.first.score }.thenBy { it.second })

        val picked = mutableListOf<Evidence>()
        for ((evidence, _) in scored) {
            if (picked.size >= limit) break
            if (evidence.score < 0.08) continue
            if (picked.any { Similarity.hamming(Similarity.simhash(Text.contentTokens(it.sentence)), Similarity.simhash(Text.contentTokens(evidence.sentence))) < 12 }) continue
            picked.add(evidence)
        }
        if (picked.isEmpty() && scored.isNotEmpty()) picked.add(scored.first().first)
        return picked
    }
}
