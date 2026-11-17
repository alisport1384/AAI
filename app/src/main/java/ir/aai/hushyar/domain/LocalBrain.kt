package ir.aai.hushyar.domain

import java.net.URLEncoder

/**
 * The on-device model as the app's *thinking* part, not just its phrasing part.
 *
 * Three jobs, all of them decisions the deterministic code already makes badly-by-template:
 *  - **decompose**: split "learn everything about X" into facets worth searching separately;
 *  - **expand**: turn a facet into 2–3 different queries (synonyms, official-source wording);
 *  - **classify**: put a learned sentence into one layer of the topic's structure.
 *
 * Every method is defensive on purpose: a small model answers with lists, preambles, or
 * complete nonsense, so each answer is parsed line-by-line, validated against hard rules, and
 * thrown away wholesale if it does not survive. When nothing survives, the caller's
 * deterministic planner takes over — the app never depends on the model being clever.
 */
class LocalBrain(
    private val generator: LocalGenerator,
    private val spec: ModelSpec = ModelCatalog.recommended,
    private val maxTokens: Int = 128
) {

    val ready: Boolean get() = generator.ready

    /** Layers every studied topic is broken into. Order matters: it is also the reading order. */
    companion object {
        const val LAYER_BASICS = "پایه"
        const val LAYER_SPECS = "ویژگی‌ها"
        const val LAYER_PRICE = "قیمت"
        const val LAYER_REVIEWS = "نظرها"
        const val LAYER_UPDATES = "به‌روزرسانی"
        const val LAYER_SOURCES = "منابع رسمی"

        val LAYERS: List<String> = listOf(
            LAYER_BASICS, LAYER_SPECS, LAYER_PRICE, LAYER_REVIEWS, LAYER_UPDATES, LAYER_SOURCES
        )
    }

    /** Facets the model proposes for a topic. Empty list = "I have nothing better than the template". */
    fun facets(topic: String, lang: String): List<String> {
        if (!ready || topic.isBlank()) return emptyList()
        val system = if (lang == "en") {
            "You split a topic into aspects worth searching separately. One aspect per line, at most 5, no numbering, no explanation."
        } else {
            "تو موضوع را به بُعدهای قابل جستجو تقسیم می‌کنی. هر بُعد در یک خط، حداکثر ۵ خط، بدون شماره و بدون توضیح."
        }
        val example = if (lang == "en") {
            "topic: solar charger\naspects:" to "price\nbattery capacity\ncharging speed\nbrands"
        } else {
            "موضوع: پاوربانک خورشیدی\nبُعدها:" to "قیمت\nظرفیت باتری\nسرعت شارژ\nبرندها و مدل‌ها"
        }
        val instruction = if (lang == "en") "topic: $topic\naspects:" else "موضوع: $topic\nبُعدها:"
        val raw = generator.generate(LocalPrompt.chat(spec.family, system, instruction, example), maxTokens)
        return validateLines(
            raw.text,
            limit = 5,
            maxLength = 60,
            bannedPrefixes = listOf("موضوع", "topic", "بُعد", "aspect")
        )
    }

    /** 2–3 alternative queries for one facet of one topic. */
    fun queries(topic: String, facet: String, lang: String): List<String> {
        if (!ready || topic.isBlank() || facet.isBlank()) return emptyList()
        val system = if (lang == "en") {
            "You write short web queries. One query per line, at most 3, no numbering, no quotes."
        } else {
            "تو پرس‌وجوی کوتاه وب می‌نویسی. هر پرس‌وجو در یک خط، حداکثر ۳ خط، بدون شماره و بدون گیومه."
        }
        val example = if (lang == "en") {
            "topic: solar charger\naspect: price\nqueries:" to
                "solar charger price\ncheapest solar charger 2026"
        } else {
            "موضوع: پاوربانک خورشیدی\nبُعد: قیمت\nپرس‌وجوها:" to
                "قیمت پاوربانک خورشیدی\nارزان‌ترین پاوربانک خورشیدی"
        }
        val instruction = if (lang == "en") {
            "topic: $topic\naspect: $facet\nqueries:"
        } else {
            "موضوع: $topic\nبُعد: $facet\nپرس‌وجوها:"
        }
        val raw = generator.generate(LocalPrompt.chat(spec.family, system, instruction, example), maxTokens)
        return validateLines(
            raw.text,
            limit = 3,
            maxLength = 80,
            bannedPrefixes = listOf("موضوع", "topic", "بُعد", "aspect", "پرس", "quer")
        )
    }

    /** Which layer a learned sentence belongs to, or null when the model's choice is not usable. */
    fun layer(claim: String, lang: String): String? {
        if (!ready || claim.isBlank()) return null
        val options = LAYERS.joinToString("، ")
        val system = if (lang == "en") {
            "You label a sentence with exactly one of these layers: ${LAYERS.joinToString(", ")}. Answer with the layer only."
        } else {
            "تو یک جمله را با دقیقاً یکی از این لایه‌ها برچسب می‌زنی: $options. فقط نام لایه را بنویس."
        }
        val raw = generator.generate(LocalPrompt.chat(spec.family, system, claim.take(300)), 16)
        val answer = raw.text?.trim()?.trim('«', '»', '"', '.', ':', ' ') ?: return null
        return LAYERS.firstOrNull { it == answer }
            ?: LAYERS.firstOrNull { answer.contains(it) && answer.length <= it.length + 3 }
    }

    private fun validateLines(
        text: String?,
        limit: Int,
        maxLength: Int,
        bannedPrefixes: List<String>
    ): List<String> {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return emptyList()
        val seen = LinkedHashSet<String>()
        for (line in raw.lines()) {
            val clean = line.trim().trimStart('-', '*', '•', '۱', '۲', '۳', '۴', '۵', '1', '2', '3', '4', '5')
                .trimStart('.', ')', '،', ':')
                .trim()
                .trim('"', '\'', '«', '»', '“', '”', ' ')
            if (clean.length < 3 || clean.length > maxLength) continue
            if (clean.contains("://") || clean.contains("http")) continue
            if (bannedPrefixes.any { clean.lowercase().startsWith(it.lowercase()) }) continue
            if (clean.count { it.isLetterOrDigit() } < 3) continue
            seen.add(clean)
            if (seen.size >= limit) break
        }
        return seen.toList()
    }

    /** Percent-encoded, ready for a URL template — kept here so the model's text never escapes raw. */
    fun encode(query: String): String = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
}
