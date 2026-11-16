package ir.aai.hushyar.domain

import ir.aai.hushyar.core.Lexicon
import ir.aai.hushyar.core.Text

/**
 * The expected answer form of a question (v0.4).
 *
 * Why this exists: the v0.3 research loop ranked candidate sentences purely by keyword overlap with
 * the question. That finds the *topic* but not the *answer* — a page about the right product that
 * never states a price was accepted, and a page about a different product that shares more words was
 * accepted too. Question classification → expected answer type is the classical fix; see
 * docs/RESEARCH-v4.md for the sources this is built on.
 */
enum class AskType { PRICE, AMOUNT, DATE, PERSON, PLACE, DEFINITION, REASON, METHOD, LIST, BOOLEAN, COMPARISON, OPINION, FACT }

/**
 * A question read as three things: the subject it is about, the property being asked for, and the
 * shape the answer must have. Everything downstream (ranking, stopping, answering, what to store)
 * keys on this instead of on raw keyword overlap.
 */
data class QuestionModel(
    val text: String,
    val langTag: String,
    val ask: AskType,
    /** Tokens naming *what* the question is about. Empty only for degenerate input. */
    val subject: List<String>,
    /** The asked property, in the user's words ("قیمت", "تاریخ", …). */
    val attribute: String?,
    /** The interrogative words themselves (never part of the subject). */
    val askWords: List<String>,
    /** Values the user pinned in the question ("زیر ۲۰ میلیون", "۲۰۲۴"). */
    val constraints: List<String>,
    /** Unit words that would make an answer well-formed for this question. */
    val preferredUnits: List<String>
) {

    val numeric: Boolean get() = ask == AskType.PRICE || ask == AskType.AMOUNT

    /**
     * Drift guard. Query expansion drifts when it moves away from the original intent, so every
     * rewrite must still carry the subject. A variant that drops it is never sent.
     */
    fun keepsAnchor(query: String): Boolean {
        if (subject.isEmpty()) return true
        val tokens = Lexicon.expand(Text.contentTokens(query))
        return subject.any { token -> tokens.any { matches(token, it) } }
    }

    /** Fraction of subject tokens present in a sentence (morphology tolerant, expansion aware). */
    fun subjectCoverage(sentence: String): Double {
        if (subject.isEmpty()) return 1.0
        val tokens = Lexicon.expand(Text.contentTokens(sentence))
        val hit = subject.count { token -> tokens.any { matches(token, it) } }
        return hit.toDouble() / subject.size
    }

    /** A sentence must at least name the subject to be about it at all. */
    fun aboutSubject(sentence: String): Boolean {
        if (subject.isEmpty()) return true
        val need = if (subject.size <= 2) 1 else kotlin.math.ceil(subject.size * 0.5).toInt()
        val hit = (subjectCoverage(sentence) * subject.size).toInt()
        // Long subjects carry filler-ish words ("گوشی سامسونگ گلکسی s24"): two solid hits are enough,
        // one hit is only enough when the subject is short enough to be unambiguous.
        return hit >= need || (subject.size >= 4 && hit >= 2)
    }

    /**
     * A deliberately tiny relation lexicon: Persian builds "ساخت/سازنده/تولید" from one root, and a
     * question worded with the verb must still match the page worded with the noun. This is a word
     * list, not a model — it is bounded, reviewable, and only widens the subject gate.
     */
    private val RELATION_GROUPS = listOf(
        setOf("ساخت", "سازنده", "ساخته", "تولید", "تولیدکننده", "manufacturer", "made", "built", "produce", "produces"),
        setOf("معرفی", "عرضه", "رونمایی", "release", "released", "launch", "launched"),
        setOf("قیمت", "قیمتی", "هزینه", "نرخ", "price", "cost", "costs"),
        setOf("دلیل", "علت", "چون", "زیرا", "reason", "cause"),
        setOf("خرید", "فروش", "فروخته", "buy", "sell", "sold")
    )

    private fun matches(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length >= 4 && b.length >= 4 && (a.startsWith(b) || b.startsWith(a))) return true
        return RELATION_GROUPS.any { group -> a in group && b in group }
    }
}

object QuestionReader {

    private val ASK_WORDS = setOf(
        "چقدر", "چند", "چه", "چی", "چیه", "کی", "کجا", "چگونه", "چطور", "چرا", "آیا", "کدام",
        "چیست", "چگونه‌است", "چطوره", "مگر", "چه‌قدر",
        "what", "which", "when", "where", "who", "why", "how", "is", "are", "does", "do", "much", "many"
    )

    /** Words that carry no subject meaning in a question. */
    private val FILLERS = setOf(
        "است", "هست", "هستند", "بود", "بودند", "شد", "شده", "می‌شود", "میشود", "میباشد", "می‌باشد",
        "دارد", "دارند", "کرد", "کرده", "کنم", "کنید", "بگو", "بگویید", "بده", "لطفا", "لطفاً",
        "برای", "از", "در", "به", "با", "و", "یا", "که", "این", "آن", "را", "رو", "هم", "تا", "بر",
        "می", "نمی", "ترین", "یک", "های", "ها", "ای", "شما", "تو", "من", "ما", "خودش", "خود",
        "کسی", "کسانی", "چیزی", "چیزهایی", "زمانی", "مکانی", "وقتی", "طور", "حال",
        "say", "says", "said", "think", "know", "get", "can", "will", "would",
        "the", "a", "an", "of", "for", "to", "in", "on", "and", "or", "please", "me", "tell", "about",
        "its", "it", "this", "that", "there", "has", "have", "with", "at", "by", "be", "do"
    )

    /** Attribute words, mapped to the property the user is asking about. */
    private val ATTRIBUTES: List<Pair<String, List<String>>> = listOf(
        "قیمت" to listOf("قیمت", "قیمتش", "هزینه", "نرخ", "ارزان", "گران", "price", "cost", "rate", "cheap", "expensive"),
        "تاریخ" to listOf("تاریخ", "زمان", "سال", "روز", "ماه", "کی", "date", "year", "when"),
        "ظرفیت" to listOf("ظرفیت", "حافظه", "رم", "باتری", "mah", "gb", "capacity", "battery", "storage", "ram"),
        "مشخصات" to listOf("مشخصات", "ویژگی", "وزن", "ابعاد", "اندازه", "صفحه", "دوربین", "پردازنده", "spec", "specs", "weight", "size", "screen", "camera", "cpu"),
        "علت" to listOf("علت", "دلیل", "چرا", "cause", "reason", "why"),
        "روش" to listOf("روش", "چطور", "چگونه", "مراحل", "راه", "method", "how", "steps"),
        "تعریف" to listOf("تعریف", "معنی", "معنا", "definition", "meaning"),
        "برند" to listOf("برند", "سازنده", "تولیدکننده", "brand", "maker", "manufacturer"),
        "بهترین" to listOf("بهترین", "بهترین‌ها", "پیشنهاد", "توصیه", "best", "recommend"),
        "مقایسه" to listOf("فرق", "تفاوت", "مقایسه", "difference", "compare", "versus", "vs")
    )

    private val PRICE_UNITS = listOf("تومان", "تومن", "ریال", "دلار", "یورو", "درهم", "toman", "rial", "usd", "dollar", "euro")

    private val MEASURE_UNITS = listOf(
        "میلی‌آمپر", "میلی امپر", "mah", "گیگابایت", "gb", "مگابایت", "mb", "ترابایت", "tb",
        "گیگاهرتز", "ghz", "مگاهرتز", "mhz", "مگاپیکسل", "mp", "اینچ", "inch", "میلی‌متر", "mm",
        "سانتی‌متر", "cm", "گرم", "کیلوگرم", "kg", "وات", "watt", "درصد", "%", "ساعت", "دقیقه", "ماه", "روز", "سال"
    )

    fun read(question: String, langTag: String = "fa"): QuestionModel {
        val norm = Text.normalize(question)
        val ask = classify(norm)
        val attribute = attributeOf(norm)
        val askWords = ASK_WORDS.filter { norm.contains(it) }
        val subject = Text.contentTokens(norm).filter { token ->
            token.length > 1 &&
                token !in ASK_WORDS &&
                token !in FILLERS &&
                // "زمانی" belongs to the attribute "زمان", "قیمتی" to "قیمت": strip by prefix, not equality.
                attributeWords(attribute).none { word -> token == word || (word.length >= 3 && token.startsWith(word)) }
        }.distinct()
        val units = when (ask) {
            AskType.PRICE -> PRICE_UNITS
            AskType.AMOUNT -> MEASURE_UNITS.filter { unit -> norm.contains(Text.normalize(unit)) }
                .ifEmpty { MEASURE_UNITS }
            else -> emptyList()
        }
        return QuestionModel(
            text = question,
            langTag = langTag,
            ask = ask,
            subject = subject,
            attribute = attribute,
            askWords = askWords,
            constraints = constraintsOf(norm),
            preferredUnits = units
        )
    }

    /** The asked property in the user's words, or null when the question names no property. */
    fun attributeWords(attribute: String?): List<String> =
        ATTRIBUTES.firstOrNull { it.first == attribute }?.second?.map { Text.normalize(it) } ?: emptyList()

    fun attributeOf(norm: String): String? = ATTRIBUTES.firstOrNull { (_, words) ->
        words.any { word -> norm.contains(Text.normalize(word)) }
    }?.first

    /** Numbers the user pinned in the question itself; an answer must respect them. */
    private fun constraintsOf(norm: String): List<String> =
        Regex("(زیر|بالای|حداکثر|حداقل|کمتر از|بیشتر از|around|under|over)\\s+[0-9۰-۹.,٬]+\\s*(هزار|میلیون|میلیارد|تومان|ریال)?").findAll(norm)
            .map { it.value.trim() }.toList()

    /**
     * Order matters. Price before quantity ("چند تومان" is a price), definition before list
     * ("چیست" asks for a meaning, not a set of items), comparison/opinion before the generic
     * "کدام" list reading.
     */
    fun classify(norm: String): AskType {
        val has = { word: String -> norm.contains(Text.normalize(word)) }
        return when {
            // Comparison and recommendation override the interrogative word: "کدام گوشی بهتر است؟"
            // is not a list question and "بهترین قیمت" is not a price question.
            listOf("فرق", "تفاوت", "مقایسه", "difference", "versus", "vs ").any(has) -> AskType.COMPARISON
            listOf("بهترین", "پیشنهاد", "توصیه", "best ", "recommend").any(has) -> AskType.OPINION
            // Explicit temporal cues. Kept before PRICE so "قیمت … چه زمانی ارزان می‌شود" asks a date.
            listOf("چه زمانی", "چه موقع", "چه سالی", "چه تاریخی", "چندم", "کی معرفی", "کی عرضه", "when was", "when did", "release date").any(has) -> AskType.DATE
            // Naming the property at all means asking for it, with or without "چقدر".
            listOf("قیمت", "قیمتش", "هزینه", "نرخ", "price", "cost", "how much").any(has) -> AskType.PRICE
            listOf("چیست", "چیه", "یعنی چه", "به چه معنا", "تعریف", "چه هست", "what is a", "what is the", "meaning of").any(has) -> AskType.DEFINITION
            listOf("چرا", "علت", "دلیل", "why ").any(has) -> AskType.REASON
            listOf("چگونه", "چطور", "به چه روش", "how to", "how do").any(has) -> AskType.METHOD
            listOf("چه کسی", "کی بود", "چه کسانی", "سازندهٔ", "مخترع", "who ").any(has) -> AskType.PERSON
            listOf("کجا", "کدام شهر", "کدام کشور", "where ").any(has) -> AskType.PLACE
            // "چند" as a whole token: "چندین" (several) is not a quantity question.
            Text.tokens(norm).contains("چند") -> AskType.AMOUNT
            listOf("آیا", "مگر", "is there", "does it").any(has) -> AskType.BOOLEAN
            listOf("کدام", "چه چیزهایی", "چه مواردی", "لیست", "فهرست", "which ").any(has) -> AskType.LIST
            else -> AskType.FACT
        }
    }

    /** Persian wording for the expected answer form, used in gaps and in refusals. */
    fun expectedAnswerLabel(ask: AskType): String = when (ask) {
        AskType.PRICE -> "قیمت با واحد روشن (تومان/ریال)"
        AskType.AMOUNT -> "عدد همراه با واحد"
        AskType.DATE -> "تاریخ (سال/ماه/روز)"
        AskType.PERSON -> "نام شخص"
        AskType.PLACE -> "نام مکان"
        AskType.DEFINITION -> "جملهٔ تعریف"
        AskType.REASON -> "دلیل (چون/به دلیل)"
        AskType.METHOD -> "روش/مراحل"
        AskType.LIST -> "فهرست موارد"
        AskType.BOOLEAN -> "تأیید یا رد صریح"
        AskType.COMPARISON -> "مقایسهٔ دو طرف"
        AskType.OPINION -> "گزینهٔ پیشنهادی"
        AskType.FACT -> "جملهٔ حاوی پاسخ"
    }

    /** Query term that specifically chases the missing answer shape (used for the next round). */
    fun gapTerm(ask: AskType, langTag: String): String {
        val fa = langTag != "en"
        return when (ask) {
            AskType.PRICE -> if (fa) "قیمت تومان" else "price toman"
            AskType.AMOUNT -> if (fa) "مشخصات عددی" else "specs number"
            AskType.DATE -> if (fa) "تاریخ معرفی" else "release date"
            AskType.PERSON -> if (fa) "سازنده نام" else "who invented"
            AskType.PLACE -> if (fa) "محل کشور" else "location country"
            AskType.DEFINITION -> if (fa) "تعریف یعنی" else "definition meaning"
            AskType.REASON -> if (fa) "دلیل علت" else "reason cause"
            AskType.METHOD -> if (fa) "روش مراحل" else "how steps"
            AskType.LIST -> if (fa) "فهرست موارد" else "list of"
            AskType.BOOLEAN -> if (fa) "آیا هست" else "is it true"
            AskType.COMPARISON -> if (fa) "مقایسه تفاوت" else "comparison difference"
            AskType.OPINION -> if (fa) "بهترین پیشنهاد" else "best recommendation"
            AskType.FACT -> if (fa) "جزئیات" else "details"
        }
    }
}
