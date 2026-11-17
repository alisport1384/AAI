package ir.aai.hushyar.domain

import ir.aai.hushyar.core.Jalali
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

/** What happened to one learned item when it was merged into the knowledge store. */
data class KnowledgeResult(val id: String, val decision: KnowledgeDecision, val summary: String)

/**
 * The only way the agent can touch the world. Each function is implemented by the app with
 * SQLite/files/HTTP; tests implement it in memory, so the whole loop is testable without
 * Android and without a network.
 */
interface AgentTools {
    fun now(): Long
    fun catalog(): Catalog

    fun memoryWrite(kind: String, text: String, source: String): MemoryItem
    fun memorySearch(query: String, limit: Int): List<MemoryHit>
    fun memoryAll(limit: Int): List<MemoryItem>
    fun memoryDelete(id: String): Boolean
    fun memoryTouch(ids: List<String>)

    /** Fresh, question-relevant knowledge the assistant acquired by itself. */
    fun knowledgeAnswerable(question: String, limit: Int): List<KnowledgeItem>
    fun knowledgeAll(limit: Int): List<KnowledgeItem>
    fun knowledgeStale(limit: Int): List<KnowledgeItem>

    /** Merge one observation into the store; the decision (NEW/CORRECT/…) is reported back. */
    fun knowledgeStore(item: KnowledgeItem): KnowledgeResult
    fun knowledgeDelete(id: String): Boolean

    fun webSearch(query: String, langTag: String): SearchReport
    fun fetchPage(url: String, langTag: String): ExtractedPage?
    fun insertListings(listings: List<Listing>): Int

    fun logAppend(kind: String, actor: String, message: String, detail: String)
    fun logRecent(limit: Int): List<LogEvent>
    fun backupNow(): String
    fun exportLogMarkdown(): String

    /** "سریع / متعادل / عمیق" — comes from user preferences. */
    fun researchBudget(): ResearchBudget

    // ---- v0.5: learning from answered questions (see Learning.kt) ----

    /** An answered question that can be reused for a similar question while it is still fresh. */
    fun caseFind(model: QuestionModel, question: String): QaCase?
    fun caseLearn(model: QuestionModel, question: String, answer: String, source: Evidence?)
    fun caseTouch(id: String)
    fun casesAll(limit: Int): List<QaCase>

    /** Which query worked before, for which topic. */
    fun playbookRecord(topicKey: String, query: String, won: Boolean)
    fun playbookBest(topicKey: String, limit: Int): List<PlayEntry>
    fun playbookRefusals(topicKey: String): Int

    /** Which sources answered before (tie-break only). */
    fun domainFactor(domain: String): Double
    fun domainRecord(domain: String, won: Boolean)

    // ---- v0.5: the plan the assistant works through without being asked ----

    fun researchPlan(): ResearchPlan
    fun planProgress(): PlanProgress
    fun planProgressSave(progress: PlanProgress)
    fun proactiveBudget(): ProactiveBudget
}

/**
 * The agent loop of v0.3.
 *
 * ```text
 * question
 *   ├─ data command?        → act on the app's own data (save/forget/backup/log/…)
 *   ├─ local catalog (آگهی‌ها) → exact, offline answer with citations
 *   ├─ memory                → durable user preferences
 *   ├─ knowledge store       → facts the assistant itself found earlier, if still fresh
 *   └─ autonomous research   → search · fetch · score · find gaps · search again …
 *                              → answer with citations → categorize → save → revise
 * ```
 *
 * Every branch is deterministic: same inputs, same answer. Refusals are explicit.
 */
class Agent(
    private val tools: AgentTools,
    private val phraserFactory: (String) -> Phraser = { TemplatePhraser(it) },
    private val directAnswerCoverage: Double = 0.55,
    /** v0.11: بستهٔ دانشی که از حرف خود کاربر ساخته شده (اگر ساخته شده باشد). */
    private val customDomain: DomainPack? = null,
    /** v0.11: مدل محلی، وقتی نصب و روشن است، در استخراج ویژگی کمک می‌کند. */
    private val generator: LocalGenerator? = null,
    private val modelSpec: ModelSpec? = null
) {

    private val qa = QaEngine()

    fun run(question: String, allowWeb: Boolean = true): AgentOutcome {
        val now = tools.now()
        val langEnum = LangDetect.detect(question)
        val langTag = if (langEnum == Lang.EN) "en" else "fa"
        val phraser = phraserFactory(langTag)
        val steps = mutableListOf<AgentStep>()
        val actions = mutableListOf<String>()
        val catalog = tools.catalog()
        val pack = catalog.pack

        fun step(thought: String, tool: String, args: Map<String, String> = emptyMap(), observation: String = "") {
            steps.add(AgentStep(steps.size + 1, thought, tool, args, observation.take(600)))
        }

        step("دریافت پرسش و تشخیص زبان", "input", mapOf("lang" to langTag, "text" to question.take(120)), "زبان: $langTag")

        // Same recorder passed down to the research/refresh paths, so every round is visible.
        val recorder: (String, String, Map<String, String>, String) -> Unit =
            { thought, tool, args, observation -> step(thought, tool, args, observation) }

        // ---- 1. data commands ----
        val command = CommandParser.parse(question)
        if (command != null) {
            when (command.kind) {
                CommandKind.RESEARCH -> return researchAnswer(
                    target = command.payload.ifBlank { question },
                    langTag = langTag,
                    pack = pack,
                    known = tools.knowledgeAnswerable(question, 5),
                    budget = ResearchBudget.DEEP,
                    reason = "دستور تحقیق صریح",
                    steps = steps,
                    actions = actions,
                    phraser = phraser,
                    step = recorder
                )
                CommandKind.REFRESH -> return refreshAnswer(
                    question = question, langTag = langTag, pack = pack,
                    steps = steps, actions = actions, phraser = phraser, step = recorder
                )
                else -> {
                    val outcome = runCommand(command, question, langTag, now, recorder, actions)
                    tools.logAppend("command", "agent", "command:${command.kind}", question.take(300))
                    return AgentOutcome(
                        outcome.copy(
                            trace = steps.map { "${it.index}. ${it.tool} → ${it.observation.take(160)}" },
                            actions = actions
                        ),
                        steps, actions
                    )
                }
            }
        }

        // ---- 2. the ads/listings catalog: free, exact, offline ----
        val localAnswer = qa.ask(question, catalog, now)
        step(
            "پرسش از آگهی‌های ثبت‌شده (کاتالوگ محلی)", "catalog.ask", emptyMap(),
            if (localAnswer.refused) "پاسخ محلی ممکن نبود" else "پاسخ محلی با اطمینان ${pct(localAnswer.confidence)}"
        )
        if (!localAnswer.refused && localAnswer.confidence >= 0.6) {
            tools.logAppend("answer", "agent", "local-answer", question.take(300))
            return AgentOutcome(
                localAnswer.copy(
                    trace = steps.map { it.tool },
                    bullets = localAnswer.bullets + note(if (langTag == "en") "source: your stored listings" else "منبع: آگهی‌های ثبت‌شدهٔ خودت", langTag)
                ),
                steps, actions
            )
        }

        // ---- 2.5 کالاهای فولادی: اول بفهم، بعد از دانش خودت جواب بده، بعد جستجو، بعد حساب کن ----
        //
        // «ورق ۲ میل قیمت» یک پرسش ناقص است، نه یک پرسش بی‌معنی: کالا (ورق فولادی صنعتی)، اندازه
        // (ضخامت ۲ میلی‌متر)، خواسته (قیمت) و پایهٔ نامشخص. MaterialBrain همان را باز می‌کند، وزن را
        // از جدول/فرمول می‌آورد، قیمت را اگر ذخیره یا قابل جستجو بود می‌گیرد، یکای خواسته‌شده را
        // خودش حساب می‌کند و هر چه فهمید ذخیره می‌کند تا دفعهٔ بعد آفلاین باشد. اگر پرسش هیچ کالایی
        // نداشته باشد `null` می‌دهد و مسیر عمومی دست‌نخورده ادامه می‌یابد.
        MaterialBrain(tools).answer(question, langTag, recorder)?.let { material ->
            tools.logAppend("answer", "agent", material.answer.intent, "q=${question.take(120)} conf=${pct(material.answer.confidence)}")
            return AgentOutcome(
                material.answer.copy(
                    trace = (steps + material.steps).map { "${it.index}. ${it.tool} → ${it.observation.take(120)}" },
                    actions = actions + material.actions
                ),
                steps + material.steps,
                actions + material.actions
            )
        }

        // ---- 2.6 هر حوزهٔ دیگری: تی‌شرت، شلوار، پارچه… ----
        //
        // v0.11 پاسخ به «چیزهایی که گفتم فقط مثال بود»: همین مسیر هیچ اسم کالایی نمی‌شناسد.
        // کالا از بستهٔ دانش می‌آید (یکی از بسته‌های آماده، یا بسته‌ای که از حرف خودِ کاربر ساخته
        // شده)؛ ویژگی‌ها از متن پرسش خوانده می‌شوند؛ دانش نوع‌دار ذخیره می‌شود؛ قیمت یا از دانش
        // می‌آید یا از جستجو استخراج می‌شود؛ و تبدیل یکا (عدد ↔ دوجین ↔ بسته ↔ کیلو) با ضریب
        // حساب می‌شود. اگر کالا شناخته نشود `null` می‌دهد و مسیر عمومی ادامه می‌یابد.
        val knownEntityIds = tools.knowledgeAll(600)
            .mapNotNull { item -> item.topic.takeIf { it.startsWith(Facts.PREFIX) }?.removePrefix(Facts.PREFIX)?.substringBefore('|') }
            .distinct()
        val domainPacks = listOfNotNull(customDomain) + DomainPacks.all
        AnyDomainBrain(tools, domainPacks, generator, modelSpec).answer(question, langTag, knownEntityIds, recorder)
            ?.let { generic ->
                tools.logAppend(
                    "answer", "agent", generic.answer.intent,
                    "q=${question.take(120)} conf=${pct(generic.answer.confidence)}"
                )
                return AgentOutcome(
                    generic.answer.copy(
                        trace = (steps + generic.steps).map { "${it.index}. ${it.tool} → ${it.observation.take(120)}" },
                        actions = actions + generic.actions
                    ),
                    steps + generic.steps,
                    actions + generic.actions
                )
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
            return AgentOutcome(phraser.render(Critic.stripUnsupported(draft)).copy(trace = steps.map { it.tool }), steps, actions)
        }

        if (!allowWeb) {
            val draft = refusedDraft(
                "web.disabled", langTag,
                listOf(if (langTag == "en") "web search is off" else "جستجوی وب خاموش است")
            )
            return AgentOutcome(phraser.render(draft).copy(trace = steps.map { it.tool }), steps, actions)
        }

        // ---- 3.5 the case store: this exact question (or a close one) was answered before ----
        val model = QuestionReader.read(question, langTag)
        val cached = tools.caseFind(model, question)
        if (cached != null) {
            tools.caseTouch(cached.id)
            step(
                "پروندهٔ پاسخ قبلی پیدا شد", "case.reuse",
                mapOf("ask" to model.ask.name, "hits" to cached.hits.toString()),
                "پاسخ از ${Jalali.format(cached.lastVerifiedAt)} — بدون جستجوی تازه"
            )
            actions.add("case.hit:${cached.id}")
            val draft = AnswerDraft(
                intent = "case.reuse",
                headline = if (langTag == "en") {
                    "Answer: ${cached.answer} — from the case file (checked ${Jalali.format(cached.lastVerifiedAt)}, source ${cached.domain})"
                } else {
                    "پاسخ: ${cached.answer} — از پروندهٔ قبلی (بازبینی ${Jalali.format(cached.lastVerifiedAt)}، منبع ${cached.domain})"
                },
                facts = listOf(Fact("C1", cached.question.take(200))),
                citations = if (cached.url.isNotBlank()) listOf(Citation("C1", cached.domain, cached.url)) else emptyList(),
                missing = emptyList(),
                confidence = 0.72,
                refused = false,
                lang = langTag
            )
            tools.logAppend("answer", "agent", "case-reuse", "q=${question.take(120)} hits=${cached.hits}")
            return AgentOutcome(phraser.render(draft).copy(trace = steps.map { it.tool }, actions = actions), steps, actions)
        }

        // ---- 4. knowledge already acquired by the assistant ----
        val known = tools.knowledgeAnswerable(question, 5)
        val stale = known.filter { KnowledgeBank.isStale(it, now) }
        val coverage = KnowledgeBank.coverage(question, known, now, pack)
        step(
            "بررسی دانش ذخیره‌شده", "knowledge.lookup",
            mapOf("topic" to KnowledgeBank.topicKey(question)),
            "موجود=${known.size} کهنه=${stale.size} پوشش=${pct(coverage)}"
        )
        if (known.isNotEmpty() && stale.isEmpty() && coverage >= directAnswerCoverage) {
            val model = QuestionReader.read(question, langTag)
            val plan = AnswerFocus.plan(model, knowledgeCandidates(model, known, pack))
            if (plan.answered) return knowledgeAnswer(question, known, langTag, steps, actions, phraser, plan)
            step(
                "دانش ذخیره‌شده پاسخ این پرسش نیست", "knowledge.lookup",
                mapOf("ask" to model.ask.name),
                "دانش موجود=${known.size} اما هیچ‌کدام ${QuestionReader.expectedAnswerLabel(model.ask)} نداشت"
            )
        }

        // ---- 5. autonomous research: keep searching until the evidence is enough ----
        return researchAnswer(
            target = question,
            langTag = langTag,
            pack = pack,
            known = known,
            // Same topic refused before? Go deeper instead of repeating the same shallow search.
            budget = StudyDepth.escalate(tools.researchBudget(), tools.playbookRefusals(KnowledgeBank.topicKey(question))),
            reason = if (known.isEmpty()) "دانشی ذخیره نشده بود" else "دانش موجود کهنه یا ناکافی بود (پوشش ${pct(coverage)})",
            steps = steps,
            actions = actions,
            phraser = phraser,
            step = recorder
        )
    }

    // ---------- research ----------

    /**
     * Runs the loop, stores what it learned (categorised, de-duplicated, contradictions corrected),
     * optionally fills the ads table from real pages, then answers from the evidence.
     */
    private fun researchAnswer(
        target: String,
        langTag: String,
        pack: MarketPack,
        known: List<KnowledgeItem>,
        budget: ResearchBudget,
        reason: String,
        steps: MutableList<AgentStep>,
        actions: MutableList<String>,
        phraser: Phraser,
        step: (String, String, Map<String, String>, String) -> Unit
    ): AgentOutcome {
        step(
            "آغاز پژوهش خودکار", "research.start",
            mapOf("reason" to reason, "maxRounds" to budget.maxRounds.toString()),
            "بودجه: ${budget.maxRounds} دور، حداکثر ${budget.maxPages} صفحه، هدف پوشش ${pct(budget.targetCoverage)}"
        )
        val researcher = DeepResearcher(
            search = { query, lang -> tools.webSearch(query, lang) },
            fetch = { url, lang -> tools.fetchPage(url, lang) },
            budget = budget,
            now = { tools.now() }
        )
        val outcome = researcher.research(target, langTag, pack, known)

        for (round in outcome.rounds) {
            step(
                "دور ${round.index}: ${round.note}", "web.search", mapOf("q" to round.query),
                "hits=${round.hits} fetched=${round.fetched} evidence=${round.evidence} coverage=${pct(round.coverage)}"
            )
        }
        for (page in outcome.pages) {
            step("واکشی صفحه (شاهد پذیرفته شد)", "web.fetch", mapOf("url" to page.url), "متن: ${page.text.length} نویسه")
        }
        for ((url, why) in outcome.skipped) {
            step("صفحهٔ کنارگذاشته‌شده", "web.fetch", mapOf("url" to url), why)
        }

        // ---- save what was learned: categorized, de-duplicated, contradictions resolved ----
        val learned = mutableListOf<String>()
        for (draft in outcome.drafts) {
            val result = tools.knowledgeStore(draft)
            when (result.decision) {
                KnowledgeDecision.NEW -> {
                    learned.add(
                        if (langTag == "en") "new ${draft.category} fact from ${draft.domain}"
                        else "دانش تازه در «${draft.category}» از ${draft.domain}"
                    )
                    actions.add("knowledge.new:${result.id}")
                }
                KnowledgeDecision.CORRECT -> {
                    learned.add(
                        if (langTag == "en") "corrected a value using ${draft.domain}"
                        else "مقدار قبلی با دادهٔ ${draft.domain} اصلاح شد"
                    )
                    actions.add("knowledge.correct:${result.id}")
                }
                KnowledgeDecision.REFRESH -> {
                    learned.add(if (langTag == "en") "re-verified via ${draft.domain}" else "بازبینی از ${draft.domain}")
                    actions.add("knowledge.refresh:${result.id}")
                }
                KnowledgeDecision.CONFLICT -> {
                    learned.add(
                        if (langTag == "en") "conflict recorded (${draft.domain})"
                        else "تناقض منابع ثبت شد (${draft.domain})"
                    )
                    actions.add("knowledge.conflict:${result.id}")
                }
                KnowledgeDecision.IGNORE -> Unit
            }
        }
        if (outcome.drafts.isNotEmpty()) {
            step(
                "دسته‌بندی و ذخیرهٔ آنچه آموختم", "knowledge.store",
                mapOf("items" to outcome.drafts.size.toString()),
                learned.joinToString("؛ ").take(400)
            )
        }

        // ---- ads section: fill it from pages that state an explicit price ----
        var harvested = 0
        if (outcome.evidence.isNotEmpty() && pack.id != EMPTY_PACK_ID) {
            val harvest = ListingHarvester.harvest(outcome.evidence, pack, tools.now())
            if (harvest.listings.isNotEmpty()) {
                harvested = tools.insertListings(harvest.listings)
                if (harvested > 0) actions.add("listing.harvest:$harvested")
            }
            step(
                "استخراج آگهی از صفحه‌های وب", "listing.harvest",
                mapOf("candidates" to harvest.listings.size.toString()),
                "افزوده: $harvested، رد‌شده (بدون قیمت روشن): ${harvest.skipped}"
            )
        }

        // ---- answer: the answer to *this* question, or a precise statement of what is missing ----
        val plan = outcome.answer
        val model = outcome.model ?: QuestionReader.read(target, langTag)
        val draftAnswer: AnswerDraft = if (plan != null && plan.answered) {
            directDraft(plan, model, outcome, langTag)
        } else if (known.isNotEmpty()) {
            // Every engine failed, or the pages we got do not answer this question — but we are not
            // empty-handed: say what we already hold, with its source and its age. Refusing while
            // owning a stored answer was the reason "if the engine is down, it does not answer".
            partialKnowledgeDraft(known, model, outcome, langTag)
        } else {
            refusedDraft(
                when {
                    outcome.pages.isEmpty() -> "web.empty"
                    plan != null && plan.support.isEmpty() -> "web.answer-missing"
                    else -> "web.no-evidence"
                },
                langTag,
                buildList {
                    if (plan != null) addAll(plan.missing.take(2))
                    else add(if (langTag == "en") "no usable evidence" else "شاهد قابل استفاده‌ای پیدا نشد")
                    add(
                        if (langTag == "en") "asked: ${model.subject.joinToString(" ").ifBlank { target }.take(80)} — " +
                            "expected: ${QuestionReader.expectedAnswerLabel(model.ask)}"
                        else "موضوع پرسش: «${model.subject.joinToString(" ").ifBlank { target }.take(80)}» — " +
                            "چیزی که لازم داشتم: ${QuestionReader.expectedAnswerLabel(model.ask)}"
                    )
                    add(if (langTag == "en") "searched ${outcome.rounds.size} round(s), ${outcome.pages.size} page(s)"
                    else "${Text.num(outcome.rounds.size.toLong())} دور جستجو و ${Text.num(outcome.pages.size.toLong())} صفحه بررسی شد")
                    if (outcome.failures.isNotEmpty()) {
                        add(
                            (if (langTag == "en") "engine errors: " else "خطای موتورها: ") +
                                outcome.failures.entries.take(3).joinToString("؛ ") { "${it.key}: ${it.value}" }
                        )
                    }
                    if (outcome.notes.isNotEmpty()) {
                        add(
                            (if (langTag == "en") "routes: " else "مسیر پاسخ: ") +
                                outcome.notes.entries.take(3).joinToString("؛ ") { "${it.key}: ${it.value}" }
                        )
                    }
                }
            )
        }

        var answer = phraser.render(Critic.stripUnsupported(draftAnswer))
        if (!answer.refused) {
            answer = answer.copy(
                bullets = answer.bullets +
                    stopNote(outcome, langTag) +
                    note(
                        if (langTag == "en") "stored ${outcome.drafts.size} knowledge item(s), +$harvested listing(s)"
                        else "${Text.num(outcome.drafts.size.toLong())} دانسته ذخیره شد، ${Text.num(harvested.toLong())} آگهی افزوده شد",
                        langTag
                    ),
                learned = learned.take(6)
            )
        } else if (steps.isNotEmpty()) {
            answer = answer.copy(learned = emptyList())
        }
        answer = answer.copy(trace = steps.map { "${it.index}. ${it.tool}" }, actions = actions)

        // ---- learn from this answer so the next similar question is faster and better ----
        val qModel = QuestionReader.read(target, langTag)
        val topic = KnowledgeBank.topicKey(target)
        val winningQuery = outcome.queries.lastOrNull()
        if (plan != null && plan.answered) {
            tools.caseLearn(qModel, target, plan.direct!!, plan.source)
            actions.add("case.learn:${CaseStore.id(target, qModel.ask)}")
            if (winningQuery != null) tools.playbookRecord(topic, winningQuery, true)
            plan.source?.page?.url?.let { tools.domainRecord(KnowledgeBank.draftHost(it), true) }
            learned.add(
                if (langTag == "en") "this answer is now a case: next time it needs no search"
                else "این پاسخ به‌عنوان پرونده ذخیره شد: بار بعد جستجوی تازه لازم نیست"
            )
            // The answer was already composed above, so the note has to be written back into it —
            // otherwise the promise is made in the log and never shown to the user.
            answer = answer.copy(learned = learned.take(6))
        } else {
            if (winningQuery != null) tools.playbookRecord(topic, winningQuery, false)
            plan?.source?.page?.url?.let { tools.domainRecord(KnowledgeBank.draftHost(it), false) }
        }

        tools.logAppend(
            "answer", "agent", if (answer.refused) "research-refused" else "research-answer",
            "q=${target.take(120)} | rounds=${outcome.rounds.size} pages=${outcome.pages.size} " +
                "coverage=${pct(outcome.coverage)} stop=${outcome.stop} learned=${outcome.drafts.size}"
        )
        return AgentOutcome(answer, steps, actions, outcome.pages)
    }


    // ---------- answer composition ----------

    /** Stored claims ranked as if they were one-sentence pages, so the same gates apply. */
    private fun knowledgeCandidates(
        model: QuestionModel,
        items: List<KnowledgeItem>,
        pack: MarketPack
    ): List<AnswerCandidate> = items.mapNotNull { item ->
        val slot = AnswerShapes.slot(
            model.ask, item.claim, pack, QuestionReader.attributeWords(model.attribute)
        ) ?: return@mapNotNull null
        val page = ExtractedPage(url = item.url, title = item.domain, text = item.claim)
        val coverage = model.subjectCoverage(item.claim)
        if (coverage <= 0.0) return@mapNotNull null
        val evidence = Evidence(item.claim, page, item.confidence)
        AnswerCandidate(evidence, slot, coverage, true, 3.2 + 1.8 * coverage + item.confidence)
    }.sortedByDescending { it.score }

    /**
     * The direct answer, first line: the answer span itself, then who says so and how old it is,
     * then whether other sources disagree. Everything below it is verbatim support.
     */
    private fun directDraft(
        plan: AnswerPlan,
        model: QuestionModel,
        outcome: ResearchOutcome,
        langTag: String
    ): AnswerDraft {
        val source = plan.source
        val domain = source?.let { KnowledgeBank.draftHost(it.page.url) } ?: "وب"
        val when1 = Jalali.format(tools.now())
        val agreement = if (plan.agreement > 1) {
            if (langTag == "en") " — ${plan.agreement} independent sources agree" else " — ${Text.num(plan.agreement.toLong())} منبع مستقل همین را می‌گویند"
        } else ""
        val conflict = if (plan.disagreement.isNotEmpty()) {
            val others = plan.disagreement.take(2).joinToString("، ") { "${it.first} (${it.second})" }
            if (langTag == "en") " — another source says $others" else " — منبع دیگر می‌گوید: $others"
        } else ""
        val headline = if (langTag == "en") {
            "Answer (${QuestionReader.expectedAnswerLabel(plan.ask)}): ${plan.direct} — source: $domain, checked $when1$agreement$conflict"
        } else {
            "پاسخ ${model.attribute?.let { "«$it»" } ?: ""}: ${plan.direct} — منبع: $domain، بازبینی $when1$agreement$conflict"
        }
        val supporting = plan.support.take(4)
        return AnswerDraft(
            intent = "web.answer",
            headline = headline,
            facts = supporting.mapIndexed { index, candidate -> Fact("W${index + 1}", candidate.evidence.sentence.take(280)) },
            citations = supporting.mapIndexed { index, candidate ->
                Citation("W${index + 1}", KnowledgeBank.draftHost(candidate.evidence.page.url), candidate.evidence.page.url)
            },
            missing = emptyList(),
            confidence = (0.52 + 0.10 * plan.agreement + 0.18 * outcome.coverage -
                (if (plan.disagreement.isNotEmpty()) 0.10 else 0.0)).coerceIn(0.35, 0.92),
            refused = false,
            lang = langTag
        )
    }

    /**
     * Best-effort answer from what is already stored, when a fresh search produced nothing usable.
     * It is deliberately labelled: partial, dated, sourced — never presented as a fresh answer.
     */
    private fun partialKnowledgeDraft(
        known: List<KnowledgeItem>,
        model: QuestionModel,
        outcome: ResearchOutcome,
        langTag: String
    ): AnswerDraft {
        val items = known.sortedByDescending { it.lastVerifiedAt }.take(4)
        val newest = items.maxOfOrNull { it.lastVerifiedAt } ?: tools.now()
        val ageDays = ((tools.now() - newest).coerceAtLeast(0L)) / 86_400_000L
        val reason = when {
            outcome.rounds.isEmpty() || outcome.pages.isEmpty() -> if (langTag == "en") "no page could be fetched" else "هیچ صفحه‌ای واکشی نشد"
            outcome.answer != null && !outcome.answer.answered ->
                if (langTag == "en") "the pages did not contain ${QuestionReader.expectedAnswerLabel(model.ask)}"
                else "صفحه‌ها ${QuestionReader.expectedAnswerLabel(model.ask)} نداشتند"
            else -> if (langTag == "en") "the evidence was too thin" else "شواهد کافی نبود"
        }
        val engines = outcome.failures.entries.take(3).joinToString("؛ ") { "${it.key}: ${it.value}" }
        return AnswerDraft(
            intent = "knowledge.partial",
            headline = if (langTag == "en") {
                "Partial answer from stored knowledge (last verified ${Jalali.format(newest)}, ~$ageDays day(s) ago). " +
                    "A fresh search failed: $reason." + if (engines.isNotBlank()) " Engines: $engines" else ""
            } else {
                "پاسخ ناقص از دانش ذخیره‌شده (آخرین بازبینی ${Jalali.format(newest)}، حدود ${Text.num(ageDays)} روز پیش). " +
                    "جستجوی تازه نتیجه نداد: $reason." + if (engines.isNotBlank()) " موتورها: $engines" else ""
            },
            facts = items.mapIndexed { index, item -> Fact("K${index + 1}", item.claim.take(280)) },
            citations = items.mapIndexed { index, item ->
                Citation("K${index + 1}", item.domain.ifBlank { "دانش" }, item.url)
            },
            missing = buildList {
                add(if (langTag == "en") "a fresh source for ${QuestionReader.expectedAnswerLabel(model.ask)}"
                else "منبع تازه برای ${QuestionReader.expectedAnswerLabel(model.ask)}")
                if (items.any { KnowledgeBank.isStale(it, tools.now()) }) {
                    add(if (langTag == "en") "some stored items are stale" else "بخشی از دانش ذخیره‌شده کهنه است")
                }
            },
            confidence = 0.42,
            refused = false,
            lang = langTag
        )
    }

    /** Answers from knowledge the assistant already owns — no network, but shows the source and age. */
    private fun knowledgeAnswer(
        question: String,
        items: List<KnowledgeItem>,
        langTag: String,
        steps: MutableList<AgentStep>,
        actions: MutableList<String>,
        phraser: Phraser,
        plan: AnswerPlan? = null
    ): AgentOutcome {
        val newest = items.maxOfOrNull { it.lastVerifiedAt } ?: tools.now()
        val draft = AnswerDraft(
            intent = "knowledge.answer",
            headline = if (plan != null && plan.answered) {
                if (langTag == "en") {
                    "Answer: ${plan.direct} — from my knowledge base (source ${plan.source?.let { KnowledgeBank.draftHost(it.page.url) } ?: "-"}, checked ${Jalali.format(newest)})"
                } else {
                    "پاسخ: ${plan.direct} — از دانش ذخیره‌شده (منبع ${plan.source?.let { KnowledgeBank.draftHost(it.page.url) } ?: "-"}، بازبینی ${Jalali.format(newest)})"
                }
            } else if (langTag == "en") {
                "From my knowledge base (verified ${Jalali.format(newest)}):"
            } else {
                "از دانش ذخیره‌شده (آخرین بازبینی ${Jalali.format(newest)}):"
            },
            facts = items.mapIndexed { index, item -> Fact("K${index + 1}", item.claim.take(280)) },
            citations = items.mapIndexed { index, item -> Citation("K${index + 1}", item.domain.ifBlank { "دانش" }, item.url) },
            missing = emptyList(),
            confidence = items.map { it.confidence }.average().coerceIn(0.45, 0.8),
            refused = false,
            lang = langTag
        )
        val rendered = phraser.render(Critic.stripUnsupported(draft))
        val answer = rendered.copy(
            trace = steps.map { it.tool },
            bullets = rendered.bullets +
                note(if (langTag == "en") "${items.size} stored item(s), no search needed" else "${Text.num(items.size.toLong())} دانسته، بدون جستجوی تازه", langTag)
        )
        steps.add(
            AgentStep(
                steps.size + 1, "پاسخ از دانش ذخیره‌شده (بدون جستجوی تازه)", "knowledge.answer",
                mapOf("items" to items.size.toString()),
                "کهنه نیست؛ بازبینی اخیر: ${Jalali.format(newest)}"
            )
        )
        tools.logAppend("answer", "agent", "knowledge-answer", question.take(200))
        return AgentOutcome(answer, steps, actions)
    }

    /** "بروزرسانی کن": re-research what went stale, correct it, report what changed. */
    private fun refreshAnswer(
        question: String,
        langTag: String,
        pack: MarketPack,
        steps: MutableList<AgentStep>,
        actions: MutableList<String>,
        phraser: Phraser,
        step: (String, String, Map<String, String>, String) -> Unit
    ): AgentOutcome {
        // At most two topics per refresh: each one is a full research loop with the user's budget.
        val stale = tools.knowledgeStale(2)
        step("شناسایی دانش کهنه", "knowledge.stale", emptyMap(), "کهنه: ${stale.size}")
        if (stale.isEmpty()) {
            val answer = Answer(
                intent = "knowledge.refresh",
                text = if (langTag == "en") "Nothing is stale — no refresh needed." else "چیزی کهنه نیست؛ بروزرسانی لازم نداشتم.",
                confidence = 1.0,
                lang = langTag
            )
            return AgentOutcome(answer, steps, actions)
        }
        var refreshed = 0
        var corrected = 0
        val bullets = mutableListOf<String>()
        for (item in stale) {
            val outcome = DeepResearcher(
                search = { query, lang -> tools.webSearch(query, lang) },
                fetch = { url, lang -> tools.fetchPage(url, lang) },
                budget = tools.researchBudget(),
                now = { tools.now() }
                // The stale item must NOT count as "known": we are here to replace it.
            ).research(item.question.ifBlank { item.topic }, langTag, pack, known = emptyList())
            for (draft in outcome.drafts) {
                when (tools.knowledgeStore(draft).decision) {
                    KnowledgeDecision.CORRECT -> {
                        corrected++
                        bullets.add(
                            if (langTag == "en") "corrected: ${draft.claim.take(120)}"
                            else "اصلاح شد: ${draft.claim.take(120)}"
                        )
                        actions.add("knowledge.correct:${draft.id}")
                    }
                    KnowledgeDecision.NEW, KnowledgeDecision.REFRESH, KnowledgeDecision.CONFLICT -> {
                        refreshed++
                        actions.add("knowledge.refresh:${draft.id}")
                    }
                    KnowledgeDecision.IGNORE -> Unit
                }
            }
            step(
                "بروزرسانی «${item.topic.take(40)}»", "research.refresh",
                mapOf("rounds" to outcome.rounds.size.toString()),
                "شاهد=${outcome.evidence.size} توقف=${outcome.stop}"
            )
        }
        val text = if (langTag == "en") {
            "Refreshed ${refreshed + corrected} item(s); $corrected value(s) were corrected by fresher sources."
        } else {
            "${Text.num((refreshed + corrected).toLong())} مورد بروزرسانی شد؛ ${Text.num(corrected.toLong())} مقدار با منبع تازه‌تر اصلاح شد."
        }
        val answer = Answer(
            intent = "knowledge.refresh",
            text = text,
            bullets = bullets.take(6),
            confidence = 0.8,
            lang = langTag,
            trace = steps.map { it.tool },
            actions = actions
        )
        tools.logAppend("answer", "agent", "knowledge-refresh", "stale=${stale.size} corrected=$corrected")
        return AgentOutcome(answer, steps, actions)
    }

    // ---------- commands that only touch app data ----------

    private fun runCommand(
        command: Command,
        question: String,
        langTag: String,
        now: Long,
        step: (String, String, Map<String, String>, String) -> Unit,
        actions: MutableList<String>
    ): Answer {
        val pack = tools.catalog().pack
        return when (command.kind) {
            CommandKind.GOAL -> {
                val payload = command.payload.ifBlank { question }
                val written = tools.memoryWrite("goal", payload, "user")
                actions.add("goal.set:${written.id}")
                step(
                    "ثبت هدف و شروع کار خودکار", "goal.set",
                    mapOf("category" to written.category),
                    "از این لحظه پلان پژوهش ساخته می‌شود و بدون پرسش تازه ادامه پیدا می‌کند"
                )
                Answer(
                    intent = "goal.set",
                    text = if (langTag == "en") {
                        "Goal saved. I start researching it myself: overview, prices, specs, reviews, freshness."
                    } else {
                        "هدف ثبت شد. خودم شروع می‌کنم: تصویر کلی، قیمت‌ها، مشخصات، نظرات و تازه‌سازی — بدون اینکه چیزی بپرسی."
                    },
                    bullets = listOf(payload.take(240)),
                    citations = listOf(Citation(written.id, "حافظه", null)),
                    confidence = 1.0,
                    lang = langTag
                )
            }
            CommandKind.SAVE -> {
                val payload = command.payload.ifBlank { question }
                val written = tools.memoryWrite(command.memoryKind, payload, "user")
                actions.add("memory.write:${written.id}")
                step("ذخیرهٔ یادسپاری", "memory.write", mapOf("category" to written.category), "id=${written.id}")
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
                    bullets = listOf(if (langTag == "en") "Includes packs, listings, memory, knowledge, log and settings." else "شامل پک‌ها، آگهی‌ها، حافظه، دانش، لاگ و تنظیمات."),
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
                step(
                    "بازخوانی و یکپارچه‌سازی حافظه", "memory.consolidate",
                    mapOf("n" to all.size.toString()),
                    "یکتا: ${consolidated.kept.size}، ادغام: ${consolidated.merged.size}"
                )
                Answer(
                    intent = "memory.list",
                    text = MemoryBank.summary(consolidated.kept, if (langTag == "en") Lang.EN else Lang.FA),
                    bullets = consolidated.kept.sortedByDescending { it.importance }.take(8)
                        .map { "${it.category}: ${it.text.take(140)}" },
                    confidence = 1.0,
                    lang = langTag
                )
            }
            CommandKind.KNOWLEDGE -> {
                val items = tools.knowledgeAll(400)
                val stale = KnowledgeBank.staleItems(items, now)
                step(
                    "گزارش دانش", "knowledge.report",
                    mapOf("n" to items.size.toString()), "کهنه: ${stale.size}"
                )
                Answer(
                    intent = "knowledge.list",
                    text = KnowledgeBank.summary(items, now, if (langTag == "en") Lang.EN else Lang.FA),
                    bullets = items.filter { it.fresh }.sortedByDescending { it.lastVerifiedAt }.take(8)
                        .map { "${it.category} • ${it.domain} • ${Jalali.format(it.lastVerifiedAt)}: ${it.claim.take(120)}" },
                    citations = items.filter { it.fresh }.take(8).mapIndexed { index, item ->
                        Citation("K${index + 1}", item.domain.ifBlank { "دانش" }, item.url)
                    },
                    confidence = 1.0,
                    lang = langTag
                )
            }
            CommandKind.GOAL, CommandKind.RESEARCH, CommandKind.REFRESH -> Answer(
                intent = "noop", text = "", confidence = 0.0, lang = langTag
            )
        }
    }

    // ---------- helpers ----------

    private fun needsFreshData(question: String): Boolean {
        val q = Text.normalize(question)
        return listOf("امروز", "الان", "قیمت روز", "جدیدترین", "آخرین", "today", "now", "latest", "current", "price today")
            .any { q.contains(Text.normalize(it)) }
    }

    private fun confidenceOf(outcome: ResearchOutcome): Double {
        if (outcome.evidence.isEmpty()) return 0.0
        val hosts = outcome.evidence.map { KnowledgeBank.draftHost(it.page.url) }.distinct().size
        val relevance = outcome.evidence.map { it.score }.average().coerceIn(0.0, 1.0)
        val base = 0.24 + 0.10 * hosts.coerceAtMost(4) + 0.26 * relevance + 0.20 * outcome.coverage
        return base.coerceIn(0.1, 0.88)
    }

    private fun stopNote(outcome: ResearchOutcome, langTag: String): List<String> = when (outcome.stop) {
        ResearchStop.SUFFICIENT -> listOf(
            if (langTag == "en") "Evidence was sufficient — stopped searching."
            else "شواهد کافی شد و جستجو را متوقف کردم."
        )
        ResearchStop.NO_NEW_EVIDENCE -> listOf(
            if (langTag == "en") "No meaningful new page was found; stopped instead of repeating."
            else "صفحهٔ تازهٔ معناداری نبود؛ به‌جای تکرار، متوقف شدم."
        )
        ResearchStop.ROUNDS, ResearchStop.PAGES, ResearchStop.TIME -> listOf(
            if (langTag == "en") "Search budget reached — this answer may be incomplete. Say «بروزرسانی کن» to go deeper."
            else "سقف بودجهٔ جستجو پر شد؛ ممکن است پاسخ کامل نباشد. بگو «بروزرسانی کن» تا عمیق‌تر بگردم."
        )
        ResearchStop.NO_RESULTS -> listOf(
            if (langTag == "en") "No engine returned anything usable."
            else "هیچ موتوری نتیجهٔ قابل استفاده نداد."
        )
    }

    private fun note(text: String, langTag: String): List<String> = listOf(if (langTag == "en") "— $text" else "— $text")

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

    private fun pct(value: Double): String = "${(value * 100).toInt()}%"

    companion object {
        const val EMPTY_PACK_ID = "__none__"
    }
}

// ---------- commands ----------

enum class CommandKind { SAVE, FORGET, GOAL, BACKUP, LOG, RECALL, KNOWLEDGE, RESEARCH, REFRESH }

data class Command(val kind: CommandKind, val payload: String, val memoryKind: String = "note")

/**
 * Small, explicit command grammar. Anything that is not clearly a command falls through to the
 * research path, so a normal sentence is never silently turned into a database mutation.
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
    private val research = Regex(
        "(?i)^\\s*(تحقیق\\s*کن|جستجو\\s*کن|بگرد|پیدا\\s*کن|بررسی\\s*کن|research|look\\s*up|search)\\s*(?:دربارهٔ|درباره|برای|about|for)?\\s*[:：]?\\s*(.*)$",
        RegexOption.DOT_MATCHES_ALL
    )
    private val goal = Regex(
        "(?i)^\\s*(?:هدفم|هدف\\s*من|هدفم\\s*اینه\\s*که|قصد\\s*دارم|میخوام|می‌خوام|goal)\\s*(?:که|:)?\\s*(?:خرید|پیدا\\s*کردن)?\\s*[:：]?\\s*(.*)$",
        RegexOption.DOT_MATCHES_ALL
    )
    private val refresh = Regex("(?i)(بروز\\s*رسانی|به\\s*روز\\s*کن|به‌روز\\s*کن|تازه\\s*سازی|refresh|update)")
    private val backup = Regex("(?i)(بک\\s*آپ|بکاپ|پشتیبان\\s*گیری|نسخهٔ\\s*پشتیبان|backup)")
    private val log = Regex("(?i)(لاگ|گزارش\\s*فعالیت|log|history\\s*of\\s*actions)")
    private val knowledge = Regex("(?i)(دانش|دانسته‌ها|چه\\s*چیزهایی\\s*یاد\\s*گرفتی|چی\\s*یاد\\s*گرفتی|knowledge|what\\s+have\\s+you\\s+learned)")
    private val recall = Regex(
        "(?i)((چه|چیا)\\s*(چیز\\S*)?\\s*(یادت|در\\s*حافظه)|حافظه\\s*(را|رو)?\\s*(نشان|نشون|بگو|لیست|خالی)|^حافظه$|" +
            "what\\s+do\\s+you\\s+remember|show\\s+memory|list\\s+memory)"
    )

    fun parse(question: String): Command? {
        val original = question.trim()
        if (original.isEmpty()) return null
        // ZWNJ is meaningful in Persian but must not break command detection: match on a
        // same-length probe, then cut the payload from the original string.
        val text = original.replace('\u200c', ' ')

        save.find(text)?.let { match ->
            val payload = match.groups[1]?.let { original.substring(it.range) }.orEmpty().trim()
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
        forget.find(text)?.let { match ->
            val payload = match.groups[1]?.let { original.substring(it.range) }.orEmpty().trim()
            if (payload.isNotEmpty()) return Command(CommandKind.FORGET, payload)
        }
        // Stated goal: "هدفم خرید گوشی زیر ۲۰ میلیون است" — this is what makes the app start working
        // on its own instead of waiting for «تحقیق کن».
        goal.find(text)?.let { match ->
            val payload = match.groups[1]?.let { original.substring(it.range) }.orEmpty().trim()
            if (payload.length >= 4) return Command(CommandKind.GOAL, payload, "goal")
        }
        research.find(text)?.let { match ->
            val payload = match.groups[2]?.let { original.substring(it.range) }.orEmpty().trim()
            if (payload.isNotEmpty()) return Command(CommandKind.RESEARCH, payload)
        }
        if (refresh.containsMatchIn(text) && text.length <= 40) return Command(CommandKind.REFRESH, original)
        if (backup.containsMatchIn(text) && text.length <= 60) return Command(CommandKind.BACKUP, original)
        if (log.containsMatchIn(text) && text.length <= 40) return Command(CommandKind.LOG, original)
        if (knowledge.containsMatchIn(text) && text.length <= 60) return Command(CommandKind.KNOWLEDGE, original)
        if (recall.containsMatchIn(text) && text.length <= 40) return Command(CommandKind.RECALL, original)
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

    fun sentences(text: String): List<String> {
        val all = text.split(splitter).map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() }
        // Medium-sized sentences are normal prose. Short lines are usually fragments — except when
        // they carry a fact ("قیمت: ۵۰ میلیون تومان"), which is exactly what a shop page looks like.
        val prose = all.filter { it.length in 40..400 }
        val shortFacts = all.filter { it.length in 10 until 40 && AnswerShapes.hasFact(it) }
        val kept = (prose + shortFacts)
        return when {
            kept.isNotEmpty() -> kept
            all.isEmpty() -> emptyList()
            else -> all.take(3).filter { it.length in 8..400 }
        }
    }

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
            Evidence(sentence, page, overlap + positionBonus - lengthPenalty) to index
        }.sortedWith(compareByDescending<Pair<Evidence, Int>> { it.first.score }.thenBy { it.second })

        val picked = mutableListOf<Evidence>()
        for ((evidence, _) in scored) {
            if (picked.size >= limit) break
            if (evidence.score < 0.08) continue
            if (picked.any {
                    Similarity.hamming(
                        Similarity.simhash(Text.contentTokens(it.sentence)),
                        Similarity.simhash(Text.contentTokens(evidence.sentence))
                    ) < 12
                }
            ) continue
            picked.add(evidence)
        }
        if (picked.isEmpty() && scored.isNotEmpty()) picked.add(scored.first().first)
        return picked
    }
}
