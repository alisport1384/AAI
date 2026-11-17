package ir.aai.hushyar.domain

import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.round

/**
 * v0.10 — محاسبه‌ای که برنامه *خودش* بلد است.
 *
 * کاربر می‌گوید: «اگه جواب کلی داشت خودش محاسبه کنه و بر اساس متر بتونه قیمت هر متر رو حساب کنه».
 * پس این فایل دو چیز است: وزن‌های استاندارد (جدول/فرمول) و تبدیل‌های قیمت (کیلو ↔ متر ↔ شاخه ↔
 * برگ ↔ مترمربع ↔ تن). هر نتیجه یک [Step] برمی‌گرداند که فرمولش با عددهای واقعی نوشته شده تا
 * کاربر بتواند رد محاسبه را ببیند — عددی که نتوان نشانش داد، در پاسخ نمی‌آید.
 */
object SteelMath {

    private const val DENSITY = 7.85    // kg per mm·m² (فولاد)
    private const val PI_APPROX = 3.14159265

    /** وزن هر متر به کیلوگرم، با فرمولِ پرشده. */
    data class Weight(
        val kgPerMetre: Double?,
        val formula: String,
        val provenance: String,
        /** true = جدول استاندارد یا فرمول هندسی دقیق؛ false = تخمین. */
        val exact: Boolean,
        val note: String = ""
    )

    data class Step(val value: Double, val formula: String, val unit: String)

    fun num(v: Double, digits: Int = 2): String = Materials.num(v, digits)

    /** جدول/فرمول وزن هر متر. */
    fun weight(reading: Materials.Reading): Weight {
        val p = reading.product
        val size = reading.size
        val t = reading.thicknessMm
        return when (p.kind) {
            ProductKind.BEAM, ProductKind.CHANNEL -> {
                val fromTable = size?.let { p.table[it] }
                if (fromTable != null) {
                    Weight(
                        fromTable,
                        "${p.name} ${num(it0(size))} → ${num(fromTable)} کیلوگرم بر متر",
                        if (p.kind == ProductKind.BEAM) Provenance.IPE else Provenance.CHANNEL,
                        exact = p.kind == ProductKind.BEAM,
                        note = p.tableNote
                    )
                } else {
                    Weight(
                        null,
                        "برای ${p.name} ${num(size ?: 0.0)} جدول استاندارد را ندارم",
                        p.tableNote,
                        exact = false,
                        note = "اندازه‌های جدول: " + p.table.keys.sorted().joinToString("، ") { num(it) }
                    )
                }
            }
            ProductKind.REBAR -> {
                if (size == null) return missing(p.name, "قطر")
                val kg = size * size / 162.0
                Weight(
                    kg,
                    "وزن هر متر میلگرد ${num(size)} = ${num(size)}² ÷ ۱۶۲ = ${num(kg)} کیلوگرم",
                    Provenance.REBAR, exact = true
                )
            }
            ProductKind.SHEET -> {
                if (size == null) return missing(p.name, "ضخامت")
                val kg = DENSITY * size
                Weight(
                    kg,
                    "وزن هر مترمربع ورق ${num(size)} میل = ۷٫۸۵ × ${num(size)} = ${num(kg)} کیلوگرم (برای عرض ۱ متر: همین عدد، کیلوگرم بر متر)",
                    Provenance.SHEET, exact = true
                )
            }
            ProductKind.PIPE -> {
                if (size == null || t == null) return missing(p.name, "قطر و ضخامت")
                val kg = PI_APPROX * t * (size - t) * DENSITY / 1000.0
                Weight(
                    kg,
                    "وزن هر متر لوله ${num(size)}×${num(t)} = π × ${num(t)} × (${num(size)} − ${num(t)}) × ۷٫۸۵ ÷ ۱۰۰۰ = ${num(kg)} کیلوگرم",
                    Provenance.PIPE, exact = false, note = "تقریبی؛ به گردی و تلورانس سازنده بستگی دارد"
                )
            }
            ProductKind.BOX -> {
                val w = size ?: return missing(p.name, "ابعاد مقطع")
                val h = reading.sizes.getOrNull(1) ?: w
                val th = t ?: 2.0
                val kg = 2 * (w + h - 2 * th) * th * DENSITY / 1000.0
                Weight(
                    kg,
                    "وزن هر متر قوطی ${num(w)}×${num(h)}×${num(th)} = ۲ × (${num(w)} + ${num(h)} − ۲×${num(th)}) × ${num(th)} × ۷٫۸۵ ÷ ۱۰۰۰ = ${num(kg)} کیلوگرم",
                    Provenance.BOX, exact = false, note = "تقریبی؛ وزن کارخانه‌ای ±۵٪ تفاوت دارد"
                )
            }
            ProductKind.ANGLE -> {
                if (size == null || t == null) return missing(p.name, "بال و ضخامت")
                val kg = (2 * size - t) * t * DENSITY / 1000.0
                Weight(
                    kg,
                    "وزن هر متر نبشی ${num(size)}×${num(t)} = (۲×${num(size)} − ${num(t)}) × ${num(t)} × ۷٫۸۵ ÷ ۱۰۰۰ = ${num(kg)} کیلوگرم",
                    Provenance.ANGLE, exact = false, note = "تقریبی ±۵٪"
                )
            }
            ProductKind.UNKNOWN -> missing(p.name, "اندازه")
        }
    }

    private fun it0(v: Double?): Double = v ?: 0.0

    private fun missing(name: String, what: String) =
        Weight(null, "برای محاسبهٔ ${name} «${what}» لازم است و در پرسش نیامده", "", exact = false)

    /** وزن یک شاخه (طول استاندارد) یا یک برگ. */
    fun pieceWeight(reading: Materials.Reading, w: Weight): Step? {
        val kg = w.kgPerMetre ?: return null
        return when (reading.product.kind) {
            ProductKind.SHEET -> Step(
                kg * reading.product.sheetAreaM2,
                "${num(kg)} کیلوگرم/مترمربع × ${num(reading.product.sheetAreaM2)} مترمربع = ${num(kg * reading.product.sheetAreaM2)} کیلوگرم برای یک برگ ${reading.product.sheetLabel}",
                "کیلوگرم بر برگ"
            )
            else -> Step(
                kg * reading.product.standardLengthM,
                "${num(kg)} کیلوگرم/متر × ${num(reading.product.standardLengthM)} متر = ${num(kg * reading.product.standardLengthM)} کیلوگرم برای یک شاخه",
                "کیلوگرم بر شاخه"
            )
        }
    }

    /**
     * قیمت یکای خواسته‌شده از قیمت هر کیلو. این همان چیزی است که «قیمت کلی» را به
     * «قیمت هر متر / هر شاخه / هر برگ» تبدیل می‌کند.
     */
    fun priceIn(basis: PriceBasis, pricePerKg: Double, reading: Materials.Reading, w: Weight): Step? {
        val kgPerM = w.kgPerMetre ?: return null
        val p = reading.product
        return when (basis) {
            PriceBasis.PER_KG -> Step(pricePerKg, "قیمت هر کیلوگرم (همان عدد بازار)", "تومان/کیلوگرم")
            PriceBasis.PER_METRE -> Step(
                pricePerKg * kgPerM,
                "${num(kgPerM)} کیلوگرم/متر × ${money(pricePerKg)} = ${money(pricePerKg * kgPerM)}",
                "تومان/متر"
            )
            PriceBasis.PER_BRANCH -> Step(
                pricePerKg * kgPerM * p.standardLengthM,
                "${num(kgPerM)} کیلوگرم/متر × ${num(p.standardLengthM)} متر × ${money(pricePerKg)} = ${money(pricePerKg * kgPerM * p.standardLengthM)}",
                "تومان/شاخه ${num(p.standardLengthM)} متری"
            )
            PriceBasis.PER_SHEET -> Step(
                pricePerKg * kgPerM * p.sheetAreaM2,
                "${num(kgPerM)} کیلوگرم/مترمربع × ${num(p.sheetAreaM2)} مترمربع × ${money(pricePerKg)} = ${money(pricePerKg * kgPerM * p.sheetAreaM2)}",
                "تومان/برگ"
            )
            PriceBasis.PER_SQUARE -> Step(
                pricePerKg * kgPerM,
                "${num(kgPerM)} کیلوگرم/مترمربع × ${money(pricePerKg)} = ${money(pricePerKg * kgPerM)}",
                "تومان/مترمربع"
            )
            PriceBasis.PER_TON -> Step(
                pricePerKg * 1000.0,
                "۱۰۰۰ کیلوگرم × ${money(pricePerKg)} = ${money(pricePerKg * 1000.0)}",
                "تومان/تن"
            )
        }
    }

    /** عکسِ بالا: قیمت یک شاخه/برگ/تن را به قیمت هر کیلو تبدیل کن تا همه‌چیز یک مبنا داشته باشد. */
    fun perKgFrom(basis: PriceBasis, value: Double, reading: Materials.Reading, w: Weight): Step? {
        val kgPerM = w.kgPerMetre ?: return null
        val p = reading.product
        return when (basis) {
            PriceBasis.PER_KG -> Step(value, "قیمت هر کیلوگرم (همان عدد بازار)", "تومان/کیلوگرم")
            PriceBasis.PER_METRE -> if (kgPerM <= 0) null else Step(
                value / kgPerM,
                "${money(value)} ÷ ${num(kgPerM)} کیلوگرم/متر = ${money(value / kgPerM)}",
                "تومان/کیلوگرم"
            )
            PriceBasis.PER_BRANCH -> {
                val kg = kgPerM * p.standardLengthM
                if (kg <= 0) null else Step(
                    value / kg,
                    "${money(value)} ÷ (${num(kgPerM)} × ${num(p.standardLengthM)} = ${num(kg)} کیلوگرم) = ${money(value / kg)}",
                    "تومان/کیلوگرم"
                )
            }
            PriceBasis.PER_SHEET -> {
                val kg = kgPerM * p.sheetAreaM2
                if (kg <= 0) null else Step(
                    value / kg,
                    "${money(value)} ÷ (${num(kgPerM)} × ${num(p.sheetAreaM2)} = ${num(kg)} کیلوگرم) = ${money(value / kg)}",
                    "تومان/کیلوگرم"
                )
            }
            PriceBasis.PER_SQUARE -> if (kgPerM <= 0) null else Step(
                value / kgPerM,
                "${money(value)} ÷ ${num(kgPerM)} کیلوگرم/مترمربع = ${money(value / kgPerM)}",
                "تومان/کیلوگرم"
            )
            PriceBasis.PER_TON -> Step(value / 1000.0, "${money(value)} ÷ ۱۰۰۰ کیلوگرم = ${money(value / 1000.0)}", "تومان/کیلوگرم")
        }
    }

    /** «۴۲٬۰۰۰ تومان» با ارقام فارسی و جداکنندهٔ هزارگان. */
    fun money(v: Double): String {
        val rounded = round(v)
        val ascii = String.format(java.util.Locale.US, "%,.0f", rounded).replace(",", "\u066C")
        return Materials.persian(ascii) + " تومان"
    }

    fun money(v: Long): String = money(v.toDouble())

    /** تن از کیلوگرم، برای جواب‌های سنگین. */
    fun tons(kg: Double): String = "${num(kg / 1000.0)} تن"

    /** چند برگ/شاخه در یک تن — سؤال رایج بازار. */
    fun piecesPerTon(reading: Materials.Reading, w: Weight): Step? {
        val kg = w.kgPerMetre ?: return null
        val onePiece = when (reading.product.kind) {
            ProductKind.SHEET -> kg * reading.product.sheetAreaM2
            else -> kg * reading.product.standardLengthM
        }
        if (onePiece <= 0) return null
        val count = 1000.0 / onePiece
        return Step(
            count,
            "۱۰۰۰ کیلوگرم ÷ ${num(onePiece)} کیلوگرم = ${num(count, 1)} " +
                if (reading.product.kind == ProductKind.SHEET) "برگ در هر تن" else "شاخه در هر تن",
            if (reading.product.kind == ProductKind.SHEET) "برگ/تن" else "شاخه/تن"
        )
    }

    /** فقط برای تست/نمایش: آیا این عدد قیمت فولاد است یا خطای خواندن؟ */
    fun plausiblePricePerKg(v: Double): Boolean = v in 2_000.0..2_000_000.0

    fun close(a: Double, b: Double, tolerance: Double = 0.01): Boolean =
        if (b == 0.0) abs(a) < tolerance else abs(a - b) / abs(b) <= tolerance

    /** ضریب چگالی، برای تست. */
    val density: Double get() = DENSITY
}
