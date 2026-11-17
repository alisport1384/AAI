package ir.aai.hushyar.domain

import ir.aai.hushyar.core.Text as Txt

/**
 * The on-device language model, as a *value*: which GGUF files exist, how big they
 * are, what prompt template each family needs, and what the model is allowed to say.
 *
 * Everything in this file is pure Kotlin — no Android, no network, no JNI — so the
 * whole policy layer is unit-tested without a phone and without a 500 MB download.
 * The engine itself lives in `core/LlamaNative.kt` + `data/LocalModel.kt`.
 *
 * Design rule that keeps v0.6's guarantees intact: **the model never produces facts.**
 * It only re-phrases a draft that was already built from stored knowledge or cited web
 * pages. Every number in the sentence must already exist in that draft (see [Grounding]),
 * otherwise the answer falls back to the deterministic template and says why.
 */
data class ModelSpec(
    val id: String,
    val title: String,
    /** Chat-template family: "qwen2.5" (ChatML) or "gemma3". */
    val family: String,
    val fileName: String,
    val url: String,
    val bytes: Long,
    /** SHA-256 of the exact file at [url]; a download that does not match is deleted. */
    val sha256: String,
    val contextTokens: Int,
    val license: String,
    /** Honest quality note in Persian, shown before the user spends ~500 MB of data. */
    val note: String,
    val recommended: Boolean = false
) {
    val megabytes: Long get() = bytes / (1024 * 1024)
    val sizeLabel: String get() = "${Txt.num(megabytes)} مگابایت"
}

/**
 * Three real, downloadable GGUF files. Sizes and SHA-256 values were read from the
 * hosting API (`x-linked-size` / `x-linked-etag`) and one of them was downloaded and
 * hashed end-to-end, so the pins are verified facts, not guesses.
 */
object ModelCatalog {

    const val HOST = "https://huggingface.co"

    val all: List<ModelSpec> = listOf(
        ModelSpec(
            id = "gemma-3-270m",
            title = "Gemma 3 270M (Q8_0)",
            family = "gemma3",
            fileName = "gemma-3-270m-it-Q8_0.gguf",
            url = "$HOST/ggml-org/gemma-3-270m-it-GGUF/resolve/main/gemma-3-270m-it-Q8_0.gguf",
            bytes = 291_545_600L,
            sha256 = "0ef57d2c838458a1952664260dcba38e5bdda37494f3af732f06e4add24068e3",
            contextTokens = 4096,
            license = "Gemma Terms of Use",
            note = "کوچک‌ترین گزینه (۲۹۱ مگابایت) و سریع حتی روی گوشی ضعیف؛ فارسی آن ضعیف است " +
                "و بیشتر جمله می‌سازد تا این‌که داده را خلاصه کند. برای آزمایش کارکرد موتور."
        ),
        ModelSpec(
            id = "qwen2.5-0.5b",
            title = "Qwen2.5 0.5B Instruct (Q4_K_M)",
            family = "qwen2.5",
            fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            url = "$HOST/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            bytes = 491_400_032L,
            sha256 = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db",
            contextTokens = 32_768,
            license = "Apache-2.0",
            note = "پیشنهاد پیش‌فرض: فارسی را می‌فهمد و می‌نویسد، ۴۹۱ مگابایت، روی گوشی متوسط " +
                "چند توکن در ثانیه. مجوز Apache-2.0، بدون محدودیت استفاده.",
            recommended = true
        ),
        ModelSpec(
            id = "qwen2.5-0.5b-q4_0",
            title = "Qwen2.5 0.5B Instruct (Q4_0)",
            family = "qwen2.5",
            fileName = "qwen2.5-0.5b-instruct-q4_0.gguf",
            url = "$HOST/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_0.gguf",
            bytes = 428_730_208L,
            sha256 = "7671c0c304e6ce5a7fc577bcb12aba01e2c155cc2efd29b2213c95b18edaf6ed",
            contextTokens = 32_768,
            license = "Apache-2.0",
            note = "همان مدل با کوانت کم‌حجم‌تر: ۴۰۹ مگابایت (۶۰ مگابایت کمتر از Q4_K_M). " +
                "کیفیت کمی پایین‌تر است ولی برای یک جملهٔ بازنویسی کافی."
        ),
        ModelSpec(
            id = "qwen2.5-1.5b",
            title = "Qwen2.5 1.5B Instruct (Q4_K_M)",
            family = "qwen2.5",
            fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            url = "$HOST/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            bytes = 1_117_320_736L,
            sha256 = "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e",
            contextTokens = 32_768,
            license = "Apache-2.0",
            note = "بهترین کیفیت فارسی این فهرست، ولی ۱٫۱ گیگابایت دانلود و حدود ۲ گیگابایت RAM؛ " +
                "برای گوشی‌های جدید. کارکرد بدون آن هم کامل است."
        )
    )

    val byId: Map<String, ModelSpec> = all.associateBy { it.id }

    val recommended: ModelSpec = all.first { it.recommended }

    fun find(id: String?): ModelSpec = byId[id] ?: recommended

    /** Which template to use when we do not recognise the family. */
    const val GENERIC_FAMILY = "generic"
}

/**
 * Prompt construction. Two jobs only:
 *  1. wrap a conversation in the chat template the file was trained with;
 *  2. phrase the re-writing instruction so a small model cannot invent anything.
 */
object LocalPrompt {

    const val MARKER_UNRELIABLE_FA = "پاسخ قابل اتکا نیست"
    const val MARKER_UNRELIABLE_EN = "not reliable"

    // Measured on the real file: any extra instruction sentence here (e.g. "if the data is not
    // enough, write …") makes the 0.5B model stitch labels instead of writing a sentence, so the
    // system prompt stays deliberately this short. Insufficiency is handled upstream — a refused
    // draft never reaches the model at all.
    private const val SYSTEM_FA =
        "تو یک بازنویس هستی. فقط داده‌ای را که به تو می‌دهند به یک جملهٔ روان فارسی تبدیل می‌کنی. " +
            "هیچ عدد یا نام تازه‌ای نمی‌سازی."

    private const val SYSTEM_EN =
        "You are a re-writer. You turn the given data into one fluent sentence and nothing else. " +
            "You never invent a number or a name."

    /**
     * A single worked example, deliberately about a *different* subject. Measured on the real
     * Qwen2.5-0.5B file: without it the model echoes the data list, with it the model writes
     * one grounded sentence and stops. The numbers here are decoys — if the model copies them
     * instead of reading the data, [Grounding] rejects the answer and the template speaks.
     */
    private val EXAMPLE_FA = "داده: موجودی «لپ‌تاپ ایسوس» بر پایهٔ ۲ آگهی: ۴ عدد؛ قیمت ۳۵٬۰۰۰٬۰۰۰ تومان.\nجمله:" to
        "قیمت «لپ‌تاپ ایسوس» ۳۵٬۰۰۰٬۰۰۰ تومان است و ۴ عدد از آن موجود است."

    private val EXAMPLE_EN = "Data: stock of \"Asus laptop\" from 2 listings: 4 units; price 35,000,000 toman.\nSentence:" to
        "The Asus laptop costs 35,000,000 toman and 4 units are in stock."

    fun system(lang: String): String = if (lang == "en") SYSTEM_EN else SYSTEM_FA

    fun example(lang: String): Pair<String, String> = if (lang == "en") EXAMPLE_EN else EXAMPLE_FA

    /** One line of data: the draft's subject followed by every fact, semicolon-separated. */
    fun dataLine(lang: String, draft: AnswerDraft): String {
        val facts = draft.facts.joinToString("؛ ") { "${it.label} ${it.value}" }
        val subject = draft.headline.trimEnd(':', '：').trim()
        return if (lang == "en") {
            "Data: $subject: $facts."
        } else {
            "داده: $subject: $facts."
        }
    }

    fun cue(lang: String): String = if (lang == "en") "Sentence:" else "جمله:"

    /** The question the model is actually asked, plus the example turn that keeps it in format. */
    fun groundedUser(lang: String, draft: AnswerDraft): String =
        dataLine(lang, draft) + "\n" + cue(lang)

    /**
     * Wraps system + (optional) example + instruction in the template the model was trained with.
     */
    fun chat(
        family: String,
        system: String,
        user: String,
        example: Pair<String, String>? = null
    ): String = when (family) {
        "qwen2.5" -> buildString {
            append("<|im_start|>system\n").append(system).append("<|im_end|>\n")
            if (example != null) {
                append("<|im_start|>user\n").append(example.first).append("<|im_end|>\n")
                append("<|im_start|>assistant\n").append(example.second).append("<|im_end|>\n")
            }
            append("<|im_start|>user\n").append(user).append("<|im_end|>\n")
            append("<|im_start|>assistant\n")
        }
        "gemma3" -> buildString {
            // Gemma has no system role: the instruction rides in the first user turn.
            append("<start_of_turn>user\n").append(system)
            if (example != null) append("\n\n").append(example.first)
            append("<end_of_turn>\n")
            if (example != null) {
                append("<start_of_turn>model\n").append(example.second).append("<end_of_turn>\n")
            }
            append("<start_of_turn>user\n").append(user).append("<end_of_turn>\n")
            append("<start_of_turn>model\n")
        }
        else -> buildString {
            append("### System\n").append(system).append("\n\n")
            if (example != null) {
                append("### User\n").append(example.first).append("\n\n")
                append("### Assistant\n").append(example.second).append("\n\n")
            }
            append("### User\n").append(user).append("\n\n### Assistant\n")
        }
    }

    fun groundedRewrite(spec: ModelSpec, lang: String, draft: AnswerDraft): String =
        chat(spec.family, system(lang), groundedUser(lang, draft), example(lang))

    /** A fixed, harmless prompt used by the "آزمایش موتور" button. */
    fun selfTest(spec: ModelSpec): String =
        chat(
            spec.family,
            "تو یک دستیار کوتاه‌گو هستی.",
            "در یک جملهٔ کوتاه فارسی بگو هوای آفتابی برای چه کاری مناسب است."
        )
}

/** Result of one generation attempt: text, or the reason there is none. */
data class LocalGeneration(
    val text: String? = null,
    val error: String? = null,
    val millis: Long = 0,
    val tokens: Int = 0
) {
    val ok: Boolean get() = !text.isNullOrBlank()
    val tokensPerSecond: Double get() = if (millis <= 0) 0.0 else tokens * 1000.0 / millis
}

/**
 * Port for "run this text through the model". Implemented by the llama.cpp engine on
 * device and by a fake in tests, so all phrasing policy below is testable.
 */
interface LocalGenerator {
    val ready: Boolean
    fun generate(prompt: String, maxTokens: Int): LocalGeneration
    fun cancel()
}

/** Why a model sentence was rejected. Kept as data so the UI can print the exact reason. */
data class Verdict(val ok: Boolean, val reason: String? = null) {
    companion object {
        val ACCEPT = Verdict(true)
        fun reject(reason: String) = Verdict(false, reason)
    }
}

/**
 * The guard every model sentence passes through before the user sees it.
 *
 * A small model asked to summarise prices will happily invent a number, so the check is
 * mechanical: every digit group in the output must already appear in the draft. Persian
 * and Arabic-Indic digits are normalised, so «۱۲٬۰۰۰» matches «12,000».
 */
object Grounding {

    const val MAX_CHARS = 480
    private const val MIN_CHARS = 8

    private val NUM = Regex("[0-9\u06F0-\u06F9\u0660-\u0669]+(?:[.,\u066B\u066C][0-9\u06F0-\u06F9]+)*")
    private val PERSIAN = Regex("[\u0600-\u06FF]")
    private val PREAMBLE = Regex(
        "(?i)^\\s*(پاسخ|جواب|assistant|answer)\\s*[:：]\\s*" +
            "|^(به عنوان|as an ai|i'm sorry|متأسفم|متاسفم)"
    )
    private val URL = Regex("(?i)https?://|www\\.")

    fun numbers(text: String): List<String> = NUM.findAll(text).map { canonical(it.value) }.toList()

    /** "۱۲٬۰۰۰٫۵" → "12000.5" so two spellings of the same number compare equal. */
    fun canonical(raw: String): String {
        val digits = buildString {
            for (ch in raw) {
                when {
                    ch in '0'..'9' -> append(ch)
                    ch in '\u06F0'..'\u06F9' -> append('0' + (ch - '\u06F0'))
                    ch in '\u0660'..'\u0669' -> append('0' + (ch - '\u0660'))
                    ch == '\u066B' -> append('.')
                    ch == '.' -> append('.')
                    else -> {} // thousands separators and Persian decimal marks are dropped
                }
            }
        }
        if (digits.isEmpty()) return digits
        val parts = digits.split('.', limit = 2)
        val whole = parts[0].trimStart('0').ifEmpty { "0" }
        val frac = parts.getOrNull(1)?.trimEnd('0').orEmpty()
        return if (frac.isEmpty()) whole else "$whole.$frac"
    }

    /** Numbers the model is allowed to repeat: whatever the evidence already said. */
    fun allowedNumbers(draft: AnswerDraft): Set<String> = buildSet {
        addAll(numbers(draft.headline))
        draft.facts.forEach {
            addAll(numbers(it.label))
            addAll(numbers(it.value))
        }
    }

    fun check(draft: AnswerDraft, raw: String, lang: String): Verdict {
        val text = clean(raw)
        if (text.length < MIN_CHARS) return Verdict.reject("پاسخ مدل خیلی کوتاه بود")
        if (text.length > MAX_CHARS) return Verdict.reject("پاسخ مدل بیش از ${Txt.numEn(MAX_CHARS.toLong())} نویسه بود")
        if (URL.containsMatchIn(text)) return Verdict.reject("مدل لینک ساخت")
        if (PREAMBLE.containsMatchIn(text) && !text.startsWith(draft.headline.take(12))) {
            return Verdict.reject("پاسخ مدل با مقدمه/عذرخواهی شروع شد")
        }
        // Measured failure mode of the small model: it re-prints the data list instead of
        // writing a sentence. An answer is one or two lines; a data dump is not an answer.
        if (text.lines().count { it.isNotBlank() } > 2) {
            return Verdict.reject("مدل به‌جای جمله، فهرست/داده را تکرار کرد")
        }

        if (lang != "en" && PERSIAN.findAll(text).count() < 4) {
            return Verdict.reject("پاسخ مدل فارسی نبود")
        }
        val allowed = allowedNumbers(draft)
        val invented = numbers(text).filter { it !in allowed }
        if (invented.isNotEmpty()) {
            return Verdict.reject("عدد بدون شاهد در پاسخ مدل: ${invented.take(3).joinToString("، ")}")
        }
        return Verdict.ACCEPT
    }

    /** Trims the noise a small model adds around a perfectly good sentence. */
    fun clean(raw: String): String {
        var text = raw.trim()
        text = text.removePrefix("\"").removeSuffix("\"").trim()
        text = text.removePrefix("«").removeSuffix("»").trim()
        text = PREAMBLE.replace(text, "").trim()
        text = text.replace(Regex("[ \\t]+"), " ")
        text = text.replace(Regex("\\n{2,}"), "\n")
        return text.trim()
    }
}

/**
 * The v0.6 template phraser, upgraded: when a model is installed the headline sentence is
 * written by the model, but the bullets, citations, confidence and refusal logic stay
 * exactly as deterministic as before.
 *
 * Any rejection — engine missing, timeout, invented number, non-Persian output — falls back
 * to [TemplatePhraser] and records the reason in [lastVerdict], so the app can tell the user
 * what happened instead of silently degrading.
 */
class LocalPhraser(
    override val lang: String,
    private val generator: LocalGenerator,
    private val spec: ModelSpec = ModelCatalog.recommended,
    private val fallback: Phraser = TemplatePhraser(lang),
    private val maxTokens: Int = 160
) : Phraser {

    var lastVerdict: Verdict? = null
        private set

    override fun render(draft: AnswerDraft): Answer {
        // A refusal is already the honest answer; a model must never talk over it.
        if (draft.refused || !generator.ready) {
            val why = if (draft.refused) "پاسخ رد شده بود" else "موتور محلی آماده نیست"
            lastVerdict = Verdict.reject(why)
            return withTrace(fallback.render(draft), "phraser: قالب (${why})")
        }

        val prompt = LocalPrompt.groundedRewrite(spec, lang, draft)
        val generation = generator.generate(prompt, maxTokens)
        val text = generation.text?.trim().orEmpty()
        val verdict = if (text.isEmpty()) {
            Verdict.reject(generation.error ?: "مدل خروجی نداد")
        } else {
            Grounding.check(draft, text, lang)
        }
        lastVerdict = verdict
        if (!verdict.ok) {
            return withTrace(fallback.render(draft), "phraser: قالب (${verdict.reason})")
        }

        return withTrace(
            fallback.render(draft).copy(text = Grounding.clean(text)),
            "phraser: مدل محلی ${spec.id} در ${generation.millis}ms"
        )
    }

    /** Provenance is appended, never overwritten: the trace shows which phraser spoke. */
    private fun withTrace(answer: Answer, line: String): Answer =
        answer.copy(trace = answer.trace + line)
}

/**
 * Free-form chat with the local model, kept deliberately separate from the evidence-based
 * answer path: nothing here is written to the knowledge store and every reply is labelled
 * as model text in the UI.
 */
class LocalChatter(
    private val generator: LocalGenerator,
    private val spec: ModelSpec = ModelCatalog.recommended,
    private val maxTokens: Int = 256
) {

    fun reply(question: String, lang: String = "fa"): LocalGeneration {
        if (!generator.ready) {
            return LocalGeneration(error = "مدل محلی نصب یا بارگذاری نشده است")
        }
        if (question.isBlank()) return LocalGeneration(error = "پرسش خالی است")
        val prompt = LocalPrompt.chat(spec.family, LocalPrompt.system(lang), question.trim())
        return generator.generate(prompt, maxTokens)
    }
}

/** One line a user can read: the reply, or exactly why there is none. */
fun LocalGeneration.replyLine(): String = when {
    ok -> "پاسخ مدل (${millis}ms): $text"
    else -> "مدل جواب نداد: ${error ?: "دلیل نامشخص"}"
}

/** What the UI shows about the on-device core. Pure data, built by the view model. */
enum class ModelPhase { MISSING, DOWNLOADING, READY, ENGINE_ERROR }

data class LocalModelUi(
    val phase: ModelPhase = ModelPhase.MISSING,
    val specId: String = ModelCatalog.recommended.id,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val engineReady: Boolean = false,
    /** The model file is on disk and verified — independent of whether the engine can load it. */
    val installed: Boolean = false,
    val backend: String = "",
    val usingForAnswers: Boolean = false,
    val message: String? = null,
    val lastTest: String? = null
) {
    val spec: ModelSpec get() = ModelCatalog.find(specId)
    val progress: Float
        get() = if (totalBytes <= 0) 0f else (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    val percent: Int get() = (progress * 100).toInt()
}
