package ir.aai.hushyar.domain

import ir.aai.hushyar.core.Text
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * A small, auditable purchase worksheet. Conversation turns can supply ONLY user
 * assumptions; typed, unexpired knowledge can supply a source rate. Model prose,
 * snippets and previous assistant answers are never parsed as financial facts.
 * Unknown inclusive/exclusive tax, delivery fees and delivery taxability block
 * an asserted grand total, but the known parts are still explained.
 */
object PurchaseComposer {
    private val interest = Regex("(?i)مالیات|ارزش\\s*افزوده|حمل|کرایه|ارسال|جمع|قیمت\\s*نهایی|مبلغ\\s*نهایی|\\btax\\b|\\bshipping\\b")
    private val financial = Regex("(?i)مالیات|ارزش\\s*افزوده|حمل|کرایه|ارسال|جمع|قیمت\\s*نهایی|مبلغ\\s*نهایی")
    private val money = Regex("([0-9]+(?:[.,٬][0-9]{3})*)\\s*(هزار|میلیون)?\\s*(تومان|تومن|ریال)")
    private val quantity = Regex("(?:برای|مقدار|تعداد|سفارش)\\s*([0-9]+(?:[./٫][0-9]{1,3})?)\\s*(کیلوگرم|کیلو|kg|تن|شاخه|برگ|عدد|قطعه|مترمربع|متر\\s*مربع|متر)(?![آ-ی])")
    private val percent = Regex("(?:مالیات|ارزش\\s*افزوده)\\s*(?:به\\s*نرخ|:)?\\s*([0-9]+(?:[.٫][0-9]{1,2})?)\\s*(?:درصد|٪|%)|([0-9]+(?:[.٫][0-9]{1,2})?)\\s*(?:درصد|٪|%)\\s*(?:مالیات|ارزش\\s*افزوده)")
    private val unitRate = Regex("(?:هر|بر|/)\\s*(کیلوگرم|کیلو|kg|تن|شاخه|برگ|عدد|قطعه|مترمربع|متر\\s*مربع|متر)(?![آ-ی])")
    private val role = Regex("قیمت|نرخ|حمل|کرایه|ارسال")
    private val maxMoney = BigDecimal("10000000000000000")

    data class Rate(val toman: BigDecimal, val per: String, val source: String,
                    val citation: Citation? = null, val userAssumption: Boolean = false)

    data class Parts(
        val quantity: BigDecimal? = null, val per: String? = null,
        val userRate: Rate? = null, val taxPercent: BigDecimal? = null,
        val freight: BigDecimal? = null, val includesTax: Boolean? = null,
        val freightTaxable: Boolean? = null,
        val wantsFreight: Boolean = false, val wantsTax: Boolean = false
    ) {
        fun overlay(other: Parts) = Parts(
            quantity = other.quantity ?: quantity, per = other.per ?: per,
            userRate = other.userRate ?: userRate,
            taxPercent = other.taxPercent ?: taxPercent,
            freight = other.freight ?: freight,
            includesTax = other.includesTax ?: includesTax,
            freightTaxable = other.freightTaxable ?: freightTaxable,
            wantsFreight = wantsFreight || other.wantsFreight,
            wantsTax = wantsTax || other.wantsTax
        )
    }

    data class Plan(val priceQuestion: String, val parts: Parts, val usedHistory: Boolean)

    private fun norm(s: String) = Text.normalize(s).replace('٫', '.')
    private fun decimal(raw: String): BigDecimal? = runCatching {
        BigDecimal(raw.replace('٫', '.').replace('/', '.'))
    }.getOrNull()?.takeIf { it >= BigDecimal.ZERO && it <= maxMoney }

    private fun moneyValue(m: MatchResult): BigDecimal? {
        val n = decimal(m.groupValues[1].replace(Regex("[.,٬]"), "")) ?: return null
        val scale = when (m.groupValues[2]) { "هزار" -> 1_000; "میلیون" -> 1_000_000; else -> 1 }
        val toman = n.multiply(BigDecimal(scale))
            .let { if (m.groupValues[3] == "ریال") it.divide(BigDecimal.TEN) else it }
        return toman.takeIf { it <= maxMoney }
    }

    private fun basis(raw: String): String? = when (raw.replace(" ", "")) {
        "کیلو", "کیلوگرم", "kg" -> "kg"
        "تن" -> "ton"
        "شاخه", "عدد", "قطعه" -> "piece"
        "برگ" -> "sheet"
        "متر" -> "m"
        "مترمربع" -> "m2"
        else -> null
    }

    /** Read only the user's own text; a missing value is not a zero. */
    fun parse(text: String): Parts {
        val s = norm(text)
        val amount = quantity.findAll(s).lastOrNull()
        val qty = amount?.groupValues?.get(1)?.let(::decimal)?.takeIf { it > BigDecimal.ZERO && it <= BigDecimal("1000000") }
        val per = amount?.groupValues?.get(2)?.let(::basis)
        val pct = percent.findAll(s).lastOrNull()?.groupValues?.let { it[1].ifBlank { it[2] } }
            ?.let(::decimal)?.takeIf { it <= BigDecimal("100") && it.scale() <= 2 }
        var price: Rate? = null
        var shipping: BigDecimal? = null
        for (m in money.findAll(s)) {
            val value = moneyValue(m) ?: continue
            val before = s.substring(0, m.range.first)
            val last = role.findAll(before).lastOrNull() ?: continue
            if (before.length - last.range.first > 95) continue
            when (last.value) {
                "حمل", "کرایه", "ارسال" -> shipping = value
                "قیمت", "نرخ" -> {
                    val unit = unitRate.findAll(before.takeLast(95)).lastOrNull()?.groupValues?.get(1)?.let(::basis)
                    if (unit != null) price = Rate(value, unit, "فرض اعلام‌شدهٔ خود شما (نرخ بازار تأیید نشده)",
                        userAssumption = true)
                }
            }
        }
        if (Regex("(?:حمل|ارسال|کرایه)\\s*رایگان").containsMatchIn(s)) shipping = BigDecimal.ZERO
        val included = when {
            Regex("(?:قیمت|نرخ)(?:\\s+پایه)?\\s+(?:شامل|با\\s*احتساب)\\s*(?:مالیات|ارزش\\s*افزوده)").containsMatchIn(s) -> true
            Regex("(?:قیمت|نرخ)[^؛\\n]{0,65}?(?:بدون|جدا\\s*از)\\s*(?:مالیات|ارزش\\s*افزوده)").containsMatchIn(s) -> false
            else -> null
        }
        val shippingTax = when {
            Regex("حمل\\s*مشمول\\s*مالیات|مالیات(?:\\s*[0-9]+(?:\\.[0-9]+)?\\s*(?:درصد|٪|%))?\\s*(?:روی|بر)\\s*(?:کالا\\s*و\\s*حمل|حمل)")
                .containsMatchIn(s) -> true
            Regex("(?:مالیات(?:\\s*[0-9]+(?:\\.[0-9]+)?\\s*(?:درصد|٪|%))?\\s*)?فقط\\s*(?:روی|بر)\\s*کالا|حمل\\s*(?:بدون|غیر\\s*مشمول)\\s*مالیات|مالیات\\s*حمل\\s*را\\s*حساب\\s*نکن")
                .containsMatchIn(s) -> false
            else -> null
        }
        return Parts(qty, per, price, pct, shipping, included, shippingTax,
            wantsFreight = Regex("حمل|کرایه|ارسال|جمع|نهایی").containsMatchIn(s),
            wantsTax = Regex("مالیات|ارزش\\s*افزوده|جمع|نهایی").containsMatchIn(s))
    }

    /** Remove financial *numbers* before any product parser or public search sees the query. */
    fun productQuery(question: String): String {
        val normalized = norm(question)
        val start = financial.find(normalized)
        var product = if (start == null) normalized else normalized.substring(0, start.range.first)
        if (!product.any(Char::isLetter) ||
            (Materials.read(product) == null && FrameParser.parse(product)?.entityKnown != true)) {
            // A finance-first sentence like "جمع قیمت نبشی ۴...": find the
            // named product, not an arbitrary numeric suffix or old answer.
            val labels = (Materials.all.flatMap { it.aliases } +
                DomainPacks.all.flatMap { pack -> pack.entities.flatMap { it.aliases + it.label } })
                .map(Text::normalize).filter { it.length > 2 }
            val offset = labels.map { normalized.indexOf(it) }.filter { it >= 0 }.minOrNull()
            if (offset != null) {
                val tail = normalized.substring(offset)
                product = tail.substringBefore(financial.find(tail)?.value ?: "\u0000")
            }
        }
        product = product.replace(quantity, " ").replace(money, " ")
            .replace(Regex("[0-9]+(?:[.٫][0-9]+)?\\s*(?:درصد|٪|%)"), " ")
            .replace(unitRate, " ")
            .replace(Regex("(?:^|\\s)(?:حالا|و|با|رو|را)(?=\\s|$)"), " ")
            .replace(Regex("\\s+"), " ").trim(' ', '؛', '،', '?', '؟')
        return if (product.startsWith("قیمت") || product.startsWith("نرخ")) product else "قیمت $product".trim()
    }

    private fun signature(query: String): String? {
        val reading = Materials.read(query)
        if (reading != null && reading.size != null) return "${reading.product.kind}:${Materials.num(reading.size!!)}"
        val frame = FrameParser.parse(query) ?: return null
        return if (frame.entityKnown) "${frame.entity}:${frame.qualifierText}" else null
    }

    /** Inherit explicit values only since the latest identical named product in this thread. */
    fun plan(question: String, resolved: String, threadId: String,
             history: List<ChatTurn>, usedHistory: Boolean): Plan? {
        if (!interest.containsMatchIn(norm(question))) return null
        val clean = productQuery(resolved).take(300)
        val key = signature(clean)
        // Do not steal an unrelated "جمع منابع / ارسال داده" request. An
        // unknown product is still usable as a *hypothetical* worksheet when
        // the user supplies an explicit per-unit price in this same message.
        if (key == null && parse(question).userRate == null) return null
        val earlier = if (usedHistory && key != null) {
            val recent = history.filter { it.threadId == threadId && it.answer != null }.takeLast(8)
            val keys = recent.map { turn -> signature(productQuery(turn.question)) }
            // A later, explicitly named *different* product closes the old
            // worksheet. Reintroducing the original product must not inherit
            // rates, tax status or freight from that intervening segment.
            val barrier = keys.indexOfLast { it != null && it != key }
            val anchor = keys.indices.lastOrNull { it > barrier && keys[it] == key }
            if (anchor != null) recent.drop(anchor).map { it.question } else emptyList()
        } else emptyList()
        val input = (earlier + question).fold(Parts()) { acc, utterance -> acc.overlay(parse(utterance)) }
        return Plan(clean, input, earlier.isNotEmpty())
    }

    /** Only a typed, unexpired and exact-size steel fact can supply an implicit rate. */
    fun storedRate(query: String, items: List<KnowledgeItem>, now: Long): Rate? {
        val r = Materials.read(query) ?: return null
        val size = r.size ?: return null
        val topic = "price:${r.product.kind.name}:${Materials.num(size)}"
        val candidates = items.filter { item ->
            item.topic == topic && item.attribute == MaterialBrain.PRICE_ATTRIBUTE &&
                item.unit == "تومان/کیلوگرم" && item.supersededById == null &&
                item.url.startsWith("https://") && item.domain.isNotBlank() && item.claim.isNotBlank() &&
                item.numericValue != null && item.numericValue.isFinite() && item.numericValue > 0 &&
                (item.lastVerifiedAt.takeIf { it > 0 } ?: item.createdAt) in (now - MaterialBrain.PRICE_TTL_MS)..(now + 300_000L)
        }
        // Two active rates for the same exact product are not one quote. Do not
        // silently choose a seller/date just because one row sorts first.
        if (candidates.map { it.numericValue }.distinct().size > 1) return null
        val found = candidates.maxByOrNull { it.lastVerifiedAt.takeIf { at -> at > 0 } ?: it.createdAt } ?: return null
        val price = BigDecimal.valueOf(found.numericValue!!)
        if (price > maxMoney) return null
        return Rate(price, "kg", "دانش قیمتِ نوع‌دار ${found.domain.ifBlank { "ذخیره‌شده" }}؛ " +
            "ثبت ${found.lastVerifiedAt.takeIf { it > 0 } ?: found.createdAt} (اعتبار روز فروشنده تأیید نشده)",
            citation = found.url.takeIf { it.startsWith("https://") }?.let {
                Citation("P1", found.domain.ifBlank { "منبع نرخ" }, it)
            })
    }

    private fun shown(v: BigDecimal): String {
        val parts = v.stripTrailingZeros().toPlainString().split('.')
        val grouped = parts.first().reversed().chunked(3).joinToString("٬").reversed()
        return Materials.persian(grouped + if (parts.size == 2) "٫${parts.last()} تومان" else " تومان")
    }

    /** Calculation uses BigDecimal only, not language-model output or a past reply. */
    fun answer(plan: Plan, typedRate: Rate? = null): Answer {
        val p = plan.parts
        val rate = p.userRate ?: typedRate
        val missing = mutableListOf<String>()
        if (rate == null) missing += "قیمت واحدِ دارای شاهد نوع‌دار یا نرخ فرضیِ صریح شما"
        if (p.quantity == null || p.per == null) missing += "مقدار سفارش و واحدش (مثلاً برای ۲ کیلو)"
        if (rate != null && p.per != null && rate.per != p.per) missing += "یکای مقدار و قیمت باید یکی باشد؛ تبدیل بی‌شاهد مجاز نیست"
        if (p.per in listOf("piece", "sheet") && (p.quantity?.stripTrailingZeros()?.scale() ?: 0) > 0) {
            missing += "تعداد شاخه/برگ باید صحیح باشد"
        }
        if (p.includesTax == null) missing += "آیا قیمت پایه شامل یا بدون مالیات است؟"
        if (p.includesTax == false && p.taxPercent == null) missing += "نرخ مالیاتِ صریح (درصد)"
        if (p.wantsFreight && p.freight == null) missing += "هزینهٔ حمل با ارز صریح"
        if (p.freight != null && p.freight > BigDecimal.ZERO && p.freightTaxable == null) {
            missing += "آیا حمل مشمول مالیات است؟"
        }
        if (p.freight != null && p.freight > BigDecimal.ZERO && p.freightTaxable == true && p.taxPercent == null) {
            missing += "نرخ مالیات حمل"
        }
        val base = if (rate != null && p.quantity != null && p.per == rate.per)
            rate.toman.multiply(p.quantity).setScale(1, RoundingMode.HALF_UP) else null
        if (base != null && base > maxMoney) missing += "مبلغ خارج از محدودهٔ مجاز است"
        var tax: BigDecimal? = null
        var total: BigDecimal? = null
        if (missing.isEmpty() && base != null) {
            val taxable = (if (p.includesTax == false) base else BigDecimal.ZERO)
                .add(if (p.freightTaxable == true) p.freight ?: BigDecimal.ZERO else BigDecimal.ZERO)
            tax = taxable.multiply(p.taxPercent ?: BigDecimal.ZERO)
                .divide(BigDecimal("100"), 1, RoundingMode.HALF_UP)
            total = base.add(p.freight ?: BigDecimal.ZERO).add(tax)
            if (total > maxMoney) {
                missing += "جمع خارج از محدودهٔ مجاز است"
                total = null
            }
        }
        val included = if (total != null && p.includesTax == true && p.taxPercent != null && base != null)
            base.multiply(p.taxPercent).divide(BigDecimal("100").add(p.taxPercent), 1, RoundingMode.HALF_UP)
        else null
        val bullets = listOfNotNull(
            "موضوع: ${plan.priceQuestion}؛ نرخ و شرایط از منابع جداگانه خوانده شدند.",
            if (base != null && base <= maxMoney) "بهای کالا: ${shown(base)} = ${shown(rate!!.toman)} × ${p.quantity!!.stripTrailingZeros().toPlainString()} ${p.per}؛ ${rate.source}." else null,
            if (p.includesTax == true) "قیمت پایه طبق فرض صریح شما شامل مالیات است؛ مالیات کالا دوباره اضافه نمی‌شود." else
                if (p.includesTax == false) "قیمت پایه طبق فرض صریح شما بدون مالیات است." else null,
            p.taxPercent?.let { "نرخ مالیاتِ اعلام‌شدهٔ شما: ${it.stripTrailingZeros().toPlainString()} درصد." },
            p.freight?.let { "حملِ اعلام‌شدهٔ شما: ${shown(it)}." },
            included?.let { "مالیات درون قیمت کالا: ${shown(it)} (اضافه نشده)." },
            if (p.includesTax == true && p.taxPercent == null && p.wantsTax)
                "نرخ و سهم مالیات درون قیمت نامعلوم است؛ بهای کالای شامل مالیات معلوم است ولی مبلغ مالیات جداگانه قابل تفکیک نیست." else null,
            tax?.takeIf { it > BigDecimal.ZERO }?.let { "مالیات افزوده‌شده: ${shown(it)}؛ گرد کردن تا نزدیک‌ترین ریال." },
            if (total == null) "جمع نهایی قابل‌اعلام نیست؛ لازم است: ${missing.joinToString("؛ ")}." else
                "${if (p.freight == null) "جمع کالا با مالیات، بدون محاسبهٔ حمل" else "جمع قیمت کالا، مالیات و حمل"}: ${shown(total)}؛ برآورد است، نه فاکتور فروش."
        )
        return Answer(intent = "purchase.total", text = bullets.last(), bullets = bullets.dropLast(1),
            citations = rate?.citation?.let(::listOf).orEmpty(),
            confidence = if (total != null && !rate!!.userAssumption) 0.75 else if (total != null) 0.55 else 0.2,
            missing = missing, refused = total == null,
            trace = listOf("برنامهٔ چندبخشی محلی؛ ${if (plan.usedHistory) "شرایط فقط از کاربرِ همین گفتگو" else "شرایط همین پیام"}؛ " +
                "پاسخ‌های قبلی/وب به‌عنوان مبلغ استفاده نشدند"))
    }
}
