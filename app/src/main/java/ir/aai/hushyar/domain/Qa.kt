package ir.aai.hushyar.domain

import ir.aai.hushyar.core.Text

data class IntentMatch(val id: String, val label: String, val score: Double)

/** Deterministic intent matching: pack keywords + built-in families (DESIGN §10.1). */
object IntentClassifier {

    fun classify(question: String, pack: MarketPack): IntentMatch {
        val q = " " + Text.normalize(question) + " "
        var best = IntentMatch("unknown", "پرسش عمومی", 0.0)
        for (spec in pack.intents) {
            var score = 0.0
            for (keywordRaw in spec.keywords) {
                val keyword = Text.normalize(keywordRaw)
                if (keyword.isBlank()) continue
                if (q.contains(" $keyword ") || q.contains(keyword)) {
                    score += 1.0 + 0.3 * (Text.tokens(keyword).size - 1).coerceAtLeast(0)
                }
            }
            if (score > 0) {
                score /= (1.0 + 0.15 * spec.keywords.size)
                if (score > best.score) best = IntentMatch(spec.id, spec.label, score)
            }
        }
        return best
    }
}

data class Fact(val label: String, val value: String)

/** Structured, pre-language answer. Rendering is a separate port so an on-device LLM phraser can replace templates later. */
data class AnswerDraft(
    val intent: String,
    val headline: String,
    val facts: List<Fact>,
    val citations: List<Citation>,
    val missing: List<String>,
    val confidence: Double,
    val refused: Boolean,
    val lang: String
)

interface Phraser {
    val lang: String
    fun render(draft: AnswerDraft): Answer
}

class TemplatePhraser(override val lang: String = "fa") : Phraser {
    override fun render(draft: AnswerDraft): Answer {
        val text = when {
            draft.refused && draft.missing.isNotEmpty() ->
                if (lang == "en") "I can't answer this reliably. Missing: ${draft.missing.joinToString("; ")}"
                else "نمی‌توانم پاسخ قابل اتکا بدهم. کمبود: ${draft.missing.joinToString("؛ ")}"
            draft.refused ->
                if (lang == "en") "I can't answer this from my current data." else "با دادهٔ فعلی نمی‌توانم به این پرسش پاسخ بدهم."
            else -> draft.headline
        }
        return Answer(
            intent = draft.intent,
            text = text,
            bullets = if (draft.refused) emptyList() else draft.facts.map { "${it.label}: ${it.value}" },
            citations = if (draft.refused) emptyList() else draft.citations,
            confidence = if (draft.refused) 0.0 else draft.confidence,
            missing = draft.missing,
            refused = draft.refused,
            lang = lang
        )
    }
}

/** Removes numeric claims that have no evidence attached (DESIGN §10.3). */
object Critic {

    fun stripUnsupported(draft: AnswerDraft): AnswerDraft {
        if (draft.refused) return draft
        val hasCitation = draft.citations.isNotEmpty()
        val kept = draft.facts.filter { fact -> !fact.value.any { it.isDigit() } || hasCitation }
        val dropped = draft.facts.size - kept.size
        val missing = if (dropped > 0) draft.missing + "شاهد برای $dropped عدد" else draft.missing
        val refuse = kept.isEmpty() && draft.facts.isNotEmpty()
        return draft.copy(
            facts = kept,
            missing = missing,
            refused = refuse,
            confidence = if (refuse) 0.0 else draft.confidence * if (dropped > 0) 0.8 else 1.0
        )
    }
}

data class Catalog(
    val pack: MarketPack,
    val listings: List<Listing>,
    val products: List<ProductGroup>
)

/**
 * Question answering over the local catalog. No model, no network: every number is
 * traceable to a stored listing, and absence of evidence produces a refusal.
 */
class QaEngine(private val phraser: Phraser = TemplatePhraser()) {

    fun ask(question: String, catalog: Catalog, now: Long = System.currentTimeMillis()): Answer {
        val pack = catalog.pack
        val intent = IntentClassifier.classify(question, pack)
        val draft = when {
            intent.id == "compare" -> compareDraft(question, catalog, intent)
            intent.id == "price.history" -> historyDraft(question, catalog, intent)
            intent.id == "spec.lookup" -> specDraft(question, catalog, intent)
            intent.id.startsWith("faq_") -> searchDraft(question, catalog, intent)
            else -> priceDraft(question, catalog, intent)
        }
        return phraser.render(Critic.stripUnsupported(draft))
    }

    private fun priceDraft(question: String, catalog: Catalog, intent: IntentMatch): AnswerDraft {
        val product = Resolver.findInQuestion(question, catalog.products)
            ?: return refuse(intent, listOf("کالای مورد پرسش مشخص نشد"), question)
        val prices = product.prices
        if (prices.isEmpty()) {
            return refuse(intent, listOf("قیمت ثبت‌شده برای «${product.canonicalName}»"), question)
        }
        val reference = product.listings.maxByOrNull { it.createdAt }?.priceIrt
        val stats = PriceStatsCalculator.stats(prices, reference)
        val facts = buildList {
            reference?.let { add(Fact("آخرین قیمت", Text.num(it) + " تومان")) }
            stats?.let {
                add(Fact("میانه", Text.num(it.median) + " تومان"))
                add(Fact("بازه", "${Text.num(it.min)} تا ${Text.num(it.max)} تومان"))
                add(Fact("تعداد آگهی", Text.numEn(it.count.toLong())))
            }
        }
        val citations = product.listings.take(5).map { l ->
            Citation("L:${l.id.take(8)}", "${l.source} • ${l.priceIrt?.let { Text.num(it) } ?: "نامعلوم"}", l.url)
        }
        return AnswerDraft(
            intent = intent.id,
            headline = "قیمت «${product.canonicalName}» بر پایهٔ ${citations.size} آگهی:",
            facts = facts,
            citations = citations,
            missing = product.listings.filter { it.priceIrt == null }.map { "قیمت نامعلوم در ${it.source}" },
            confidence = if (stats != null && stats.count >= 2) 0.85 else 0.55,
            refused = false,
            lang = phraser.lang
        )
    }

    private fun historyDraft(question: String, catalog: Catalog, intent: IntentMatch): AnswerDraft {
        val product = Resolver.findInQuestion(question, catalog.products)
            ?: return refuse(intent, listOf("کالای مورد پرسش مشخص نشد"), question)
        val observed = product.listings.filter { it.priceIrt != null }.sortedBy { it.createdAt }
        if (observed.size < 2) {
            return refuse(intent, listOf("دست‌کم دو مشاهدهٔ قیمت برای «${product.canonicalName}»"), question)
        }
        val facts = observed.takeLast(5).map { l ->
            Fact(javaDate(l.createdAt), Text.num(l.priceIrt ?: 0) + " تومان")
        }
        val citations = observed.takeLast(5).map { Citation("L:${it.id.take(8)}", it.source, it.url) }
        val first = observed.first().priceIrt ?: 0
        val last = observed.last().priceIrt ?: 0
        val change = if (first > 0) ((last - first) * 100.0 / first) else 0.0
        return AnswerDraft(
            intent = intent.id,
            headline = "روند قیمت «${product.canonicalName}» (${observed.size} مشاهده، تغییر ${"%.0f".format(change)}٪):",
            facts = facts,
            citations = citations,
            missing = emptyList(),
            confidence = 0.7,
            refused = false,
            lang = phraser.lang
        )
    }

    private fun specDraft(question: String, catalog: Catalog, intent: IntentMatch): AnswerDraft {
        val product = Resolver.findInQuestion(question, catalog.products)
            ?: return refuse(intent, listOf("کالای مورد پرسش مشخص نشد"), question)
        val findings = catalog.pack.attributes.mapNotNull { attr ->
            product.attrs[attr.id]?.takeIf { it.isNotBlank() }?.let { Fact(attr.label, it) }
        }
        if (findings.isEmpty()) {
            return refuse(intent, listOf("هیچ مشخصه‌ای برای «${product.canonicalName}» ثبت نشده"), question)
        }
        val citations = product.listings.take(3).map { Citation("L:${it.id.take(8)}", it.source, it.url) }
        return AnswerDraft(
            intent = intent.id,
            headline = "مشخصات «${product.canonicalName}»:",
            facts = findings,
            citations = citations,
            missing = catalog.pack.attributes.filter { product.attrs[it.id].isNullOrBlank() }.map { it.label },
            confidence = 0.75,
            refused = false,
            lang = phraser.lang
        )
    }

    private fun compareDraft(question: String, catalog: Catalog, intent: IntentMatch): AnswerDraft {
        val products = Resolver.findManyInQuestion(question, catalog.products)
        if (products.size < 2) {
            return refuse(intent, listOf("دو کالای مشخص برای مقایسه"), question)
        }
        val a = products[0]
        val b = products[1]
        val facts = catalog.pack.attributes.map { attr ->
            val av = a.attrs[attr.id]?.takeIf { it.isNotBlank() } ?: "—"
            val bv = b.attrs[attr.id]?.takeIf { it.isNotBlank() } ?: "—"
            Fact(attr.label, "${a.canonicalName.take(20)}: $av | ${b.canonicalName.take(20)}: $bv")
        }
        val citations = (a.listings + b.listings).take(4).map { Citation("L:${it.id.take(8)}", it.source, it.url) }
        return AnswerDraft(
            intent = intent.id,
            headline = "مقایسهٔ «${a.canonicalName}» و «${b.canonicalName}»:",
            facts = facts,
            citations = citations,
            missing = emptyList(),
            confidence = 0.7,
            refused = false,
            lang = phraser.lang
        )
    }

    private fun searchDraft(question: String, catalog: Catalog, intent: IntentMatch): AnswerDraft {
        val tokens = Text.contentTokens(question).toSet()
        val hits = catalog.listings
            .map { l -> l to Text.contentTokens(l.title).count { it in tokens } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(5)
            .map { it.first }
        if (hits.isEmpty()) {
            return refuse(intent, listOf("آگهی مرتبط با این پرسش"), question)
        }
        val facts = hits.map { l ->
            Fact(l.title.take(40), l.priceIrt?.let { Text.num(it) + " تومان" } ?: "قیمت نامعلوم")
        }
        val citations = hits.map { Citation("L:${it.id.take(8)}", it.source, it.url) }
        return AnswerDraft(
            intent = intent.id,
            headline = "برای «${intent.label.take(40)}» این موارد را در داده‌هایم پیدا کردم:",
            facts = facts,
            citations = citations,
            missing = emptyList(),
            confidence = 0.6,
            refused = false,
            lang = phraser.lang
        )
    }

    private fun refuse(intent: IntentMatch, missing: List<String>, question: String): AnswerDraft {
        val label = if (question.length > 40) question.take(40) + "…" else question
        return AnswerDraft(
            intent = intent.id,
            headline = "برای «$label» دادهٔ کافی ندارم.",
            facts = emptyList(),
            citations = emptyList(),
            missing = missing,
            confidence = 0.0,
            refused = true,
            lang = phraser.lang
        )
    }

    private fun javaDate(ts: Long): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        return fmt.format(java.util.Date(ts))
    }
}
