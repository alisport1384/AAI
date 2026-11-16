package ir.aai.hushyar.domain

import ir.aai.hushyar.core.Similarity
import ir.aai.hushyar.core.Text

/** The answer span found inside one sentence: exactly the text a user would copy as "the answer". */
data class Slot(
    val text: String,
    val kind: String,
    val value: Double? = null,
    val unit: String? = null
)

/**
 * Answer-shape detection. A question of type PRICE is only answered by a sentence that states a
 * price with a unit; DATE only by a sentence with a date; and so on. This is the difference between
 * "found a page about the topic" and "found the answer".
 */
object AnswerShapes {

    private val PRICE = Regex(
        "([۰-۹0-9][۰-۹0-9.,٬،]{0,18})\\s*(هزار|میلیون|میلیارد|thousand|million|billion)?\\s*" +
            "(تومان|تومن|ریال|دلار|یورو|درهم|toman|rial|usd|dollar|euro)"
    )

    private val MEASURE = Regex(
        "([۰-۹0-9][۰-۹0-9.,٬،]{0,10})\\s*" +
            "(میلی‌آمپر|میلی امپر|mah|گیگابایت|gb|مگابایت|mb|ترابایت|tb|گیگاهرتز|ghz|مگاهرتز|mhz|" +
            "مگاپیکسل|mp|اینچ|inch|میلی‌متر|mm|سانتی‌متر|cm|کیلوگرم|kg|گرم|وات|watt|درصد|%|" +
            "ساعت|دقیقه|ماه|روز|سال)"
    )

    private val MONTHS = listOf(
        "ژانویه", "فوریه", "مارس", "آوریل", "مه", "ژوئن", "ژوئیه", "اوت", "سپتامبر", "اکتبر", "نوامبر", "دسامبر",
        "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور", "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند",
        "january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december"
    )

    private val YEAR = Regex("([۰-۹]{4})|(?:19|20)[0-9]{2}")

    private val HONORIFIC = Regex("(آقای|خانم|دکتر|مهندس|استاد|جناب|پروفسور)\\s+[\\p{L}]+(?:\\s+[\\p{L}]+)?")
    private val LATIN_NAME = Regex("\\b[A-Z][a-z]{2,}\\s+[A-Z][a-z]{2,}\\b")
    private val NAME_CUE = Regex("(نام|سازنده|مخترع|بنیان‌گذار|بنیانگذار|مؤسس|موسس)[^.؛]{2,40}(است|بود|می‌باشد)")

    private val PLACES = listOf(
        "ایران", "تهران", "اصفهان", "مشهد", "شیراز", "تبریز", "کرج", "اهواز", "قم", "کرمانشاه",
        "چین", "آمریکا", "امریکا", "ژاپن", "کره", "کره جنوبی", "آلمان", "فرانسه", "انگلستان", "ترکیه",
        "امارات", "دبی", "هند", "ویتنام", "تایوان", "روسیه", "کانادا", "ایتالیا", "اسپانیا",
        "china", "usa", "japan", "korea", "germany", "france", "turkey", "india", "vietnam", "taiwan"
    )

    private val DEFINITION_CUES = listOf(
        "عبارت است از", "به معنای", "یعنی", "گونه‌ای", "گونهای", "نوعی از", "چیزی است که",
        "refers to", "is a ", "is an ", "means ", "is defined as"
    )
    private val REASON_CUES = listOf("چون", "زیرا", "به دلیل", "به خاطر", "دلیل آن", "باعث", "because", "due to", "since ")
    private val METHOD_CUES = listOf("از طریق", "به وسیله", "با استفاده", "مراحل", "ابتدا", "سپس", "by using", "steps")
    private val COMPARISON_CUES = listOf("بیشتر", "کمتر", "بهتر", "ارزان‌تر", "ارزانتر", "گران‌تر", "گرانتر", "more", "less", "better", "cheaper")
    private val OPINION_CUES = listOf("بهترین", "پیشنهاد", "توصیه", "best", "recommend")

    private fun around(sentence: String, start: Int, end: Int, radius: Int = 60): String {
        val from = (start - radius).coerceAtLeast(0)
        val to = (end + radius).coerceAtMost(sentence.length)
        return sentence.substring(from, to).trim().trim(',', '،', '؛', '.')
    }

    private fun clauseFrom(sentence: String, cue: String): String {
        val index = Text.normalize(sentence).indexOf(Text.normalize(cue))
        if (index < 0) return sentence.take(220)
        return sentence.substring(index).take(220).trim()
    }

    fun priceOf(sentence: String): Slot? {
        val match = PRICE.find(sentence) ?: return null
        val numeric = KnowledgeBank.numeric(match.value) ?: return null
        return Slot(match.value.trim(), "price", numeric.first, numeric.second)
    }

    fun amountOf(sentence: String): Slot? {
        val match = MEASURE.find(sentence) ?: return null
        val value = Text.asciiDigits(match.groupValues[1]).replace(",", "").replace("٬", "").toDoubleOrNull() ?: return null
        if (value <= 0) return null
        return Slot(match.value.trim(), "amount", value, match.groupValues[2])
    }

    fun dateOf(sentence: String): Slot? {
        // A four-digit run is only a year inside a plausible calendar range; otherwise "۴۰۰۰ میلی‌آمپر"
        // would be read as a date.
        for (match in YEAR.findAll(sentence)) {
            val year = Text.asciiDigits(match.value).toIntOrNull() ?: continue
            val plausible = year in 1300..1500 || year in 1900..2100
            if (!plausible) continue
            return Slot(around(sentence, match.range.first, match.range.last + 1, 40), "date")
        }
        // Month names must match a whole token: "مه" lives inside "مهم", "دی" inside "دیگر".
        val tokens = Text.tokens(sentence)
        val month = MONTHS.firstOrNull { name -> tokens.any { it == Text.normalize(name) } } ?: return null
        val index = Text.normalize(sentence).indexOf(Text.normalize(month))
        return Slot(around(sentence, index, index + month.length, 40), "date", unit = month)
    }

    fun personOf(sentence: String): Slot? {
        HONORIFIC.find(sentence)?.let { return Slot(it.value.trim(), "person") }
        NAME_CUE.find(sentence)?.let { return Slot(it.value.trim(), "person") }
        LATIN_NAME.find(sentence)?.let { return Slot(it.value.trim(), "person") }
        return null
    }

    fun placeOf(sentence: String): Slot? {
        val norm = Text.normalize(sentence)
        PLACES.firstOrNull { norm.contains(Text.normalize(it)) }?.let { return Slot(it, "place") }
        return null
    }

    fun definitionOf(sentence: String): Slot? {
        DEFINITION_CUES.firstOrNull { Text.normalize(sentence).contains(Text.normalize(it)) }?.let {
            return Slot(clauseFrom(sentence, it), "definition")
        }
        return null
    }

    fun reasonOf(sentence: String): Slot? {
        REASON_CUES.firstOrNull { Text.normalize(sentence).contains(Text.normalize(it)) }?.let {
            return Slot(clauseFrom(sentence, it), "reason")
        }
        return null
    }

    fun methodOf(sentence: String): Slot? {
        METHOD_CUES.firstOrNull { Text.normalize(sentence).contains(Text.normalize(it)) }?.let {
            return Slot(clauseFrom(sentence, it), "method")
        }
        return null
    }

    fun comparisonOf(sentence: String): Slot? {
        // A comparison is the whole sentence ("X از Y بیشتر است"), not a clause cut at the cue.
        return if (COMPARISON_CUES.any { Text.normalize(sentence).contains(Text.normalize(it)) }) {
            Slot(sentence.take(240), "comparison")
        } else {
            null
        }
    }

    fun opinionOf(sentence: String): Slot? {
        OPINION_CUES.firstOrNull { Text.normalize(sentence).contains(Text.normalize(it)) }?.let {
            return Slot(clauseFrom(sentence, it), "opinion")
        }
        return null
    }

    /** Yes/no reading: the sentence itself is the proof, the polarity is the answer. */
    fun booleanOf(sentence: String): Slot? {
        val negative = KnowledgeBank.isNegative(sentence)
        return Slot(if (negative) "نه" else "بله", "boolean")
    }

    fun listOfItems(sentence: String): Slot? {
        val commas = sentence.count { it == '،' || it == ',' }
        return if (commas >= 2) Slot(sentence.take(240), "list") else null
    }

    /**
     * Extracts the answer span a sentence offers for this kind of question. Null means
     * "this sentence is not an answer", not "I am not sure".
     */
    fun slot(ask: AskType, sentence: String, pack: MarketPack? = null): Slot? = when (ask) {
        AskType.PRICE -> priceOf(sentence)
        AskType.AMOUNT -> amountOf(sentence) ?: priceOf(sentence)
        AskType.DATE -> dateOf(sentence)
        AskType.PERSON -> personOf(sentence)
        AskType.PLACE -> placeOf(sentence)
        // A definition question is answered by the sentence that predicates something of the subject
        // ("هوشیار یک دستیار محلی است") even when it carries no explicit cue word.
        AskType.DEFINITION -> definitionOf(sentence) ?: sentence.take(240).let { text ->
            if (Text.normalize(text).contains(" است") || Text.normalize(text).contains("هست")) Slot(text, "definition") else null
        }
        AskType.REASON -> reasonOf(sentence)
        AskType.METHOD -> methodOf(sentence)
        AskType.COMPARISON -> comparisonOf(sentence)
        // An opinion question ("بهترین … چیست؟") is answered by text about the subject, not by a
        // dedicated answer shape: the recommendation sentence itself is the answer.
        AskType.OPINION -> opinionOf(sentence) ?: sentence.take(240).let { Slot(it, "opinion") }
        AskType.LIST -> listOfItems(sentence)
        AskType.BOOLEAN -> booleanOf(sentence)
        AskType.FACT -> sentence.take(240).let { Slot(it, "fact") }
    }

    /** True when a line carries any fact shape at all (price, number+unit, date). */
    fun hasFact(sentence: String): Boolean =
        priceOf(sentence) != null || amountOf(sentence) != null || dateOf(sentence) != null

    /** Does this stored claim answer a question of this type at all? Used to gate knowledge answers. */
    fun answers(ask: AskType, claim: String, pack: MarketPack? = null): Boolean =
        slot(ask, claim, pack) != null
}

data class AnswerCandidate(
    val evidence: Evidence,
    val slot: Slot?,
    val subjectCoverage: Double,
    val attributeHit: Boolean,
    val score: Double
) {
    val typeMatch: Boolean get() = slot != null
}

/**
 * The composed answer, before it becomes language. Either there is a direct answer with its source,
 * or there is an explicit statement of *which* part is missing — never a pile of topical sentences
 * presented as an answer.
 */
data class AnswerPlan(
    val ask: AskType,
    val direct: String?,
    val source: Evidence?,
    val agreement: Int,
    val disagreement: List<Pair<String, String>>,
    val support: List<AnswerCandidate>,
    val context: List<AnswerCandidate>,
    val missing: List<String>
) {
    val answered: Boolean get() = direct != null
}

/**
 * Answer-focused ranking. Replaces "which sentence shares the most words with the question" with
 * "which sentence answers it": the subject gate removes sentences about something else, the answer
 * shape decides whether the question is actually answered, and proximity keeps the answer next to
 * the thing it is about.
 */
object AnswerFocus {

    fun rank(
        model: QuestionModel,
        pages: List<ExtractedPage>,
        pack: MarketPack? = null,
        limit: Int = 8
    ): List<AnswerCandidate> {
        if (pages.isEmpty()) return emptyList()
        val out = mutableListOf<AnswerCandidate>()
        val seenDomains = mutableSetOf<String>()
        var position = 0
        for (page in pages) {
            val domain = KnowledgeBank.draftHost(page.url)
            for (sentence in Extractive.sentences(page.text)) {
                position++
                // Hard gate: a sentence that does not name the subject is about something else.
                if (!model.aboutSubject(sentence)) continue
                val coverage = model.subjectCoverage(sentence)
                val slot = AnswerShapes.slot(model.ask, sentence, pack)
                val attributeHit = model.attribute?.let { attribute ->
                    val words = listOf(attribute) + (pack?.attributes?.firstOrNull { it.label == attribute }?.synonyms ?: emptyList())
                    words.any { Text.normalize(sentence).contains(Text.normalize(it)) }
                } ?: false
                val novelty = if (seenDomains.add(domain)) 0.2 else 0.0
                val positionBonus = if (position <= 30) 0.1 else 0.0
                val lengthPenalty = if (sentence.length > 300) 0.15 else 0.0
                val score = 3.2 * (if (slot != null) 1.0 else 0.0) +
                    1.8 * coverage +
                    0.4 * (if (attributeHit) 1.0 else 0.0) +
                    novelty + positionBonus - lengthPenalty
                out.add(AnswerCandidate(Evidence(sentence, page, score / 5.5), slot, coverage, attributeHit, score))
            }
        }
        val sorted = out.sortedByDescending { it.score }
        // One sentence per idea: near-duplicates from mirror sites must not count as agreement.
        val picked = mutableListOf<AnswerCandidate>()
        for (candidate in sorted) {
            if (picked.size >= limit) break
            val duplicate = picked.any { other ->
                val sameValue = other.slot?.value != null && candidate.slot?.value != null &&
                    kotlin.math.abs(other.slot!!.value!! - candidate.slot!!.value!!) <=
                    0.10 * kotlin.math.max(kotlin.math.abs(other.slot!!.value!!), kotlin.math.abs(candidate.slot!!.value!!))
                val conflicting = other.slot?.value != null && candidate.slot?.value != null && !sameValue
                !conflicting && Similarity.hamming(
                    Similarity.simhash(Text.contentTokens(other.evidence.sentence)),
                    Similarity.simhash(Text.contentTokens(candidate.evidence.sentence))
                ) < 12
            }
            if (!duplicate) picked.add(candidate)
        }
        return picked
    }

    /** Same numeric value within the tolerance used by the knowledge merge (10 %). */
    private fun sameAnswer(a: Slot, b: Slot): Boolean {
        if (a.value != null && b.value != null) {
            val x = a.value
            val y = b.value
            return kotlin.math.abs(x - y) <= 0.10 * kotlin.math.max(kotlin.math.abs(x), kotlin.math.abs(y))
        }
        return Text.normalize(a.text) == Text.normalize(b.text)
    }

    fun plan(model: QuestionModel, ranked: List<AnswerCandidate>, pages: List<ExtractedPage> = emptyList()): AnswerPlan {
        val support = ranked.filter { it.slot != null }
        val context = ranked.filter { it.slot == null }.take(3)
        if (support.isEmpty()) {
            val missing = buildList {
                add("شاهد حاوی ${QuestionReader.expectedAnswerLabel(model.ask)}")
                if (context.isNotEmpty()) {
                    add("موضوع در ${Text.num(context.size.toLong())} جمله آمد اما پاسخِ «${model.attribute ?: model.ask.name}» در آن‌ها نبود")
                } else if (pages.isNotEmpty()) {
                    add("جمله‌ای که دربارهٔ «${model.subject.joinToString(" ").ifBlank { model.text }}» باشد")
                } else {
                    add("صفحهٔ مرتبط با موضوع")
                }
            }
            return AnswerPlan(model.ask, null, null, 0, emptyList(), support, context, missing)
        }
        val best = support.first()
        val bestSlot = best.slot!!
        val agreeing = support.filter { sameAnswer(bestSlot, it.slot!!) }
        val agreement = agreeing.map { KnowledgeBank.draftHost(it.evidence.page.url) }.distinct().size
        val disagreement = support
            .filter { !sameAnswer(bestSlot, it.slot!!) }
            .map { it.slot!!.text.trim() to KnowledgeBank.draftHost(it.evidence.page.url) }
            .distinctBy { Text.normalize(it.first) }
        return AnswerPlan(
            ask = model.ask,
            direct = bestSlot.text.trim(),
            source = best.evidence,
            agreement = agreement.coerceAtLeast(1),
            disagreement = disagreement,
            support = support,
            context = context,
            missing = emptyList()
        )
    }

    /** Rank + plan in one step, for the agent and for tests measuring answer accuracy. */
    fun answer(model: QuestionModel, pages: List<ExtractedPage>, pack: MarketPack? = null): AnswerPlan =
        plan(model, rank(model, pages, pack), pages)
}
