package ir.aai.hushyar.domain

import ir.aai.hushyar.core.Text

/**
 * The research plan of v0.5: the assistant does not wait for a question.
 *
 * The user's picture was painting an elephant: first the big body, then layer by layer the details.
 * Concretely, the goal ("this market, these products, these attributes") is turned into ordered
 * layers, and a layer may only start when the layers it depends on are finished. The researcher then
 * keeps working through the plan across runs, saving its cursor, instead of doing one shot per
 * question.
 *
 * Design basis: deep-research systems plan before retrieving — decompose the goal into sub-goals,
 * decide *what to search for, in what order* (Deep Research: A Survey of Autonomous Research Agents,
 * arXiv:2508.12752, §2 Planning). This file is the plan; Proactive.kt executes it.
 */
enum class LayerKind { MARKET, PRODUCTS, ATTRIBUTES, DOSSIER, COMPARISON, FRESHNESS }

enum class LayerStatus { PENDING, PARTIAL, DONE }

data class PlanLayer(
    val id: String,
    val kind: LayerKind,
    /** 0 = the big body, higher = finer detail. Execution order is depth order. */
    val depth: Int,
    val title: String,
    val queries: List<String>,
    /** Layers that must be DONE before this one may run. */
    val requires: List<String> = emptyList()
)

data class ResearchPlan(
    val goal: String,
    val layers: List<PlanLayer>,
    val createdAt: Long
) {

    fun layer(id: String): PlanLayer? = layers.firstOrNull { it.id == id }

    /** Layers still to do, whose prerequisites are all DONE, coarse before fine. */
    fun unlocked(progress: Map<String, LayerStatus>): List<PlanLayer> =
        layers.sortedBy { it.depth }
            .filter { progress[it.id] != LayerStatus.DONE }
            .filter { layer -> layer.requires.all { progress[it] == LayerStatus.DONE } }

    /** The next layer to work on: shallowest unlocked layer. Null = the plan is finished. */
    fun next(progress: Map<String, LayerStatus>): PlanLayer? = unlocked(progress).firstOrNull()

    fun done(progress: Map<String, LayerStatus>): Int = layers.count { progress[it.id] == LayerStatus.DONE }

    fun ratio(progress: Map<String, LayerStatus>): Double =
        if (layers.isEmpty()) 1.0 else done(progress).toDouble() / layers.size

    /** Human-readable plan, for the UI and for the log. */
    fun describe(progress: Map<String, LayerStatus>, langTag: String = "fa"): List<String> =
        layers.sortedBy { it.depth }.map { layer ->
            val state = when (progress[layer.id]) {
                LayerStatus.DONE -> if (langTag == "en") "done" else "انجام شد"
                LayerStatus.PARTIAL -> if (langTag == "en") "in progress" else "نیمه‌کاره"
                else -> if (langTag == "en") "queued" else "در نوبت"
            }
            "${layer.depth}. ${layer.title} — $state (${Text.num(layer.queries.size.toLong())} پرسش)"
        }
}

object PlanBuilder {

    private const val MAX_PRODUCTS = 4
    private const val MAX_ATTRIBUTES = 2
    private const val MAX_QUESTIONS = 5

    /**
     * Goal → layers. `questions` are the questions the user has actually asked; they become their own
     * layer (a dossier), which is what turns "answer once" into "answer better next time".
     */
    fun build(
        pack: MarketPack,
        products: List<ProductGroup> = emptyList(),
        questions: List<String> = emptyList(),
        langTag: String = "fa",
        now: Long = 0L
    ): ResearchPlan {
        val market = pack.name.ifBlank { "بازار" }
        val fa = langTag != "en"
        val attributes = pack.attributes.map { it.label }.filter { it.isNotBlank() }.distinct().take(MAX_ATTRIBUTES)
        val names = products.map { it.canonicalName }.filter { it.isNotBlank() }.distinct().take(MAX_PRODUCTS)

        val layers = mutableListOf<PlanLayer>()

        // 0 — the big body: what this market is and which attributes matter at all.
        layers.add(
            PlanLayer(
                id = "market",
                kind = LayerKind.MARKET,
                depth = 0,
                title = if (fa) "تصویر کلی بازار «$market»" else "Market overview: $market",
                queries = buildList {
                    add(if (fa) "بازار $market چیست و چه کالاهایی دارد" else "$market market what products")
                    if (attributes.isNotEmpty()) {
                        add(if (fa) "$market ${attributes.joinToString(" ")}" else "$market ${attributes.joinToString(" ")}")
                    }
                }.distinct()
            )
        )

        // 1 — the limbs: one solid query per product.
        if (names.isNotEmpty()) {
            layers.add(
                PlanLayer(
                    id = "products",
                    kind = LayerKind.PRODUCTS,
                    depth = 1,
                    title = if (fa) "کالاهای اصلی (${names.size})" else "Main products (${names.size})",
                    queries = names.map { if (fa) "قیمت $it $market" else "$it price $market" },
                    requires = listOf("market")
                )
            )
        }

        // 2 — the details: attribute by attribute, per product.
        if (names.isNotEmpty() && attributes.isNotEmpty()) {
            val queries = mutableListOf<String>()
            for (name in names.take(MAX_PRODUCTS)) {
                for (attribute in attributes) {
                    queries.add(if (fa) "$name $attribute" else "$name $attribute")
                }
            }
            layers.add(
                PlanLayer(
                    id = "attributes",
                    kind = LayerKind.ATTRIBUTES,
                    depth = 2,
                    title = if (fa) "ویژگی‌ها به تفکیک کالا" else "Attributes per product",
                    queries = queries.distinct(),
                    requires = listOf("products")
                )
            )
        }

        // 3 — the dossier: what the user actually asked. This is the layer that makes re-asking cheaper.
        val asked = questions.map { Text.normalize(it).trim().trimEnd('?', '؟', '.') }
            .filter { it.length >= 10 }
            .distinct()
            .take(MAX_QUESTIONS)
        if (asked.isNotEmpty()) {
            layers.add(
                PlanLayer(
                    id = "dossier",
                    kind = LayerKind.DOSSIER,
                    depth = 3,
                    title = if (fa) "پروندهٔ پرسش‌های کاربر (${asked.size})" else "User question dossier (${asked.size})",
                    queries = asked,
                    requires = listOf("market")
                )
            )
        }

        // 4 — comparison across the limbs.
        if (names.size >= 2) {
            val comparisons = mutableListOf<String>()
            comparisons.add(if (fa) "مقایسه ${names[0]} و ${names[1]}" else "compare ${names[0]} and ${names[1]}")
            if (attributes.isNotEmpty()) {
                comparisons.add(if (fa) "بهترین ${names[0]} از نظر ${attributes[0]}" else "best ${names[0]} ${attributes[0]}")
            }
            layers.add(
                PlanLayer(
                    id = "comparison",
                    kind = LayerKind.COMPARISON,
                    depth = 4,
                    title = if (fa) "مقایسه و انتخاب" else "Comparison and choice",
                    queries = comparisons.distinct(),
                    requires = listOf("attributes")
                )
            )
        }

        // 5 — the finishing touches: freshness of the numbers we already hold.
        if (names.isNotEmpty()) {
            layers.add(
                PlanLayer(
                    id = "freshness",
                    kind = LayerKind.FRESHNESS,
                    depth = 5,
                    title = if (fa) "تازه‌سازی قیمت‌ها" else "Price freshness",
                    queries = names.take(2).map {
                        if (fa) "قیمت $it امسال" else "$it price this year"
                    },
                    requires = listOf("comparison", "attributes")
                )
            )
        }

        return ResearchPlan(
            goal = market,
            layers = layers.map { layer -> layer.copy(queries = layer.queries.filter { it.isNotBlank() }) }
                .filter { it.queries.isNotEmpty() },
            createdAt = now
        )
    }

    /** Progress of a fresh plan: every layer pending. */
    fun emptyProgress(plan: ResearchPlan): Map<String, LayerStatus> =
        plan.layers.associate { it.id to LayerStatus.PENDING }
}
