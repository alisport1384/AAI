package ir.aai.hushyar.domain

/**
 * v0.11 — بسته‌های دانش، همه به شکل داده.
 *
 * «ورق و تیرآهن» فقط یک نمونه بود. این فایل نشان می‌دهد همان ساختار برای پوشاک هم کار می‌کند، و
 * مهم‌تر: کاربر یا مدل محلی می‌تواند بستهٔ سوم، چهارم، هزارم را برای بازار خودش بسازد — بدون
 * تغییر یک خط کد.
 *
 * دو قاعدهٔ صداقت:
 *  * عددی که «استاندارد» است با منبعش می‌آید (جدول IPE، فرمول میلگرد).
 *  * عددی که «بازاری» است به‌صورت **محدوده** می‌آید و در پاسخ گفته می‌شود که محدوده است، نه دقیق.
 *    قیمت هیچ‌جا در بستهٔ دانش نیست؛ قیمت همیشه باید از منبع یا از خود کاربر بیاید.
 */
object DomainPacks {

    // ---------------------------------------------------------------- پوشاک

    private val garmentSizes = listOf("XS", "S", "M", "L", "XL", "XXL", "3XL")

    private val garmentAttributes = listOf(
        AttrDef(
            "size", "سایز", AttrKind.ENUM, values = garmentSizes,
            synonyms = listOf("سایز", "اندازه", "سایزبندی", "size")
        ),
        AttrDef(
            "material", "جنس", AttrKind.ENUM,
            values = listOf("نخ پنبه", "نخی", "پنبه", "پلی‌استر", "مخلوط", "ویکور", "کتان", "جین", "فلامنت"),
            synonyms = listOf("جنس", "پارچه", "الیاف", "fabric", "material")
        ),
        AttrDef(
            "color", "رنگ", AttrKind.ENUM,
            values = listOf("مشکی", "سفید", "طوسی", "آبی", "قرمز", "سبز", "کرم", "زرشکی", "سرمه‌ای"),
            synonyms = listOf("رنگ", "color")
        ),
        AttrDef(
            "fabric_weight", "وزن پارچه", AttrKind.NUMBER, unit = "g",
            synonyms = listOf("گرماژ", "وزن پارچه", "گرم بر متر", "gsm")
        ),
        AttrDef(
            "pack_size", "تعداد در بسته", AttrKind.NUMBER, unit = "count",
            synonyms = listOf("بسته", "بسته‌بندی", "کارتن")
        )
    )

    val garment: DomainPack = DomainPack(
        id = "garment",
        name = "پوشاک (تی‌شرت، شلوار، پیراهن)",
        attributes = garmentAttributes,
        entities = listOf(
            EntityDef(
                id = "tshirt",
                label = "تی‌شرت",
                aliases = listOf("تی‌شرت", "تیشرت", "تی شرت", "tshirt", "t-shirt"),
                whatItIs = "تی‌شرت پوشاک بالاتنه با آستین کوتاه است؛ در بازار عمده معمولاً با سایزبندی " +
                    "S تا XXL و بر پایهٔ «عدد» یا «دوجین» فروخته می‌شود.",
                sellUnits = listOf("count", "dozen", "pack"),
                attributes = garmentAttributes
            ),
            EntityDef(
                id = "pants",
                label = "شلوار",
                aliases = listOf("شلوار", "شلوارک", "pants", "trouser"),
                whatItIs = "شلوار پوشاک پایین‌تنه است و در بازار با شمارهٔ کمر (۲۸ تا ۴۲) یا سایز " +
                    "حرفی فروخته می‌شود؛ جنس و «قد» تعیین‌کنندهٔ قیمت است.",
                sellUnits = listOf("count", "dozen"),
                attributes = garmentAttributes + AttrDef(
                    "waist", "شمارهٔ کمر", AttrKind.NUMBER, unit = "cm", synonyms = listOf("کمر", "waist")
                )
            ),
            EntityDef(
                id = "shirt",
                label = "پیراهن",
                aliases = listOf("پیراهن", "shirt"),
                whatItIs = "پیراهن پوشاک بالاتنهٔ دکمه‌دار است؛ سایز یقه و قد آستین در قیمتش اثر دارد.",
                sellUnits = listOf("count", "dozen"),
                attributes = garmentAttributes
            ),
            EntityDef(
                id = "hoodie",
                label = "سویشرت و هودی",
                aliases = listOf("سویشرت", "هودی", "hoodie", "sweatshirt"),
                whatItIs = "سویشرت و هودی پوشاک بالاتنهٔ ضخیم با پارچهٔ گرم‌تر (معمولاً ۲۴۰ تا ۳۵۰ گرم " +
                    "بر مترمربع) هستند؛ گرماژ مهم‌ترین ویژگی قیمتی آن‌هاست.",
                sellUnits = listOf("count", "dozen"),
                attributes = garmentAttributes
            ),
            EntityDef(
                id = "fabric",
                label = "پارچه",
                aliases = listOf("پارچه", "متقال", "جین", "fabric"),
                whatItIs = "پارچه در بازار «متری» فروخته می‌شود؛ عرض پارچه و گرماژ (گرم بر مترمربع) " +
                    "دو ویژگی اصلی آن است.",
                sellUnits = listOf("m", "kg"),
                attributes = listOf(
                    AttrDef("material", "جنس", AttrKind.ENUM, values = garmentAttributes[1].values),
                    AttrDef("width", "عرض", AttrKind.NUMBER, unit = "cm", synonyms = listOf("عرض", "width")),
                    AttrDef("fabric_weight", "گرماژ", AttrKind.NUMBER, unit = "g", synonyms = listOf("گرماژ", "gsm"))
                )
            )
        ),
        sources = listOf("فهرست ویژگی‌های رایج بازار پوشاک"),
        note = "قیمتی در این بسته نیست. سایزبندی و واژه‌ها ساختار بازار را می‌سازند؛ قیمت باید " +
            "از منبع یا از خود کاربر بیاید."
    )

    // ---------------------------------------------------------------- فلزات

    val steel: DomainPack = DomainPack(
        id = "steel",
        name = "آهن و فولاد",
        attributes = listOf(
            AttrDef("size", "اندازه", AttrKind.NUMBER, unit = "mm", synonyms = listOf("سایز", "شماره", "ضخامت")),
            AttrDef("grade", "جنس", AttrKind.ENUM, values = listOf("ST37", "ST52", "A36", "A2", "A3", "گالوانیزه")),
            AttrDef("length", "طول شاخه", AttrKind.NUMBER, unit = "m", synonyms = listOf("طول", "شاخه"))
        ),
        entities = Materials.all.map { p ->
            EntityDef(
                id = p.kind.name.lowercase(),
                label = p.name,
                aliases = p.aliases,
                whatItIs = p.whatItIs,
                sellUnits = when (p.kind) {
                    ProductKind.SHEET -> listOf("count", "kg", "ton", "m2")
                    ProductKind.REBAR, ProductKind.BEAM, ProductKind.ANGLE,
                    ProductKind.CHANNEL, ProductKind.PIPE, ProductKind.BOX -> listOf("kg", "ton", "m", "count")
                    ProductKind.UNKNOWN -> listOf("kg")
                },
                attributes = listOf(
                    AttrDef(
                        "size", p.measure, AttrKind.NUMBER,
                        unit = if (p.measureInCm) "cm" else "mm",
                        synonyms = listOf(p.measure, "سایز", "شماره")
                    )
                ) + listOf(AttrDef("grade", "جنس", AttrKind.ENUM, values = p.grades.take(4)))
            )
        },
        sources = listOf(Provenance.IPE, Provenance.REBAR, Provenance.SHEET),
        note = "وزن‌ها از جدول/فرمول استانداردند؛ قیمت از منبع یا کاربر."
    )

    val all: List<DomainPack> = listOf(steel, garment)

    fun byId(id: String?): DomainPack? = all.firstOrNull { it.id == id }

    /** برای صف‌بندی: کدام بسته با این متن بیشترین تطابق را دارد. */
    fun detect(normalized: String): DomainPack? {
        var best: DomainPack? = null
        var bestLen = 0
        for (pack in all) {
            for (e in pack.entities) for (alias in e.aliases + e.label) {
                val a = ir.aai.hushyar.core.Text.normalize(alias)
                if (a.length > bestLen && normalized.contains(a)) {
                    bestLen = a.length
                    best = pack
                }
            }
        }
        return best
    }

    /**
     * «من فروشگاه تی‌شرت و شلوار هستم، سایز و رنگ مهم است» — این جمله نباید یک پرسش باشد؛ باید
     * بستهٔ دانش بسازد. تشخیصش با واژه‌های کسب‌وکار است، نه با مدل، تا همیشه کار کند.
     */
    object DomainSetup {
        private val TRIGGERS = listOf(
            "فروشگاه", "مغازه", "بازار", "من میفروشم", "من می فروشم", "کار من",
            "شغل من", "تولید میکنم", "تولید می کنم", "shop", "store", "business"
        )

        fun describe(question: String): String? {
            val norm = ir.aai.hushyar.core.Text.normalize(question)
            if (norm.length < 8) return null
            val hits = TRIGGERS.count { norm.contains(it) }
            return if (hits >= 1) question.take(400) else null
        }
    }

    /**
     * بستهٔ دانش از حرف‌های خود کاربر («من فروشگاه تی‌شرت و شلوار هستم»). بدون مدل هم کار می‌کند:
     * اسم هر کالایی که در جمله آمده یک «موجودیت» می‌شود و ویژگی‌های نام‌برده به آن می‌چسبند.
     */
    fun fromDescription(text: String, id: String = "custom"): DomainPack? {
        val norm = ir.aai.hushyar.core.Text.normalize(text)
        val tokens = ir.aai.hushyar.core.Text.contentTokens(norm).distinct()
        if (tokens.isEmpty()) return null
        val attrWords = listOf(
            "سایز" to "size", "اندازه" to "size", "رنگ" to "color", "جنس" to "material",
            "وزن" to "weight", "قیمت" to "price", "موجودی" to "stock", "مدل" to "model",
            "گرماژ" to "fabric_weight", "برند" to "brand", "کیفیت" to "quality"
        )
        val attrs = attrWords.filter { (w, _) -> norm.contains(w) }.map { (w, id2) ->
            AttrDef(id2, w, AttrKind.TEXT)
        }.distinctBy { it.id }
        val entity = EntityDef(
            id = "custom-" + ir.aai.hushyar.core.Similarity.fnv1a64(norm).toString(16).take(6),
            label = tokens.take(3).joinToString(" "),
            aliases = tokens.take(6),
            attributes = attrs.ifEmpty { listOf(AttrDef("price", "قیمت", AttrKind.NUMBER, unit = "toman")) }
        )
        return DomainPack(
            id = id,
            name = entity.label.ifBlank { "بازار من" },
            entities = listOf(entity),
            attributes = entity.attributes,
            note = "از توضیح خودِ کاربر ساخته شد؛ هر چه بپرسی یاد می‌گیرد و ذخیره می‌کند."
        )
    }
}
