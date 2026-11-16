package ir.aai.hushyar.domain

import ir.aai.hushyar.core.PriceRules
import ir.aai.hushyar.core.Text

/**
 * The market is introduced by the user at runtime. No market is compiled in.
 * The interview is data (not UI), so it can be rendered, skipped, re-run, or replaced by
 * an imported pack JSON — which is what makes swapping markets a data operation.
 */
object Interview {

    data class Question(val id: String, val prompt: String, val hint: String)

    val questions: List<Question> = listOf(
        Question("market_name", "نام این بازار یا فروشگاه چیست؟", "مثال: بازار قطعات کامپیوتر"),
        Question("items", "این بازار چه چیزهایی می‌فروشد؟ چند نمونه بنویس.", "مثال: کارت گرافیک، پردازنده، رم"),
        Question("categories", "دسته‌بندی‌ها را بنویس (با کاما جدا کن).", "مثال: قطعات داخلی، لوازم جانبی"),
        Question("attributes", "برای بررسی هر کالا، چه ویژگی‌هایی را بسنجم؟", "مثال: ظرفیت، برند، گارانتی، سال تولید"),
        Question("weights", "کدام ویژگی برای مشتری مهم‌تر است؟ (نام همان ویژگی را بنویس)", "مثال: گارانتی"),
        Question("currency_unit", "قیمت‌ها با چه واحدی وارد می‌شوند؟", "تومان / ریال / هزار تومان / میلیون تومان"),
        Question("sources", "داده‌ها از کجا می‌آید؟ نام منبع‌ها را بنویس.", "مثال: فهرست خودم، سایت الف، سایت ب"),
        Question("faq", "مشتری‌ها بیشتر چه می‌پرسند؟ (هر پرسش در یک خط)", "مثال: قیمت این کالا چقدر است؟"),
        Question("risks", "چه چیزی را باید به‌عنوان هشدار اعلام کنم؟", "مثال: قیمت غیرعادی پایین، مغایرت مشخصات"),
        Question("langs", "به چه زبانی پاسخ بدهم؟", "fa / en / هر دو"),
        Question("limits", "چه چیزهایی را نباید پاسخ بدهم؟", "مثال: توصیهٔ حقوقی یا مالی قطعی")
    )

    fun splitList(raw: String): List<String> =
        raw.split("\n", "\r", "،", ",", "؛", ";", "·", "|")
            .map { it.trim().removePrefix("-").removePrefix("•").trim() }
            .filter { it.isNotEmpty() }

    private val NUMERIC_HINTS = listOf(
        "حافظه", "رم", "ظرفیت", "باتری", "وزن", "اندازه", "اینچ", "تعداد",
        "سال", "عمر", "قدرت", "توان", "حجم", "سرعت", "هسته", "هارد", "مگاپیکسل", "گیگ"
    )

    private fun inferType(label: String): AttrType {
        val norm = Text.normalize(label)
        return if (NUMERIC_HINTS.any { norm.contains(Text.normalize(it)) }) AttrType.NUMBER else AttrType.TEXT
    }

    private fun detectPriceRules(answer: String): PriceRules {
        val norm = Text.normalize(answer)
        return when {
            norm.contains("ریال") -> PriceRules(currency = "IRR", unitScale = 0.1)
            norm.contains("هزار") -> PriceRules(currency = "IRT", unitScale = 1_000.0)
            norm.contains(Text.normalize("میلیون")) -> PriceRules(currency = "IRT", unitScale = 1_000_000.0)
            else -> PriceRules(currency = "IRT", unitScale = 1.0)
        }
    }

    private fun detectLangs(answer: String): List<String> {
        val norm = Text.normalize(answer)
        val wantsEn = norm.contains("en") || norm.contains("english") || norm.contains(Text.normalize("انگلیسی"))
        val wantsFa = norm.contains("fa") || norm.contains("persian") || norm.contains(Text.normalize("فارسی")) ||
            norm.contains(Text.normalize("هر دو")) || norm.isBlank()
        return buildList {
            if (wantsFa || !wantsEn) add("fa")
            if (wantsEn) add("en")
        }
    }

    private fun builtinIntents(): List<IntentSpec> = listOf(
        IntentSpec("price.check", "قیمت کالا", listOf("قیمت", "چند", "چنده", "هزینه", "price", "cost")),
        IntentSpec("price.history", "روند و تاریخچهٔ قیمت", listOf("تاریخچه", "روند", "گران", "ارزان", "تغییر", "history", "trend")),
        IntentSpec("spec.lookup", "مشخصات کالا", listOf("مشخصات", "ویژگی", "چیست", "spec", "specs", "details")),
        IntentSpec("compare", "مقایسهٔ کالاها", listOf("مقایسه", "بهتر", "کدام", "فرق", "تفاوت", "compare", "better"))
    )

    private fun defaultFlags(): List<RedFlagSpec> = listOf(
        RedFlagSpec("price_too_low", "high", "قیمت این آگهی به‌شکل غیرعادی پایین‌تر از بازهٔ همین کالاست؛ اصالت و کامل‌بودن را بررسی کنید."),
        RedFlagSpec("price_outlier", "medium", "قیمت با میانهٔ این کالا فاصلهٔ زیادی دارد."),
        RedFlagSpec("spec_conflict", "medium", "دو منبع برای یک ویژگی مقدار متفاوت داده‌اند."),
        RedFlagSpec("missing_required_attr", "low", "یکی از ویژگی‌های ضروری در آگهی نیامده است.")
    )

    /**
     * Builds a pack out of the interview answers. Deterministic: same answers -> same pack.
     */
    fun build(answers: Map<String, String>, now: Long): PackBuildResult {
        val name = answers["market_name"].orEmpty().trim().ifEmpty { "بازار بدون نام" }
        val attrLabels = splitList(answers["attributes"].orEmpty())
            .ifEmpty { splitList(answers["items"].orEmpty()) }
            .ifEmpty { listOf("عنوان", "قیمت") }

        val weightHint = Text.normalize(answers["weights"].orEmpty())
        val rawWeights = attrLabels.map { label ->
            val base = 1.0
            if (weightHint.isNotBlank() && Text.normalize(label).let { l -> l.isNotBlank() && weightHint.contains(l) }) base * 2.0 else base
        }
        val weightSum = rawWeights.sum()

        val attributes = attrLabels.mapIndexed { index, label ->
            AttributeSpec(
                id = "attr_${index + 1}",
                label = label,
                type = inferType(label),
                weight = if (weightSum > 0) rawWeights[index] / weightSum else 0.1,
                required = index < 2
            )
        }

        val faqLabels = splitList(answers["faq"].orEmpty())
        val customIntents = faqLabels.mapIndexed { index, question ->
            IntentSpec(
                id = "faq_${index + 1}",
                label = question,
                keywords = Text.contentTokens(question).distinct().take(6)
            )
        }.filter { it.keywords.isNotEmpty() }

        val riskTexts = splitList(answers["risks"].orEmpty())
        val flags = defaultFlags() + riskTexts.mapIndexed { index, text ->
            RedFlagSpec("custom_${index + 1}", "medium", text)
        }

        val pack = MarketPack(
            id = Text.slug(name),
            name = name,
            version = 1,
            langs = detectLangs(answers["langs"].orEmpty()),
            categories = splitList(answers["categories"].orEmpty()),
            attributes = attributes,
            intents = builtinIntents() + customIntents,
            redFlags = flags,
            sources = splitList(answers["sources"].orEmpty()),
            priceRules = detectPriceRules(answers["currency_unit"].orEmpty()),
            refusalNote = answers["limits"].orEmpty().trim(),
            createdAt = now
        )
        return PackBuildResult(pack, PackValidator.validate(pack))
    }
}

data class PackBuildResult(val pack: MarketPack, val report: ValidationReport)
