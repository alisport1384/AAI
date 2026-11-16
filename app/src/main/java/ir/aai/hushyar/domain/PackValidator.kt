package ir.aai.hushyar.domain

data class ValidationReport(
    val blocking: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
) {
    val ok: Boolean get() = blocking.isEmpty()
}

object PackValidator {

    fun validate(pack: MarketPack): ValidationReport {
        val blocking = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (pack.schema != MarketPack.SCHEMA) {
            blocking.add("نسخهٔ پک ناشناخته: ${pack.schema}")
        }
        if (pack.name.isBlank()) blocking.add("نام بازار خالی است.")
        if (pack.attributes.isEmpty()) blocking.add("هیچ ویژگی‌ای تعریف نشده؛ بدون ویژگی، تحلیل ممکن نیست.")
        if (pack.langs.isEmpty()) blocking.add("زبان پاسخ تعیین نشده است.")

        val ids = mutableSetOf<String>()
        var weightSum = 0.0
        for (attr in pack.attributes) {
            if (attr.id.isBlank()) blocking.add("شناسهٔ ویژگی خالی است (${attr.label}).")
            else if (!ids.add(attr.id)) blocking.add("شناسهٔ ویژگی تکراری: ${attr.id}")
            if (attr.label.isBlank()) blocking.add("برچسب ویژگی خالی است (${attr.id}).")
            if (attr.weight < 0.0) blocking.add("وزن منفی برای ${attr.label}.")
            if (attr.weight > 1.0) warnings.add("وزن ${attr.label} بزرگ‌تر از ۱ است.")
            weightSum += attr.weight
        }
        if (pack.attributes.isNotEmpty() && weightSum <= 0.0) {
            blocking.add("مجموع وزن‌ها صفر است.")
        }
        if (pack.priceRules.unitScale <= 0.0) blocking.add("مقیاس واحد قیمت نامعتبر است.")
        if (pack.priceRules.currency !in setOf("IRT", "IRR")) {
            warnings.add("واحد پول ناشناخته: ${pack.priceRules.currency}")
        }
        if (pack.intents.isEmpty()) warnings.add("هیچ نیت پرسشی تعریف نشده؛ پرسش‌ها عمومی پاسخ می‌گیرند.")
        if (pack.sources.isEmpty()) warnings.add("منبع دادهٔ بیرونی تعریف نشده؛ فقط دادهٔ واردشده تحلیل می‌شود.")
        if (pack.refusalNote.isBlank()) warnings.add("مرزهای پاسخ (چه چیزی نگویم) تعیین نشده.")

        return ValidationReport(blocking, warnings)
    }
}
