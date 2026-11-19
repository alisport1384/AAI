package ir.aai.hushyar.domain

import ir.aai.hushyar.core.Json
import ir.aai.hushyar.core.MiniJson
import ir.aai.hushyar.core.Text

/** A local-only conversation. Threads share the app's verified knowledge, not each other's turns. */
data class ChatThread(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long
)

/** One durable user message and its (possibly pending/failed) assistant reply. */
data class ChatTurn(
    val id: String,
    val threadId: String,
    val question: String,
    val resolvedQuestion: String = "",
    val answer: Answer? = null,
    val createdAt: Long,
    val finishedAt: Long = 0L,
    val failure: String? = null
)

/** Preserve the whole answer, including citations, refusals and provenance, across process death. */
object ChatAnswerCodec {
    fun encode(answer: Answer): String = MiniJson.write(MiniJson.obj(
        "intent" to MiniJson.str(answer.intent),
        "text" to MiniJson.str(answer.text),
        "bullets" to MiniJson.strs(answer.bullets),
        "citations" to MiniJson.arr(answer.citations.map { citation -> MiniJson.obj(
            "ref" to MiniJson.str(citation.ref),
            "label" to MiniJson.str(citation.label),
            "url" to MiniJson.str(citation.url.orEmpty())
        ) }),
        "confidence" to MiniJson.num(answer.confidence),
        "missing" to MiniJson.strs(answer.missing),
        "refused" to MiniJson.bool(answer.refused),
        "lang" to MiniJson.str(answer.lang),
        "trace" to MiniJson.strs(answer.trace),
        "actions" to MiniJson.strs(answer.actions),
        "learned" to MiniJson.strs(answer.learned)
    ), pretty = false)

    fun decode(raw: String): Answer {
        val root = MiniJson.parse(raw)
        require(root is Json.Obj && root["text"] is Json.Str && root["intent"] is Json.Str) {
            "پاسخ ذخیره‌شده خوانا نیست"
        }
        return Answer(
            intent = root["intent"]!!.asStringOrNull()!!,
            text = root["text"]!!.asStringOrNull()!!,
            bullets = root["bullets"]?.asStrings().orEmpty(),
            citations = root["citations"]?.asObjects().orEmpty().map { row ->
                Citation(
                    ref = row["ref"]?.asStringOrNull() ?: "",
                    label = row["label"]?.asStringOrNull() ?: "",
                    url = row["url"]?.asStringOrNull()?.takeIf { it.isNotBlank() }
                )
            },
            confidence = root["confidence"]?.asDoubleOrNull() ?: 0.0,
            missing = root["missing"]?.asStrings().orEmpty(),
            refused = root["refused"]?.asBoolOrNull() ?: false,
            lang = root["lang"]?.asStringOrNull() ?: "fa",
            trace = root["trace"]?.asStrings().orEmpty(),
            actions = root["actions"]?.asStrings().orEmpty(),
            learned = root["learned"]?.asStrings().orEmpty()
        )
    }
}

/** The original utterance stays visible; the effective query is used for planning and evidence. */
data class ConversationResolution(
    val effectiveQuestion: String,
    val usedHistory: Boolean = false,
    val note: String = "",
    val directAnswer: Answer? = null,
    val forceFresh: Boolean = false
)

/**
 * Bounded, per-thread reference resolution. Past answers are never offered as current price
 * evidence. The small local model may suggest a rewrite, but only a mechanically checked rewrite
 * is used; if the model is absent or fails, the deterministic reading remains available.
 */
object ConversationContext {
    /** A bounded *working* window; the complete transcript still stays in SQLite/backup. */
    private const val MAX_TURNS = 12
    private val GENERIC = setOf(
        "قیمت", "قیمتی", "نرخ", "هزینه", "price", "cost", "چنده", "چقدر", "چی", "چیه", "چیست",
        "چطور", "چگونه", "چرا", "کجا", "کدام", "کدومش", "کدومشون", "کدامشون", "کدامشان", "بهصرفهتره", "صرفهتره", "what", "which", "why", "how", "much",
        "این", "اون", "آن", "همین", "همان", "همون", "قبلی", "بعدی", "دیگری", "یکی", "ش", "شون", "لینکش",
        "منبع", "منبعش", "منابع", "لینک", "سایت", "پیوند", "کجاست", "چقدره", "source", "sources", "پاسخ", "جواب", "عدد", "اطلاعات", "موضوع",
        "جدید", "تازه", "الان", "امروز", "حالا", "دوباره", "بروز", "بروزش", "بروزرسانی",
        "بهروز", "بهروزش", "بهروزرسانی", "روزرسانی", "میلیون", "هزار", "میلیارد", "بخواهم", "بخوام", "بخریم", "بگیرم", "بگیریم", "بخرم", "تفاوت", "مقایسه", "بهتر", "بیشتر", "توضیح", "بده", "بگو", "میخواهم",
        "اگر", "باشد", "بشه", "می‌شود", "میشه", "چند", "تا", "از", "برای", "در", "مورد", "هر",
        "هم", "به", "جای", "که", "را", "رو", "کن", "کنید", "است", "هست", "؟", "?",
        "وزن", "وزنش", "سایز", "سایزش", "اندازه", "ضخامت", "طول", "رنگ", "رنگش", "جنسش", "مدل", "مدلش", "مشخصاتش", "کیلو", "کیلوگرم", "گرم",
        "kg", "g", "میل", "متر", "عدد", "شاخه", "تومان", "ریال", "usd", "دلار",
        "it", "that", "this", "those", "there", "same", "previous", "again", "now", "today",
        "if", "instead", "about", "and", "then", "please", "update", "refresh", "source",
        "مالیات", "افزوده", "حمل", "نقل", "کرایه", "باربری", "ارسال", "اضافه", "جمع", "نهایی",
        "درصد", "بحث", "بحثمون", "موضوعمون", "موضوعش", "ادامه", "دربارش", "دربارهش", "راجع",
        "shipping", "freight", "tax", "vat", "total", "discussion", "conversation",
        "مالیاتش", "حملش", "کرایهاش", "جمعش", "فقط", "روی", "بر", "کالا", "مشمول", "غیرمشمول",
        "فرض", "بدون", "شامل", "رایگان", "جمعشون", "هزینهاش", "حساب", "کردن", "مبلغ", "نرخش",
        "درباره", "دربارهش", "دربارهاش", "اش", "موردش", "بحثمون", "میخواهی", "آیا"
    )
    private val REFERENCE = Regex(
        "(?i)(?:^|\\s)(?:این|اون|آن|همین|همان|قبلی|یکی|دیگری|حالا|بعدی|همون|" +
            "this|that|it|those|same|previous|instead|and then)(?:\\s|$)|" +
            "قیمتش|وزنش|منبعش|مشخصاتش|مدلش|چقدره|چطورش|بروزش|بهروزش|" +
            "مالیاتش|حملش|کرایهاش|جمعش|ادامه|دربارش|دربارهاش|درباره.?اش|موردش|بحثمون|راجع\\s+به"
    )
    private val RECALL = Regex("(?i)(?:سوال|سؤال|پرسش|بحث|موضوع|question).{0,35}(?:قبلی|قبل|پیشین|آخرین|previous|last)|(?:قبلی|قبل|پیشین|آخرین|previous|last).{0,35}(?:سوال|سؤال|پرسش|بحث|موضوع|question)")
    private val SOURCE = Regex("(?i)منبع|منابع|از کجا|source|citation|reference")
    private val EXPLAIN_NUMBER = Regex("(?i)چرا\\s+(?:این|اون|همون)\\s+(?:عدد|قیمت)|این\\s+عدد\\s+از\\s+کجا")
    private val REFRESH = Regex("(?i)بروز|به\\s*روز|تازه\\s*کن|دوباره\\s*بررسی|refresh|update")
    private val CORRECTION = Regex("(?i)(?:^|\\s)(?:نه|نخیر|اشتباه|غلط|درستش\\s*کن|اصلاح\\s*کن|این\\s*نیست|این\\s*اشتباه|no|wrong|incorrect|not\\s+this)(?:\\s|$|!|؟|\\?)")
    private val LINK_REFERENCE = Regex("(?i)(?:همان|همون|این|اون)\\s+(?:لینک|سایت|منبع)|لینکش|same\\s+link|that\\s+link")
    private val COMPARE_FOLLOWUP = Regex("(?i)کدومش|کدامش|مقایسهشون|فرقشون|بینشون|دوتاشون|هر\\s*دو|compare\\s+them|which\\s+one")
    private val AMBIGUOUS = Regex("(?i)(?:اون|آن|همون)\\s+یکی|دومی|اولی|the other one")
    private val WEIGHT = Regex("(?i)(?:کیلو(?:گرم)?|kg|گرم|\\bg\\b)")
    private val OLD_WEIGHT = Regex("(?i)(?<![\\p{L}\\p{N}])[0-9۰-۹]+(?:[/٫.,٬][0-9۰-۹]+)?\\s*(?:کیلو(?:گرم)?|kg|گرم|g)(?![\\p{L}\\p{N}])")
    private val SIMPLE_TALK = setOf("سلام", "درود", "ممنون", "مرسی", "خداحافظ", "hello", "hi", "thanks")

    fun resolve(
        question: String,
        threadId: String,
        history: List<ChatTurn>,
        generator: LocalGenerator? = null,
        spec: ModelSpec = ModelCatalog.recommended
    ): ConversationResolution {
        val clean = question.trim()
        val ownHistory = history.filter { it.threadId == threadId }
        val recent = ownHistory.filter { it.answer != null }.takeLast(MAX_TURNS)
        val last = recent.lastOrNull()
        val normalized = Text.normalize(clean)
        if (RECALL.containsMatchIn(normalized)) {
            val previous = ownHistory.lastOrNull { it.question.isNotBlank() }
            val reply = if (previous == null) "در همین گفتگو هنوز سؤال قبلی ثبت نشده است."
                else "سؤال قبلی‌ات در همین گفتگو: «${previous.question.take(700)}». " +
                    "این یادآوری از تاریخچهٔ محلی است؛ قیمت و پاسخ قبلی شاهد تازه نیستند."
            return ConversationResolution(clean, usedHistory = previous != null,
                note = "پرسش قبلی فقط از رشتهٔ انتخاب‌شده خوانده شد؛ هیچ جستجوی تازه‌ای نبود.",
                directAnswer = Answer("conversation.recall", reply,
                    confidence = if (previous != null) 1.0 else 0.0,
                    refused = previous == null,
                    missing = if (previous == null) listOf("سؤال قبلی در همین گفتگو") else emptyList(),
                    trace = listOf("یادآوری محلی؛ ادعای قیمت/وب بازبینی نشده است")))
        }
        // Correction: "نه! این اشتباست، فلان کارو بکن" — discard previous answer, force fresh research.
        if (CORRECTION.containsMatchIn(normalized) && last != null) {
            val corrected = clean.replace(CORRECTION, " ").trim().trim('!', '؟', '?', '،', ':')
            val effective = if (corrected.length >= 4) corrected else clean
            return ConversationResolution(
                "تحقیق کن دربارهٔ $effective",
                usedHistory = true,
                note = "اصلاح کاربر تشخیص داده شد؛ پاسخ قبلی کنار گذاشته شد و جستجوی تازه انجام می‌شود.",
                forceFresh = true
            )
        }
        if (normalized in SIMPLE_TALK) return ConversationResolution(clean)
        val command = CommandParser.parse(clean)
        if (command != null && command.kind !in setOf(CommandKind.REFRESH, CommandKind.RESEARCH)) {
            return ConversationResolution(clean)
        }
        val readSubject = subject(clean)
        // A weight, size or count by itself is a constraint, not a new named product.
        val explicit = readSubject.takeIf { words -> words.split(' ').any { token ->
            token.any(Char::isLetter) && token !in GENERIC
        } }.orEmpty()
        val asksFresh = REFRESH.containsMatchIn(normalized)
        val comparesEarlier = COMPARE_FOLLOWUP.containsMatchIn(normalized)
        val refers = comparesEarlier || REFERENCE.containsMatchIn(normalized) ||
            (explicit.isEmpty() && (clean.length <= 100 || SOURCE.containsMatchIn(clean))) ||
            (last != null && clean.length <= 75 && normalized.endsWith("چطور؟"))
        // A self-contained update also has to fetch new evidence, not replay a cached case.
        if (!refers && explicit.isNotEmpty() && asksFresh) return ConversationResolution(
            "تحقیق کن دربارهٔ $clean", forceFresh = true
        )
        if (last == null) {
            return if (comparesEarlier || (refers && explicit.isEmpty()))
                clarify(clean, "هنوز در این گفت‌وگو موضوعی برای ارجاع ندارم؛ نام کالا یا موضوع را بنویس.")
            else ConversationResolution(clean)
        }
        val distinctTopics = recent.asReversed().map { subject(it.resolvedQuestion.ifBlank { it.question }) }
            .filter { it.any(Char::isLetter) }.distinct()
        if (comparesEarlier && explicit.isEmpty()) {
            if (distinctTopics.size != 2) {
                return clarify(clean, "برای مقایسهٔ «آن دو» دو موضوع مشخص لازم دارم؛ هر دو را نام ببر.")
            }
            val joined = distinctTopics.reversed().joinToString(" و ")
            return ConversationResolution("مقایسهٔ $joined؛ $clean", usedHistory = true,
                note = "دو موضوع مشخصِ همین گفت‌وگو برای مقایسه کنار هم گذاشته شدند؛ پاسخ‌های قبلی شاهد تازه نیستند.")
        }
        if (AMBIGUOUS.containsMatchIn(normalized) && explicit.isEmpty()) {
            if (distinctTopics.size == 2) {
                val other = distinctTopics[1]
                val previousAsk = QuestionReader.read(recent.last().resolvedQuestion.ifBlank { recent.last().question }).ask
                return ConversationResolution("${if (previousAsk == AskType.PRICE) "قیمت " else ""}$other؛ $clean",
                    usedHistory = true, note = "«آن یکی» به موضوع دیگرِ همین گفت‌وگو اشاره کرد.")
            }
            return clarify(clean, "«آن یکی» مشخص نیست؛ نام یا مشخصات مورد نظرت را بنویس تا کالاها را اشتباه نگیرم.")
        }
        val anchorTurn = recent.asReversed().firstOrNull { turn ->
            subject(turn.resolvedQuestion.ifBlank { turn.question }).any(Char::isLetter) &&
                turn.answer?.intent != "conversation.clarify"
        } ?: last
        val previousQuery = anchorTurn.resolvedQuestion.ifBlank { anchorTurn.question }
        val previousSubject = subject(previousQuery)
        // A clarification is a question back to the user. A subject-only reply completes it.
        if (last.answer?.intent == "conversation.clarify" && explicit.isNotEmpty() &&
            QuestionReader.read(last.question).ask == AskType.PRICE &&
            QuestionReader.read(clean).ask == AskType.FACT
        ) {
            return ConversationResolution("قیمت $clean چقدر است؟", usedHistory = true, note = "موضوع را از پاسخ روشن‌کننده تکمیل کردم.")
        }
        // تکمیل پرسش قبلی: فقط وقتی پیام، نام محصول جدید ندارد — مثلاً «ضخامت 4» یا «برای تهران»
        // اگر پیام خودش نام محصول دیگر دارد (ورق vs نبشی) موضوع تازه است نه تکمیل
        val PRODUCT_WORDS = setOf("ورق","پلیت","صفحه","میلگرد","تیرآهن","تیر","هاش","نبشی","ناودانی","سپری","پروفیل","قوطی","لوله","بولت","انکر","تسمه","چهارپهلو","مفتول","مش","توری","رابیتس","شمش","قراضه")
        val hasProductWord = PRODUCT_WORDS.any { normalized.contains(it) }
        val looksLikeSupplement = !refers && clean.length <= 60 && !hasProductWord && (
            Regex("(?i)(ضخامت|قطر|سایز|اندازه|ابعاد|طول|عرض|گرید|استاندارد|شاخه|عدد|تن|کیلو|برگ|رول|برای|تهران|اصفهان|مشهد|تبریز|شیراز|ارسال|حمل|قیمتش|موجود|سایز)").containsMatchIn(normalized)
        )
        if (looksLikeSupplement && previousSubject.isNotEmpty()) {
            val merged = "$previousSubject؛ $clean"
            return ConversationResolution(merged, usedHistory = true, note = "جزئیات تکمیلی به موضوع قبلی «$previousSubject» افزوده شد؛ یک پرسش کامل ساخته شد.")
        }
        // تغییر محصول صریح (ورق vs نبشی) موضوع تازه است — حتی اگر کوتاه باشد
        if (!refers && explicit.isNotEmpty()) return ConversationResolution(clean)
        if (previousSubject.isEmpty() && explicit.isEmpty()) {
            return clarify(clean, "موضوع پیام قبلی مشخص نبود؛ لطفاً نام کالا یا موضوع را بگو.")
        }
        // "منبعش؟" means the *previous answer's* citations, not a new, unrelated web search.
        if (SOURCE.containsMatchIn(normalized) && explicit.isEmpty() && !REFRESH.containsMatchIn(normalized) &&
            !LINK_REFERENCE.containsMatchIn(normalized)) {
            val citations = last.answer?.citations.orEmpty()
            val answer = if (citations.isEmpty()) Answer(
                intent = "conversation.source", text = "برای پاسخ قبلی منبع قابل نمایشی ثبت نشده است؛ نمی‌توانم منبعی بسازم.",
                refused = true, missing = listOf("منبع پاسخ قبلی"), trace = listOf("ارجاع به پاسخ پیشین همین گفت‌وگو")
            ) else Answer(
                intent = "conversation.source", text = "این‌ها منبع‌های ثبت‌شده برای پاسخ قبلی‌اند؛ در این نوبت دوباره بررسی نشده‌اند.",
                citations = citations, confidence = 0.6, trace = listOf("منبع‌ها عیناً از پاسخ قبلی همین گفت‌وگو خوانده شدند")
            )
            return ConversationResolution(clean, usedHistory = true, note = "ارجاع به منابع پاسخ قبلی", directAnswer = answer)
        }
        if (EXPLAIN_NUMBER.containsMatchIn(normalized) && explicit.isEmpty()) {
            val prior = last.answer!!
            val supported = prior.citations.isNotEmpty()
            val answer = Answer(
                intent = "conversation.provenance",
                text = if (supported) "شواهد و توضیح‌های ثبت‌شده برای همان پاسخ قبلی در پایین آمده‌اند؛ این تأیید تازهٔ عدد نیست."
                else "برای عدد پاسخ قبلی منبع یا محاسبهٔ قابل نمایشی ثبت نشده؛ دلیل آن را حدس نمی‌زنم.",
                bullets = if (supported) prior.bullets.take(5) else emptyList(),
                citations = if (supported) prior.citations else emptyList(),
                confidence = if (supported) 0.6 else 0.0,
                refused = !supported,
                missing = if (supported) emptyList() else listOf("شاهد یا محاسبه برای عدد پاسخ قبلی"),
                trace = listOf("فقط شواهد پاسخ قبلی همین گفت‌وگو خوانده شدند")
            )
            return ConversationResolution(clean, usedHistory = true, note = "توضیح شواهد پاسخ قبلی", directAnswer = answer)
        }
        // An earlier user-supplied weight was a constraint, not evidence for a new weight question.
        val property = when {
            normalized.contains("وزنش") -> "وزن"
            normalized.contains("رنگش") -> "رنگ"
            normalized.contains("جنسش") -> "جنس"
            normalized.contains("سایزش") -> "سایز"
            normalized.contains("مشخصاتش") -> "مشخصات"
            else -> null
        }
        if (!asksFresh && explicit.isEmpty() && property != null && !normalized.contains("اگر") &&
            previousSubject.isNotEmpty()) {
            val referent = if (property == "وزن") OLD_WEIGHT.replace(previousSubject, " ").trim() else previousSubject
            return ConversationResolution("$property $referent ${if (property == "وزن") "چقدر است؟" else "چیست؟"}",
                usedHistory = true, note = "ویژگیِ خواسته‌شده را از موضوع همین گفت‌وگو جدا کردم؛ عدد قبلی شاهد این ویژگی نیست.")
        }
        val isFresh = asksFresh || LINK_REFERENCE.containsMatchIn(normalized)
        val base = if (explicit.isNotEmpty()) "" else stripOverriddenWeight(previousSubject, clean)
        val previousAsk = QuestionReader.read(previousQuery).ask
        val currentAsk = QuestionReader.read(clean).ask
        val genericHow = normalized.endsWith("چطور؟") || normalized.endsWith("چطور?") ||
            normalized.endsWith("چطور") || normalized.endsWith("چطوره؟")
        val hasOwnIntent = (currentAsk != AskType.FACT && !(currentAsk == AskType.METHOD && genericHow)) ||
            listOf("کیفیت", "مشخصات", "وزن", "سایز", "منبع", "تفاوت").any { normalized.contains(it) &&
                !normalized.contains("وزنش") && !normalized.contains("سایزش") }
        if (explicit.isNotEmpty() && !isFresh && (hasOwnIntent || previousAsk != AskType.PRICE)) {
            return ConversationResolution(clean)
        }
        val intent = if (!hasOwnIntent && previousAsk == AskType.PRICE) "قیمت " else ""
        val fallback = when {
            explicit.isNotEmpty() -> (intent + clean).trim()
            base.isNotBlank() -> (intent + base + "؛ " + clean).trim()
            else -> clean
        }
        // A request to refresh must actually fetch again, never silently replay stored prices.
        if (isFresh) {
            val urls = (UrlIngest.links(anchorTurn.question) + last.answer?.citations.orEmpty().mapNotNull { it.url })
                .filter { it.startsWith("https://") || it.startsWith("http://") }.distinct().take(3)
            if (LINK_REFERENCE.containsMatchIn(normalized) && urls.isEmpty()) {
                return clarify(clean, "در همین گفت‌وگو لینکی برای بازکردن ندارم؛ نشانی صفحه را بفرست.")
            }
            val topicQuery = (if (previousAsk == AskType.PRICE && !fallback.contains("قیمت")) "قیمت $fallback" else fallback)
                .replace(REFRESH, " ").replace(LINK_REFERENCE, " ").trim(' ', '؛', '،')
            val query = if (urls.isNotEmpty() && QuestionReader.read(topicQuery).ask == AskType.PRICE) {
                "$topicQuery ${urls.joinToString(" ")}".trim()
            } else "تحقیق کن دربارهٔ $topicQuery" // forced fresh research, without a cached answer
            return ConversationResolution(query, usedHistory = true, note = "به‌روزرسانی با بررسی دوبارهٔ منبع/جستجو", forceFresh = true)
        }
        val modelAttempted = generator?.ready == true && base.isNotBlank()
        val proposed = if (modelAttempted) rewriteWithModel(clean, previousSubject, recent, fallback, generator!!, spec)
            else null
        val effective = proposed ?: fallback
        return ConversationResolution(
            effective, usedHistory = true,
            note = when {
                proposed != null -> "ارجاع پیام با پیشنهادِ بررسی‌شدهٔ مدل محلی فهمیده شد."
                modelAttempted -> "پیشنهاد مدل محلی قابل تأیید نبود یا مدل پاسخی نداد؛ ارجاع با قاعدهٔ محلی فهمیده شد."
                else -> "مدل محلی فعال نیست؛ ارجاع با قاعدهٔ محلی فهمیده شد."
            }
        )
    }

    /** Only substantive tokens, never URLs, verbs or the user's numeric answer from a prior turn. */
    private fun subject(question: String): String {
        val withoutUrls = UrlIngest.links(question).fold(question) { s, url ->
            s.replace(url, " ", ignoreCase = true).replace(url.removePrefix("https://").removePrefix("http://"), " ", ignoreCase = true)
        }
        // Financial numbers are user assumptions, never product sizes or a cached
        // price to feed to a subsequent search/model. Keep the *product* size.
        val safe = Text.normalize(withoutUrls)
            .replace(Regex("(?:برای|مقدار|تعداد|سفارش)\\s*[0-9]+(?:[./٫][0-9]+)?\\s*(?:کیلوگرم|کیلو|kg|تن|شاخه|برگ|عدد|متر)(?![\\p{L}])"), " ")
            .replace(Regex("[0-9]+(?:[.,٬][0-9]{3})*\\s*(?:هزار|میلیون|میلیارد)?\\s*(?:تومان|تومن|ریال)"), " ")
            .replace(Regex("[0-9]+(?:[.,][0-9]+)?\\s*(?:درصد|٪|%)"), " ")
        val fromQuestion = QuestionReader.read(safe).subject.toSet()
        // QuestionReader intentionally discards one-character search terms. In conversation a
        // single digit often IS the product size ("نبشی ۴"), so put it back in its original order.
        return Text.tokens(safe)
            .filter { token -> token in fromQuestion || token.all(Char::isDigit) }
            .filterNot { it in GENERIC || it.startsWith("http") || it.startsWith("www") }
            .take(18).joinToString(" ").take(160)
    }

    private fun stripOverriddenWeight(subject: String, question: String): String =
        if (WEIGHT.containsMatchIn(question) && Regex("[0-9۰-۹]").containsMatchIn(question))
            OLD_WEIGHT.replace(subject, " ").replace(Regex("\\s+"), " ").trim()
        else subject

    private fun clarify(question: String, message: String): ConversationResolution = ConversationResolution(
        question, directAnswer = Answer(
            intent = "conversation.clarify", text = message, refused = true,
            missing = listOf("موضوع ارجاع"), trace = listOf("ارجاع بدون موضوع روشن؛ جستجو انجام نشد")
        )
    )

    private fun rewriteWithModel(
        question: String, anchor: String, recent: List<ChatTurn>, fallback: String,
        generator: LocalGenerator, spec: ModelSpec
    ): String? {
        val transcript = recent.takeLast(4).joinToString("\n") { turn ->
            "کاربر: ${turn.question.take(150)}\nبرداشت قبلی: ${turn.resolvedQuestion.take(150)}\n" +
                "پاسخ قبلی، فقط برای فهم ارجاع: ${turn.answer?.text?.take(110).orEmpty()}"
        }
        val system = "فقط پرسش تازه را با استفاده از گفت‌وگوی همین رشته مستقل و روشن بازنویسی کن. " +
            "پاسخ نده. اسم، عدد یا لینک تازه اضافه نکن. عدد و قیمت پاسخ قبلی شاهد قیمت تازه نیست. فقط یک سطر پرسش بنویس."
        val prompt = LocalPrompt.chat(spec.family, system, "$transcript\nپرسش تازه: $question\nپرسش مستقل:")
        val raw = runCatching { generator.generate(prompt, 128).text }.getOrNull() ?: return null
        val proposed = LocalPrompt.stripThinking(raw).lineSequence().firstOrNull()?.trim()
            ?.removePrefix("پرسش مستقل:")?.trim()?.trim('«', '»', '"')?.trim().orEmpty()
        if (proposed.length !in 8..400) return null
        // Reject invented URLs and numbers, even when the previous answer happened to contain them.
        val permitted = question + " " + recent.joinToString(" ") { it.resolvedQuestion.ifBlank { it.question } }
        if (UrlIngest.links(proposed).any { it !in UrlIngest.links(question) }) return null
        val withoutLinks = UrlIngest.links(permitted, max = 50).fold(permitted) { text, url ->
            text.replace(url, " ", ignoreCase = true)
                .replace(url.removePrefix("https://").removePrefix("http://"), " ", ignoreCase = true)
        }
        val allowedNumbers = Grounding.numbers(withoutLinks).toSet()
        if (!allowedNumbers.containsAll(Grounding.numbers(proposed))) return null
        if (!Grounding.numbers(proposed).containsAll(Grounding.numbers(question))) return null
        val allowedWords = Text.contentTokens(permitted + " " + fallback).toSet() + GENERIC
        if (Text.contentTokens(proposed).any { word -> word !in allowedWords }) return null
        val anchorWords = Text.contentTokens(anchor).filter { it !in GENERIC }
        if (anchorWords.isNotEmpty() && anchorWords.none { it in Text.contentTokens(proposed) }) return null
        if (QuestionReader.read(fallback).ask == AskType.PRICE && QuestionReader.read(proposed).ask != AskType.PRICE) return null
        return proposed.takeIf { Text.normalize(it) != Text.normalize(question) }
    }
}
