package ir.aai.hushyar.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import ir.aai.hushyar.core.Ids
import ir.aai.hushyar.core.Json
import ir.aai.hushyar.core.MiniJson
import ir.aai.hushyar.core.Text
import ir.aai.hushyar.domain.AuditEntry
import ir.aai.hushyar.domain.Listing
import ir.aai.hushyar.domain.MarketPack
import ir.aai.hushyar.domain.MemoryItem
import ir.aai.hushyar.domain.Backup
import ir.aai.hushyar.domain.LogEvent
import ir.aai.hushyar.domain.MemoryBank
import ir.aai.hushyar.domain.PackCodec

/**
 * Single-file SQLite schema (DESIGN-v2.0 §7, subset required by v0.1).
 * WAL + no cloud backup: the store is the user's property and stays on the device.
 */
class Db(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE pack(
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                version INTEGER NOT NULL,
                body TEXT NOT NULL,
                active INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            )"""
        )
        db.execSQL(
            """CREATE TABLE interview(
                k TEXT PRIMARY KEY,
                v TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )"""
        )
        db.execSQL(
            """CREATE TABLE listing(
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                title_norm TEXT NOT NULL,
                price_raw TEXT NOT NULL,
                price_irt INTEGER,
                price_note TEXT NOT NULL,
                source TEXT NOT NULL,
                url TEXT,
                seller TEXT,
                city TEXT,
                attrs TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX idx_listing_norm ON listing(title_norm)")
        db.execSQL(MEMORY_TABLE)
        db.execSQL(EVENT_TABLE)
        db.execSQL("CREATE TABLE preference(k TEXT PRIMARY KEY, v TEXT NOT NULL)")
        db.execSQL(
            """CREATE TABLE audit(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                at INTEGER NOT NULL,
                actor TEXT NOT NULL,
                action TEXT NOT NULL,
                target TEXT NOT NULL,
                detail TEXT NOT NULL
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Additive-only: v0.1 data survives the upgrade, nothing is dropped.
        if (oldVersion < 2) {
            for (column in MEMORY_V2_COLUMNS) {
                runCatching { db.execSQL(column) }
            }
            db.execSQL(EVENT_TABLE.replace("CREATE TABLE", "CREATE TABLE IF NOT EXISTS"))
        }
    }

    companion object {
        private const val NAME = "hushyar.db"
        private const val VERSION = 2

        private const val MEMORY_TABLE = """CREATE TABLE memory(
                id TEXT PRIMARY KEY,
                kind TEXT NOT NULL,
                body TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                category TEXT NOT NULL DEFAULT 'عمومی',
                tags TEXT NOT NULL DEFAULT '[]',
                importance REAL NOT NULL DEFAULT 0.5,
                use_count INTEGER NOT NULL DEFAULT 0,
                last_used_at INTEGER NOT NULL DEFAULT 0,
                source TEXT NOT NULL DEFAULT 'user'
            )"""

        private const val EVENT_TABLE = """CREATE TABLE event(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                at INTEGER NOT NULL,
                kind TEXT NOT NULL,
                actor TEXT NOT NULL,
                message TEXT NOT NULL,
                detail TEXT NOT NULL DEFAULT ''
            )"""

        private val MEMORY_V2_COLUMNS = listOf(
            "ALTER TABLE memory ADD COLUMN category TEXT NOT NULL DEFAULT 'عمومی'",
            "ALTER TABLE memory ADD COLUMN tags TEXT NOT NULL DEFAULT '[]'",
            "ALTER TABLE memory ADD COLUMN importance REAL NOT NULL DEFAULT 0.5",
            "ALTER TABLE memory ADD COLUMN use_count INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE memory ADD COLUMN last_used_at INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE memory ADD COLUMN source TEXT NOT NULL DEFAULT 'user'"
        )
    }
}

class Repos(private val db: Db) {

    private val now: Long get() = System.currentTimeMillis()

    // ---------- packs ----------

    fun savePack(pack: MarketPack, makeActive: Boolean = true) {
        val existingVersion = packVersion(pack.id) ?: 0
        val version = if (existingVersion > 0) existingVersion + 1 else 1
        val stored = pack.copy(version = version, createdAt = pack.createdAt.takeIf { it > 0 } ?: now)
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            if (makeActive) w.execSQL("UPDATE pack SET active = 0")
            val values = ContentValues().apply {
                put("id", stored.id)
                put("name", stored.name)
                put("version", version)
                put("body", PackCodec.encode(stored, pretty = false))
                put("active", if (makeActive) 1 else 0)
                put("created_at", stored.createdAt)
            }
            w.insertWithOnConflict("pack", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    fun activePack(): MarketPack? {
        db.readableDatabase.query("pack", null, "active = 1", null, null, null, "created_at DESC", "1").use { c ->
            if (!c.moveToFirst()) return null
            return decodePack(c.getString(c.getColumnIndexOrThrow("body")))
        }
    }

    fun packVersion(id: String): Int? {
        db.readableDatabase.query("pack", arrayOf("version"), "id = ?", arrayOf(id), null, null, null).use { c ->
            if (!c.moveToFirst()) return null
            return c.getInt(0)
        }
    }

    fun packs(): List<Triple<String, String, Int>> {
        db.readableDatabase.query("pack", arrayOf("id", "name", "version"), null, null, null, null, "created_at DESC").use { c ->
            val out = mutableListOf<Triple<String, String, Int>>()
            while (c.moveToNext()) out.add(Triple(c.getString(0), c.getString(1), c.getInt(2)))
            return out
        }
    }

    fun activatePack(id: String) {
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            w.execSQL("UPDATE pack SET active = 0")
            w.execSQL("UPDATE pack SET active = 1 WHERE id = ?", arrayOf(id))
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    fun deletePack(id: String) {
        db.writableDatabase.delete("pack", "id = ?", arrayOf(id))
    }

    fun importPackJson(json: String): MarketPack {
        val pack = PackCodec.decode(json)
        savePack(pack, makeActive = true)
        return pack
    }

    private fun decodePack(body: String): MarketPack? = runCatching { PackCodec.decode(body) }.getOrNull()

    // ---------- interview ----------

    fun answers(): Map<String, String> {
        db.readableDatabase.query("interview", arrayOf("k", "v"), null, null, null, null, "updated_at ASC").use { c ->
            val out = LinkedHashMap<String, String>()
            while (c.moveToNext()) out[c.getString(0)] = c.getString(1)
            return out
        }
    }

    fun saveAnswer(key: String, value: String) {
        val values = ContentValues().apply {
            put("k", key)
            put("v", value)
            put("updated_at", now)
        }
        db.writableDatabase.insertWithOnConflict("interview", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun clearAnswers() {
        db.writableDatabase.delete("interview", null, null)
    }

    // ---------- listings ----------

    fun insertListing(listing: Listing): Boolean {
        val values = listingValues(listing.copy(createdAt = listing.createdAt.takeIf { it > 0 } ?: now))
        return db.writableDatabase.insertWithOnConflict("listing", null, values, SQLiteDatabase.CONFLICT_REPLACE) > 0
    }

    fun insertListings(items: List<Listing>): Int {
        val w = db.writableDatabase
        var count = 0
        w.beginTransaction()
        try {
            items.forEach { if (insertListing(it)) count++ }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
        return count
    }

    fun listings(): List<Listing> {
        db.readableDatabase.query("listing", null, null, null, null, null, "created_at DESC").use { c ->
            val out = mutableListOf<Listing>()
            while (c.moveToNext()) out.add(readListing(c))
            return out
        }
    }

    fun deleteListing(id: String) {
        db.writableDatabase.delete("listing", "id = ?", arrayOf(id))
    }

    fun clearListings() {
        db.writableDatabase.delete("listing", null, null)
    }

    private fun readListing(c: Cursor): Listing {
        val attrsJson = c.getString(c.getColumnIndexOrThrow("attrs"))
        val attrs = runCatching {
            MiniJson.parse(attrsJson).fieldsOrEmpty.mapValues { it.value.asStringOrNull().orEmpty() }
        }.getOrDefault(emptyMap())
        val priceIdx = c.getColumnIndexOrThrow("price_irt")
        return Listing(
            id = c.getString(c.getColumnIndexOrThrow("id")),
            title = c.getString(c.getColumnIndexOrThrow("title")),
            titleNorm = c.getString(c.getColumnIndexOrThrow("title_norm")),
            priceRaw = c.getString(c.getColumnIndexOrThrow("price_raw")),
            priceIrt = if (c.isNull(priceIdx)) null else c.getLong(priceIdx),
            priceNote = c.getString(c.getColumnIndexOrThrow("price_note")),
            source = c.getString(c.getColumnIndexOrThrow("source")),
            url = c.getString(c.getColumnIndexOrThrow("url")),
            seller = c.getString(c.getColumnIndexOrThrow("seller")),
            city = c.getString(c.getColumnIndexOrThrow("city")),
            attrs = attrs,
            createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"))
        )
    }

    // ---------- memory / preferences ----------

    /** Backwards-compatible entry point: classification happens automatically. */
    fun addMemory(kind: String, body: String) {
        val category = MemoryBank.classify(body, null)
        addMemory(kind, body, category, MemoryBank.tags(body, null), MemoryBank.importance(body, true, null), "user")
    }

    fun addMemory(
        kind: String,
        body: String,
        category: String,
        tags: List<String>,
        importance: Double,
        source: String
    ): MemoryItem {
        val item = MemoryItem(
            id = Ids.new("mem"),
            kind = kind,
            text = body.take(4000),
            createdAt = now,
            category = category,
            tags = tags.take(14),
            importance = importance.coerceIn(0.0, 1.0),
            source = source
        )
        db.writableDatabase.insertWithOnConflict("memory", null, memoryValues(item), SQLiteDatabase.CONFLICT_REPLACE)
        return item
    }

    private fun memoryValues(item: MemoryItem): ContentValues = ContentValues().apply {
        put("id", item.id)
        put("kind", item.kind)
        put("body", item.text)
        put("created_at", item.createdAt)
        put("category", item.category)
        put("tags", MiniJson.write(MiniJson.strs(item.tags), pretty = false))
        put("importance", item.importance)
        put("use_count", item.useCount)
        put("last_used_at", item.lastUsedAt)
        put("source", item.source)
    }

    fun memories(limit: Int = 50): List<MemoryItem> {
        db.readableDatabase.query("memory", null, null, null, null, null, "created_at DESC", limit.toString()).use { c ->
            val out = mutableListOf<MemoryItem>()
            while (c.moveToNext()) out.add(readMemory(c))
            return out
        }
    }

    fun memoryAll(limit: Int = 1000): List<MemoryItem> =
        db.readableDatabase.query("memory", null, null, null, null, null, "importance DESC, created_at DESC", limit.toString()).use { c ->
            val out = mutableListOf<MemoryItem>()
            while (c.moveToNext()) out.add(readMemory(c))
            return out
        }

    private fun readMemory(c: Cursor): MemoryItem {
        val tagsIdx = c.getColumnIndex("tags")
        val tags = if (tagsIdx < 0) emptyList() else runCatching {
            MiniJson.parse(c.getString(tagsIdx)).asStrings()
        }.getOrDefault(emptyList())
        fun text(column: String, fallback: String): String {
            val idx = c.getColumnIndex(column)
            return if (idx < 0) fallback else c.getString(idx) ?: fallback
        }
        fun real(column: String, fallback: Double): Double {
            val idx = c.getColumnIndex(column)
            return if (idx < 0) fallback else c.getDouble(idx)
        }
        fun integer(column: String, fallback: Long): Long {
            val idx = c.getColumnIndex(column)
            return if (idx < 0) fallback else c.getLong(idx)
        }
        return MemoryItem(
            id = c.getString(c.getColumnIndexOrThrow("id")),
            kind = c.getString(c.getColumnIndexOrThrow("kind")),
            text = c.getString(c.getColumnIndexOrThrow("body")),
            createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
            category = text("category", MemoryBank.CAT_GENERAL),
            tags = tags,
            importance = real("importance", 0.5),
            useCount = integer("use_count", 0L).toInt(),
            lastUsedAt = integer("last_used_at", 0L),
            source = text("source", "user")
        )
    }

    /** Direct record editing from the data-control screen. */
    fun updateMemory(item: MemoryItem) {
        db.writableDatabase.update("memory", memoryValues(item), "id = ?", arrayOf(item.id))
        audit("user", "memory.update", item.id, item.text.take(200))
    }

    fun deleteMemory(id: String): Boolean {
        val removed = db.writableDatabase.delete("memory", "id = ?", arrayOf(id)) > 0
        if (removed) audit("user", "memory.delete", id, "")
        return removed
    }

    fun touchMemory(ids: List<String>) {
        if (ids.isEmpty()) return
        val stamp = now
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            for (id in ids) {
                w.execSQL(
                    "UPDATE memory SET use_count = use_count + 1, last_used_at = ? WHERE id = ?",
                    arrayOf<Any>(stamp, id)
                )
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    fun countMemories(): Int =
        db.readableDatabase.rawQuery("SELECT COUNT(*) FROM memory", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    // ---------- event log (the assistant's own .md source) ----------

    fun log(kind: String, actor: String, message: String, detail: String = "") {
        val values = ContentValues().apply {
            put("at", now)
            put("kind", kind)
            put("actor", actor)
            put("message", message.take(1000))
            put("detail", detail.take(2000))
        }
        db.writableDatabase.insert("event", null, values)
    }

    fun events(limit: Int = 200): List<LogEvent> {
        db.readableDatabase.query("event", null, null, null, null, null, "id DESC", limit.toString()).use { c ->
            val out = mutableListOf<LogEvent>()
            while (c.moveToNext()) {
                out.add(
                    LogEvent(
                        id = c.getLong(c.getColumnIndexOrThrow("id")),
                        at = c.getLong(c.getColumnIndexOrThrow("at")),
                        kind = c.getString(c.getColumnIndexOrThrow("kind")),
                        actor = c.getString(c.getColumnIndexOrThrow("actor")),
                        message = c.getString(c.getColumnIndexOrThrow("message")),
                        detail = c.getString(c.getColumnIndexOrThrow("detail"))
                    )
                )
            }
            return out
        }
    }

    fun clearEvents() {
        db.writableDatabase.delete("event", null, null)
    }

    // ---------- listings (edit in place) ----------

    fun updateListing(listing: Listing) {
        val values = ContentValues().apply {
            put("title", listing.title)
            put("title_norm", Text.normalize(listing.title))
            put("price_raw", listing.priceRaw)
            put("price_irt", listing.priceIrt)
            put("price_note", listing.priceNote)
            put("source", listing.source)
            put("url", listing.url)
            put("seller", listing.seller)
            put("city", listing.city)
            put("attrs", MiniJson.write(MiniJson.objOf(listing.attrs.mapValues { MiniJson.str(it.value) }), pretty = false))
        }
        db.writableDatabase.update("listing", values, "id = ?", arrayOf(listing.id))
        audit("user", "listing.update", listing.id, listing.title.take(160))
    }

    fun putPreference(key: String, value: String) {
        val values = ContentValues().apply { put("k", key); put("v", value) }
        db.writableDatabase.insertWithOnConflict("preference", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun preference(key: String): String? {
        db.readableDatabase.query("preference", arrayOf("v"), "k = ?", arrayOf(key), null, null, null).use { c ->
            if (!c.moveToFirst()) return null
            return c.getString(0)
        }
    }

    // ---------- audit ----------

    fun audit(actor: String, action: String, target: String, detail: String) {
        val values = ContentValues().apply {
            put("at", now)
            put("actor", actor)
            put("action", action)
            put("target", target)
            put("detail", detail.take(2000))
        }
        db.writableDatabase.insert("audit", null, values)
    }

    fun audits(limit: Int = 100): List<AuditEntry> {
        db.readableDatabase.query("audit", null, null, null, null, null, "id DESC", limit.toString()).use { c ->
            val out = mutableListOf<AuditEntry>()
            while (c.moveToNext()) {
                out.add(
                    AuditEntry(
                        at = c.getLong(c.getColumnIndexOrThrow("at")),
                        actor = c.getString(c.getColumnIndexOrThrow("actor")),
                        action = c.getString(c.getColumnIndexOrThrow("action")),
                        target = c.getString(c.getColumnIndexOrThrow("target")),
                        detail = c.getString(c.getColumnIndexOrThrow("detail"))
                    )
                )
            }
            return out
        }
    }

    fun resetEverything() {
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            listOf("listing", "pack", "interview", "memory", "preference", "audit", "event").forEach { w.delete(it, null, null) }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    // ---------- backup / restore ----------

    fun packsAll(): List<MarketPack> =
        db.readableDatabase.query("pack", arrayOf("body"), null, null, null, null, "created_at ASC").use { c ->
            val out = mutableListOf<MarketPack>()
            while (c.moveToNext()) decodePack(c.getString(0))?.let { out.add(it) }
            return out
        }

    fun activePackId(): String? =
        db.readableDatabase.query("pack", arrayOf("id"), "active = 1", null, null, null, null).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    fun allAnswers(): Map<String, String> = answers()

    fun allPreferences(): Map<String, String> =
        db.readableDatabase.query("preference", null, null, null, null, null, null).use { c ->
            val out = LinkedHashMap<String, String>()
            while (c.moveToNext()) out[c.getString(c.getColumnIndexOrThrow("k"))] = c.getString(c.getColumnIndexOrThrow("v"))
            out
        }

    fun auditsAll(limit: Int = 2000): List<AuditEntry> = audits(limit)

    /** Everything the app knows, ready to be sealed by [Backup]. */
    fun snapshot(): Backup.Snapshot = Backup.Snapshot(
        packs = packsAll(),
        activePackId = activePackId(),
        listings = listings(),
        memory = memoryAll(5000),
        logs = events(5000),
        audits = auditsAll(5000),
        answers = allAnswers(),
        preferences = allPreferences()
    )

    /**
     * Restore is a full replacement inside one transaction: either the whole backup lands or
     * the previous state stays intact. No partial brain.
     */
    fun restore(snapshot: Backup.Snapshot) {
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            listOf("listing", "pack", "interview", "memory", "preference", "audit", "event").forEach { w.delete(it, null, null) }
            for (pack in snapshot.packs) {
                val values = ContentValues().apply {
                    put("id", pack.id)
                    put("name", pack.name)
                    put("version", pack.version)
                    put("body", PackCodec.encode(pack, pretty = false))
                    put("active", if (pack.id == snapshot.activePackId) 1 else 0)
                    put("created_at", pack.createdAt.takeIf { it > 0 } ?: now)
                }
                w.insertWithOnConflict("pack", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            for (listing in snapshot.listings) {
                w.insertWithOnConflict("listing", null, listingValues(listing), SQLiteDatabase.CONFLICT_REPLACE)
            }
            for (item in snapshot.memory) {
                w.insertWithOnConflict("memory", null, memoryValues(item), SQLiteDatabase.CONFLICT_REPLACE)
            }
            for (event in snapshot.logs) {
                val values = ContentValues().apply {
                    put("at", event.at)
                    put("kind", event.kind)
                    put("actor", event.actor)
                    put("message", event.message)
                    put("detail", event.detail)
                }
                w.insert("event", null, values)
            }
            for (audit in snapshot.audits) {
                val values = ContentValues().apply {
                    put("at", audit.at)
                    put("actor", audit.actor)
                    put("action", audit.action)
                    put("target", audit.target)
                    put("detail", audit.detail)
                }
                w.insert("audit", null, values)
            }
            for ((k, v) in snapshot.answers) {
                w.insertWithOnConflict("interview", null, ContentValues().apply {
                    put("k", k); put("v", v); put("updated_at", now)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            for ((k, v) in snapshot.preferences) {
                w.insertWithOnConflict("preference", null, ContentValues().apply {
                    put("k", k); put("v", v)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
        audit("user", "backup.restore", snapshot.activePackId ?: "-", "پک:${snapshot.packs.size} آگهی:${snapshot.listings.size} حافظه:${snapshot.memory.size}")
    }

    private fun listingValues(listing: Listing): ContentValues = ContentValues().apply {
        put("id", listing.id)
        put("title", listing.title)
        put("title_norm", listing.titleNorm.ifBlank { Text.normalize(listing.title) })
        put("price_raw", listing.priceRaw)
        put("price_irt", listing.priceIrt)
        put("price_note", listing.priceNote)
        put("source", listing.source)
        put("url", listing.url)
        put("seller", listing.seller)
        put("city", listing.city)
        put("attrs", MiniJson.write(MiniJson.objOf(listing.attrs.mapValues { MiniJson.str(it.value) }), pretty = false))
        put("created_at", listing.createdAt)
    }
}

/**
 * CSV import with header detection. Positional fallback: title, price, source.
 * Pack attribute labels (or their slugs) are matched to header cells, so the same
 * importer works for any market without code changes.
 */
object CsvImporter {

    data class Result(val listings: List<Listing>, val skipped: Int, val errors: List<String>)

    fun parse(text: String, pack: MarketPack, now: Long): Result {
        val lines = text.split("\n", "\r\n").filter { it.isNotBlank() }
        if (lines.isEmpty()) return Result(emptyList(), 0, listOf("ورودی خالی است."))
        val delimiter = detectDelimiter(lines.first())
        val header = splitLine(lines.first(), delimiter).map { Text.normalize(it) }
        val hasHeader = header.any { it.contains("title") || it.contains(Text.normalize("عنوان")) || it.contains("price") || it.contains(Text.normalize("قیمت")) }
        val attrByHeader = pack.attributes.associateBy { Text.normalize(it.label) }

        val out = mutableListOf<Listing>()
        val errors = mutableListOf<String>()
        var skipped = 0

        val body = if (hasHeader) lines.drop(1) else lines
        body.forEachIndexed { index, line ->
            val cells = splitLine(line, delimiter)
            val row = if (hasHeader) mapRow(header, cells) else mapPositional(cells)
            val title = row["title"].orEmpty().trim()
            if (title.isEmpty()) { skipped++; return@forEachIndexed }
            val priceRaw = row["price"].orEmpty()
            val price = ir.aai.hushyar.core.PriceParser.parse(priceRaw, pack.priceRules)
            val attrs = LinkedHashMap<String, String>()
            for ((k, v) in row) {
                val spec = attrByHeader[k] ?: pack.attributes.firstOrNull { Text.normalize(it.id) == k }
                if (spec != null && v.isNotBlank()) attrs[spec.id] = v.trim()
            }
            if (price.irt == null) errors.add("سطر ${index + 1}: قیمت «$priceRaw» نامعلوم است (${price.note}).")
            out.add(
                Listing(
                    id = Ids.new("l"),
                    title = title,
                    titleNorm = Text.normalize(title),
                    priceRaw = priceRaw,
                    priceIrt = price.irt,
                    priceNote = price.note,
                    source = row["source"].orEmpty().ifBlank { "ورود فایل" },
                    url = row["url"]?.takeIf { it.isNotBlank() },
                    seller = row["seller"]?.takeIf { it.isNotBlank() },
                    city = row["city"]?.takeIf { it.isNotBlank() },
                    attrs = attrs,
                    createdAt = now
                )
            )
        }
        return Result(out, skipped, errors)
    }

    private fun detectDelimiter(line: String): Char = when {
        line.count { it == ',' } >= line.count { it == ';' } && line.contains(',') -> ','
        line.contains(';') && !line.contains(',') -> ';'
        line.contains('\t') -> '\t'
        else -> ','
    }

    private fun splitLine(line: String, delimiter: Char): List<String> {
        val cells = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        for (ch in line) {
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch == delimiter && !inQuotes -> { cells.add(sb.toString()); sb.clear() }
                else -> sb.append(ch)
            }
        }
        cells.add(sb.toString())
        return cells.map { it.trim() }
    }

    private fun mapRow(header: List<String>, cells: List<String>): Map<String, String> {
        val row = LinkedHashMap<String, String>()
        header.forEachIndexed { i, key ->
            val value = cells.getOrNull(i)?.trim().orEmpty()
            val canonical = when {
                key.contains("title") || key.contains(Text.normalize("عنوان")) || key.contains(Text.normalize("نام")) -> "title"
                key.contains("price") || key.contains(Text.normalize("قیمت")) -> "price"
                key.contains("source") || key.contains(Text.normalize("منبع")) -> "source"
                key.contains("url") || key.contains("link") || key.contains(Text.normalize("لینک")) -> "url"
                key.contains("seller") || key.contains(Text.normalize("فروشنده")) -> "seller"
                key.contains("city") || key.contains(Text.normalize("شهر")) -> "city"
                else -> key
            }
            if (value.isNotEmpty()) row[canonical] = value
        }
        return row
    }

    private fun mapPositional(cells: List<String>): Map<String, String> = mapOf(
        "title" to cells.getOrElse(0) { "" },
        "price" to cells.getOrElse(1) { "" },
        "source" to cells.getOrElse(2) { "" }
    )
}
