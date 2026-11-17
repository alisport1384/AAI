package ir.aai.hushyar.core

import java.text.Normalizer

/**
 * Persian/English text handling. The **display** text is never mutated;
 * this object only produces the index/matching form (see DESIGN §8.1).
 */
object Text {

    private val DIACRITICS = Regex("[\\u064B-\\u0652\\u0670\\u0640\\u06D6-\\u06ED]")
    private val INVISIBLES = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\uFEFF]")

    private val CHAR_MAP = mapOf(
        'ك' to 'ک', 'ي' to 'ی', 'ى' to 'ی', 'ﻯ' to 'ی', 'ﻰ' to 'ی',
        'ة' to 'ه', 'ۀ' to 'ه', 'أ' to 'ا', 'إ' to 'ا', 'آ' to 'ا', 'ٱ' to 'ا',
        'ؤ' to 'و', 'ئ' to 'ی', 'ء' to '\u0000'
    )

    private const val FA_DIGIT_START = '\u06F0' // ۰
    private const val AR_DIGIT_START = '\u0660' // ٠
    private const val DIGIT_ZERO = '0'

    fun asciiDigits(input: String): String {
        val sb = StringBuilder(input.length)
        for (c in input) {
            sb.append(
                when (c) {
                    in '\u06F0'..'\u06F9' -> DIGIT_ZERO + (c - FA_DIGIT_START)
                    in '\u0660'..'\u0669' -> DIGIT_ZERO + (c - AR_DIGIT_START)
                    else -> c
                }
            )
        }
        return sb.toString()
    }

    /** Matching form: NFC, digit folding, letter unification, no ZWNJ/diacritics, lowercased. */
    fun normalize(input: String): String {
        var s = Normalizer.normalize(input, Normalizer.Form.NFC)
        s = DIACRITICS.replace(s, "")
        s = INVISIBLES.replace(s, "")
        val sb = StringBuilder(s.length)
        for (c in s) {
            val mapped = CHAR_MAP[c]
            if (mapped == null) sb.append(c) else if (mapped != '\u0000') sb.append(mapped)
        }
        s = asciiDigits(sb.toString())
        s = s.lowercase()
        s = s.replace('\u0653', ' ').replace('\u0654', ' ')
        // پیش از یکسان‌سازی فاصله‌ها، چون فاصلهٔ دوگانه مرز کلمه در نوشتارِ کلمه‌به‌کلمه است.
        return repair(s).replace(Regex("\\s+"), " ").trim()
    }

    /**
     * v0.11.1 — «ق ی م ت  ت ی ش ر ت» هم یک پرسش است.
     *
     * کاربر گزارش داد وقتی جمله کلمه‌به‌کلمه با فاصله نوشته می‌شود، هوش آن را نمی‌فهمد. سه شکل رایج
     * اینجا درست می‌شوند:
     *
     *  * **حرف‌به‌حرف**: «ق ی م ت» → «قیمت» (سه حرف یا بیشترِ تک‌حرفی پشت سر هم)
     *  * **با جداکننده**: «ق.ی.م.ت» / «ق-ی-م-ت» / «ق_ی_م_ت» → «قیمت»
     *  * **کلمه‌به‌کلمه**: «قیمت  ت ی ش ر ت  ن خ ی» → «قیمت تیشرت نخی» (فاصلهٔ دو یا بیشتر،
     *    تب یا خط جدید = مرز دو کلمه)
     *
     * قاعده‌ها محافظه‌کارانه‌اند: حرف «و» عطف هیچ‌وقت جزو دستهٔ تک‌حرفی شمرده نمی‌شود، و دسته‌ای
     * که بیشتر از ۱۲ حرف شود (یعنی تقریباً مطمئناً چند کلمه بوده) به حال خودش می‌ماند تا کلمه‌ها
     * به هم نچسبند.
     */
    fun repair(input: String): String {
        if (input.isEmpty()) return input
        // ۱) جداکننده‌های نقطه‌ای و خط‌تیره: ق.ی.م.ت → قیمت
        var s = Regex("(?:\\p{L}\\s*[._\\-]\\s*){2,}\\p{L}").replace(input) { m ->
            val letters = m.value.filter { it.isLetter() }
            if (letters.length in 3..12) letters else m.value
        }
        // ۲) مرزهای سخت کلمه: تب/خط جدید یا دو فاصلهٔ پشت سر هم
        val segments = s.split(Regex("[\\t\\n\\r]+| {2,}"))
        val fixed = segments.map { segment ->
            val words = segment.trim().split(' ').filter { it.isNotEmpty() }
            // همهٔ واژه‌های بخش باید تک‌حرفی باشند. «و» هم داخل این دسته یک حرف از خودِ کلمه است
            // («ش ل و ا ر» → «شلوار»)؛ فقط بخشِ تک‌واژه‌ایِ «و» جدا می‌ماند، چون به سه تک‌حرفی نمی‌رسد.
            val allSingle = words.isNotEmpty() && words.all { it.length == 1 && it[0].isLetter() }
            when {
                words.size >= 3 && allSingle &&
                    words.joinToString("").length <= 12 -> words.joinToString("")
                else -> segment.trim()
            }
        }
        s = fixed.filter { it.isNotEmpty() }.joinToString(" ")
        return s
    }

    private val TOKEN_SEP = Regex("[^\\p{L}\\p{N}]+")

    fun tokens(input: String): List<String> =
        normalize(input).split(TOKEN_SEP).filter { it.isNotEmpty() }

    fun contentTokens(input: String): List<String> =
        tokens(input).filter { it.length > 1 && it !in FA_STOPWORDS && it !in EN_STOPWORDS }

    val FA_STOPWORDS: Set<String> = setOf(
        "و", "در", "به", "از", "که", "این", "آن", "را", "با", "برای", "است", "هست",
        "می", "نمی", "تا", "بر", "هم", "یا", "اگر", "چه", "چی", "چند", "کدام", "کجا",
        "من", "تو", "او", "ما", "شما", "آنها", "خود", "یک", "دو", "شود", "شد", "کرد",
        "های", "ها", "ای", "اما", "ولی", "پس", "چون", "بین", "روی", "زیر", "کن", "بده"
    )

    val EN_STOPWORDS: Set<String> = setOf(
        "the", "a", "an", "of", "in", "on", "for", "to", "is", "are", "was", "were",
        "and", "or", "with", "what", "which", "how", "much", "many", "do", "does",
        "i", "you", "he", "she", "it", "we", "they", "this", "that", "at", "by",
        "be", "been", "can", "could", "should", "would", "me", "my", "your", "please"
    )

    /** Stable ASCII id from a label; falls back to a hash when nothing ASCII survives. */
    fun slug(input: String): String {
        val base = normalize(input).replace(Regex("[^a-z0-9]+"), "-").trim('-')
        if (base.isNotEmpty()) return base.take(40)
        return "x" + Integer.toHexString(input.hashCode())
    }

    private const val FA_ZERO = '\u06F0'
    private const val THOUSANDS_SEP = '\u066C'

    /** Persian-digit, group-separated number for display. */
    fun num(v: Long): String {
        val negative = v < 0
        val digits = kotlin.math.abs(v).toString()
        val sb = StringBuilder()
        for ((i, c) in digits.withIndex()) {
            if (i > 0 && (digits.length - i) % 3 == 0) sb.append(THOUSANDS_SEP)
            sb.append(FA_ZERO + (c - DIGIT_ZERO))
        }
        return if (negative) "-$sb" else sb.toString()
    }

    fun numEn(v: Long): String {
        val digits = kotlin.math.abs(v).toString()
        val sb = StringBuilder()
        for ((i, c) in digits.withIndex()) {
            if (i > 0 && (digits.length - i) % 3 == 0) sb.append(',')
            sb.append(c)
        }
        return if (v < 0) "-$sb" else sb.toString()
    }

    fun isPersian(text: String): Boolean {
        var fa = 0
        var en = 0
        for (c in text) {
            if (c in '\u0600'..'\u06FF') fa++
            else if (c in 'a'..'z' || c in 'A'..'Z') en++
        }
        return fa >= en
    }
}
