package ir.aai.hushyar.domain

import ir.aai.hushyar.core.Json
import ir.aai.hushyar.core.MiniJson
import java.net.URI

/** Explicit commands only: ordinary chat never sends a prompt or a file to the optional host. */
object PrivateRoute {
    private val DOCUMENTS = Regex("(?is)^\\s*(?:از\\s+اسناد(?:م|\\s+من)?|docs)\\s*[:：]\\s*(.*)$")
    private val CALCULATE = Regex("(?is)^\\s*(?:حساب\\s*کن|calc)\\s*[:：]\\s*(.*)$")

    fun documents(question: String): String? = DOCUMENTS.matchEntire(question)?.groupValues?.get(1)?.trim()
    fun calculate(question: String): String? = CALCULATE.matchEntire(question)?.groupValues?.get(1)?.trim()
}

/** HTTPS + an explicit certificate fingerprint. Token is deliberately absent from toString/logs. */
class PersonalBackendConfig private constructor(
    val baseUrl: String,
    val pinSha256: String,
    val token: String
) {
    override fun toString(): String = "PersonalBackendConfig(baseUrl=$baseUrl, pinSha256=$pinSha256, token=[redacted])"

    companion object {
        fun of(url: String, fingerprint: String, secret: String): PersonalBackendConfig {
            val uri = try { URI(url.trim()) } catch (_: Exception) { null }
            require(uri?.scheme?.lowercase() == "https" && !uri.host.isNullOrBlank() &&
                uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                (uri.rawPath.isNullOrBlank() || uri.rawPath == "/") && uri.port <= 65535) {
                "نشانی بک‌اند باید https:// همراه با میزبان معتبر، بدون مسیر و پارامتر باشد."
            }
            val pin = fingerprint.lowercase().replace(":", "").trim()
            require(Regex("[0-9a-f]{64}").matches(pin)) { "اثر انگشت SHA-256 گواهی باید ۶۴ رقم شانزده‌شانزدهی باشد." }
            val token = secret.trim()
            require(token.length >= 32 && token.length <= 256 && !token.any(Char::isWhitespace)) {
                "کلید خصوصی باید دست‌کم ۳۲ نویسه و بدون فاصله باشد."
            }
            return PersonalBackendConfig(uri.toString().trimEnd('/'), pin, token)
        }
    }
}

data class PersonalDocument(val id: String, val title: String, val chunks: Int, val createdAt: Long)

data class PersonalIndexResult(val id: String, val chunks: Int, val duplicate: Boolean)

/** Small, strict protocol: *only quoted spans* may be displayed as document evidence. */
object PersonalDocsCodec {
    const val SCHEMA = "hushyar.docs/1"
    private val DOC_ID = Regex("[a-f0-9]{32}")
    private val REF = Regex("D:[a-f0-9]{32}:[0-9]+")

    fun requestQuestion(threadId: String, question: String): String {
        require(threadId.matches(Regex("[A-Za-z0-9_-]{3,70}"))) { "شناسهٔ گفتگو معتبر نیست" }
        require(question.isNotBlank() && question.length <= 400) { "پرسش اسناد باید ۱ تا ۴۰۰ نویسه باشد" }
        return MiniJson.write(MiniJson.obj(
            "thread_id" to MiniJson.str(threadId), "question" to MiniJson.str(question.trim())
        ))
    }

    fun requestDocument(threadId: String, name: String, text: String, kind: String): String {
        require(threadId.matches(Regex("[A-Za-z0-9_-]{3,70}"))) { "شناسهٔ گفتگو معتبر نیست" }
        require(name.isNotBlank() && name.length <= 120) { "عنوان سند باید ۱ تا ۱۲۰ نویسه باشد" }
        require(kind in setOf("text", "markdown", "csv", "html")) { "قالب سند باید متنی باشد" }
        require(text.isNotBlank() && text.toByteArray(Charsets.UTF_8).size <= 100_000) {
            "سند باید متن غیرخالی و حداکثر ۱۰۰ کیلوبایت باشد"
        }
        return MiniJson.write(MiniJson.obj(
            "thread_id" to MiniJson.str(threadId), "title" to MiniJson.str(name.take(120)),
            "text" to MiniJson.str(text), "kind" to MiniJson.str(kind)
        ))
    }

    fun readAnswer(raw: String): Answer {
        val root = root(raw)
        val refused = root["refused"]?.asBoolOrNull() ?: error("وضعیت پاسخِ اسناد مشخص نیست")
        val text = root["text"]?.asStringOrNull()?.takeIf { it.length in 1..500 }
            ?: error("متن پاسخِ اسناد معتبر نیست")
        val bullets = root["bullets"]?.asStrings() ?: error("نقل‌قول‌های اسناد وجود ندارند")
        val citations = root["citations"]?.asObjects() ?: error("استنادهای اسناد وجود ندارند")
        if (refused) {
            require(bullets.isEmpty() && citations.isEmpty()) { "پاسخ ردشده نباید شاهد جعلی داشته باشد" }
            return Answer("personal.docs", text, refused = true,
                missing = root["missing"]?.asStrings().orEmpty().take(3),
                trace = listOf("بک‌اند شخصی شاهد مستند پیدا نکرد؛ حدس نزد."))
        }
        require(citations.isNotEmpty() && citations.size in 1..5 && bullets.size == citations.size) {
            "پاسخ اسناد بدون استناد هم‌اندازه پذیرفته نمی‌شود"
        }
        // No model-generated paragraph with an ungrounded numeric claim is allowed.
        require(Grounding.numbers(text).isEmpty()) { "متن پاسخ عددیِ بدون شاهد دارد" }
        val checked = citations.mapIndexed { index, row ->
            val ref = row["ref"]?.asStringOrNull().orEmpty()
            val label = row["label"]?.asStringOrNull().orEmpty()
            val quote = row["quote"]?.asStringOrNull().orEmpty()
            require(REF.matches(ref) && label.length in 1..100 && quote.length in 1..340 &&
                row["url"]?.asStringOrNull() == null && bullets[index] == "«$quote»") {
                "نقل‌قول یا شناسهٔ استناد معتبر نیست"
            }
            Citation(ref, "سند شخصی: $label", null)
        }
        return Answer("personal.docs", text, bullets = bullets, citations = checked, confidence = 0.65,
            trace = listOf("فقط متن‌های نقل‌شدهٔ اسناد همین گفتگو از سرویس شخصی خوانده شدند؛ وب دوباره بررسی نشد."))
    }

    fun readDocuments(raw: String): List<PersonalDocument> {
        val root = root(raw)
        val list = (root["documents"] as? Json.Arr)?.items ?: error("فهرست اسناد معتبر نیست")
        require(list.size <= 100) { "فهرست اسناد بیش از حد است" }
        return list.map { entry ->
            val id = entry["id"]?.asStringOrNull().orEmpty()
            val title = entry["title"]?.asStringOrNull().orEmpty()
            val chunks = entry["chunks"]?.asIntOrNull() ?: -1
            // A 100 KB text file split at frequent paragraph boundaries can exceed 200 chunks.
            // Keep this bound aligned with the server's byte cap and chunker (at most ~435 chunks).
            require(DOC_ID.matches(id) && title.length in 1..120 && chunks in 1..500) {
                "ردیف سند معتبر نیست"
            }
            PersonalDocument(id, title, chunks, entry["created_at"]?.asLongOrNull() ?: 0L)
        }
    }

    fun readIndex(raw: String): PersonalIndexResult {
        val root = root(raw)
        val id = root["id"]?.asStringOrNull().orEmpty()
        val chunks = root["chunks"]?.asIntOrNull() ?: -1
        require(DOC_ID.matches(id) && chunks in 1..500) { "رسید نمایه‌سازی معتبر نیست" }
        return PersonalIndexResult(id, chunks, root["duplicate"]?.asBoolOrNull() == true)
    }

    fun readDeleted(raw: String): Boolean = root(raw)["deleted"]?.asBoolOrNull()
        ?: error("نتیجهٔ حذف سند معتبر نیست")

    fun readDeletedCount(raw: String): Int = root(raw)["deleted_count"]?.asIntOrNull()
        ?.takeIf { it in 0..100 } ?: error("نتیجهٔ پاک‌سازی سرور معتبر نیست")

    fun readHealth(raw: String): String {
        val root = root(raw)
        require(root["status"]?.asStringOrNull() == "ok" && root["retrieval"]?.asStringOrNull() == "sqlite-fts5") {
            "سرویس جستجوی اسناد آماده نیست"
        }
        return "بک‌اند شخصی آماده است: جستجوی متنی SQLite FTS5؛ بدون API پولی یا مدل سروری."
    }

    private fun root(raw: String): Json.Obj {
        require(raw.length <= 200_000) { "پاسخ سرویس بیش از حد طولانی است" }
        val root = MiniJson.parse(raw) as? Json.Obj ?: error("پاسخ سرویس JSON معتبر نیست")
        require(root["schema"]?.asStringOrNull() == SCHEMA) { "نسخهٔ بک‌اند شخصی سازگار نیست" }
        return root
    }
}
