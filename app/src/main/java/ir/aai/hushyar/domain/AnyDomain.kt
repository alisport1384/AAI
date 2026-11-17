package ir.aai.hushyar.domain

import ir.aai.hushyar.core.Jalali
import ir.aai.hushyar.core.Similarity
import ir.aai.hushyar.core.Text

/**
 * v0.11 — «چیزهایی که در مورد ورق و تیرآهن گفتم فقط مثال بود!»
 *
 * درست است. v0.10 برای فلزات جدول و فرمول داشت و همین، مرزِ دانشِ برنامه را می‌ساخت. این فایل آن
 * مرز را برمی‌دارد و چهار چیز عمومی می‌سازد که برای تی‌شرت، شلوار، سیمان، فرش یا هر چیز دیگری
 * یکی است:
 *
 * 1. **[Units]** — یکای عمومی: عدد/دوجین/بسته، گرم/کیلو/تن، میلی‌متر/متر، مترمربع، تومان/ریال،
 *    درصد. هر تبدیلی با ضریب انجام می‌شود، پس «قیمت دوجین» از «قیمت عدد» ساخته می‌شود.
 * 2. **[Frame]** — چارچوب پرسش برای *هر* کالا: کالا چیست، چه ویژگی‌هایی خواسته شده، چه چیزی
 *    پرسیده شده (قیمت/وزن/موجودی/اندازه/تعریف)، قیمت به چه پایه‌ای، چه مقدار.
 * 3. **[Facts]** — دانشِ نوع‌دار: `کالا + ویژگی + مقدار + یکا + منبع + تاریخ`. همه‌چیز در همان
 *    KnowledgeStore موجود ذخیره می‌شود، پس بین نسخه‌ها می‌ماند و در پشتیبان می‌آید.
 * 4. **[AnyDomainBrain]** — مسیر: اول دانش خودم، بعد جستجو، بعد استخراج (کد + مدل محلی)، بعد
 *    محاسبه و تبدیل یکا، بعد ذخیره. هر عدد در پاسخ یا از دانش است یا حاصل محاسبه‌ای که چاپ می‌شود.
 *
 * حیلهٔ کار این است: چیزی که به دستهٔ کالا وابسته است، **داده** است ([DomainPack])، نه کد.
 */

// ---------------------------------------------------------------------------- یکاها

enum class Dim { COUNT, MASS, LENGTH, AREA, VOLUME, MONEY, PERCENT }

data class UnitDef(
    val id: String,
    val fa: String,
    val dim: Dim,
    /** ضریب به یکای پایه: عدد، گرم، میلی‌متر، میلی‌متر مربع، میلی‌لیتر، تومان، درصد. */
    val toBase: Double,
    val aliases: List<String> = emptyList(),
    /** «بسته» و «کارتن» اندازه‌شان را فقط از داده می‌دانیم، نه از استاندارد. */
    val learnedSize: Boolean = false
)

object Units {

    val all: List<UnitDef> = listOf(
        UnitDef("count", "عدد", Dim.COUNT, 1.0, listOf("عدد", "دانه", "تا", "عددی", "piece", "pcs", "pcs.")),
        UnitDef("pair", "جفت", Dim.COUNT, 2.0, listOf("جفت", "pair")),
        UnitDef("dozen", "دوجین", Dim.COUNT, 12.0, listOf("دوجین", "dozen")),
        UnitDef("pack", "بسته", Dim.COUNT, 1.0, listOf("بسته", "پک", "pack"), learnedSize = true),
        UnitDef("carton", "کارتن", Dim.COUNT, 1.0, listOf("کارتن", "carton", "box"), learnedSize = true),
        UnitDef("g", "گرم", Dim.MASS, 1.0, listOf("گرم", "g", "gr", "gram")),
        UnitDef("kg", "کیلوگرم", Dim.MASS, 1000.0, listOf("کیلوگرم", "کیلو", "كيلو", "kg", "kilo")),
        UnitDef("ton", "تن", Dim.MASS, 1_000_000.0, listOf("تن", "ton", "tonne")),
        UnitDef("mm", "میلی‌متر", Dim.LENGTH, 1.0, listOf("میلی‌متر", "میلی متر", "میلی", "مم", "mm", "میل")),
        UnitDef("cm", "سانتی‌متر", Dim.LENGTH, 10.0, listOf("سانتی‌متر", "سانتی متر", "سانت", "cm")),
        UnitDef("m", "متر", Dim.LENGTH, 1000.0, listOf("متر", "متری", "metre", "meter", "m")),
        UnitDef("cm2", "سانتی‌متر مربع", Dim.AREA, 100.0, listOf("سانتی‌متر مربع", "cm2")),
        UnitDef("m2", "مترمربع", Dim.AREA, 1_000_000.0, listOf("مترمربع", "متر مربع", "مربع", "m2", "sqm")),
        UnitDef("ml", "میلی‌لیتر", Dim.VOLUME, 1.0, listOf("میلی‌لیتر", "ml")),
        UnitDef("l", "لیتر", Dim.VOLUME, 1000.0, listOf("لیتر", "liter", "litre", "l")),
        UnitDef("toman", "تومان", Dim.MONEY, 1.0, listOf("تومان", "تومن", "toman")),
        UnitDef("rial", "ریال", Dim.MONEY, 0.1, listOf("ریال", "rial")),
        UnitDef("percent", "درصد", Dim.PERCENT, 1.0, listOf("درصد", "٪", "%", "percent"))
    )

    private val byId: Map<String, UnitDef> = all.associateBy { it.id }

    /** مترادف‌ها از بلندترین به کوتاه‌ترین، تا «کیلوگرم» پیش از «کیلو» خوانده شود. */
    private val aliases: List<Pair<String, UnitDef>> = all
        .flatMap { u -> u.aliases.map { it to u } }
        .sortedByDescending { it.first.length }

    fun of(id: String): UnitDef? = byId[id]

    fun byAlias(word: String): UnitDef? = aliases.firstOrNull { it.first == word }?.second

    /** یکا از انتهای متن: «کیلوگرم» در «قیمت هر کیلوگرم» و «مترمربع» در «هر مترمربع». */
    fun detect(normalized: String): UnitDef? =
        aliases.firstOrNull { normalized.contains(it.first) }?.second

    fun convert(value: Double, from: String, to: String): Double? {
        val a = byId[from] ?: return null
        val b = byId[to] ?: return null
        if (a.dim != b.dim) return null
        // «بسته» و «کارتن» اندازهٔ استاندارد ندارند؛ تبدیلشان فقط با دانشِ آموخته‌شده انجام می‌شود.
        if (a.learnedSize || b.learnedSize) return null
        return value * a.toBase / b.toBase
    }

    /** «۴۲ هزار تومان» → ۴۲۰۰۰ تومان. عدد و یکا با هم خوانده می‌شوند. */
    val NUMBER_UNIT = Regex(
        "([0-9][0-9,.\u066C\u060C\\s]{0,14})\\s*(هزار|میلیون|میلیارد)?\\s*" +
            "(تومان|تومن|ریال|کیلوگرم|کیلو|گرم|تن|مترمربع|متر مربع|متر|سانتی‌متر|سانت|میلی‌متر|میل|عدد|دانه|دوجین|بسته|کارتن|لیتر|درصد)"
    )

    fun amount(text: String, unit: UnitDef): Double? {
        val m = NUMBER_UNIT.find(text) ?: return null
        val digits = m.groupValues[1].replace(" ", "").replace("\u066C", "").replace("\u060C", "")
            .replace(",", "").toDoubleOrNull() ?: return null
        val scale = when (m.groupValues[2]) {
            "هزار" -> 1_000.0
            "میلیون" -> 1_000_000.0
            "میلیارد" -> 1_000_000_000.0
            else -> 1.0
        }
        val unitWord = m.groupValues[3]
        val found = byAlias(unitWord) ?: byAlias(unitWord.replace(" ", "")) ?: return null
        if (found.dim != unit.dim) return null
        return convert(digits * scale, found.id, unit.id)
    }

    fun label(id: String?): String = id?.let { byId[it]?.fa } ?: "?"
}

/** یک نرخ: «۴۲٬۰۰۰ تومان بر کیلوگرم». پایهٔ نرخ خودش یک یکاست. */
data class Rate(val value: Double, val unit: String, val per: String?) {
    fun label(): String = Units.label(unit) + (per?.let { " بر " + Units.label(it) } ?: "")
}

// ---------------------------------------------------------------------------- پرسش

enum class Ask(val fa: String, val attribute: String) {
    PRICE("قیمت", "price"),
    WEIGHT("وزن", "weight"),
    SIZE("اندازه", "size"),
    AVAILABILITY("موجودی", "stock"),
    MATERIAL("جنس", "material"),
    DEFINITION("تعریف", "definition"),
    OTHER("نامشخص", "other")
}

data class Qualifier(val name: String, val value: String, val fa: String)

/**
 * چارچوب عمومی پرسش. همان چهار سؤالی که کاربر گفت برنامه باید خودش بپرسد، این بار برای هر کالایی:
 * «این چیست؟»، «این ویژگی یعنی چه؟»، «چرا این عدد؟»، «قیمت به چه؟»
 */
data class Frame(
    val text: String,
    val entity: String,
    val entityLabel: String,
    /** true = کالا از یک بستهٔ دانش یا از دانشِ آموخته‌شده شناخته شد (نه حدس از متن). */
    val entityKnown: Boolean = false,
    val qualifiers: List<Qualifier>,
    val ask: Ask,
    val basis: String?,
    val quantity: Pair<Double, String>?,
    /** آنچه از پرسش فهمیدیم — خط‌به‌خط، برای نمایش. */
    val lines: List<String>,
    /** ویژگی‌هایی که برای پاسخ لازم‌اند و در پرسش نبودند. */
    val gaps: List<String>
) {
    val qualifierText: String get() = qualifiers.joinToString(" ") { it.fa }
    fun label(): String = (entityLabel + " " + qualifierText).trim()
}

object FrameParser {

    private val PRICE_WORDS = listOf("قیمت", "قیمتش", "نرخ", "بها", "چنده", "چند شد", "تومان", "تومن", "price", "cost")
    private val WEIGHT_WORDS = listOf("وزن", "سنگینی", "چند کیلو", "گرم است", "weight")
    private val SIZE_WORDS = listOf("سایز", "اندازه", "چه سایزی", "size")
    private val STOCK_WORDS = listOf("موجود", "هست", "دارید", "دارین", "انبار", "stock", "available")
    private val MATERIAL_WORDS = listOf("جنس", "جنسش", "چه جنسی", "الیاف", "material", "fabric")
    private val DEF_WORDS = listOf("چیه", "چیست", "یعنی چی", "یعنی چه", "چی هست", "تعریف", "what is")

    private val BASIS_RE = Regex("هر\\s*(کیلوگرم|کیلو|متر|مترمربع|عدد|دانه|دوجین|بسته|کارتن|تن|گرم)")

    /** یکاهای شمارشی‌ای که آمدنشان در پرسش، پایهٔ قیمت را می‌سازد. */
    private val COUNTING_UNITS = listOf("دوجین", "بسته", "کارتن", "جفت")
    private val SIZE_TOKENS = listOf("xxl", "xl", "xs", "s", "m", "l", "3xl", "2xl")

    /**
     * کالا را از متن پیدا می‌کند: اول از بستهٔ دانش ([DomainPack]) — که خودش می‌تواند از حرف‌های
     * خودِ کاربر ساخته شده باشد — و اگر نبود، مهم‌ترین اسمِ متن را برمی‌دارد. نام‌های آموخته‌شده
     * ([knownEntities]) هم امتیاز بیشتری می‌گیرند تا سؤال دومِ همان بازار درست خوانده شود.
     */
    fun parse(
        question: String,
        packs: List<DomainPack> = DomainPacks.all,
        knownEntities: List<String> = emptyList()
    ): Frame? {
        val norm = Text.normalize(question)
        if (norm.isBlank()) return null

        var entity = ""
        var entityLabel = ""
        var best = 0
        var known = false
        for (pack in packs) {
            for (e in pack.entities) {
                for (alias in (e.aliases + e.label)) {
                    val a = Text.normalize(alias)
                    if (a.length > best && norm.contains(a)) {
                        best = a.length
                        entity = e.id
                        entityLabel = e.label
                        known = true
                    }
                }
            }
        }
        for (seen in knownEntities) {
            val k = Text.normalize(seen)
            if (k.length >= 3 && norm.contains(k) && k.length > best) {
                best = k.length
                entity = k
                entityLabel = seen
                known = true
            }
        }
        if (entity.isEmpty()) {
            // هیچ بستهٔ دانشی این کالا را نمی‌شناسد: مهم‌ترین واژهٔ محتوایی را برمی‌داریم، ولی با
            // امتیاز کم — کافی است تا بپرسیم «منظورت همین است؟» و شروع به یادگیری کنیم.
            val token = Text.contentTokens(norm).maxByOrNull { it.length } ?: return null
            if (token.length < 3) return null
            entity = token
            entityLabel = token
            best = 1
            known = false
        }

        val qualifiers = mutableListOf<Qualifier>()
        // ویژگی‌های عددی با یکا: ضخامت، وزن، اندازه…
        Units.NUMBER_UNIT.findAll(norm).take(4).forEach { m ->
            val unitWord = m.groupValues[3]
            val u = Units.byAlias(unitWord) ?: return@forEach
            val num = m.groupValues[1].replace(" ", "").replace("\u066C", "").replace(",", "")
                .toDoubleOrNull() ?: return@forEach
            val scale = when (m.groupValues[2]) {
                "هزار" -> 1_000.0
                "میلیون" -> 1_000_000.0
                "میلیارد" -> 1_000_000_000.0
                else -> 1.0
            }
            qualifiers += Qualifier("amount", "${num * scale}", "${Materials.num(num * scale)} ${u.fa}")
        }
        // سایزهای حرفی و عددیِ بسته‌ها
        for (pack in packs) {
            val e = pack.entities.firstOrNull { it.id == entity } ?: continue
            for (attr in e.attributes) {
                if (attr.kind != AttrKind.ENUM) continue
                for (value in attr.values) {
                    val v = Text.normalize(value)
                    if (v.length >= 1 && Regex("(^|\\s)${Regex.escape(v)}(\\s|$)").containsMatchIn(norm)) {
                        qualifiers += Qualifier(attr.id, value, "${attr.label}: $value")
                    }
                }
            }
        }
        // اعداد بدون یکا که به سایز می‌خورند (تی‌شرت «L» و شلوار «۳۲»)
        Regex("(?<![0-9])([0-9]{1,2})(?![0-9])").findAll(norm).forEach { m ->
            val n = m.value.toIntOrNull() ?: return@forEach
            if (n in 20..46 && qualifiers.none { it.name == "size" }) {
                qualifiers += Qualifier("size", m.value, "سایز: ${Materials.num(n.toDouble())}")
            }
        }

        val ask = when {
            PRICE_WORDS.any { norm.contains(it) } -> Ask.PRICE
            WEIGHT_WORDS.any { norm.contains(it) } -> Ask.WEIGHT
            SIZE_WORDS.any { norm.contains(it) } -> Ask.SIZE
            MATERIAL_WORDS.any { norm.contains(it) } -> Ask.MATERIAL
            STOCK_WORDS.any { norm.contains(it) } -> Ask.AVAILABILITY
            DEF_WORDS.any { norm.contains(it) } -> Ask.DEFINITION
            else -> Ask.OTHER
        }

        val basisMatch = BASIS_RE.find(norm)
        // «قیمت تی‌شرت دوجین» هم پایه دارد: یکای شمارشیِ ایستاده، بدون «هر».
        val looseBasis = COUNTING_UNITS.firstOrNull { word -> norm.contains(word) }
            ?.let { Units.byAlias(it)?.id }
        val basis = basisMatch?.let { Units.byAlias(it.groupValues[1])?.id } ?: looseBasis
        val quantity = quantityOf(norm)

        val lines = mutableListOf<String>()
        lines += "کالا: $entityLabel"
        if (qualifiers.isNotEmpty()) lines += "ویژگی‌ها: " + qualifiers.joinToString("، ") { it.fa }
        lines += "خواسته: ${ask.fa}"
        if (basis != null) lines += "پایه: هر ${Units.label(basis)}"
        if (quantity != null) lines += "مقدار: ${Materials.num(quantity.first)} ${Units.label(quantity.second)}"

        val gaps = mutableListOf<String>()
        if (ask == Ask.PRICE && basis == null) gaps += "پایهٔ قیمت (هر عدد؟ هر کیلو؟ هر دوجین؟)"
        if (qualifiers.isEmpty() && ask == Ask.PRICE) gaps += "کدام مدل/سایز/جنس"
        if (ask == Ask.OTHER) gaps += "این‌که دقیقاً چه چیزی می‌خواهی (قیمت، وزن، موجودی، اندازه)"

        return Frame(
            text = norm,
            entity = entity,
            entityLabel = entityLabel,
            entityKnown = known,
            qualifiers = qualifiers,
            ask = ask,
            basis = basis,
            quantity = quantity,
            lines = lines,
            gaps = gaps
        )
    }

    private fun quantityOf(norm: String): Pair<Double, String>? {
        val m = Units.NUMBER_UNIT.find(norm) ?: return null
        val u = Units.byAlias(m.groupValues[3]) ?: return null
        val n = m.groupValues[1].replace(" ", "").replace("\u066C", "").toDoubleOrNull() ?: return null
        val minimum = when (u.dim) {
            Dim.MONEY, Dim.MASS -> 100.0
            else -> 3.0
        }
        return if (n >= minimum) n to u.id else null
    }

    /** سایزهای حرفی را جدا می‌خوانیم چون «M» و «L» تک‌حرفی‌اند و با مرز واژه لازم‌اند. */
    fun sizeTokens(norm: String): List<String> = SIZE_TOKENS.filter { token ->
        Regex("(^|[\\s/،,])${Regex.escape(token)}($|[\\s/،,])", RegexOption.IGNORE_CASE).containsMatchIn(norm)
    }
}

// ---------------------------------------------------------------------------- دانش نوع‌دار

data class DomainFact(
    val entity: String,
    val attribute: String,
    val qualifier: String = "",
    val value: Double,
    /** یکای مقدار به شکل canonical ([Units]). */
    val unit: String,
    /** پایهٔ نرخ، برای ویژگی «قیمت». */
    val per: String? = null,
    /** متن اصلی منبع؛ برای نمایش و بازبینی. */
    val evidence: String = "",
    val domain: String = "",
    val url: String = "",
    val at: Long = 0L,
    val confidence: Double = 0.6
) {
    fun fresh(now: Long, ttlDays: Int): Boolean = now - at <= ttlDays.toLong() * 86_400_000L
    fun label(): String = "${Materials.num(value)} ${Units.label(unit)}" + (per?.let { " بر ${Units.label(it)}" } ?: "")
}

object Facts {

    const val PREFIX = "fact:"
    const val ATTRIBUTE_PREFIX = "fact_"
    const val PRICE_TTL_DAYS = 3

    fun topic(entity: String, attribute: String): String = "$PREFIX$entity|$attribute"

    fun toItem(fact: DomainFact, now: Long): KnowledgeItem {
        val claim = buildString {
            append(entityLabel(fact.entity)).append(" — ").append(fact.attributeLabel()).append(": ")
            append(fact.label())
            if (fact.qualifier.isNotBlank()) append(" (").append(fact.qualifier).append(")")
            if (fact.domain.isNotBlank()) append(" — ").append(fact.domain)
        }
        return KnowledgeItem(
            id = "k-" + Similarity.fnv1a64(topic(fact.entity, fact.attribute) + "|" + fact.value.toLong()).toString(16),
            topic = topic(fact.entity, fact.attribute),
            question = fact.qualifier.take(200),
            claim = claim.take(600),
            category = fact.attributeLabel(),
            attribute = ATTRIBUTE_PREFIX + fact.attribute,
            numericValue = fact.value,
            unit = fact.unit + (fact.per?.let { "/$it" } ?: ""),
            url = fact.url,
            domain = fact.domain,
            confidence = fact.confidence,
            createdAt = now,
            lastVerifiedAt = if (fact.at > 0) fact.at else now,
            ttlDays = if (fact.attribute == "price") PRICE_TTL_DAYS else 365
        )
    }

    fun from(item: KnowledgeItem): DomainFact? {
        val attribute = item.attribute ?: return null
        if (!attribute.startsWith(ATTRIBUTE_PREFIX)) return null
        val v = item.numericValue ?: return null
        val unitParts = (item.unit ?: "").split("/")
        return DomainFact(
            entity = item.topic.removePrefix(PREFIX).substringBefore('|'),
            attribute = attribute.removePrefix(ATTRIBUTE_PREFIX),
            qualifier = item.question,
            value = v,
            unit = unitParts.firstOrNull().orEmpty(),
            per = unitParts.getOrNull(1),
            evidence = item.claim,
            domain = item.domain,
            url = item.url,
            at = if (item.lastVerifiedAt > 0) item.lastVerifiedAt else item.createdAt,
            confidence = item.confidence
        )
    }

    fun find(tools: AgentTools, entity: String, attribute: String): List<DomainFact> =
        tools.knowledgeAll(600)
            .filter { it.attribute == ATTRIBUTE_PREFIX + attribute && it.topic == topic(entity, attribute) }
            .mapNotNull { from(it) }
            .sortedByDescending { it.at }

    fun save(tools: AgentTools, fact: DomainFact, now: Long): KnowledgeResult =
        tools.knowledgeStore(toItem(fact.copy(at = if (fact.at > 0) fact.at else now), now))

    fun entityLabel(entity: String): String = entity

    fun attributeLabel(attribute: String): String = when (attribute) {
        "price" -> "قیمت"
        "weight" -> "وزن"
        "size" -> "اندازه"
        "stock" -> "موجودی"
        "material" -> "جنس"
        "per_pack" -> "تعداد در بسته"
        else -> attribute
    }

    private fun DomainFact.attributeLabel(): String = attributeLabel(attribute)
}

// ---------------------------------------------------------------------------- بستهٔ دانش

enum class AttrKind { NUMBER, ENUM, TEXT }

data class AttrDef(
    val id: String,
    val label: String,
    val kind: AttrKind = AttrKind.TEXT,
    val unit: String? = null,
    val values: List<String> = emptyList(),
    val synonyms: List<String> = emptyList()
)

data class EntityDef(
    val id: String,
    val label: String,
    val aliases: List<String> = emptyList(),
    val attributes: List<AttrDef> = emptyList(),
    val whatItIs: String = "",
    /** یکای فروش رایج در همین بازار؛ برای تبدیل «قیمت دوجین» به «قیمت عدد». */
    val sellUnits: List<String> = emptyList()
)

/** یک بستهٔ دانش، فقط داده. کاربر (یا مدل) می‌تواند بستهٔ جدید برای بازار خودش بسازد. */
data class DomainPack(
    val id: String,
    val name: String,
    val entities: List<EntityDef>,
    val attributes: List<AttrDef> = emptyList(),
    val sources: List<String> = emptyList(),
    val note: String = ""
) {
    companion object {
        const val SCHEMA = "hushyar.domain/1"
    }
}
