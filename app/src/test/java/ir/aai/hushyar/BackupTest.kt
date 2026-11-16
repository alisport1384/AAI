package ir.aai.hushyar

import ir.aai.hushyar.core.Text
import ir.aai.hushyar.domain.AuditEntry
import ir.aai.hushyar.domain.Backup
import ir.aai.hushyar.domain.Interview
import ir.aai.hushyar.domain.KnowledgeItem
import ir.aai.hushyar.domain.Listing
import ir.aai.hushyar.domain.LogEvent
import ir.aai.hushyar.domain.Markdown
import ir.aai.hushyar.domain.MarketPack
import ir.aai.hushyar.domain.MemoryBank
import ir.aai.hushyar.domain.MemoryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupTest {

    private fun pack(): MarketPack = Interview.build(
        mapOf(
            "market_name" to "بازار پشتیبان",
            "attributes" to "حافظه، رم، گارانتی",
            "currency_unit" to "تومان"
        ),
        7_000L
    ).pack

    private fun listing() = Listing(
        id = "l1",
        title = "گوشی سامسونگ گلکسی S24 حافظه 256",
        titleNorm = Text.normalize("گوشی سامسونگ گلکسی S24 حافظه 256"),
        priceRaw = "۵۰ میلیون تومان",
        priceIrt = 50_000_000,
        priceNote = "مستقیم",
        source = "تست",
        url = "https://example.com/l1",
        seller = "فروشگاه",
        city = "تهران",
        attrs = mapOf("attr_1" to "256"),
        createdAt = 12_000L
    )

    private fun memory() = MemoryItem(
        id = "m1",
        kind = "note",
        text = "سقف بودجه‌ام ۲۰ میلیون تومان است",
        createdAt = 13_000L,
        category = MemoryBank.CAT_BUDGET,
        tags = listOf("بودجه", "budget"),
        importance = 0.85,
        useCount = 3,
        lastUsedAt = 14_000L,
        source = "user"
    )

    private fun snapshot() = Backup.Snapshot(
        packs = listOf(pack()),
        activePackId = pack().id,
        listings = listOf(listing()),
        memory = listOf(memory()),
        logs = listOf(LogEvent(1, 15_000L, "answer", "agent", "پاسخ وب", "q=تست")),
        audits = listOf(AuditEntry(16_000L, "user", "backup.create", "x.zip", "")),
        answers = mapOf("market_name" to "بازار پشتیبان"),
        preferences = mapOf("lang" to "fa")
    )

    @Test
    fun encodeThenDecodeRoundTripsEverything() {
        val original = snapshot()
        val json = Backup.encode(original, "0.2.0", 20_000L)

        assertTrue(json.contains(Backup.SCHEMA))
        val info = Backup.inspect(json)
        assertEquals("0.2.0", info.appVersion)
        assertEquals(1, info.counts["packs"])
        assertEquals(64, info.sha256.length)

        val restored = Backup.decode(json)
        assertEquals(1, restored.packs.size)
        assertEquals(original.activePackId, restored.activePackId)
        assertEquals(1, restored.listings.size)
        assertEquals(50_000_000L, restored.listings.first().priceIrt)
        assertEquals("تهران", restored.listings.first().city)
        assertEquals(1, restored.memory.size)
        assertEquals(MemoryBank.CAT_BUDGET, restored.memory.first().category)
        assertEquals(3, restored.memory.first().useCount)
        assertEquals(0.85, restored.memory.first().importance, 0.0001)
        assertEquals(listOf("بودجه", "budget"), restored.memory.first().tags)
        assertEquals(1, restored.logs.size)
        assertEquals(1, restored.audits.size)
        assertEquals("fa", restored.preferences["lang"])
        assertEquals("بازار پشتیبان", restored.answers["market_name"])
    }

    @Test
    fun tamperedPayloadIsRejected() {
        val json = Backup.encode(snapshot(), "0.2.0", 20_000L)
        val tampered = json.replace("۲۰ میلیون", "۹۹ میلیون")
        assertNotEquals(json, tampered)
        try {
            Backup.inspect(tampered)
            fail("چک‌سام نباید قبول می‌کرد")
        } catch (expected: Backup.BackupException) {
            assertTrue(expected.message!!.contains("چک‌سام"))
        }
    }

    @Test
    fun unknownSchemaIsRejected() {
        val json = Backup.encode(snapshot(), "0.2.0", 20_000L).replace(Backup.SCHEMA, "hushyar.backup/9")
        try {
            Backup.inspect(json)
            fail("نسخهٔ ناشناخته نباید قبول شود")
        } catch (expected: Backup.BackupException) {
            assertTrue(expected.message!!.contains("پشتیبانی"))
        }
    }

    @Test
    fun brokenJsonFailsCleanly() {
        try {
            Backup.inspect("{ not json")
            fail("JSON ناقص نباید قبول شود")
        } catch (expected: Backup.BackupException) {
            assertTrue(expected.message!!.isNotBlank())
        }
    }

    @Test
    fun v2BackupsRemainRestorable() {
        val legacy = Backup.encode(Backup.Snapshot(listings = listOf(listing()), memory = listOf(memory())), "0.2.0", 5_000L, schema = "hushyar.backup/2")
        val info = Backup.inspect(legacy)
        assertEquals("hushyar.backup/2", info.schema)
        val restored = Backup.decode(legacy)
        assertEquals(1, restored.listings.size)
        assertTrue(restored.knowledge.isEmpty())
    }

    @Test
    fun knowledgeSurvivesTheRoundTrip() {
        val knowledge = KnowledgeItem(
            id = "k1",
            topic = "قیمت-گوشی",
            question = "قیمت گوشی چقدر است؟",
            claim = "قیمت گوشی ۵۰ میلیون تومان است",
            category = MemoryBank.CAT_FACT,
            numericValue = 50_000_000.0,
            unit = "تومان",
            url = "https://example.com/x",
            domain = "example.com",
            confidence = 0.7,
            createdAt = 30_000L,
            lastVerifiedAt = 31_000L,
            sourceCount = 2,
            supersededById = "k2",
            tags = listOf("قیمت")
        )
        val json = Backup.encode(snapshot().copy(knowledge = listOf(knowledge)), "0.3.0", 40_000L)
        assertEquals(1, Backup.inspect(json).counts["knowledge"])
        val restored = Backup.decode(json).knowledge.single()
        assertEquals("k1", restored.id)
        assertEquals(50_000_000.0, restored.numericValue!!, 1.0)
        assertEquals("تومان", restored.unit)
        assertEquals("k2", restored.supersededById)
        assertEquals(2, restored.sourceCount)
        assertFalse(restored.fresh)
    }

    @Test
    fun emptySnapshotIsStillValid() {
        val json = Backup.encode(Backup.Snapshot(), "0.2.0", 1L)
        val info = Backup.inspect(json)
        assertEquals(0, info.counts["memory"])
        val restored = Backup.decode(json)
        assertTrue(restored.packs.isEmpty())
        assertEquals(null, restored.activePackId)
    }

    @Test
    fun markdownLogIsHumanReadableAndTimestamped() {
        val md = Markdown.log(
            listOf(
                LogEvent(1, 1_700_000_000_000L, "answer", "agent", "پاسخ وب", "q=تست"),
                LogEvent(2, 1_700_000_060_000L, "backup", "user", "بک‌آپ ساخته شد", "")
            )
        )
        assertTrue(md.startsWith("# گزارش فعالیت هوشیار"))
        assertTrue(md.contains("## خط زمانی"))
        assertTrue(md.contains("پاسخ وب"))
        assertTrue(md.contains("1402")) // Jalali year of Nov 2023
    }

    @Test
    fun markdownMemoryGroupsByCategory() {
        val md = Markdown.memory(listOf(memory()))
        assertTrue(md.contains(MemoryBank.CAT_BUDGET))
        assertTrue(md.contains("m1"))
        assertTrue(md.contains("۰.۸۵") || md.contains("0.85"))
    }

    @Test
    fun markdownLogOfEmptySetIsStillAValidDocument() {
        val md = Markdown.log(emptyList())
        assertTrue(md.contains("رویدادی ثبت نشده"))
        assertFalse(md.contains("## خط زمانی\n\n###"))
    }
}
