package ir.aai.hushyar.core

/** Price rules carried by a market pack (see docs/DESIGN-v2.0.md §6). */
data class PriceRules(
    val currency: String = "IRT",
    val unitScale: Double = 1.0,
    val madZ: Double = 3.5,
    val goodBand: Double = 0.25,
    val fairBand: Double = 0.55,
    val highBand: Double = 0.80
)

/**
 * @param irt canonical value in Toman, or null when the input cannot be read safely.
 * @param ambiguous true when the value came from a risky reading; never used silently.
 */
data class ParsedPrice(
    val irt: Long?,
    val ambiguous: Boolean,
    val note: String
)

/**
 * Deterministic price normalisation (DESIGN §8.4):
 * fold digits, resolve thousands separators, detect multiplier words, convert Rial to Toman,
 * and refuse to guess when the magnitude is implausible and no unit was given.
 *
 * Precedence: an explicit currency word in the text wins over the pack's unit convention,
 * so a number is never divided twice.
 */
object PriceParser {

    private const val MIN_PLAUSIBLE = 10_000L
    private const val MAX_PLAUSIBLE = 100_000_000_000L

    private val NUMBER = Regex("[0-9][0-9\\.,\\u066C\\u060C\\s]*[0-9]|[0-9]")

    /**
     * "۱ میلیون و ۵۰۰ هزار" — two scale groups in one price. Reading only the first group used to
     * return 1,000,000 instead of 1,500,000, marked `ambiguous = false`: a wrong price that looks
     * certain. This is the most common Persian price shape for anything above a million.
     */
    private val COMPOSITE = Regex(
        "([0-9]+(?:\\.[0-9]+)?)\\s*(میلیون|میلیارد|million|billion)\\s*(?:و|and)?\\s*" +
            "([0-9]+(?:\\.[0-9]+)?)\\s*(هزار|میلیون|thousand|million)"
    )

    private val SCALE_FACTORS = mapOf(
        "میلیون" to 1_000_000.0, "million" to 1_000_000.0,
        "میلیارد" to 1_000_000_000.0, "billion" to 1_000_000_000.0
    )

    fun parse(raw: String, rules: PriceRules = PriceRules()): ParsedPrice {
        if (raw.isBlank()) return ParsedPrice(null, true, "خالی")
        val s = Text.asciiDigits(raw).lowercase().replace('\u066B', '.')

        val hasRial = s.contains("ریال") || s.contains("rial")
        val hasToman = s.contains("تومان") || s.contains("toman") || s.contains("تومن")

        var multiplier = 1.0
        val noteParts = mutableListOf<String>()

        val composite = COMPOSITE.find(s)
        val numeric: Double = if (composite != null) {
            val big = composite.groupValues[1].toDoubleOrNull()
            val small = composite.groupValues[3].toDoubleOrNull()
            if (big == null || small == null) {
                return ParsedPrice(null, true, "عدد مرکب نامعتبر: ${composite.value}")
            }
            val bigScale = SCALE_FACTORS[composite.groupValues[2]] ?: 1_000_000.0
            // "۲ میلیارد و ۲۵۰ میلیون" is milliard + million, not milliard + thousand.
            val smallScale = when (composite.groupValues[4]) {
                "میلیون", "million" -> 1_000_000.0
                else -> 1_000.0
            }
            noteParts.add("ترکیبی")
            big * bigScale + small * smallScale
        } else {
            when {
                s.contains("میلیارد") || s.contains("billion") -> { multiplier *= 1_000_000_000.0; noteParts.add("×میلیارد") }
                s.contains("میلیون") || s.contains("ملیون") || s.contains("million") -> { multiplier *= 1_000_000.0; noteParts.add("×میلیون") }
                s.contains("هزار") || s.contains("thousand") -> { multiplier *= 1_000.0; noteParts.add("×هزار") }
            }
            val match = NUMBER.find(s) ?: return ParsedPrice(null, true, "عددی پیدا نشد")
            parseNumeric(match.value) ?: return ParsedPrice(null, true, "عدد نامعتبر: ${match.value}")
        }

        val scale = when {
            hasRial -> 0.1
            hasToman -> 1.0
            else -> rules.unitScale
        }
        if (hasRial) noteParts.add("ریال÷۱۰")

        val value = numeric * multiplier * scale
        val rounded = value.toLong()
        val unitGiven = multiplier != 1.0 || hasRial || hasToman

        if (!unitGiven && rounded < MIN_PLAUSIBLE) {
            return ParsedPrice(null, true, "مقیاس نامعلوم (عدد کوچک بدون واحد)")
        }
        if (rounded <= 0 || rounded > MAX_PLAUSIBLE) {
            return ParsedPrice(null, true, "خارج از بازهٔ معتبر")
        }
        return ParsedPrice(rounded, false, if (noteParts.isEmpty()) "مستقیم" else noteParts.joinToString(" و "))
    }

    /** Handles "1.250.000" (thousands) and "1250.5" (decimal). */
    internal fun parseNumeric(raw: String): Double? {
        val cleaned = raw.replace("\u066C", "").replace("\u060C", "").replace(" ", "").trim()
        if (cleaned.isEmpty()) return null
        val dots = cleaned.count { it == '.' }
        val commas = cleaned.count { it == ',' }
        val s: String = when {
            dots > 0 && commas > 0 -> {
                val s2 = if (cleaned.lastIndexOf('.') > cleaned.lastIndexOf(',')) {
                    cleaned.replace(",", "").replace(".", "#")
                } else {
                    cleaned.replace(".", "").replace(",", "#")
                }
                s2.replace("#", ".")
            }
            dots > 1 -> cleaned.replace(".", "")
            commas > 1 -> cleaned.replace(",", "")
            dots == 1 || commas == 1 -> {
                val sep = if (dots == 1) '.' else ','
                val after = cleaned.length - cleaned.indexOf(sep) - 1
                if (after == 3) cleaned.replace(sep.toString(), "") else cleaned.replace(sep, '.')
            }
            else -> cleaned
        }
        return s.toDoubleOrNull()
    }
}
