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
        db.execSQL(
            """CREATE TABLE memory(
                id TEXT PRIMARY KEY,
                kind TEXT NOT NULL,
                body TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )"""
        )
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
        // v0.1: schema is additive-only; a fresh install is the supported path.
    }

    companion object {
        private const val NAME = "hushyar.db"
        private const val VERSION = 1
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
        val values = ContentValues().apply {
            put("id", listing.id)
            put("title", listing.title)
            put("title_norm", listing.titleNorm)
            put("price_raw", listing.priceRaw)
            if (listing.priceIrt != null) put("price_irt", listing.priceIrt) else putNull("price_irt")
            put("price_note", listing.priceNote)
            put("source", listing.source)
            put("url", listing.url)
            put("seller", listing.seller)
            put("city", listing.city)
            put("attrs", MiniJson.write(MiniJson.objOf(listing.attrs.mapValues { Json.Str(it.value) })))
            put("created_at", listing.createdAt.takeIf { it > 0 } ?: now)
        }
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

    fun addMemory(kind: String, body: String) {
        val values = ContentValues().apply {
            put("id", Ids.new("mem"))
            put("kind", kind)
            put("body", body.take(4000))
            put("created_at", now)
        }
        db.writableDatabase.insertWithOnConflict("memory", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun memories(limit: Int = 50): List<MemoryItem> {
        db.readableDatabase.query("memory", null, null, null, null, null, "created_at DESC", limit.toString()).use { c ->
            val out = mutableListOf<MemoryItem>()
            while (c.moveToNext()) {
                out.add(
                    MemoryItem(
                        id = c.getString(c.getColumnIndexOrThrow("id")),
                        kind = c.getString(c.getColumnIndexOrThrow("kind")),
                        text = c.getString(c.getColumnIndexOrThrow("body")),
                        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"))
                    )
                )
            }
            return out
        }
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
            listOf("listing", "pack", "interview", "memory", "preference", "audit").forEach { w.delete(it, null, null) }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
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
