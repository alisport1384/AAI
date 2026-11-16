package ir.aai.hushyar.domain

/**
 * Derives the assistant's self-description from the pack and the current data.
 * Nothing here is market-specific: swap the pack and the self-model changes with it.
 */
object SelfModel {

    fun derive(pack: MarketPack, listingCount: Int, productCount: Int): SelfManifest {
        val focus = pack.attributes.map { it.label }
        val intents = pack.intents.map { it.label }.distinct()

        val identity = "من «هوشیار» هستم: موتور تحلیل کالا و پرسش‌وپاسخ بازار «${pack.name}»، " +
            "که کاملاً روی همین دستگاه کار می‌کند."

        val duties = buildList {
            add("بررسی هر کالا بر پایهٔ این ویژگی‌ها: " + focus.joinToString("، "))
            if (intents.isNotEmpty()) add("پاسخ به این خانواده‌های پرسش: " + intents.take(6).joinToString("، "))
            add("نگه‌داشتن قیمت و تاریخچهٔ کالاها در حافظهٔ محلی و اعلام تغییر غیرعادی")
            val flagTexts = pack.redFlags.take(3).map { it.text }
            if (flagTexts.isNotEmpty()) add("اعلام هشدار برای: " + flagTexts.joinToString(" / "))
        }

        val abilities = listOf(
            "خواندن ویژگی‌ها از عنوان و متن آگهی و ورودی دستی",
            "نرمال‌سازی قیمت (ارقام فارسی، تومان/ریال، هزار و میلیون)",
            "یکسان‌سازی آگهی‌های تکراری یک کالا",
            "محاسبهٔ میانه، بازه و صدک قیمت و امتیاز ارزش",
            "پاسخ دادن فقط با استناد به آگهی‌ها و دادهٔ ذخیره‌شده",
            "یادگیری ترجیح شما و نگه‌داشتن آن بین نشست‌ها"
        )

        val limits = buildList {
            add("هیچ داده‌ای به بیرون ارسال نمی‌شود؛ برنامه در v0.1 اصلاً مجوز شبکه ندارد.")
            add("بدون شاهد، عدد یا مشخصات نمی‌گویم؛ در نبود داده صریحاً می‌گویم «نمی‌دانم».")
            if (pack.sources.isEmpty()) add("منبع بیرونی برایم تعریف نشده؛ فقط دادهٔ واردشده را می‌شناسم.")
            if (pack.refusalNote.isNotBlank()) add("مرز اعلام‌شدهٔ شما: ${pack.refusalNote}")
        }

        val unknowns = buildList {
            if (listingCount == 0) add("هنوز هیچ آگهی‌ای وارد نشده؛ هیچ تحلیلی در دسترس نیست.")
            if (productCount == 0 && listingCount > 0) add("کالایی یکسان‌سازی نشده است.")
            add("قیمت‌های بیرون از این دستگاه و بازارهایی که به من وصل نکرده‌اید.")
            add("موجودی و شرایط لحظه‌ای فروشنده.")
            val enums = pack.attributes.filter { it.type == AttrType.ENUM }
            if (enums.isEmpty()) add("ارزش‌های مجاز ویژگی‌ها تعریف نشده؛ ویژگی‌ها را آزاد می‌خوانم.")
        }

        return SelfManifest(
            identity = identity,
            marketName = pack.name,
            duties = duties,
            abilities = abilities,
            limits = limits,
            unknowns = unknowns,
            focusAttributes = focus,
            intentLabels = intents
        )
    }
}
