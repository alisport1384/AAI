package ir.aai.hushyar.domain

import ir.aai.hushyar.core.Jalali
import ir.aai.hushyar.core.Text

/**
 * v0.11 — مغزِ بی‌حوزه: هر کالا، هر بازار.
 *
 * ```text
 * پرسش «قیمت تی‌شرت نخی سایز L دوجین»
 *   ├─ FrameParser  → کالا: تی‌شرت | جنس: نخ پنبه | سایز: L | خواسته: قیمت | پایه: دوجین
 *   ├─ دانش خودم    → Fact(price, 180000, toman, per=count) اگر ذخیره شده باشد
 *   ├─ وگرنه جستجو  → استخراج (اول با کد، بعد با مدل محلی و بازبینی سخت) → ذخیره
 *   ├─ محاسبه       → ۱۸۰٬۰۰۰ × ۱۲ = ۲٬۱۶۰٬۰۰۰ تومان دوجین (فرمول چاپ می‌شود)
 *   └─ پاسخ         → «۲٬۱۶۰٬۰۰۰ تومان دوجین» + منبع + تاریخ + فرض‌ها
 * ```
 *
 * هیچ‌جای این فایل اسم کالایی نیامده. دانستنِ «تی‌شرت چیست» از [DomainPack] می‌آید و می‌تواند
 * از حرف خودِ کاربر ساخته شود ([DomainPacks.fromDescription]).
 */
class AnyDomainBrain(
    private val tools: AgentTools,
    private val packs: List<DomainPack> = DomainPacks.all,
    /** وقتی مدل محلی نصب و روشن است، استخراج ویژگی از متن را هم او پیشنهاد می‌دهد. */
    private val generator: LocalGenerator? = null,
    private val spec: ModelSpec? = null,
    private val maxPages: Int = 2
) {

    companion object {
        /** ویژگی‌هایی که «یاد گرفتن»شان معنی دارد و استخراج می‌شوند. */
        val EXTRACTABLE = setOf("price", "weight", "size", "pack_size", "stock")
        const val CUSTOM_DOMAIN_KEY = "domain.custom"
    }

    fun handles(question: String, knownEntities: List<String> = emptyList()): Boolean =
        FrameParser.parse(question, packs, knownEntities)?.entityKnown == true

    fun answer(
        question: String,
        langTag: String,
        knownEntities: List<String> = emptyList(),
        step: (String, String, Map<String, String>, String) -> Unit
    ): AgentOutcome? {
        val frame = FrameParser.parse(question, packs, knownEntities) ?: return null
        if (!frame.entityKnown) return null

        val now = tools.now()
        val steps = mutableListOf<AgentStep>()
        val actions = mutableListOf<String>()
        val learned = mutableListOf<String>()
        val bullets = mutableListOf<String>()
        val citations = mutableListOf<Citation>()

        fun log(thought: String, tool: String, args: Map<String, String> = emptyMap(), obs: String = "") {
            steps += AgentStep(steps.size + 1, thought, tool, args, obs.take(600))
            step(thought, tool, args, obs)
        }

        log(
            "پرسش را خواندم (بدون فرض حوزه)",
            "frame.parse",
            mapOf("entity" to frame.entity, "ask" to frame.ask.name),
            frame.lines.joinToString(" ⁞ ")
        )

        val pack = packs.firstOrNull { pack -> pack.entities.any { it.id == frame.entity } }
        val entityDef = pack?.entities?.firstOrNull { it.id == frame.entity }
        if (entityDef != null && frame.ask == Ask.DEFINITION) {
            return definitionAnswer(frame, entityDef, steps, actions, langTag)
        }

        // ---- ۱) دانش خودم ----
        var prices = Facts.find(tools, frame.entity, "price")
        val packSizeFact = Facts.find(tools, frame.entity, "pack_size").firstOrNull()
        val weightFact = Facts.find(tools, frame.entity, "weight").firstOrNull()
        prices = prices.filter { it.fresh(now, Facts.PRICE_TTL_DAYS) }

        log(
            if (prices.isNotEmpty()) "قیمت در دانش خودم بود" else "در دانش خودم نبود؛ باید جستجو کنم",
            "facts.find",
            mapOf("entity" to frame.entity, "facts" to prices.size.toString()),
            prices.firstOrNull()?.let { "${it.label()} — ${it.domain} — ${Jalali.format(it.at)}" }
                ?: "هیچ قیمت تازه‌ای برای ${frame.entityLabel} ذخیره نشده"
        )

        // ---- ۲) جستجو و استخراج ----
        var searchNote = ""
        if (prices.isEmpty()) {
            val found = searchAndExtract(frame, now, steps.size)
            if (found.isNotEmpty()) {
                for ((confidence, fact) in found.sortedByDescending { it.first }.take(2)) {
                    val res = Facts.save(tools, fact.copy(confidence = confidence), now)
                    actions += "knowledge.store:${res.id}"
                    learned += "${frame.entityLabel}: ${Facts.attributeLabel(fact.attribute)} ${fact.label()} (${fact.domain})"
                }
                prices = Facts.find(tools, frame.entity, "price").filter { it.fresh(now, Facts.PRICE_TTL_DAYS) }
                searchNote = "قیمت از ${found.first().let { it.second.domain }} خوانده و ذخیره شد"
                log("آنچه پیدا شد ذخیره شد", "knowledge.store", emptyMap(), learned.joinToString("؛ "))
            } else {
                log(
                    "جستجو چیزی نداد",
                    "search.extract",
                    emptyMap(),
                    "برای «${frame.label()}» هیچ عدد معتبری در نتایج نبود — حدس نمی‌زنم"
                )
            }
        }

        // ---- ۳) محاسبه و پاسخ ----
        val answer = compose(frame, prices, weightFact, packSizeFact, now, bullets, citations)
        if (searchNote.isNotBlank()) bullets += searchNote
        frame.gaps.forEach { bullets += "برای دقیق‌تر شدن لازم دارم: $it" }
        entityDef?.let { bullets += "${it.label} چیست: ${it.whatItIs}" }

        val confidence = when {
            prices.isEmpty() -> 0.3
            answer.isEmpty() -> 0.35
            else -> 0.7
        }
        val text = if (answer.isNotEmpty()) {
            "${frame.label()} — $answer"
        } else {
            "${frame.label()} — پاسخ قطعی ندارم؛ در دانش خودم و در نتایج جستجوی همین حالا نبود."
        }

        return AgentOutcome(
            answer = Answer(
                intent = "domain.${frame.ask.name.lowercase()}",
                text = text,
                bullets = bullets,
                citations = citations,
                confidence = confidence,
                missing = if (prices.isEmpty()) listOf("${frame.ask.fa} ${frame.label()}") else emptyList(),
                lang = langTag,
                trace = steps.map { "${it.index}. ${it.tool} → ${it.observation.take(120)}" },
                actions = actions,
                learned = learned
            ),
            steps = steps,
            actions = actions
        )
    }

    // ------------------------------------------------------------------ پاسخ تعریف

    private fun definitionAnswer(
        frame: Frame,
        entity: EntityDef,
        steps: MutableList<AgentStep>,
        actions: MutableList<String>,
        langTag: String
    ): AgentOutcome {
        val bullets = mutableListOf<String>()
        bullets += "${entity.label} چیست: ${entity.whatItIs}"
        if (entity.attributes.isNotEmpty()) {
            bullets += "ویژگی‌های مهم در این بازار: " + entity.attributes.joinToString("، ") { it.label }
        }
        if (entity.sellUnits.isNotEmpty()) {
            bullets += "یکاهای فروش: " + entity.sellUnits.joinToString("، ") { Units.label(it) }
        }
        val derived = derivable(bullets, frame)
        return AgentOutcome(
            answer = Answer(
                intent = "domain.definition",
                text = "${entity.label}: ${entity.whatItIs}",
                bullets = bullets,
                confidence = 0.8,
                lang = langTag,
                trace = steps.map { "${it.index}. ${it.tool} → ${it.observation.take(120)}" },
                actions = actions
            ),
            steps = steps,
            actions = actions
        ).also { derived }
    }

    private fun derivable(bullets: MutableList<String>, frame: Frame) {
        if (frame.gaps.isNotEmpty()) bullets += "برای محاسبه لازم دارم: " + frame.gaps.joinToString("، ")
    }

    // ------------------------------------------------------------------ جستجو

    /**
     * چند شکل پرسش، و برای هر نتیجه دو خواننده: کد (سخت‌گیر و قابل تست) و مدل محلی (پیشنهاد، با
     * بازبینی کد). گوگل در همهٔ این پرسش‌ها اولین موتور است.
     */
    private fun searchAndExtract(
        frame: Frame,
        now: Long,
        stepOffset: Int
    ): List<Pair<Double, Fact>> {
        val q = frame.label()
        val queries = listOf(
            "قیمت $q امروز تومان",
            "$q قیمت روز بازار",
            "$q قیمت تومان"
        )
        val out = mutableListOf<Pair<Double, Fact>>()
        for (query in queries) {
            val report = tools.webSearch(query, "fa")
            out += FactExtractor.read(report.hits, frame, now)
            if (out.isNotEmpty()) break
            var fetched = 0
            for (hit in report.hits) {
                if (fetched >= maxPages) break
                val page = runCatching { tools.fetchPage(hit.url, "fa") }.getOrNull() ?: continue
                fetched++
                val pseudo = WebHit(hit.url, hit.title, page.text.take(4000), hit.engine, hit.rank)
                out += FactExtractor.read(listOf(pseudo), frame, now)
                out += FactExtractor.withModel(listOf(pseudo), frame, generator, spec, now)
                if (out.isNotEmpty()) break
            }
            if (out.isNotEmpty()) break
        }
        return out
    }

    // ------------------------------------------------------------------ محاسبه

    /**
     * پاسخ از دانش: هر پایه‌ای را که بتوان محاسبه کرد نشان می‌دهد، با فرمول. اگر پایهٔ پرسش
     * خواسته شده باشد، همان اول می‌آید. عددِ بدون فرمول در پاسخ نمی‌آید.
     */
    private fun compose(
        frame: Frame,
        prices: List<Fact>,
        weight: Fact?,
        packSize: Fact?,
        now: Long,
        bullets: MutableList<String>,
        citations: MutableList<Citation>
    ): String {
        if (prices.isEmpty()) return ""
        val price = prices.first()
        citations += Citation("P1", price.domain.ifBlank { "منبع" }, price.url.ifBlank { null })

        val per = price.per ?: "count"
        fun toPer(target: String): Pair<Double, String>? = Rates.convert(price.value, per, target, weight, packSize)

        val wanted = frame.basis
        val targets = when {
            wanted != null -> listOf(wanted)
            else -> listOfNotNull(
                per,
                if (per == "count") "dozen" else null,
                if (per != "kg" && weight != null) "kg" else null
            ).distinct()
        }

        val lines = mutableListOf<String>()
        for (target in targets) {
            val (value, formula) = toPer(target) ?: continue
            lines += "قیمت بر ${Units.label(target)}: ${SteelMath.money(value)}" +
                (if (formula.isNotBlank()) " — محاسبه: $formula" else "")
        }
        bullets += lines
        bullets += "قیمت از ${price.domain.ifBlank { "منبع" }} — ${Jalali.format(price.at)}" +
            " (پایهٔ اعلام‌شده: بر ${Units.label(per)})"
        if (price.evidence.isNotBlank()) bullets += "متن منبع: «${price.evidence.take(160)}»"

        val qty = frame.quantity
        if (qty != null && frame.ask == Ask.PRICE) {
            val unitPrice = toPer(qty.second)?.first
            if (unitPrice != null) {
                bullets += "برای ${Materials.num(qty.first)} ${Units.label(qty.second)}: " +
                    "${Materials.num(qty.first)} × ${SteelMath.money(unitPrice)} = " +
                    SteelMath.money(qty.first * unitPrice)
            }
        }

        val first = lines.firstOrNull() ?: return ""
        return first.removePrefix("قیمت بر ").replaceFirstChar { it }
    }

    fun stamp(at: Long): String = Jalali.format(at)
}

/** تبدیل نرخ بین یکاها، با فرمولِ قابل چاپ. */
object Rates {

    /** (مقدار، فرمول). فرمول خالی یعنی تبدیل لازم نبود. */
    fun convert(
        value: Double,
        from: String,
        to: String,
        weight: Fact?,
        packSize: Fact?
    ): Pair<Double, String>? {
        if (from == to) return value to ""
        // شمارشی‌ها: عدد ↔ دوجین ↔ بسته
        val fromCount = countFactor(from, packSize)
        val toCount = countFactor(to, packSize)
        if (fromCount != null && toCount != null) {
            val result = value * fromCount / toCount
            return result to "${SteelMath.money(value)} × ${Materials.num(fromCount)} ÷ ${Materials.num(toCount)}"
        }
        // جرم: کیلو ↔ تن
        val mass = Units.convert(value, from, to)
        if (mass != null) {
            return mass to "${SteelMath.money(value)} × ${Materials.num(Units.of(from)?.toBase ?: 1.0)} ÷ " +
                Materials.num(Units.of(to)?.toBase ?: 1.0)
        }
        // شمارشی ↔ جرم: با وزنِ هر عدد که یاد گرفته‌ایم
        if (weight != null) {
            val kgPerPiece = Units.convert(weight.value, weight.unit, "kg") ?: return null
            if (from != null && to == "kg" && fromCount != null) {
                val kg = fromCount * kgPerPiece
                if (kg <= 0) return null
                return value / kg to "${SteelMath.money(value)} ÷ (${Materials.num(fromCount)} × ${Materials.num(kgPerPiece)} کیلوگرم)"
            }
            if (from == "kg" && toCount != null) {
                val kg = toCount * kgPerPiece
                return value * kg to "${SteelMath.money(value)} × ${Materials.num(kg)} کیلوگرم"
            }
        }
        return null
    }

    /** تعداد «عدد» در یک یکای شمارشی؛ «بسته» اندازه‌اش از دانش می‌آید نه از استاندارد. */
    fun countFactor(unit: String, packSize: Fact?): Double? = when (unit) {
        "count" -> 1.0
        "pair" -> 2.0
        "dozen" -> 12.0
        "pack" -> packSize?.value?.takeIf { it > 0 }
        "carton" -> packSize?.value?.takeIf { it > 0 }
        else -> null
    }
}

/**
 * استخراج ویژگی از متنِ نتیجه — دو لایه.
 *
 * **لایهٔ کد**: عدد باید یکای پول یا جرم داشته باشد، پایهٔ نرخ از نزدیک‌ترین واژه‌ها خوانده شود،
 * کالا در متن آمده باشد، و مقدار در بازهٔ باورپذیر باشد. این لایه بدون مدل هم کار می‌کند.
 *
 * **لایهٔ مدل**: مدل محلی از متن خط‌هایی مثل `قیمت=۱۸۰۰۰۰ تومان هر عدد` پیشنهاد می‌کند؛ ولی
 * **هر عددی که در متنِ منبع نباشد دور انداخته می‌شود**، پس مدل می‌تواند یاد بگیرد اما نمی‌تواند
 * عدد بسازد. این همان تفاوت «مدل بازنویسی‌کننده» با «مدل یادگیرنده» است.
 */
object FactExtractor {

    private val PRICE_UNIT = listOf("تومان", "تومن", "ریال")
    private val BASIS_WORDS = listOf(
        "kilogram" to "kg", "کیلوگرم" to "kg", "کیلو" to "kg",
        "دوجین" to "dozen", "بسته" to "pack", "کارتن" to "carton",
        "عدد" to "count", "دانه" to "count", "تا" to "count", "جفت" to "pair",
        "مترمربع" to "m2", "متر مربع" to "m2", "متر" to "m"
    )

    fun read(hits: List<WebHit>, frame: Frame, now: Long): List<Pair<Double, Fact>> {
        val out = mutableListOf<Pair<Double, Fact>>()
        for (hit in hits) {
            val text = Text.normalize("${hit.title} ${hit.snippet}")
            if (text.isBlank()) continue
            if (!mentionsEntity(text, frame)) continue
            val domain = hit.url.substringAfter("://").substringBefore('/')

            // قیمت: فقط با یکای پول
            for (m in Units.NUMBER_UNIT.findAll(text)) {
                val unitWord = m.groupValues[3]
                if (unitWord !in PRICE_UNIT) continue
                var value = m.groupValues[1].replace(" ", "").replace("\u066C", "").replace(",", "")
                    .toDoubleOrNull() ?: continue
                value *= when (m.groupValues[2]) {
                    "هزار" -> 1_000.0
                    "میلیون" -> 1_000_000.0
                    "میلیارد" -> 1_000_000_000.0
                    else -> 1.0
                }
                if (unitWord == "ریال") value /= 10.0
                if (value < 1_000 || value > 5_000_000_000.0) continue
                val per = basisNear(text, m.range.first, frame)
                out += 0.7 to Fact(
                    entity = frame.entity,
                    attribute = "price",
                    qualifier = frame.qualifierText,
                    value = value,
                    unit = "toman",
                    per = per,
                    evidence = window(text, m.range.first),
                    domain = domain,
                    url = hit.url,
                    at = now,
                    confidence = 0.7
                )
                if (out.size >= 3) return out
            }
            // وزن/گرماژ: فقط با یکای جرم
            for (m in Units.NUMBER_UNIT.findAll(text)) {
                val unitWord = m.groupValues[3]
                val u = Units.byAlias(unitWord) ?: continue
                if (u.dim != Dim.MASS) continue
                val value = m.groupValues[1].replace(" ", "").replace("\u066C", "").toDoubleOrNull() ?: continue
                if (value <= 0 || value > 1_000_000) continue
                out += 0.5 to Fact(
                    entity = frame.entity,
                    attribute = "weight",
                    qualifier = frame.qualifierText,
                    value = Units.convert(value, u.id, "kg") ?: value,
                    unit = "kg",
                    evidence = window(text, m.range.first),
                    domain = domain,
                    url = hit.url,
                    at = now,
                    confidence = 0.5
                )
                break
            }
        }
        return out
    }

    /** لایهٔ مدل: پیشنهاد مدل + بازبینی سختِ کد. */
    fun withModel(
        hits: List<WebHit>,
        frame: Frame,
        generator: LocalGenerator?,
        spec: ModelSpec?,
        now: Long
    ): List<Pair<Double, Fact>> {
        if (generator == null || !generator.ready) return emptyList()
        val out = mutableListOf<Pair<Double, Fact>>()
        for (hit in hits.take(1)) {
            val snippet = Text.normalize("${hit.title} ${hit.snippet}").take(700)
            if (snippet.isBlank()) continue
            val prompt = LocalPrompt.factPrompt(snippet, frame.entityLabel, spec?.family ?: ModelCatalog.GENERIC_FAMILY)
            val reply = runCatching { generator.generate(prompt, 48) }.getOrNull()?.text ?: continue
            for (line in reply.lineSequence()) {
                val parsed = parseFactLine(line) ?: continue
                // بازبینی: عدد باید در متن منبع باشد
                val digits = Materials.persian(Text.asciiDigits(parsed.second.toString()))
                val ascii = Text.asciiDigits(parsed.second.toString())
                if (!snippet.contains(digits) && !snippet.contains(ascii)) continue
                val unit = Units.byAlias(parsed.third) ?: continue
                if (unit.dim == Dim.MONEY) {
                    out += 0.75 to Fact(
                        entity = frame.entity, attribute = "price", qualifier = frame.qualifierText,
                        value = parsed.second, unit = "toman", per = frame.basis ?: "count",
                        evidence = snippet.take(160), domain = hit.url.substringAfter("://").substringBefore('/'),
                        url = hit.url, at = now, confidence = 0.75
                    )
                } else if (unit.dim == Dim.MASS) {
                    out += 0.6 to Fact(
                        entity = frame.entity, attribute = "weight", qualifier = frame.qualifierText,
                        value = Units.convert(parsed.second, unit.id, "kg") ?: parsed.second, unit = "kg",
                        evidence = snippet.take(160), domain = hit.url.substringAfter("://").substringBefore('/'),
                        url = hit.url, at = now, confidence = 0.6
                    )
                }
            }
        }
        return out
    }

    /** `ویژگی=عدد یکا` — همان شکلی که مدل موظف است بنویسد. */
    fun parseFactLine(line: String): Triple<String, Double, String>? {
        val m = Regex("^\\s*([^=]{2,20})=\\s*([0-9۰-۹][0-9۰-۹,\\.\\u066C]*)\\s*([^\\s]{1,12})").find(line) ?: return null
        val attr = Text.normalize(m.groupValues[1]).trim()
        val value = Text.asciiDigits(m.groupValues[2]).replace("\u066C", "").replace(",", "").toDoubleOrNull() ?: return null
        val unit = Text.normalize(m.groupValues[3]).trim()
        if (attr !in AnyDomainBrain.EXTRACTABLE && attr !in EXTRA_ATTRS) return null
        return Triple(attr, value, unit)
    }

    private val EXTRA_ATTRS = setOf("وزن", "گرماژ", "قیمت", "سایز")

    private fun mentionsEntity(text: String, frame: Frame): Boolean {
        val name = Text.normalize(frame.entityLabel)
        if (name.length >= 3 && text.contains(name)) return true
        val pack = DomainPacks.byId(null)
        // مترادف‌های کالا هم قبول‌اند
        for (p in listOfNotNull(pack)) for (e in p.entities) if (e.id == frame.entity) {
            if (e.aliases.any { text.contains(Text.normalize(it)) }) return true
        }
        for (p in DomainPacks.all) for (e in p.entities) if (e.id == frame.entity) {
            if ((e.aliases + e.label).any { text.contains(Text.normalize(it)) }) return true
        }
        return text.contains(Text.normalize(frame.entity))
    }

    private fun basisNear(text: String, index: Int, frame: Frame): String? {
        val from = (index - 45).coerceAtLeast(0)
        val to = (index + 45).coerceAtMost(text.length)
        val window = text.substring(from, to)
        for ((word, unit) in BASIS_WORDS) if (window.contains(word)) return unit
        return frame.basis ?: "count"
    }

    private fun window(text: String, index: Int): String {
        val from = (index - 70).coerceAtLeast(0)
        val to = (index + 70).coerceAtMost(text.length)
        return text.substring(from, to).trim()
    }
}
