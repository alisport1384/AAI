package ir.aai.hushyar.core

/**
 * Bilingual competence layer (v0.2): language detection, Jalali dates, number words,
 * transliteration and a fa<->en expansion lexicon used for query expansion and matching.
 * Persian display text is never mutated; this is the analysis form.
 */
enum class Lang { FA, EN, MIX, OTHER }

object LangDetect {

    private val FA_RANGE = '\u0600'..'\u06FF'

    fun detect(text: String): Lang {
        var fa = 0
        var en = 0
        for (c in text) {
            if (c in FA_RANGE) fa++ else if (c in 'a'..'z' || c in 'A'..'Z') en++
        }
        return when {
            fa == 0 && en == 0 -> Lang.OTHER
            fa > 0 && en > 0 && kotlin.math.min(fa, en) * 4 >= kotlin.math.max(fa, en) -> Lang.MIX
            fa >= en -> Lang.FA
            else -> Lang.EN
        }
    }

    fun tag(lang: Lang): String = when (lang) {
        Lang.FA -> "fa"
        Lang.EN -> "en"
        Lang.MIX -> "fa-en"
        Lang.OTHER -> "other"
    }
}

/** Gregorian -> Jalali (standard algorithm, no dependency). */
object Jalali {

    fun fromGregorian(gy: Int, gm: Int, gd: Int): Triple<Int, Int, Int> {
        val gDaysInMonth = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        val gy2 = if (gm > 2) gy + 1 else gy
        var days = 355666 + (365 * gy) + ((gy2 + 3) / 4) - ((gy2 + 99) / 100) + ((gy2 + 399) / 400) + gd + gDaysInMonth[gm - 1]
        var jy = -1595 + (33 * (days / 12053))
        days %= 12053
        jy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) {
            jy += (days - 1) / 365
            days = (days - 1) % 365
        }
        return if (days < 186) {
            Triple(jy, 1 + days / 31, 1 + days % 31)
        } else {
            Triple(jy, 7 + (days - 186) / 30, 1 + (days - 186) % 30)
        }
    }

    /** "1403/08/26" for a millisecond epoch, local time zone. */
    fun format(epochMillis: Long): String {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = epochMillis
        val (y, m, d) = fromGregorian(
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        )
        return "%04d/%02d/%02d".format(y, m, d)
    }
}

/** Persian/English number words -> value. Handles compound tens and scale words. */
object NumWords {

    private val unitsFa = mapOf(
        "صفر" to 0L, "یک" to 1L, "دو" to 2L, "سه" to 3L, "چهار" to 4L, "پنج" to 5L,
        "شش" to 6L, "هفت" to 7L, "هشت" to 8L, "نه" to 9L
    )
    private val teensFa = mapOf(
        "ده" to 10L, "یازده" to 11L, "دوازده" to 12L, "سیزده" to 13L, "چهارده" to 14L,
        "پانزده" to 15L, "شانزده" to 16L, "هفده" to 17L, "هجده" to 18L, "نوزده" to 19L
    )
    private val tensFa = mapOf(
        "بیست" to 20L, "سی" to 30L, "چهل" to 40L, "پنجاه" to 50L,
        "شصت" to 60L, "هفتاد" to 70L, "هشتاد" to 80L, "نود" to 90L
    )
    private val hundredsFa = mapOf(
        "صد" to 100L, "یکصد" to 100L, "دویست" to 200L, "سیصد" to 300L, "چهارصد" to 400L,
        "پانصد" to 500L, "ششصد" to 600L, "هفتصد" to 700L, "هشتصد" to 800L, "نهصد" to 900L
    )
    private val unitsEn = mapOf(
        "zero" to 0L, "one" to 1L, "two" to 2L, "three" to 3L, "four" to 4L, "five" to 5L,
        "six" to 6L, "seven" to 7L, "eight" to 8L, "nine" to 9L, "ten" to 10L,
        "eleven" to 11L, "twelve" to 12L, "thirteen" to 13L, "fourteen" to 14L, "fifteen" to 15L,
        "sixteen" to 16L, "seventeen" to 17L, "eighteen" to 18L, "nineteen" to 19L,
        "twenty" to 20L, "thirty" to 30L, "forty" to 40L, "fifty" to 50L,
        "sixty" to 60L, "seventy" to 70L, "eighty" to 80L, "ninety" to 90L,
        "hundred" to 100L, "thousand" to 1_000L, "million" to 1_000_000L, "billion" to 1_000_000_000L
    )

    private val scaleWords = mapOf(
        "هزار" to 1_000L, "میلیون" to 1_000_000L, "میلیارد" to 1_000_000_000L,
        "thousand" to 1_000L, "million" to 1_000_000L, "billion" to 1_000_000_000L
    )

    /** Digits (+ optional scale word) first: "۱٫۲۵ میلیون", "1.5 million", "۲٫۵۰۰٫۰۰۰". */
    private fun parseDigits(text: String): Long? {
        var work = Text.asciiDigits(Text.normalize(text)).replace('٫', '.')
        // Strip grouping separators (1.250.000 / 1,250,000) before reading the number.
        Regex("\\d{1,3}(?:[.,]\\d{3})+").findAll(work).toList().reversed().forEach { match ->
            work = work.replaceRange(match.range, match.value.replace(".", "").replace(",", ""))
        }
        val match = Regex("(\\d+(?:\\.\\d+)?)\\s*(هزار|میلیون|میلیارد|thousand|million|billion)?").find(work) ?: return null
        val base = match.groupValues[1].toDoubleOrNull() ?: return null
        val scale = scaleWords[match.groupValues[2]] ?: 1L
        return (base * scale).toLong()
    }

    fun parse(text: String): Long? {
        parseDigits(text)?.let { return it }
        val tokens = Text.tokens(text)
        if (tokens.isEmpty()) return null
        var total = 0L
        var current = 0L
        var matched = false
        for (token in tokens) {
            val value = unitsFa[token] ?: teensFa[token] ?: tensFa[token] ?: hundredsFa[token]
                ?: unitsEn[token] ?: scaleWords[token]
            if (value == null) {
                if (token == "و") continue
                if (matched) break else continue
            }
            matched = true
            when {
                value >= 1_000L -> { current = if (current == 0L) value else current * value; total += current; current = 0L }
                value == 100L -> current = if (current == 0L) 100L else current * 100L
                else -> current += value
            }
        }
        if (!matched) return null
        return total + current
    }
}

object Translit {

    private val map = mapOf(
        'ا' to "a", 'آ' to "a", 'ب' to "b", 'پ' to "p", 'ت' to "t", 'ث' to "s", 'ج' to "j",
        'چ' to "ch", 'ح' to "h", 'خ' to "kh", 'د' to "d", 'ذ' to "z", 'ر' to "r", 'ز' to "z",
        'ژ' to "zh", 'س' to "s", 'ش' to "sh", 'ص' to "s", 'ض' to "z", 'ط' to "t", 'ظ' to "z",
        'ع' to "a", 'غ' to "gh", 'ف' to "f", 'ق' to "gh", 'ک' to "k", 'ك' to "k", 'گ' to "g",
        'ل' to "l", 'م' to "m", 'ن' to "n", 'و' to "v", 'ه' to "h", 'ی' to "y", 'ي' to "y",
        'ء' to "", 'ئ' to "y", 'ؤ' to "o", 'أ' to "a", 'إ' to "e", 'ة' to "h", ' ' to "-"
    )

    fun faToLatin(input: String): String {
        val sb = StringBuilder()
        for (c in Text.normalize(input)) {
            if (c in 'a'..'z' || c in '0'..'9' || c == '-') sb.append(c)
            else sb.append(map[c] ?: "")
        }
        return sb.toString().replace(Regex("-+"), "-").trim('-')
    }
}

/** fa<->en expansion used for cross-language query expansion and matching. */
object Lexicon {

    private val pairs: List<Pair<List<String>, List<String>>> = listOf(
        listOf("قیمت", "هزینه", "نرخ") to listOf("price", "cost", "rate"),
        listOf("ارزان", "ارزان‌تر") to listOf("cheap", "cheaper"),
        listOf("گران") to listOf("expensive", "pricey"),
        listOf("مقایسه", "تفاوت", "فرق") to listOf("compare", "difference"),
        listOf("مشخصات", "ویژگی") to listOf("spec", "specs", "specification", "feature"),
        listOf("گارانتی", "ضمانت") to listOf("warranty", "guarantee"),
        listOf("اصالت", "اورجینال", "اصل") to listOf("authentic", "original", "genuine"),
        listOf("فروشنده") to listOf("seller", "vendor"),
        listOf("موجودی", "انبار") to listOf("stock", "availability"),
        listOf("تاریخچه", "روند") to listOf("history", "trend"),
        listOf("خرید") to listOf("buy", "purchase"),
        listOf("گوشی", "موبایل") to listOf("phone", "mobile", "smartphone"),
        listOf("لپ‌تاپ", "لپ تاپ") to listOf("laptop", "notebook"),
        listOf("هدفون") to listOf("headphone", "headset"),
        listOf("کارت گرافیک") to listOf("gpu", "graphics", "vga"),
        listOf("پردازنده") to listOf("cpu", "processor"),
        listOf("حافظه", "هارد") to listOf("storage", "ssd", "hdd"),
        listOf("رم") to listOf("ram", "memory"),
        listOf("باتری") to listOf("battery"),
        listOf("دوربین") to listOf("camera"),
        listOf("صفحه نمایش") to listOf("display", "screen"),
        listOf("جدید", "نو") to listOf("new"),
        listOf("دست دوم", "کارکرده") to listOf("used", "second-hand"),
        listOf("ارسال") to listOf("shipping", "delivery"),
        listOf("تخفیف") to listOf("discount", "sale")
    )

    private val faToEn: Map<String, Set<String>> = buildMap {
        for ((fa, en) in pairs) for (word in fa) {
            val key = Text.normalize(word)
            put(key, (get(key) ?: emptySet()) + en.map { it.lowercase() }.toSet())
        }
    }

    private val enToFa: Map<String, Set<String>> = buildMap {
        for ((fa, en) in pairs) for (word in en) {
            val key = word.lowercase()
            put(key, (get(key) ?: emptySet()) + fa.map { Text.normalize(it) }.toSet())
        }
    }

    /** Expands a token set with bilingual equivalents (used for search & matching). */
    fun expand(tokens: Collection<String>): Set<String> {
        val out = linkedSetOf<String>()
        for (token in tokens) {
            val key = Text.normalize(token)
            if (key.isBlank()) continue
            out.add(key)
            faToEn[key]?.let { out.addAll(it) }
            enToFa[key]?.let { out.addAll(it) }
        }
        return out
    }

    /** Extra query terms for a web search, based on the market pack's attributes. */
    fun packTerms(labels: List<String>): List<String> {
        val out = mutableListOf<String>()
        for (label in labels) {
            val key = Text.normalize(label)
            out.add(key)
            faToEn[key]?.take(2)?.let { out.addAll(it) }
        }
        return out.distinct()
    }
}
