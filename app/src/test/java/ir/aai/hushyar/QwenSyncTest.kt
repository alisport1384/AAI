package ir.aai.hushyar

import ir.aai.hushyar.domain.KnowledgeBank
import ir.aai.hushyar.domain.QwenRag
import ir.aai.hushyar.domain.QwenSync
import ir.aai.hushyar.core.MiniJson
import org.junit.Assert.*
import org.junit.Test

class QwenSyncTest {

    private fun batchJson(): String {
        // باندلِ همراهِ APK — همان فایل assets
        val stream = this::class.java.classLoader.getResourceAsStream("qwen_batch_bb9e0626.json")
            ?: this::class.java.classLoader.getResourceAsStream("assets/qwen_batch_bb9e0626.json")
        if (stream != null) return stream.bufferedReader().readText()
        // fallback: خواندن از فایلِ تستِ workspace
        return java.io.File("app/src/main/assets/qwen_batch_bb9e0626.json").takeIf { it.exists() }?.readText()
            ?: java.io.File("src/main/assets/qwen_batch_bb9e0626.json").takeIf { it.exists() }?.readText()
            ?: error("batch json not found for test")
    }

    @Test
    fun chunkerExactlyMatchesQwenRag() {
        val v = QwenSync.verify(batchJson())
        assertTrue("chunker must match QwenRag: ${v.report}", v.chunkerMatch)
        assertEquals(900, QwenRag.TARGET_CHARS)
        assertEquals(140, QwenRag.OVERLAP_CHARS)
        assertEquals(200, QwenRag.MIN_CHARS)
    }

    @Test
    fun retrievalHintExactlyMatchesKnowledgeBank() {
        val v = QwenSync.verify(batchJson())
        assertTrue("retrieval_hint must match KnowledgeBank.answerable weights: ${v.report}", v.retrievalMatch)
        assertTrue(v.identical)
    }

    @Test
    fun batchContainsExpectedCounts() {
        val json = batchJson()
        val root = MiniJson.parse(json)
        val items = root.fieldsOrEmpty["items"]?.itemsOrEmpty ?: emptyList()
        assertEquals(242, items.size)
        val v = QwenSync.verify(json)
        assertEquals(242, v.itemCount)
        // دو موضوعِ اسکن‌شده — درست همان لینکِ داده‌شده
        val batch = root.fieldsOrEmpty["batch"]?.fieldsOrEmpty
        assertEquals("bb9e0626-ef04-4c81-a715-d494045b7a77", batch?.get("id")?.asStringOrNull())
    }

    @Test
    fun importedItemsAreAnswerableForBothTopics() {
        val json = batchJson()
        val root = MiniJson.parse(json)
        val rawItems = root.fieldsOrEmpty["items"]?.itemsOrEmpty ?: emptyList()
        val now = 1795128324971L // همان last_verified_atِ بَچ (آینده نیست، 1405/08/28)
        val kis = rawItems.mapNotNull { QwenSync.toKnowledgeItem(it, now) }
        assertEquals(242, kis.size)
        // قیمت میلگرد باید جواب‌گو باشد
        val forRebar = KnowledgeBank.answerable("قیمت میلگرد چنده؟", kis, now, limit = 4)
        assertTrue("قیمت میلگرد باید حداقل 1 جواب داشته باشد", forRebar.isNotEmpty())
        assertTrue(forRebar.any { it.claim.contains("میلگرد") || it.topic.contains("میلگرد") || it.tags.any { t -> t.contains("میلگرد") } || it.question.contains("میلگرد") })

        val forBeam = KnowledgeBank.answerable("قیمت تیرآهن ۱۶ چنده؟", kis, now, limit = 4)
        assertTrue("قیمت تیرآهن باید حداقل 1 جواب داشته باشد", forBeam.isNotEmpty())
    }

    @Test
    fun toKnowledgeItemPreservesRequiredFields() {
        val json = batchJson()
        val first = MiniJson.parse(json).fieldsOrEmpty["items"]?.itemsOrEmpty?.first()
            ?: error("no items")
        val ki = QwenSync.toKnowledgeItem(first, 1795128324971L)!!
        assertTrue(ki.id.isNotBlank())
        assertTrue(ki.topic.isNotBlank())
        assertTrue(ki.claim.isNotBlank())
        assertTrue(ki.url.startsWith("http"))
        assertTrue(ki.domain.isNotBlank())
        assertTrue(ki.tags.contains("qwen-sync"))
        // ttl همان 45 روزِ KnowledgeBank
        assertEquals(45, ki.ttlDays)
        // confidence همان 0.68 اسکنر
        assertTrue(ki.confidence in 0.5..0.75)
    }
}
