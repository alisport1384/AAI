package ir.aai.hushyar.domain

import ir.aai.hushyar.core.Text

/**
 * v0.10 — «ورق ۲ میل قیمت» هنوز یک پرسش کامل نیست، ولی یک پرسش *فهمیدنی* است.
 *
 * کاربر نباید برای هر سؤال بنویسد «تحقیق کن». پس این فایل سه کار می‌کند:
 *
 * 1. **معنی کلمه‌ها را می‌داند**: «ورق» در این حوزه یعنی ورق فولادی/آلیاژی صنعتی (نه ورق کاغذ)،
 *    «میل» در بافت ابعاد یعنی میلی‌متر، و «۲» ضخامت است نه تعداد.
 * 2. **جدول‌های استاندارد را با خود دارد** (بدون شبکه): وزن هر متر تیرآهن IPE، فرمول وزن
 *    میلگرد، وزن هر مترمربع ورق، و فرمول‌های لوله/نبشی/قوطی. این‌ها عددهای ثابت مهندسی‌اند،
 *    نه حدس — و منبعشان در [Provenance] نوشته شده.
 * 3. **پرسش را به یک ساختار تبدیل می‌کند** ([Reading]): کالا، اندازه، خواسته (قیمت/وزن/تعریف)،
 *    پایهٔ قیمت (کیلو/متر/شاخه/برگ/تن) و مقدار. هر چه را که *نداند* هم صریح می‌نویسد تا
 *    [MaterialBrain] بتواند یا محاسبه کند یا صادقانه کمبود را اعلام کند.
 *
 * هیچ عددی اینجا از مدل زبانی نمی‌آید؛ همه یا جدول استاندارد است یا فرمول هندسی.
 */

/** خانوادهٔ کالاهایی که این نسخه واقعاً می‌شناسد. */
enum class ProductKind(val fa: String) {
    SHEET("ورق"),
    BEAM("تیرآهن"),
    REBAR("میلگرد"),
    ANGLE("نبشی"),
    CHANNEL("ناودانی"),
    PIPE("لوله"),
    BOX("قوطی و پروفیل"),
    UNKNOWN("کالا")
}

/** چه چیزی پرسیده شده. */
enum class MaterialAsk { PRICE, WEIGHT, DEFINITION, HOW_TO_CALC, OTHER }

/**
 * پایهٔ قیمت. پرسش «قیمت ورق ۲ میل» پایه ندارد — و همین جایی است که کاربر می‌گوید
 * «قیمت به چی؟». پاسخ باید هر سه پایه را بدهد، نه یکی را به شانس.
 */
enum class PriceBasis(val fa: String, val short: String) {
    PER_KG("کیلوگرم", "تومان/کیلوگرم"),
    PER_METRE("متر", "تومان/متر"),
    PER_BRANCH("شاخه", "تومان/شاخه"),
    PER_SHEET("برگ", "تومان/برگ"),
    PER_SQUARE("مترمربع", "تومان/مترمربع"),
    PER_TON("تن", "تومان/تن")
}

/** منبع هر عدد ثابت، تا کاربر بداند این «دانش» از کجا آمده. */
object Provenance {
    const val IPE = "جدول استاندارد IPE (EN 10365)"
    const val REBAR = "فرمول استاندارد میلگرد: وزن هر متر = قطر² ÷ ۱۶۲"
    const val SHEET = "چگالی فولاد ۷٫۸۵ گرم بر سانتی‌متر مکعب → وزن هر مترمربع = ۷٫۸۵ × ضخامت(میلی‌متر)"
    const val PIPE = "فرمول لوله: وزن هر متر = π × ضخامت × (قطر − ضخامت) × ۷٫۸۵ ÷ ۱۰۰۰"
    const val BOX = "فرمول قوطی: وزن هر متر = ۲ × (عرض + ارتفاع − ۲×ضخامت) × ضخامت × ۷٫۸۵ ÷ ۱۰۰۰"
    const val ANGLE = "فرمول نبشی: وزن هر متر ≈ (۲×بال − ضخامت) × ضخامت × ۷٫۸۵ ÷ ۱۰۰۰ (تقریبی، ±۵٪)"
    const val CHANNEL = "جدول ناودانی UNP؛ ناودانی ایرانی معمولاً سبک‌تر است"
}

/**
 * یک خانوادهٔ کالا: اسمش، مترادف‌هایش، معنی‌اش، جنس‌هایش، واحد اندازه‌گیری‌اش و
 * راه رسیدن به وزن هر متر.
 */
data class ProductDef(
    val kind: ProductKind,
    val name: String,
    /** همه در شکل نرمال‌شدهٔ [Text.normalize] نوشته می‌شوند (آ → ا). */
    val aliases: List<String>,
    val whatItIs: String,
    val grades: List<String>,
    /** اندازه چه چیزی را می‌سنجد: «ضخامت»، «ارتفاع شاخه»، «قطر» … */
    val measure: String,
    val measureUnit: String,
    /** یکای اندازه در پرسش: mm برای ورق/میلگرد/لوله، cm برای تیرآهن/ناودانی. */
    val measureInCm: Boolean = false,
    val standardLengthM: Double = 12.0,
    /** برای ورق: مساحت یک برگ استاندارد. */
    val sheetAreaM2: Double = 2.0,
    val sheetLabel: String = "۱۰۰۰×۲۰۰۰ میلی‌متر (۲ مترمربع)",
    /** جدول «اندازه → کیلوگرم بر متر» برای کالاهایی که جدول دقیق دارند. */
    val table: Map<Double, Double> = emptyMap(),
    val tableNote: String = ""
)

object Materials {

    private const val STEEL_DENSITY = 7.85

    val all: List<ProductDef> = listOf(
        ProductDef(
            kind = ProductKind.SHEET,
            name = "ورق",
            aliases = listOf("ورق", "ورق فولادی", "ورق اهن", "ورق سیاه", "ورق روغنی", "ورق گالوانیزه", "sheet"),
            whatItIs = "ورق، فولاد یا آلیاژ نوردشده به شکل صفحهٔ نازک است؛ در بازار ایران منظور «ورق فولادی صنعتی» است (سیاه، روغنی، گالوانیزه، آجدار، استنلس) نه ورق کاغذ یا کارتن.",
            grades = listOf("ST37 (A283)", "ST52", "A36", "ورق آجدار A283", "گالوانیزه", "استنلس ۳۰۴/۳۱۶"),
            measure = "ضخامت",
            measureUnit = "میلی‌متر",
            standardLengthM = 2.0,
            sheetAreaM2 = 2.0,
            sheetLabel = "۱۰۰۰×۲۰۰۰ میلی‌متر (۲ مترمربع)"
        ),
        ProductDef(
            kind = ProductKind.BEAM,
            name = "تیرآهن",
            aliases = listOf("تیراهن", "تیر آهن", "تیراهن ipe", "ibeam", "i-beam", "beam"),
            whatItIs = "تیرآهن پروفیل فولادی I شکل است که در ساختمان برای تیر و ستون به کار می‌رود؛ شمارهٔ آن تقریباً ارتفاع جان به سانتی‌متر است (تیرآهن ۱۸ ≈ ارتفاع ۱۸۰ میلی‌متر).",
            grades = listOf("ST37", "ST44", "ST52", "A36"),
            measure = "شماره (ارتفاع شاخه)",
            measureUnit = "سانتی‌متر",
            measureInCm = true,
            standardLengthM = 12.0,
            // IPE — EN 10365. این‌ها جدول دقیق‌اند، نه تخمین.
            table = mapOf(
                10.0 to 8.1, 12.0 to 10.4, 14.0 to 12.9, 16.0 to 15.8, 18.0 to 18.8,
                20.0 to 22.4, 22.0 to 26.2, 24.0 to 30.7, 27.0 to 36.1, 30.0 to 42.2,
                33.0 to 49.1, 36.0 to 57.1
            ),
            tableNote = "شماره‌های ۱۴ تا ۳۰ پرکاربردترین‌اند؛ شاخهٔ استاندارد ۱۲ متر است."
        ),
        ProductDef(
            kind = ProductKind.REBAR,
            name = "میلگرد",
            aliases = listOf("میلگرد", "ميلگرد", "میل گرد", "rebar", "اژدر"),
            whatItIs = "میلگرد (آرماتور) فولاد آجدار یا سادهٔ گِرد است که در بتن برای تحمل کشش می‌رود؛ شمارهٔ آن قطر به میلی‌متر است.",
            grades = listOf("A1 (ساده)", "A2", "A3", "A4"),
            measure = "قطر",
            measureUnit = "میلی‌متر",
            standardLengthM = 12.0,
            tableNote = "وزن هر متر با فرمول قطر²÷۱۶۲ حساب می‌شود، پس هر قطری پشتیبانی می‌شود."
        ),
        ProductDef(
            kind = ProductKind.ANGLE,
            name = "نبشی",
            aliases = listOf("نبشی", "نبشى", "angle"),
            whatItIs = "نبشی پروفیل فولادی L شکل است و برای اتصال، مهاربند و ساخت قاب به کار می‌رود؛ اندازه‌اش «بال × ضخامت» به میلی‌متر است.",
            grades = listOf("ST37", "ST52"),
            measure = "بال",
            measureUnit = "میلی‌متر",
            standardLengthM = 6.0,
            tableNote = "وزن هر متر با فرمول تقریبی حساب می‌شود؛ عدد دقیق به استاندارد سازنده بستگی دارد."
        ),
        ProductDef(
            kind = ProductKind.CHANNEL,
            name = "ناودانی",
            aliases = listOf("ناودانی", "نادوانی", "channel", "unp"),
            whatItIs = "ناودانی پروفیل فولادی U شکل است که در تیرچه، قاب و سازهٔ سبک به کار می‌رود؛ شماره‌اش ارتفاع به سانتی‌متر است.",
            grades = listOf("ST37", "ST52"),
            measure = "شماره (ارتفاع)",
            measureUnit = "سانتی‌متر",
            measureInCm = true,
            standardLengthM = 6.0,
            table = mapOf(
                6.0 to 6.0, 8.0 to 8.64, 10.0 to 10.6, 12.0 to 13.4, 14.0 to 16.0,
                16.0 to 18.8, 18.0 to 22.0, 20.0 to 25.3
            ),
            tableNote = "اعداد جدول UNP استاندارد است؛ ناودانی ایرانی سبک‌تر درمی‌آید."
        ),
        ProductDef(
            kind = ProductKind.PIPE,
            name = "لوله",
            aliases = listOf("لوله", "لوله فولادی", "pipe", "لوله مانیسمان", "لوله سیاه"),
            whatItIs = "لولهٔ فولادی استوانه‌ای است که اندازه‌اش «قطر بیرونی × ضخامت» به میلی‌متر است و برای سیالات، سازه و داربست به کار می‌رود.",
            grades = listOf("API 5L", "ST37", "گالوانیزه", "مانیسمان"),
            measure = "قطر بیرونی",
            measureUnit = "میلی‌متر",
            standardLengthM = 6.0,
            tableNote = "وزن هر متر با فرمول استوانه حساب می‌شود."
        ),
        ProductDef(
            kind = ProductKind.BOX,
            name = "قوطی",
            aliases = listOf("قوطی", "پروفیل", "قوطی پروفیل", "box", "پروفیل مبلی"),
            whatItIs = "قوطی و پروفیل، مقطع فولادی بسته (مربع یا مستطیل) است؛ اندازه‌اش «عرض × ارتفاع × ضخامت» به میلی‌متر است.",
            grades = listOf("ST37", "گالوانیزه", "روغنی"),
            measure = "بعد",
            measureUnit = "میلی‌متر",
            standardLengthM = 6.0,
            tableNote = "وزن هر متر با فرمول محیط مقطع حساب می‌شود."
        )
    )

    fun byKind(kind: ProductKind): ProductDef? = all.firstOrNull { it.kind == kind }

    /** کالای حاضر در متن؛ بلندترین مترادف برنده است تا «میلگرد» با «میل» اشتباه نشود. */
    fun detect(normalized: String): ProductDef? {
        var best: ProductDef? = null
        var bestLen = -1
        for (p in all) {
            for (alias in p.aliases) {
                if (alias.isEmpty() || !normalized.contains(alias)) continue
                // «تیرآهن» نرمال‌شده «تیراهن» است؛ هر دو شکل در مترادف‌ها آمده‌اند.
                if (alias.length > bestLen) {
                    best = p
                    bestLen = alias.length
                }
            }
        }
        return best
    }

    /**
     * واژه‌های ابعادی که در بازار به‌جای «میلی‌متر» به کار می‌روند. «میل» تنها یکی از آن‌هاست و
     * همین یک واژه است که «ورق ۲ میل» را از «۲ ورق» جدا می‌کند.
     */
    private val MM_WORDS = listOf("میلیمتر", "میلی متر", "میلی", "مم", "mil", "mm")
    private val CM_WORDS = listOf("سانتیمتر", "سانتی متر", "سانت", "cm")

    private val KG_WORDS = listOf("کیلوگرم", "کیلو", "kg", "کیلوی")
    private val METRE_WORDS = listOf("هر متر", "متر", "متری", "metre", "meter")
    private val BRANCH_WORDS = listOf("شاخه", "شاخه‌ای")
    private val SHEET_WORDS = listOf("برگ", "برگی", "ورق برگ")
    private val TON_WORDS = listOf("تن", "تنی", "ton")
    private val SQUARE_WORDS = listOf("مترمربع", "متر مربع", "مربع", "m2")

    private val PRICE_WORDS = listOf(
        "قیمت", "قیمتش", "قیمتها", "نرخ", "بها", "چنده", "چند است", "چند شد", "چه قیمتی",
        "تومان", "تومن", "price", "cost"
    )
    private val WEIGHT_WORDS = listOf(
        "وزن", "چند کیلو", "چند کیلوگرم", "سنگینی", "وزنش", "کیلوگرم است", "weight"
    )
    private val DEFINITION_WORDS = listOf(
        "چیه", "چیست", "یعنی چی", "یعنی چه", "چی هست", "تعریف", "چه نوع", "چه جنسی", "جنسش", "what is"
    )
    private val CALC_WORDS = listOf(
        "چطور محاسبه", "چگونه محاسبه", "محاسبه", "حساب", "فرمول", "چطور حساب"
    )

    /**
     * آنچه از پرسش فهمیده شد — همان چهار سؤالی که کاربر می‌گوید برنامه باید خودش بپرسد:
     * «ورق چیه؟، میل چیه؟، ۲ چرا؟، قیمت به چی؟»
     */
    data class Reading(
        val product: ProductDef,
        /** اندازه‌ها در یکای خودِ کالا: ورق/میلگرد/لوله میلی‌متر، تیرآهن/ناودانی سانتی‌متر. */
        val sizes: List<Double>,
        /** اندازهٔ دومی که برای لوله/قوطی/نبشی لازم است (ضخامت). */
        val thicknessMm: Double?,
        val ask: MaterialAsk,
        val basis: PriceBasis?,
        /** عبارت خامِ پرسش (نرمال‌شده) و کلمهٔ کالا که دیده شد. */
        val raw: String,
        val quantity: Double? = null,
        val quantityUnit: PriceBasis? = null,
        /** «چه فهمیدم» — همان چیزی که به کاربر نشان داده می‌شود. */
        val lines: List<String> = emptyList(),
        /** فرض‌هایی که باید صریح گفته شوند. */
        val assumptions: List<String> = emptyList()
    ) {
        val size: Double? get() = sizes.firstOrNull()
    }

    /**
     * پرسش را می‌خواند. اگر هیچ کالایی در متن نباشد `null` برمی‌گردد تا مسیر عمومی برنامه
     * دست‌نخورده بماند (این ماژول نباید سؤال‌های دیگر را بدزدد).
     */
    fun read(question: String): Reading? {
        val norm = Text.normalize(question)
        if (norm.isBlank()) return null
        val product = detect(norm) ?: return null

        // اعداد: ۱۸ ، ۲ ، ۲.۵ ، ۱۶
        val numbers = Regex("[0-9]+(?:\\.[0-9]+)?").findAll(norm).mapNotNull { it.value.toDoubleOrNull() }.toList()
        val hasMmWord = MM_WORDS.any { norm.contains(it) }
        val hasCmWord = CM_WORDS.any { norm.contains(it) }
        // بازار «نبشی ۴» را معمولاً بال ۴ سانتی/۴۰ میلی‌متری می‌نامد.
        // اندازه باید بلافاصله پس از نام کالا باشد تا «برای ۲ کیلو نبشی ۴»
        // مقدار سفارش را به‌اشتباه اندازهٔ نبشی نگیرد. «۴ میلی‌متر» صریحاً ۴ است.
        val angleLabelSize = if (product.kind == ProductKind.ANGLE)
            Regex("نبشی\\s*([0-9]+(?:\\.[0-9]+)?)").find(norm)?.groupValues?.get(1)?.toDoubleOrNull()
        else null
        val explicitAngleMm = hasMmWord || Regex("(?<![\\p{L}])میل(?![\\p{L}])").containsMatchIn(norm)
        val angleSizeMm = angleLabelSize?.let { size ->
            val mm = if (hasCmWord || (!explicitAngleMm && size in 2.0..20.0)) size * 10.0 else size
            mm.takeIf { it in 15.0..200.0 }
        }

        // زنجیرهٔ ابعاد مثل «۴۰×۴۰×۲» یا «۶۰×۳» را مستقیم می‌خوانیم؛ دقتش از حدس بیشتر است.
        val chain = dimsChain(norm)
        val sizes: List<Double>
        val thickness: Double?
        when (product.kind) {
            ProductKind.PIPE -> {
                sizes = chain.take(1).ifEmpty { sizesOf(product, numbers, norm, hasMmWord, hasCmWord) }
                thickness = chain.getOrNull(1) ?: explicitThickness(norm)
            }
            ProductKind.BOX -> {
                sizes = chain.take(2).ifEmpty { sizesOf(product, numbers, norm, hasMmWord, hasCmWord) }
                thickness = chain.getOrNull(2) ?: explicitThickness(norm)
            }
            ProductKind.ANGLE -> {
                sizes = chain.take(1).ifEmpty {
                    listOfNotNull(angleSizeMm).ifEmpty { sizesOf(product, numbers, norm, hasMmWord, hasCmWord) }
                }
                thickness = chain.getOrNull(1) ?: explicitThickness(norm)
            }
            else -> {
                sizes = if (chain.isNotEmpty()) chain else sizesOf(product, numbers, norm, hasMmWord, hasCmWord)
                thickness = explicitThickness(norm)
            }
        }

        val ask = when {
            CALC_WORDS.any { norm.contains(it) } -> MaterialAsk.HOW_TO_CALC
            WEIGHT_WORDS.any { norm.contains(it) } && !PRICE_WORDS.any { norm.contains(it) } -> MaterialAsk.WEIGHT
            PRICE_WORDS.any { norm.contains(it) } -> MaterialAsk.PRICE
            DEFINITION_WORDS.any { norm.contains(it) } -> MaterialAsk.DEFINITION
            sizes.isNotEmpty() -> MaterialAsk.DEFINITION
            else -> MaterialAsk.DEFINITION
        }

        val basis = basisOf(norm)
        val (qty, qtyUnit) = quantityOf(norm)

        val lines = mutableListOf<String>()
        lines += "${product.name} = ${product.whatItIs.substringBefore("؛")}"
        if (sizes.isNotEmpty()) {
            val label = product.measure
            lines += if (product.measureInCm) {
                "$label: ${sizes.joinToString(" و ") { num(it) }} سانتی‌متر (${sizes.joinToString(" و ") { num(it * 10.0) }} میلی‌متر)"
            } else {
                "$label: ${sizes.joinToString(" و ") { num(it) }} ${product.measureUnit}"
            }
        }
        if (hasMmWord) lines += "«میل/میلی‌متر» = یکای اندازه در این پرسش (نه تعداد و نه وزن)"

        val assumptions = mutableListOf<String>()
        if (product.kind == ProductKind.ANGLE && angleLabelSize != null &&
            angleLabelSize in 2.0..20.0 && !explicitAngleMm && sizes.firstOrNull() == angleLabelSize * 10.0) {
            assumptions += "نام رایج «نبشی ${num(angleLabelSize)}» به بال ${num(angleLabelSize * 10.0)} میلی‌متر تعبیر شد؛ ضخامت و سازنده از این نام معلوم نیستند"
        }
        if (ask == MaterialAsk.PRICE) {
            if (basis == null) {
                assumptions += "پایهٔ قیمت در پرسش نیامده؛ هر پایه‌ای که وزنش را بدانم حساب می‌کنم (کیلو، متر، شاخه/برگ)"
            } else {
                assumptions += "قیمت به پایهٔ «${basis.fa}» خواسته شده"
            }
            assumptions += "قیمت بازار متغیر است؛ تاریخِ خواندنِ قیمت در پاسخ نوشته می‌شود"
        }
        if (product.kind == ProductKind.SHEET && sizes.isNotEmpty()) {
            assumptions += "برگ استاندارد ${product.sheetLabel} فرض شده"
        }
        if (product.table.isNotEmpty() && sizes.isNotEmpty() && sizes.any { product.table[it] == null }) {
            assumptions += "برای این شماره جدول استاندارد را ندارم؛ فقط اندازه‌های جدول را می‌گویم"
        }

        return Reading(
            product = product,
            sizes = sizes,
            thicknessMm = thickness,
            ask = ask,
            basis = basis,
            raw = norm,
            quantity = qty,
            quantityUnit = qtyUnit,
            lines = lines,
            assumptions = assumptions
        )
    }

    private fun sizesOf(
        product: ProductDef,
        numbers: List<Double>,
        norm: String,
        hasMmWord: Boolean,
        hasCmWord: Boolean
    ): List<Double> {
        if (numbers.isEmpty()) return emptyList()
        val candidates = when (product.kind) {
            ProductKind.SHEET -> numbers.filter { it in 0.1..30.0 }        // ضخامت
            ProductKind.BEAM, ProductKind.CHANNEL -> numbers.map { if (it >= 40.0) it / 10.0 else it }
                .filter { it in 5.0..40.0 }
            ProductKind.REBAR -> numbers.filter { it in 5.0..64.0 }
            ProductKind.PIPE -> numbers.filter { it in 5.0..2000.0 }
            ProductKind.ANGLE -> numbers.filter { it in 15.0..200.0 }
            ProductKind.BOX -> numbers.filter { it in 5.0..300.0 }
            ProductKind.UNKNOWN -> emptyList()
        }.distinct()
        // «کیلو ۲۰۰» یا «۲ تن» اندازه نیست، مقدار است — از فهرست اندازه‌ها بیرون می‌ماند.
        val (qty, _) = quantityOf(norm)
        val filtered = if (qty == null) candidates else candidates.filter { it != qty }
        if (hasCmWord && !product.measureInCm) return filtered.map { it / 10.0 }
        if (hasMmWord && product.measureInCm) return filtered.map { it / 10.0 }
        return filtered.take(4)
    }

    /** «۴۰×۴۰×۲» → [۴۰, ۴۰, ۲] (میلی‌متر). */
    private fun dimsChain(norm: String): List<Double> =
        Regex("([0-9]+(?:\\.[0-9]+)?)\\s*[×x*]\\s*([0-9]+(?:\\.[0-9]+)?)(?:\\s*[×x*]\\s*([0-9]+(?:\\.[0-9]+)?))?")
            .find(norm)?.groupValues?.drop(1)?.filter { it.isNotBlank() }?.mapNotNull { it.toDoubleOrNull() }
            ?: emptyList()

    /** «ضخامت ۲» یا «با ضخامت ۲.۵». */
    private fun explicitThickness(norm: String): Double? =
        Regex("ضخامت\\s*([0-9]+(?:\\.[0-9]+)?)").find(norm)?.groupValues?.get(1)?.toDoubleOrNull()

    private fun basisOf(norm: String): PriceBasis? = when {
        SQUARE_WORDS.any { norm.contains(it) } -> PriceBasis.PER_SQUARE
        KG_WORDS.any { norm.contains(it) } -> PriceBasis.PER_KG
        TON_WORDS.any { norm.contains(it) } -> PriceBasis.PER_TON
        BRANCH_WORDS.any { norm.contains(it) } -> PriceBasis.PER_BRANCH
        SHEET_WORDS.any { norm.contains(it) } -> PriceBasis.PER_SHEET
        METRE_WORDS.any { norm.contains(it) } -> PriceBasis.PER_METRE
        else -> null
    }

    /**
     * مقدار («۲۰۰ کیلو»، «۲۰ شاخه») از پایهٔ قیمت («هر کیلو»، «۱۸ کیلو») جدا می‌شود.
     *
     * «قیمت تیرآهن ۱۸ کیلو» در بازار یعنی «قیمت هر کیلو تیرآهن ۱۸»، نه «۱۸ کیلوگرم تیرآهن».
     * اگر عدد کوچک بود و «هر» هم قبلش نبود، پایه حساب می‌شود نه مقدار — و اندازه هم از دست نمی‌رود.
     */
    private fun quantityOf(norm: String): Pair<Double?, PriceBasis?> {
        val m = Regex("(هر\\s*)?([0-9]+(?:\\.[0-9]+)?)\\s*(کیلوگرم|کیلو|تن|شاخه|برگ|متر|مترمربع)").find(norm)
            ?: return null to null
        val hasHar = m.groupValues[1].isNotBlank()
        val value = m.groupValues[2].toDoubleOrNull() ?: return null to null
        val unit = when (m.groupValues[3]) {
            "کیلوگرم", "کیلو" -> PriceBasis.PER_KG
            "تن" -> PriceBasis.PER_TON
            "شاخه" -> PriceBasis.PER_BRANCH
            "برگ" -> PriceBasis.PER_SHEET
            "مترمربع" -> PriceBasis.PER_SQUARE
            else -> PriceBasis.PER_METRE
        }
        if (hasHar) return null to null                       // «هر کیلو» پایه است، نه مقدار
        val minimum = when (unit) {
            PriceBasis.PER_KG -> 100.0                        // «۱۸ کیلو» یعنی پایه/اندازه، نه ۱۸ کیلو کالا
            PriceBasis.PER_TON -> 1.0
            PriceBasis.PER_METRE -> 3.0
            else -> 2.0
        }
        if (value < minimum) return null to null
        return value to unit
    }

    /** عدد فارسی برای متن پاسخ (ارقام فارسی، جداکنندهٔ اعشار فارسی). */
    fun num(v: Double, digits: Int = 3): String {
        val rounded = if (kotlin.math.abs(v - kotlin.math.round(v)) < 1e-9) v.toLong().toString()
        else String.format(java.util.Locale.US, "%.${digits}f", v).trimEnd('0').trimEnd('.')
        return persian(rounded)
    }

    fun persian(ascii: String): String {
        val sb = StringBuilder(ascii.length)
        for (c in ascii) {
            sb.append(
                when (c) {
                    in '0'..'9' -> '\u06F0' + (c - '0')
                    '.' -> '\u066B'
                    '-' -> '\u2212'
                    else -> c
                }
            )
        }
        return sb.toString()
    }
}
