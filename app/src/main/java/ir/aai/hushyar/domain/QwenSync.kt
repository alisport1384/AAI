package ir.aai.hushyar.domain

import ir.aai.hushyar.core.HttpRequest
import ir.aai.hushyar.core.HttpTransport
import ir.aai.hushyar.core.MiniJson
import ir.aai.hushyar.core.Json
import ir.aai.hushyar.data.Repos

/**
 * Qwen — همگام‌سازی دانش اسکنر آنلاین.
 *
 * دانشِ خودِ اپ (IronPin) دست‌نخورده می‌ماند؛ این شیء فقط هر ۲۴ ساعت
 * همان JSONِ روشِ Qwen را از اسکنر می‌گیرد و دقیق (بدون دوباره-چانک‌کردن)
 * وارد جدول knowledge می‌کند. ورودی **دقیقاً** خروجی pipeline Qwen است:
 *   format=qwen-knowledge-v1, chunker 900/140/min200, estimator chars/2.2,
 *   retrieval_hint weights 0.45/0.30/0.15/0.10 top_k=4 — همانِ QwenRag + KnowledgeBank.
 *
 * فایلِ آفلاینِ اولیه هم همراهِ اپ است: assets/qwen_batch_bb9e0626.json
 * تا حتی بدون اینترنت ۲۴۲ نکته قیمت میلگرد/تیرآهن جواب‌گو باشد.
 */
object QwenSync {

    const val DEFAULT_BATCH_URL =
        "https://qwen.alisport0.qzz.io/api/report/batch/bb9e0626-ef04-4c81-a715-d494045b7a77"
    const val PREF_BATCH_URL = "qwen.batch.url"
    const val PREF_BATCH_ID = "qwen.batch.id"
    const val PREF_BATCH_UPDATED_AT = "qwen.batch.updated_at"
    const val PREF_LAST_SYNC = "qwen.batch.last_sync"
    const val PREF_LAST_COUNT = "qwen.batch.last_count"
    const val PREF_SEEDED_QWEN = "qwen_batch_bb9e0626_seeded"

    /** ۲۴ ساعت — طبق درخواست: اپ هر ۲۴ ساعت دانشِ خودش را از همین اطلاعات به‌روز کند. */
    const val SYNC_PERIOD_MS = 24 * 60 * 60 * 1000L
    const val SYNC_FLEX_MS = 60 * 60 * 1000L // ۱ ساعت flex

    data class Verification(
        val identical: Boolean,
        val report: String,
        val chunkerMatch: Boolean,
        val retrievalMatch: Boolean,
        val itemCount: Int
    )

    data class ImportResult(
        val batchId: String,
        val imported: Int,
        val refreshed: Int,
        val ignored: Int,
        val totalItemsInBatch: Int,
        val verification: Verification
    )

    /**
     * بررسی یکسان‌بودنِ بَچِ اسکنر با متد Qwen اپ.
     * برای نمایش به کاربر و برای CI (باید identical=true باشد).
     */
    fun verify(json: String): Verification {
        return try {
            val root = MiniJson.parse(json)
            val fields = root.fieldsOrEmpty
            val format = fields["format"]?.asStringOrNull()
            val chunker = fields["chunker"]?.fieldsOrEmpty ?: emptyMap()
            val hint = fields["retrieval_hint"]?.fieldsOrEmpty ?: emptyMap()
            val weights = hint["weights"]?.fieldsOrEmpty ?: emptyMap()
            val items = fields["items"]?.itemsOrEmpty ?: emptyList()

            val chunkMatch = chunker["target_chars"]?.asIntOrNull() == QwenRag.TARGET_CHARS &&
                chunker["overlap_chars"]?.asIntOrNull() == QwenRag.OVERLAP_CHARS &&
                chunker["min_chars"]?.asIntOrNull() == QwenRag.MIN_CHARS &&
                chunker["target_tokens"]?.asIntOrNull() == 450 &&
                chunker["estimator"]?.asStringOrNull() == "chars/2.2"

            val retrievalMatch = weights["topicMatch"]?.asDoubleOrNull() == 0.45 &&
                weights["claimRelevance"]?.asDoubleOrNull() == 0.3 &&
                weights["freshness"]?.asDoubleOrNull() == 0.15 &&
                weights["confidence"]?.asDoubleOrNull() == 0.1 &&
                hint["top_k"]?.asIntOrNull() == 4

            val formatOk = format == "qwen-knowledge-v1"
            val identical = formatOk && chunkMatch && retrievalMatch
            val report = buildString {
                append("format=").append(format ?: "?")
                append(" chunker=").append(if (chunkMatch) "✓900/140/200" else "✗")
                append(" retrieval=").append(if (retrievalMatch) "✓0.45/0.30/0.15/0.10 top4" else "✗")
                append(" items=").append(items.size)
                if (!identical) append(" — با متد Qwen یکسان نیست!")
                else append(" — دقیقاً متد Qwen")
            }
            Verification(identical, report, chunkMatch, retrievalMatch, items.size)
        } catch (e: Exception) {
            Verification(false, "parse failed: ${e.message}", false, false, 0)
        }
    }

    /** آیا ۲۴ ساعت گذشته و باید دوباره sync کرد؟ */
    fun shouldSync(repos: Repos, now: Long = System.currentTimeMillis()): Boolean {
        val last = repos.preference(PREF_LAST_SYNC)?.toLongOrNull() ?: 0L
        if (last == 0L) return true
        return now - last >= SYNC_PERIOD_MS
    }

    /** نیاز به سیدِ اولیهٔ آفلاین؟ (اصلی‌ترین درخواست: دقیق به اپ اضافه شود) */
    fun needsSeed(repos: Repos): Boolean = repos.preference(PREF_SEEDED_QWEN) != "1"

    /** تبدیلِ یک آیتمِ خامِ بَچ به KnowledgeItemِ دقیقِ اپ (بدون دوباره-چانک). */
    fun toKnowledgeItem(raw: Json, clock: Long = System.currentTimeMillis()): KnowledgeItem? {
        val f = raw.fieldsOrEmpty
        val id = f["id"]?.asStringOrNull() ?: return null
        val topicRaw = f["topic"]?.asStringOrNull() ?: return null
        val claim = f["claim"]?.asStringOrNull() ?: return null
        if (claim.isBlank()) return null
        val question = f["question"]?.asStringOrNull() ?: topicRaw
        val category = f["category"]?.asStringOrNull() ?: "rag"
        val attribute = f["attribute"]?.asStringOrNull()
        val url = f["url"]?.asStringOrNull().orEmpty()
        val domain = f["domain"]?.asStringOrNull().orEmpty()
        val confidence = f["confidence"]?.asDoubleOrNull() ?: 0.68
        val ttl = f["ttl_days"]?.asIntOrNull() ?: KnowledgeBank.DEFAULT_TTL_DAYS
        val layer = f["layer"]?.asStringOrNull().orEmpty()
        val lastVerified = f["last_verified_at"]?.asLongOrNull() ?: clock
        val created = f["created_at"]?.asLongOrNull() ?: lastVerified
        val tagsJson = f["tags"]?.itemsOrEmpty?.mapNotNull { it.asStringOrNull() } ?: emptyList()
        // numeric استخراج از claim (همان KnowledgeBank.numeric)
        val numeric = KnowledgeBank.numeric(claim)
        // topic را به topicKey نرمال می‌کنیم تا answerable با امتیاز کامل (1.0) تطبیق دهد؛
        // topic خامِ اسکنر هم در tags حفظ می‌شود.
        val topicKey = KnowledgeBank.topicKey(topicRaw)
        return KnowledgeItem(
            id = id,
            topic = topicKey,
            question = question.take(300),
            claim = claim.take(600),
            category = category,
            attribute = attribute,
            numericValue = numeric?.first,
            unit = numeric?.second,
            url = url,
            domain = domain,
            confidence = confidence.coerceIn(0.0, 1.0),
            createdAt = created,
            lastVerifiedAt = lastVerified,
            ttlDays = ttl,
            sourceCount = 1,
            layer = layer,
            tags = (tagsJson + listOf("qwen-sync", "scanner:$topicRaw")).distinct().take(16)
        )
    }

    /**
     * واردکردنِ یک JSONِ کاملِ بَچ (از شبکه یا از assets) به knowledge.
     * idempotent — تکرارش فقط REFRESH می‌کند.
     */
    fun importJson(json: String, repos: Repos, clock: Long = System.currentTimeMillis()): ImportResult {
        val verification = verify(json)
        val root = MiniJson.parse(json)
        val batchId = root.fieldsOrEmpty["batch"]?.fieldsOrEmpty?.get("id")?.asStringOrNull()
            ?: root.fieldsOrEmpty["batch"]?.fieldsOrEmpty?.get("id")?.asStringOrNull()
            ?: "unknown"
        val updatedAt = root.fieldsOrEmpty["batch"]?.fieldsOrEmpty?.get("updated_at")?.asLongOrNull()
            ?: clock
        val items = root.fieldsOrEmpty["items"]?.itemsOrEmpty ?: emptyList()
        var imported = 0; var refreshed = 0; var ignored = 0
        for (raw in items) {
            val ki = toKnowledgeItem(raw, clock) ?: continue
            val res = repos.knowledgeUpsert(ki)
            when (res.decision) {
                KnowledgeDecision.NEW -> imported++
                KnowledgeDecision.REFRESH, KnowledgeDecision.CORRECT, KnowledgeDecision.CONFLICT -> refreshed++
                KnowledgeDecision.IGNORE -> ignored++
            }
        }
        // ذخیره متادیتای بَچ
        repos.putPreference(PREF_BATCH_ID, batchId)
        repos.putPreference(PREF_BATCH_UPDATED_AT, updatedAt.toString())
        repos.putPreference(PREF_LAST_SYNC, clock.toString())
        repos.putPreference(PREF_LAST_COUNT, items.size.toString())
        repos.putPreference(PREF_BATCH_URL, DEFAULT_BATCH_URL)
        // اگر این اولین سیدِ آفلاین بود، علامت بزن تا دانشِ اپ باقی بماند و دوباره سیدِ کامل نشود
        if (needsSeed(repos)) repos.putPreference(PREF_SEEDED_QWEN, "1")
        repos.log("qwen-sync", "system", "import batch=$batchId items=${items.size} new=$imported refreshed=$refreshed ignored=$ignored ${verification.report}", DEFAULT_BATCH_URL)
        return ImportResult(batchId, imported, refreshed, ignored, items.size, verification)
    }

    /**
     * گرفتنِ زنده از اسکنر و واردکردن. فقط در صورت موفقیت last_sync به‌روز می‌شود
     * تا آفلاین‌بودن باعث از دست‌رفتنِ نوبتِ بعدی نشود.
     */
    fun fetchAndImport(
        repos: Repos,
        transport: HttpTransport,
        url: String = repos.preference(PREF_BATCH_URL) ?: DEFAULT_BATCH_URL,
        clock: Long = System.currentTimeMillis()
    ): Result<ImportResult> {
        return try {
            val resp = transport.get(HttpRequest(url, mapOf("Accept" to "application/json")))
            if (!resp.ok) return Result.failure(IllegalStateException("HTTP ${resp.status} ${resp.error ?: ""}".trim()))
            if (resp.body.isBlank()) return Result.failure(IllegalStateException("پاسخ خالی از اسکنر"))
            Result.success(importJson(resp.body, repos, clock))
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /** خواندنِ فایلِ assets بدون نیاز به Android Context (برای تست JVM هم). */
    fun importFromAssetsJson(json: String, repos: Repos, clock: Long = System.currentTimeMillis()): ImportResult =
        importJson(json, repos, clock)

    fun statusLine(repos: Repos): String {
        val batchId = repos.preference(PREF_BATCH_ID) ?: "—"
        val cnt = repos.preference(PREF_LAST_COUNT) ?: "0"
        val last = repos.preference(PREF_LAST_SYNC)?.toLongOrNull()
        val age = if (last == null || last == 0L) "هرگز" else ageFa(System.currentTimeMillis() - last)
        val url = repos.preference(PREF_BATCH_URL) ?: DEFAULT_BATCH_URL
        return "بَچ $batchId · $cnt نکته · آخرین همگام‌سازی $age · $url"
    }

    private fun ageFa(deltaMs: Long): String {
        val h = deltaMs / 3_600_000
        if (h < 1) return "همین حالا"
        if (h < 24) return "$h ساعت پیش"
        val d = h / 24; return "$d روز پیش"
    }
}
