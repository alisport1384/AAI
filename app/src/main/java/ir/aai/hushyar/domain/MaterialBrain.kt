package ir.aai.hushyar.domain

import ir.aai.hushyar.core.Jalali
import ir.aai.hushyar.core.Text

/**
 * v0.10 — «اول داده‌ها را می‌گردد، اگر نداشت جستجو می‌کند، بعد خودش حساب می‌کند، بعد یاد می‌گیرد.»
 *
 * این کلاس همان مسیر است، برای کالاهای فولادی — دقیقاً همان چیزی که در «ورق ۲ میل قیمت» و
 * «تیرآهن ۱۸ و ۱۶ قیمت» کم بود:
 *
 * ```text
 * خواندن پرسش (Materials.read)
 *   ├─ دانش خودم: تعریف کالا + جدول/فرمول وزن  ← بدون شبکه، همیشه در دسترس
 *   ├─ قیمت: دانش ذخیره‌شدهٔ قبلی (اگر تازه باشد)            ← بدون شبکه
 *   ├─ وگرنه: جستجوی هدفمند (گوگل اول) → استخراج قیمت از نتیجه/صفحه
 *   ├─ محاسبهٔ یکای خواسته‌شده (کیلو ↔ متر ↔ شاخه/برگ ↔ تن)  ← برنامه، نه مدل
 *   └─ ذخیرهٔ آنچه فهمید (قیمت + وزن) تا سؤال بعدی آفلاین جواب بگیرد
 * ```
 *
 * قاعدهٔ سخت: هیچ عددی در پاسخ از مدل زبانی نمی‌آید. هر عدد یا از جدول/فرمول است، یا از متن
 * منبع با آدرس، یا حاصل محاسبه‌ای که فرمولش چاپ می‌شود. اگر قیمت پیدا نشد، پاسخ می‌گوید
 * «پیدا نشد» و همان چیزی را که می‌داند (وزن و محاسبه) می‌دهد — جای خالی را با حدس پر نمی‌کند.
 */
data class PriceFact(
    val key: String,
    val tomanPerKg: Double,
    val asStated: Double,
    val basis: PriceBasis,
    val domain: String,
    val url: String,
    val at: Long,
    val evidence: String
) {
    fun fresh(now: Long): Boolean = now - at <= MaterialBrain.PRICE_TTL_MS
    fun ageDays(now: Long): Long = ((now - at) / 86_400_000L).coerceAtLeast(0)
}

class MaterialBrain(private val tools: AgentTools) {

    companion object {
        /** قیمت فولاد سریع حرکت می‌کند: بعد از این مدت «کهنه» حساب می‌شود. */
        const val PRICE_TTL_MS = 3L * 86_400_000L
        const val PRICE_ATTRIBUTE = "price_per_kg"
        const val WEIGHT_ATTRIBUTE = "weight_per_metre"
        private const val MAX_PAGES = 2
    }

    /** آیا این پرسش در حوزهٔ این ماژول است؟ (بدون کالا شناسایی‌شده، نه.) */
    fun handles(question: String): Boolean = Materials.read(question) != null

    /**
     * تلاش برای پاسخ. `null` یعنی «این پرسش مال من نیست» و مسیر عمومی برنامه باید ادامه دهد.
     */
    fun answer(
        question: String,
        langTag: String,
        step: (String, String, Map<String, String>, String) -> Unit
    ): AgentOutcome? {
        val reading = Materials.read(question) ?: return null
        val now = tools.now()
        val w = SteelMath.weight(reading)
        val steps = mutableListOf<AgentStep>()
        val actions = mutableListOf<String>()
        val learned = mutableListOf<String>()

        fun log(thought: String, tool: String, args: Map<String, String> = emptyMap(), observation: String = "") {
            val s = AgentStep(steps.size + 1, thought, tool, args, observation.take(600))
            steps += s
            step(thought, tool, args, observation)
        }

        log(
            "پرسش را خواندم: کالا، اندازه، خواسته",
            "materials.read",
            mapOf("product" to reading.product.name, "sizes" to reading.sizes.joinToString(",")),
            "خواسته: ${reading.ask.name} | پایه: ${reading.basis?.fa ?: "نامشخص"} | " +
                reading.lines.joinToString(" ⁞ ")
        )
        log(
            "دانش خودم (بدون شبکه)",
            "materials.table",
            mapOf("kind" to reading.product.kind.name),
            w.formula + (if (w.note.isNotBlank()) " — ${w.note}" else "")
        )

        // ---- مسیرهای آفلاین: تعریف، وزن، روش محاسبه ----
        if (reading.ask != MaterialAsk.PRICE) {
            return offlineAnswer(reading, w, steps, actions, learned, now, langTag)
        }

        // ---- قیمت: اول دانش ذخیره‌شده، بعد جستجو ----
        var fact = storedFact(reading, now)
        log(
            if (fact != null) "قیمت در دانش ذخیره‌شده بود" else "قیمت را نداشتم؛ باید جستجو کنم",
            "knowledge.price",
            mapOf("key" to keyOf(reading)),
            fact?.let { "${SteelMath.money(it.tomanPerKg)}/کیلوگرم، ${it.ageDays(now)} روز پیش، ${it.domain}" }
                ?: "هیچ قیمت تازه‌ای برای ${keyOf(reading)} ذخیره نشده"
        )

        var searchNote = ""
        if (fact == null || !fact.fresh(now)) {
            val found = searchPrice(reading, w)
            if (found != null) {
                fact = found
                searchNote = "قیمت از ${found.domain} خوانده شد"
                val item = priceItem(reading, found, now)
                val res = tools.knowledgeStore(item)
                actions += "knowledge.store:${item.id}"
                learned += "قیمت ${label(reading)} = ${SteelMath.money(found.tomanPerKg)} بر کیلوگرم (${found.domain})"
                log(
                    "آنچه پیدا شد در دانش ذخیره شد",
                    "knowledge.store",
                    mapOf("decision" to res.decision.name),
                    res.summary
                )
            } else {
                log(
                    "جستجو قیمت را نداد",
                    "search.price",
                    emptyMap(),
                    "هیچ قیمت معتبری برای ${label(reading)} در نتایج نبود (${lastSearchSummary()})"
                )
            }
        }

        // وزن استاندارد هم دانش است: یک بار ذخیره می‌شود و بعد از آن آفلاین است.
        if (w.kgPerMetre != null) {
            val wi = weightItem(reading, w, now)
            tools.knowledgeStore(wi)
            learned += "وزن ${label(reading)} = ${Materials.num(w.kgPerMetre!!)} کیلوگرم بر متر (${w.provenance})"
        }

        return priceAnswer(reading, w, fact, now, steps, actions, learned, searchNote)
    }

    // ------------------------------------------------------------------ آفلاین

    private fun offlineAnswer(
        reading: Materials.Reading,
        w: SteelMath.Weight,
        steps: MutableList<AgentStep>,
        actions: MutableList<String>,
        learned: MutableList<String>,
        now: Long,
        langTag: String
    ): AgentOutcome {
        val p = reading.product
        val bullets = mutableListOf<String>()
        bullets += "${p.name} چیست: ${p.whatItIs}"
        bullets += "جنس‌های رایج: ${p.grades.joinToString("، ")}"
        val oneSize = reading.sizes.firstOrNull()
        if (oneSize != null) {
            bullets += if (p.measureInCm) {
                "«${Materials.num(oneSize)}» در بازار یعنی ${p.measure} ${Materials.num(oneSize)} سانتی‌متر (${Materials.num(oneSize * 10)} میلی‌متر)"
            } else {
                "«${Materials.num(oneSize)}» یعنی ${p.measure} ${Materials.num(oneSize)} ${p.measureUnit}"
            }
        }
        bullets += w.formula
        if (w.note.isNotBlank()) bullets += "یادداشت: ${w.note}"
        if (w.provenance.isNotBlank()) bullets += "منبع عدد: ${w.provenance}"
        SteelMath.pieceWeight(reading, w)?.let { bullets += "وزن یک شاخه/برگ: ${it.formula}" }
        SteelMath.piecesPerTon(reading, w)?.let { bullets += it.formula }
        if (reading.quantity != null && reading.quantityUnit != null) {
            quantityAnswer(reading, w, reading.quantity, reading.quantityUnit)?.let { bullets += it }
        }
        reading.assumptions.forEach { bullets += "فرض: $it" }

        if (w.kgPerMetre != null) {
            val wi = weightItem(reading, w, now)
            tools.knowledgeStore(wi)
            actions += "knowledge.store:${wi.id}"
            learned += "وزن ${label(reading)} = ${Materials.num(w.kgPerMetre)} کیلوگرم بر متر"
        }

        val text = buildString {
            append("${p.name}")
            reading.size?.let { append(" ${Materials.num(it)}") }
            append(" — ")
            if (w.kgPerMetre != null) {
                append("وزن هر متر ${Materials.num(w.kgPerMetre)} کیلوگرم")
                SteelMath.pieceWeight(reading, w)?.let { append("، هر شاخه/برگ ${Materials.num(it.value)} کیلوگرم") }
            } else {
                append("برای این اندازه جدول استاندارد را ندارم")
            }
            append(". ")
            append(p.whatItIs.substringBefore("؛"))
            append(".")
        }

        return AgentOutcome(
            answer = Answer(
                intent = if (reading.ask == MaterialAsk.WEIGHT) "material.weight" else "material.definition",
                text = text,
                bullets = bullets,
                confidence = if (w.kgPerMetre != null && w.exact) 0.85 else if (w.kgPerMetre != null) 0.65 else 0.4,
                lang = langTag,
                trace = steps.map { "${it.index}. ${it.tool} → ${it.observation.take(120)}" },
                actions = actions,
                learned = learned
            ),
            steps = steps,
            actions = actions
        )
    }

    // ------------------------------------------------------------------ قیمت

    private fun priceAnswer(
        reading: Materials.Reading,
        w: SteelMath.Weight,
        fact: PriceFact?,
        now: Long,
        steps: MutableList<AgentStep>,
        actions: MutableList<String>,
        learned: List<String>,
        searchNote: String
    ): AgentOutcome {
        val p = reading.product
        val bullets = mutableListOf<String>()
        val citations = mutableListOf<Citation>()

        bullets += "چه فهمیدم: " + reading.lines.joinToString(" ⁞ ")
        if (w.kgPerMetre != null) {
            bullets += "دانش خودم: ${w.formula}"
            if (w.provenance.isNotBlank()) bullets += "منبع این عدد: ${w.provenance}"
        }

        val basisList = if (reading.basis != null) listOf(reading.basis) else
            listOf(PriceBasis.PER_KG, PriceBasis.PER_METRE, PriceBasis.PER_BRANCH, PriceBasis.PER_SQUARE, PriceBasis.PER_TON)
                .filter { it != PriceBasis.PER_SQUARE || p.kind == ProductKind.SHEET }
                .filter { it != PriceBasis.PER_BRANCH || p.kind != ProductKind.SHEET }
                .map { if (p.kind == ProductKind.SHEET && it == PriceBasis.PER_BRANCH) PriceBasis.PER_SHEET else it }
                .distinct()

        var headline = ""
        var confidence = 0.4
        if (fact != null) {
            val stepsOfPrice = basisList.mapNotNull { basis ->
                SteelMath.priceIn(basis, fact.tomanPerKg, reading, w)?.let { basis to it }
            }
            if (stepsOfPrice.isEmpty()) {
                // وزن را نمی‌دانیم، پس فقط قیمت هر کیلو را می‌گوییم.
                bullets += "قیمت: ${SteelMath.money(fact.tomanPerKg)} بر کیلوگرم"
            } else {
                for ((basis, s) in stepsOfPrice) {
                    bullets += "قیمت ${basis.fa} (${s.unit}): ${SteelMath.money(s.value)} — محاسبه: ${s.formula}"
                }
                val main = stepsOfPrice.firstOrNull { it.first == (reading.basis ?: PriceBasis.PER_KG) }
                    ?: stepsOfPrice.first()
                headline = "${main.second.unit}: ${SteelMath.money(main.second.value)}"
            }
            val age = fact.ageDays(now)
            bullets += "قیمت هر کیلو از ${fact.domain} خوانده شد" +
                (if (age == 0L) " (امروز)" else " (${Materials.num(age.toDouble(), 0)} روز پیش)") +
                (if (!fact.fresh(now)) " — کهنه است، برای قیمت روز دوباره بپرس" else "")
            bullets += "متن منبع: «${fact.evidence.take(180)}»"
            if (fact.url.isNotBlank()) citations += Citation("P1", fact.domain, fact.url)
            confidence = if (fact.fresh(now) && w.exact) 0.78 else if (fact.fresh(now)) 0.68 else 0.5
        } else {
            bullets += "قیمت را پیدا نکردم — نه در دانش ذخیره‌شده و نه در نتایج جستجوی همین حالا."
            bullets += "چیزی که می‌توانم قطعی بگویم: وزن و محاسبه‌ها (بالا). با همان وزن، هر قیمت هر کیلویی که خودت بدهی را می‌توانم برای متر و شاخه حساب کنم."
            bullets += "جستجو: ${lastSearchSummary()}"
            confidence = 0.35
        }

        if (reading.quantity != null && reading.quantityUnit != null) {
            val line = quantityAnswer(reading, w, reading.quantity, reading.quantityUnit)
            if (line != null) bullets += line
        }
        SteelMath.pieceWeight(reading, w)?.let { bullets += "وزن یک شاخه/برگ: ${it.formula}" }
        reading.assumptions.forEach { bullets += "فرض: $it" }
        if (searchNote.isNotBlank()) bullets += searchNote

        val text = buildString {
            append(label(reading))
            if (headline.isNotBlank()) append(" — $headline") else append(" — قیمت در دسترس نبود")
            if (w.kgPerMetre != null) {
                append(". وزن هر متر ${Materials.num(w.kgPerMetre)} کیلوگرم")
                SteelMath.pieceWeight(reading, w)?.let { append("، هر شاخه/برگ ${Materials.num(it.value)} کیلوگرم") }
            }
            append('.')
        }

        return AgentOutcome(
            answer = Answer(
                intent = "material.price",
                text = text,
                bullets = bullets,
                citations = citations,
                confidence = confidence,
                missing = if (fact == null) listOf("قیمت روز ${label(reading)}") else emptyList(),
                lang = "fa",
                trace = steps.map { "${it.index}. ${it.tool} → ${it.observation.take(120)}" },
                actions = actions,
                learned = learned
            ),
            steps = steps,
            actions = actions
        )
    }

    /** «۲۰۰ کیلو تیرآهن ۱۸» → وزن و قیمت کل. */
    private fun quantityAnswer(
        reading: Materials.Reading,
        w: SteelMath.Weight,
        qty: Double,
        unit: PriceBasis
    ): String? {
        val kgPerM = w.kgPerMetre ?: return null
        val p = reading.product
        val kg = when (unit) {
            PriceBasis.PER_KG -> qty
            PriceBasis.PER_TON -> qty * 1000.0
            PriceBasis.PER_METRE -> qty * kgPerM
            PriceBasis.PER_BRANCH -> qty * kgPerM * p.standardLengthM
            PriceBasis.PER_SHEET -> qty * kgPerM * p.sheetAreaM2
            PriceBasis.PER_SQUARE -> qty * kgPerM
        }
        val formula = when (unit) {
            PriceBasis.PER_KG -> "${Materials.num(qty)} کیلوگرم"
            PriceBasis.PER_TON -> "${Materials.num(qty)} تن × ۱۰۰۰ = ${Materials.num(kg)} کیلوگرم"
            PriceBasis.PER_METRE -> "${Materials.num(qty)} متر × ${Materials.num(kgPerM)} = ${Materials.num(kg)} کیلوگرم"
            PriceBasis.PER_BRANCH -> "${Materials.num(qty)} شاخه × ${Materials.num(p.standardLengthM)} متر × ${Materials.num(kgPerM)} = ${Materials.num(kg)} کیلوگرم"
            PriceBasis.PER_SHEET -> "${Materials.num(qty)} برگ × ${Materials.num(p.sheetAreaM2)} مترمربع × ${Materials.num(kgPerM)} = ${Materials.num(kg)} کیلوگرم"
            PriceBasis.PER_SQUARE -> "${Materials.num(qty)} مترمربع × ${Materials.num(kgPerM)} = ${Materials.num(kg)} کیلوگرم"
        }
        return "برای مقدار خواسته‌شده: $formula"
    }

    // ------------------------------------------------------------------ جستجو

    private var lastSummary: String = ""

    private fun lastSearchSummary(): String = lastSummary.ifBlank { "جستجویی انجام نشد" }

    /**
     * جستجوی هدفمند: چند شکل پرسش (فارسی و انگلیسی) و بعد استخراج قیمت از عنوان/بریده، و اگر
     * بریده‌ها قیمت نداشتند، خواندن حداکثر دو صفحهٔ اول. گوگل در همهٔ این پرسش‌ها اولین موتور است.
     */
    private fun searchPrice(reading: Materials.Reading, w: SteelMath.Weight): PriceFact? {
        val p = reading.product
        val sizeLabel = reading.size?.let { Materials.num(it) } ?: ""
        val queries = mutableListOf(
            "قیمت هر کیلو ${p.name} $sizeLabel امروز تومان",
            "قیمت ${p.name} $sizeLabel کیلوگرم",
            "${p.name} $sizeLabel قیمت روز بازار"
        )
        if (reading.size == null) queries.replaceAll { it.replace("  ", " ") }

        val notes = mutableListOf<String>()
        for (q in queries) {
            val report = tools.webSearch(q, "fa")
            notes += "«$q»: ${report.hits.size} نتیجه، موتورها ${report.perEngine.keys.joinToString("+").ifBlank { "هیچ" }}"
            val fromResults = bestFact(report.hits, reading, w)
            if (fromResults != null) {
                lastSummary = notes.joinToString("؛ ")
                return fromResults
            }
            // بریده‌ها قیمت نداشتند: دو صفحهٔ اول را بخوان (اگر دامنه‌اش قابل واکشی باشد).
            var fetched = 0
            for (hit in report.hits) {
                if (fetched >= MAX_PAGES) break
                val page = runCatching { tools.fetchPage(hit.url, "fa") }.getOrNull() ?: continue
                fetched++
                val pseudo = WebHit(hit.url, hit.title, page.text.take(4000), hit.engine, hit.rank)
                val fromPage = bestFact(listOf(pseudo), reading, w)
                if (fromPage != null) {
                    lastSummary = notes.joinToString("؛ ") + "؛ قیمت از متن ${hit.url.substringAfter("://").substringBefore('/')}"
                    return fromPage
                }
            }
        }
        lastSummary = notes.joinToString("؛ ")
        return null
    }

    private fun bestFact(hits: List<WebHit>, reading: Materials.Reading, w: SteelMath.Weight): PriceFact? =
        hits.asSequence()
            .flatMap { hit -> PriceReader.read(hit, reading, w, tools.now()).asSequence() }
            .maxByOrNull { it.first }
            ?.second

    // ------------------------------------------------------------------ دانش

    private fun keyOf(reading: Materials.Reading): String {
        val size = reading.size?.let { Materials.num(it) } ?: "?"
        return "${reading.product.kind.name}:$size"
    }

    private fun label(reading: Materials.Reading): String =
        reading.product.name + (reading.size?.let { " ${Materials.num(it)}" } ?: "")

    private fun topicOf(reading: Materials.Reading): String = "price:" + keyOf(reading)

    /** قیمت ذخیره‌شده برای همین کالا و همین اندازه (تازه یا کهنه — کهنگی در پاسخ گفته می‌شود). */
    fun storedFact(reading: Materials.Reading, now: Long): PriceFact? {
        val key = topicOf(reading)
        val item = tools.knowledgeAll(400)
            .filter { it.attribute == PRICE_ATTRIBUTE && it.topic == key && it.supersededById == null }
            .maxByOrNull { it.lastVerifiedAt }
            ?: return null
        val v = item.numericValue ?: return null
        return PriceFact(
            key = key,
            tomanPerKg = v,
            asStated = v,
            basis = PriceBasis.PER_KG,
            domain = item.domain.ifBlank { "دانش ذخیره‌شده" },
            url = item.url,
            at = if (item.lastVerifiedAt > 0) item.lastVerifiedAt else item.createdAt,
            evidence = item.claim
        )
    }

    private fun priceItem(reading: Materials.Reading, fact: PriceFact, now: Long): KnowledgeItem =
        KnowledgeItem(
            id = "k-" + ir.aai.hushyar.core.Similarity.fnv1a64(topicOf(reading) + "|" + fact.tomanPerKg.toLong()).toString(16),
            topic = topicOf(reading),
            question = "قیمت ${label(reading)}".take(300),
            claim = "قیمت هر کیلو ${label(reading)}: ${SteelMath.money(fact.tomanPerKg)} — ${fact.domain}".take(600),
            category = "قیمت",
            attribute = PRICE_ATTRIBUTE,
            numericValue = fact.tomanPerKg,
            unit = "تومان/کیلوگرم",
            url = fact.url,
            domain = fact.domain,
            confidence = 0.7,
            createdAt = now,
            lastVerifiedAt = fact.at,
            ttlDays = 3
        )

    private fun weightItem(reading: Materials.Reading, w: SteelMath.Weight, now: Long): KnowledgeItem =
        KnowledgeItem(
            id = "k-" + ir.aai.hushyar.core.Similarity.fnv1a64("weight|${keyOf(reading)}").toString(16),
            topic = "weight:" + keyOf(reading),
            question = "وزن ${label(reading)}".take(300),
            claim = "${label(reading)}: ${Materials.num(w.kgPerMetre!!)} کیلوگرم بر متر — ${w.provenance}".take(600),
            category = "مهندسی",
            attribute = WEIGHT_ATTRIBUTE,
            numericValue = w.kgPerMetre,
            unit = "کیلوگرم/متر",
            domain = "جدول استاندارد",
            confidence = if (w.exact) 0.95 else 0.75,
            createdAt = now,
            lastVerifiedAt = now,
            ttlDays = 3650
        )

    /** تاریخ خواندن قیمت، برای نمایش در کارت دانش. */
    fun stamp(at: Long): String = Jalali.format(at)
}

/**
 * استخراج قیمت از یک نتیجه یا متن صفحه.
 *
 * قاعده‌ها عمداً سخت‌گیرند: عدد باید یکای پول داشته باشد، پایهٔ قیمت (کیلو/متر/شاخه/برگ/تن)
 * از نزدیک‌ترین واژه‌ها خوانده شود، و بعد به «تومان بر کیلوگرم» تبدیل شود تا با ۲٫۰۰۰ تا
 * ۲٫۰۰۰٫۰۰۰ تومان بازهٔ قیمت فولاد مقایسه شود. عددی که این آزمون را رد نکند، قیمت نیست.
 */
object PriceReader {

    private val MONEY = Regex(
        "([0-9][0-9,\\.\u066C\u060C\\s]{2,15})\\s*(هزار|میلیون|میلیارد)?\\s*(تومان|تومن|ریال|toman|rial)"
    )

    fun read(hit: WebHit, reading: Materials.Reading, w: SteelMath.Weight, now: Long): List<Pair<Double, PriceFact>> {
        val text = Text.normalize("${hit.title} ${hit.snippet} ${hit.snippet}".take(6000))
        if (text.isBlank()) return emptyList()
        val domain = hit.url.substringAfter("://").substringBefore('/')
        val out = mutableListOf<Pair<Double, PriceFact>>()

        for (m in MONEY.findAll(text)) {
            val raw = m.groupValues[1]
            val scale = when (m.groupValues[2]) {
                "هزار" -> 1_000.0
                "میلیون" -> 1_000_000.0
                "میلیارد" -> 1_000_000_000.0
                else -> 1.0
            }
            val unit = m.groupValues[3]
            var value = raw.replace(" ", "").replace("\u066C", "").replace("\u060C", "").replace(",", "").toDoubleOrNull()
                ?: continue
            value *= scale
            if (unit == "ریال" || unit == "rial") value /= 10.0   // ریال → تومان
            if (value < 1_000) continue

            val basis = basisNear(text, m.range.first)
            val perKg = SteelMath.perKgFrom(basis, value, reading, w)?.value ?: continue
            if (!SteelMath.plausiblePricePerKg(perKg)) continue
            // اندازه باید در متن باشد؛ وگرنه ممکن است قیمت سایز دیگری باشد.
            // (نرمال‌سازی ارقام فارسی به لاتین شده است، پس مقایسه با رقم لاتین کافی است.)
            val sizeMentioned = reading.size?.let { size ->
                val mm = size.toLong().toString()
                val cm = (size * 10).toLong().toString()
                text.contains(mm) || text.contains(cm)
            } ?: true
            if (!sizeMentioned) continue

            val confidence = 0.6 + (if (basis == PriceBasis.PER_KG) 0.25 else 0.05) +
                (if (unit == "تومان" || unit == "toman") 0.05 else 0.0)
            out += confidence to PriceFact(
                key = reading.product.kind.name,
                tomanPerKg = perKg,
                asStated = value,
                basis = basis,
                domain = domain,
                url = hit.url,
                at = now,
                evidence = text.substring((m.range.first - 60).coerceAtLeast(0), (m.range.last + 60).coerceAtMost(text.length)).trim()
            )
        }
        return out
    }

    private fun basisNear(text: String, index: Int): PriceBasis {
        val from = (index - 40).coerceAtLeast(0)
        val to = (index + 40).coerceAtMost(text.length)
        val window = text.substring(from, to)
        return when {
            window.contains("کیلو") || window.contains("kg") -> PriceBasis.PER_KG
            window.contains("تن") -> PriceBasis.PER_TON
            window.contains("شاخه") -> PriceBasis.PER_BRANCH
            window.contains("برگ") -> PriceBasis.PER_SHEET
            window.contains("مربع") -> PriceBasis.PER_SQUARE
            window.contains("متر") -> PriceBasis.PER_METRE
            else -> PriceBasis.PER_KG     // بازار فولاد ایران پیش‌فرض کیلو است
        }
    }
}
