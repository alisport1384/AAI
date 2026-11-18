package ir.aai.hushyar.domain

import ir.aai.hushyar.core.Html
import ir.aai.hushyar.core.PriceParser
import ir.aai.hushyar.core.Text
import java.net.URI

/**
 * خواندنِ نشانی‌هایی که کاربر در همان پرسش می‌دهد؛ بدون مدل، کلید API یا حدس زدن عدد.
 *
 * `PageExtractor` متن مقاله‌ای را برمی‌دارد و سطرهای کوتاه (اغلب خودِ قیمت‌ها) را دور می‌ریزد.
 * این مسیر HTML خام را می‌خواند: برای تلگرام هر پست و برای جدول‌های شبکه‌ای هر ردیف جداست.
 * **عددِ اول سطر قیمت نیست**؛ ممکن است سایز، ضخامت، وزن، تلفن یا میزانِ کاهش قیمت باشد.
 */
object UrlIngest {
    /** Records are separate from general facts: same product, different size/weight ≠ correction. */
    const val SOURCE_PRICE_CATEGORY = "قیمتِ خوانده‌شده از منبع"

    enum class OfferStatus { PRICED, CONTACT_ONLY, NO_NUMBER }

    data class Offer(
        /** عین نوشتهٔ منبع، با سرستون‌های جدول در صورت وجود. */
        val line: String,
        val sourceUrl: String,
        /** مقدار در صورت وجود واحد صریح، پس از تبدیل ریال به تومان؛ بدون واحد، خودِ عدد چاپ‌شده. */
        val amountToman: Long?,
        /** توصیف کالا در سطر؛ «۸/۵ کیلو» الزاماً به معنی قیمتِ هر کیلو نیست. */
        val unit: String?,
        /** اگر خالی باشد، واحد پول در خودِ سطر نوشته نشده؛ نباید آن را تومان نامید. */
        val currencyWord: String?,
        val status: OfferStatus,
        val note: String = ""
    )

    data class PageReport(
        val url: String,
        val fetched: Boolean,
        val title: String = "",
        val lines: Int = 0,
        val offers: List<Offer> = emptyList(),
        val problem: String = ""
    )

    /** نشانیِ بی‌پیشوند هم مجاز است: `ahanmelal.com` و `t.me/s/channel`. */
    private val LINK = Regex(
        """(?i)(?<![\p{L}\p{N}@/])(?:https?://)?(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,24}(?::[0-9]{2,5})?(?:[/?#][^\s<>"'،؛)\]»]*)?"""
    )
    private val TRIM_END = charArrayOf('.', ',', '،', '؛', ':', ')', '(', ']', '»', '«', '"', '\'')

    fun links(question: String, max: Int = 6): List<String> = LINK.findAll(question)
        .map { it.value.trimEnd(*TRIM_END) }
        .map { if (it.startsWith("http", ignoreCase = true)) it else "https://$it" }
        .filter { url ->
            val uri = runCatching { URI(url) }.getOrNull()
            uri?.host != null &&
                (uri?.scheme?.lowercase() == "http" || uri?.scheme?.lowercase() == "https") && url.length > 10
        }
        .distinct()
        .take(max)
        .toList()

    fun hasLinks(question: String): Boolean = links(question, 1).isNotEmpty()

    /** A link in the question is never part of the product name, size or search terms. */
    private fun withoutLinks(question: String): String = LINK.replace(question, " ")

    /** The deterministic price reader must not mislabel an unrelated link question as a price. */
    fun asksPrice(question: String): Boolean =
        QuestionReader.classify(Text.normalize(withoutLinks(question))) == AskType.PRICE

    private val LETTER_NUMBER_EDGE = Regex("(?<=\\p{L})(?=[0-9])|(?<=[0-9])(?=\\p{L})")

    fun keywords(question: String): List<String> {
        val text = LETTER_NUMBER_EDGE.replace(Text.normalize(withoutLinks(question)), " ")
        return KnowledgeBank.subjectTokensOf(text)
            .filter { token -> token.any { c -> !c.isDigit() } && token !in INSTRUCTION_WORDS }
            .toList()
    }

    /** فعل‌ها و توضیحِ منبع، نه نامِ کالای مورد پرسش. */
    val INSTRUCTION_WORDS: Set<String> = setOf(
        "منبع", "منابع", "منابعی", "خودم", "خودت", "دادم", "دادی", "پیدا", "کن", "کنید", "بخوان",
        "بخون", "نگاه", "ببین", "جستجو", "سرچ", "لینک", "لینکها", "آدرس", "سایت", "سایتها",
        "صفحه", "صفحهها", "کانال", "تلگرام", "رو", "از", "که", "این", "اینها", "همین", "لطفا",
        "لطفاً", "است", "هست", "چند", "چقدر", "چنده", "الان", "امروز", "بازار", "قیمت",
        "جواب", "پاسخ", "بهم", "بده", "بگو", "بگویید", "برای", "میشه", "میتونی", "بدین",
        "استخراج", "بررسی", "بیار", "بیرون", "بنویس", "نمایش", "بکش", "ازش", "نشون", "نشان"
    )

    /**
     * عددِ کنار **نام کالا** سایز است؛ «نبشی ۴، وزن ۸/۵ کیلو» سه سایزِ ۴ و ۸ و ۵ نیست.
     * اگر هیچ عددِ متصل به کالا یا «سایز/اندازه» نبود، تنها یک عددِ کوتاهِ بی‌ابهام پذیرفته می‌شود.
     */
    fun sizes(question: String): List<String> {
        val text = Text.normalize(withoutLinks(question))
        // واژهٔ اول هستهٔ کالا است؛ «نرمال ۲تا۳متر» یا «وزن ۸/۵کیلو» سایزِ کالا نیست.
        val nearProduct = keywords(question).firstOrNull()
            ?.let { productSizes(text, it) }.orEmpty()
        if (nearProduct.isNotEmpty()) return nearProduct
        val tagged = Regex("""(?:سایز|اندازه|مدل|شماره)\s*([0-9]{1,3})(?![0-9])""")
            .findAll(text).map { it.groupValues[1] }.distinct().toList()
        if (tagged.isNotEmpty()) return tagged
        val separated = LETTER_NUMBER_EDGE.replace(text, " ")
        val candidates = Text.tokens(separated)
            .filter { token -> token.all { c -> c.isDigit() } && token.length <= 3 }
            .distinct()
        return candidates.takeIf { it.size == 1 } ?: emptyList()
    }

    private val RANGE_SPEC = Regex("""[0-9]{1,3}\s*تا\s*[0-9]{1,3}\s*(?:کیلوگرم|کیلو|گرم|متر|میل)""")
    private val UNIT_SPEC = Regex("""[0-9]{1,3}(?:[/٫.,][0-9]{1,3})?\s*(?:کیلوگرم|کیلو|گرم|متر|میل)""")

    /** وزن/طولِ صریحِ خواسته‌شده باید در ردیفِ شاهد هم باشد، نه فقط سایز اصلی. */
    fun qualifiers(question: String): List<String> {
        val text = Text.normalize(withoutLinks(question))
        val ranges = RANGE_SPEC.findAll(text).toList()
        return (ranges.map { it.value } + UNIT_SPEC.findAll(text)
            .filter { found -> ranges.none { found.range.first in it.range } }
            .map { it.value }.toList())
            .map { it.replace(Regex("\\s+"), "") }.distinct()
    }

    fun isTelegram(url: String): Boolean {
        val host = runCatching { URI(url).host?.removePrefix("www.") }.getOrNull()
        return host == "t.me" || host == "telegram.me"
    }

    // متن پستِ واقعی در Telegram بلافاصله با «footer» می‌آید، نه با دو تگ </div> پشت سر هم.
    private val TELEGRAM_POST = Regex(
        """(?is)<div\b[^>]*class\s*=\s*["'][^"']*\btgme_widget_message_text\b[^"']*["'][^>]*>(.*?)</div>"""
    )

    /** متن‌های مستقل برای جلوگیری از چسبیدن قیمتِ پست/ردیف بعدی به کالای قبلی. */
    fun documents(html: String, url: String): List<String> {
        if (isTelegram(url)) {
            val posts = TELEGRAM_POST.findAll(html)
                .map { linesOf(it.groupValues[1]) }
                .filter { it.isNotBlank() }
                .toList()
            if (posts.isNotEmpty()) return posts
        }
        val grid = gridRows(html)
        if (grid.isNotEmpty()) return grid
        return linesOf(html).takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList()
    }

    /** برخلاف `Html.mainText` سطرهای کوتاه را حفظ می‌کند. */
    fun linesOf(html: String): String = Html.toText(html)
        .split('\n')
        .map { it.replace(Regex("[\\u200c\\u200f\\u200e]"), "\u200c").trim() }
        .filter { it.isNotEmpty() && it.length <= 400 }
        .joinToString("\n")

    /** فقط پیوندِ همان دامنه و با نامِ کالای خواسته‌شده؛ صفحه‌های فرعیِ قیمت، نه کل وب. */
    fun relevantLinks(html: String, baseUrl: String, keywords: List<String>, max: Int = 1): List<String> {
        if (keywords.isEmpty()) return emptyList()
        val host = Html.domainOf(baseUrl) ?: return emptyList()
        return Html.links(html, baseUrl, 220)
            .asSequence()
            .filter { it.url != baseUrl && Html.domainOf(it.url) == host }
            .filter { link -> keywords.all { Text.normalize(link.text).contains(it) } }
            .sortedByDescending { if (Text.normalize(it.text).contains("قیمت")) 1 else 0 }
            .map { it.url }
            .distinct()
            .take(max)
            .toList()
    }

    /**
     * A site may use a CSS grid instead of `<table>`: `div.tr.trbody.tr-row`, direct `div.td`
     * cells, and a `div.td.price`. Header labels travel with each row. Only **visible** text from
     * the price cell is read; `data-price` and `data-price2` may represent different tax modes.
     */
    private val OPEN_DIV = Regex("""(?is)<div\b[^>]*>""")
    private val DIV_TAG = Regex("""(?is)</?div\b[^>]*>""")
    private val CLASS_ATTR = Regex("""(?is)\bclass\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""")

    private fun classes(tag: String): Set<String> = CLASS_ATTR.find(tag)?.groupValues?.drop(1)
        ?.firstOrNull { it.isNotBlank() }?.split(Regex("\\s+"))?.toSet().orEmpty()

    private fun block(html: String, start: Int): String? {
        var depth = 0
        for (tag in DIV_TAG.findAll(html, start)) {
            if (tag.value.startsWith("</", ignoreCase = true)) depth-- else depth++
            if (depth == 0) return html.substring(start, tag.range.last + 1)
        }
        return null
    }

    private data class Cell(val text: String, val classes: Set<String>)

    private fun cells(row: String): List<Cell> {
        var depth = 0
        var from = -1
        var cellClasses = emptySet<String>()
        val out = mutableListOf<Cell>()
        for (tag in DIV_TAG.findAll(row)) {
            if (!tag.value.startsWith("</", ignoreCase = true)) {
                if (depth == 1) {
                    from = tag.range.last + 1
                    cellClasses = classes(tag.value)
                }
                depth++
            } else {
                if (depth == 2 && from >= 0) {
                    val text = linesOf(row.substring(from, tag.range.first)).replace('\n', ' ').trim()
                    if (text.isNotBlank()) out.add(Cell(text, cellClasses))
                    from = -1
                }
                depth--
            }
        }
        return out
    }

    private fun gridRows(html: String): List<String> {
        var header = emptyList<String>()
        val result = mutableListOf<String>()
        for (start in OPEN_DIV.findAll(html)) {
            val group = classes(start.value)
            if ("gridHeader" !in group && !("trbody" in group && "tr-row" in group)) continue
            val row = block(html, start.range.first) ?: continue
            val parts = cells(row)
            if ("gridHeader" in group) {
                header = parts.map { it.text }
                continue
            }
            val priceIndex = parts.indexOfFirst { "price" in it.classes }
                .takeIf { it >= 0 } ?: header.indexOfFirst { Text.normalize(it) == "قیمت" }
            if (priceIndex !in parts.indices || parts.isEmpty()) continue
            val label = parts.first().text
            val price = parts[priceIndex].text
            val details = parts.mapIndexedNotNull { index, cell ->
                val heading = header.getOrNull(index).orEmpty()
                if (index == 0 || index == priceIndex || heading.isBlank() ||
                    !listOf("سایز", "ضخامت", "طول", "وزن", "اندازه").any { heading.contains(it) }
                ) null else "$heading: ${cell.text}"
            }.take(3)
            result.add((listOf(label) + details + "قیمت: $price").joinToString(" | "))
        }
        return result
    }

    /** Size written right after a product word, optionally via a «سایز» column/word. */
    internal fun productSizes(row: String, keyword: String): List<String> {
        val norm = Text.normalize(row)
        val expr = Regex(
            """${Regex.escape(Text.normalize(keyword))}\s*(?:[-–_]\s*)?(?:(?:سایز|اندازه|مدل|شماره)\s*)?([0-9]{1,3})(?![0-9])"""
        )
        return expr.findAll(norm).map { it.groupValues[1] }.distinct().toList()
    }

    internal fun matches(
        row: String, keywords: List<String>, sizes: List<String>, qualifiers: List<String> = emptyList()
    ): Boolean {
        if (keywords.isEmpty()) return false
        val norm = Text.normalize(row)
        if (!keywords.all { norm.contains(Text.normalize(it)) }) return false
        val compact = norm.replace(Regex("\\s+"), "")
        if (!qualifiers.all { compact.contains(it) }) return false
        if (sizes.isEmpty()) return true
        val explicit = keywords.firstOrNull()?.let { productSizes(row, it) }.orEmpty()
        // Two different product sizes on one line: the price cannot safely be assigned to either.
        if (explicit.size > 1) return false
        // If «نبشی۵ | ضخامت: ۴» is printed, ۴ is not the size of this نبشی.
        if (explicit.isNotEmpty()) return explicit.first() in sizes
        // «نبشی ... سایز ۴» is okay, but a bare ۴ in weight/thickness/date is not.
        return Regex("""(?:سایز|اندازه|مدل|شماره)\s*([0-9]{1,3})(?![0-9])""")
            .findAll(norm).any { it.groupValues[1] in sizes }
    }

    private val CONTACT = Regex("""(?i)تماس|استعلام|هماهنگ|ناموجود|اتمام|موجود نیست|تلفن|شماره تماس|موبایل|call for price""")
    private val CHANGE = Regex("""(?i)کاهش|افزایش|افت قیمت|رشد قیمت|درصد تغییر""")
    private val CURRENCY = Regex("""(?i)^(ریال|تومان|تومن|rial|toman)(?=$|[^\p{L}])""")
    private val NUMBER = Regex("""(?<![\p{L}\p{N}])(?:[0-9]{1,3}(?:[,.٬][0-9]{3})+|[0-9]{1,12})(?![0-9])""")
    private val SCALED = Regex(
        """(?<![0-9])[0-9]+(?:[.,][0-9]+)?\s*(?:هزار|میلیون|میلیارد)(?:\s*و\s*[0-9]+(?:[.,][0-9]+)?\s*(?:هزار|میلیون))?\s*(?:ریال|تومان|تومن)?"""
    )
    private val UNITS = listOf("کیلوگرم", "کیلو", "شاخه", "عدد", "متر", "تن", "بسته", "کارتن")
    private data class Amount(val toman: Long, val unit: String?, val currency: String?)

    /** Extract the price candidate, not the first number of a line (size/weight/date/phone). */
    private fun readAmount(row: String): Amount? {
        if (CONTACT.containsMatchIn(row)) return null
        val delta = CHANGE.find(row)
        // «نبشی ۴: ۵۰۰۰ تومان کاهش» is a price *change*, not a price. If an actual «قیمت:»
        // precedes a comparison, examine only that part; otherwise refuse this line.
        val scoped = if (delta == null) row else row.substring(0, delta.range.first)
            .takeIf { it.contains("قیمت") || it.contains("نرخ") } ?: return null
        val ascii = Text.asciiDigits(scoped)
        val candidates = mutableListOf<Amount>()
        val scaledRanges = mutableListOf<IntRange>()
        for (match in SCALED.findAll(ascii)) {
            scaledRanges.add(match.range)
            val parsed = PriceParser.parse(match.value)
            if (parsed.irt != null && !parsed.ambiguous) {
                val currency = currencyOf(match.value)
                candidates.add(Amount(parsed.irt, UNITS.firstOrNull { scoped.contains(it) }, currency))
            }
        }
        for (match in NUMBER.findAll(ascii)) {
            if (scaledRanges.any { match.range.first in it }) continue
            val digits = match.value.filter { it.isDigit() }
            // Phone numbers beginning in 0 must never be mistaken for prices.
            if (digits.length >= 9 && digits.startsWith('0')) continue
            val tail = ascii.substring(match.range.last + 1).trimStart()
            val currencyWord = CURRENCY.find(tail)?.value
            val phrase = match.value + (currencyWord?.let { " $it" } ?: "")
            val parsed = PriceParser.parse(phrase)
            if (parsed.irt == null || parsed.ambiguous) continue
            candidates.add(Amount(parsed.irt, UNITS.firstOrNull { scoped.contains(it) }, currencyOf(phrase)))
        }
        // Two *different* prices on one line cannot safely be associated with the same variant.
        return candidates.distinctBy { it.toman }.singleOrNull()
    }

    private fun currencyOf(text: String): String? = when {
        text.contains("ریال") || text.contains("rial", ignoreCase = true) -> "ریال"
        text.contains("تومان") || text.contains("تومن") || text.contains("toman", ignoreCase = true) -> "تومان"
        else -> null
    }

    /** Following lines may only be a bare price, not the next product's own line. */
    private val PRICE_LINE_WORDS = setOf(
        "قیمت", "نرخ", "روز", "هر", "واحد", "ریال", "تومان", "تومن", "هزار", "میلیون", "میلیارد",
        "کیلو", "کیلوگرم", "شاخه", "متر", "عدد", "هرعدد", "toman", "rial", "price", "per", "unit"
    )

    private fun standalonePriceLine(line: String): Boolean =
        Text.contentTokens(line).filter { word -> word.any(Char::isLetter) }
            .all { it in PRICE_LINE_WORDS }

    fun offers(
        text: String,
        keywords: List<String>,
        sizes: List<String>,
        sourceUrl: String,
        lookAhead: Int = 2,
        qualifiers: List<String> = emptyList()
    ): List<Offer> {
        if (keywords.isEmpty()) return emptyList()
        val rows = text.split('\n')
        val found = mutableListOf<Offer>()
        for ((index, row) in rows.withIndex()) {
            if (!matches(row, keywords, sizes, qualifiers)) continue
            if (CONTACT.containsMatchIn(row)) {
                found.add(Offer(row, sourceUrl, null, null, null, OfferStatus.CONTACT_ONLY, "منبع عددی اعلام نکرد"))
                continue
            }
            val own = readAmount(row)
            if (own != null) {
                found.add(Offer(row, sourceUrl, own.toman, own.unit, own.currency, OfferStatus.PRICED))
                continue
            }
            var next: Pair<String, Amount>? = null
            var contact = false
            for (offset in 1..lookAhead) {
                val candidate = rows.getOrNull(index + offset) ?: break
                // Anything with a product label is a *new* line, even if its size is not asked.
                if (keywords.any { Text.normalize(candidate).contains(Text.normalize(it)) }) break
                if (CONTACT.containsMatchIn(candidate)) {
                    contact = true
                    break
                }
                if (!standalonePriceLine(candidate)) break
                val amount = readAmount(candidate)
                if (amount != null) {
                    next = candidate to amount
                    break
                }
            }
            when {
                next != null -> {
                    val (candidate, amount) = next
                    found.add(Offer("$row ⟶ $candidate", sourceUrl, amount.toman, amount.unit,
                        amount.currency, OfferStatus.PRICED))
                }
                contact -> found.add(Offer(row, sourceUrl, null, null, null,
                    OfferStatus.CONTACT_ONLY, "در خط بعد: تماس/عدم موجودی"))
                else -> found.add(Offer(row, sourceUrl, null, null, null,
                    OfferStatus.NO_NUMBER, "برای این کالا و اندازه عدد قیمت پیدا نشد"))
            }
        }
        return found.distinctBy { it.line }
    }

    /** Use only when the source explicitly states its currency, or when displaying a conversion. */
    fun money(toman: Long): String = Text.num(toman) + " تومان"

    fun displayAmount(offer: Offer): String {
        val value = offer.amountToman ?: return "پیدا نشد"
        return when (offer.currencyWord) {
            "ریال" -> "${money(value)} (تبدیل از ریال؛ تقسیم بر ۱۰)"
            "تومان" -> money(value)
            else -> "${Text.num(value)} (واحد پول در سطر ذکر نشده)"
        }
    }
}
