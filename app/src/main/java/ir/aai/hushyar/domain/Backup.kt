package ir.aai.hushyar.domain

import ir.aai.hushyar.core.Json
import ir.aai.hushyar.core.MiniJson
import java.security.MessageDigest

/**
 * Complete, verifiable backup of everything the app owns: market packs, listings, memory,
 * the event log, the audit trail, interview answers and preferences. One JSON document with
 * a SHA-256 over its own data block, so a truncated or tampered file is refused instead of
 * silently restoring half a brain.
 */
object Backup {

    const val SCHEMA: String = "hushyar.backup/3"

    /** Older snapshots stay restorable: v0.2 files simply carry no knowledge block. */
    val ACCEPTED_SCHEMAS: Set<String> = setOf(SCHEMA, "hushyar.backup/2")

    data class Snapshot(
        val packs: List<MarketPack> = emptyList(),
        val activePackId: String? = null,
        val listings: List<Listing> = emptyList(),
        val memory: List<MemoryItem> = emptyList(),
        val knowledge: List<KnowledgeItem> = emptyList(),
        val logs: List<LogEvent> = emptyList(),
        val audits: List<AuditEntry> = emptyList(),
        val answers: Map<String, String> = emptyMap(),
        val preferences: Map<String, String> = emptyMap()
    )

    data class Info(
        val schema: String,
        val createdAt: Long,
        val appVersion: String,
        val counts: Map<String, Int>,
        val sha256: String
    )

    class BackupException(message: String) : Exception(message)

    /**
     * @param schema defaults to the current schema; the parameter exists so compatibility with
     * older envelopes can be exercised in tests instead of being asserted in comments.
     */
    fun encode(snapshot: Snapshot, appVersion: String, createdAt: Long, schema: String = SCHEMA): String {
        val data = MiniJson.obj(
            "packs" to MiniJson.arr(snapshot.packs.map { pack ->
                MiniJson.obj(
                    "id" to MiniJson.str(pack.id),
                    "version" to MiniJson.num(pack.version),
                    "active" to MiniJson.bool(pack.id == snapshot.activePackId),
                    "body" to MiniJson.str(PackCodec.encode(pack, pretty = false))
                )
            }),
            "listings" to MiniJson.arr(snapshot.listings.map { listing ->
                MiniJson.obj(
                    "id" to MiniJson.str(listing.id),
                    "title" to MiniJson.str(listing.title),
                    "titleNorm" to MiniJson.str(listing.titleNorm),
                    "priceRaw" to MiniJson.str(listing.priceRaw),
                    "priceIrt" to MiniJson.numOrNull(listing.priceIrt),
                    "priceNote" to MiniJson.str(listing.priceNote),
                    "source" to MiniJson.str(listing.source),
                    "url" to MiniJson.str(listing.url ?: ""),
                    "seller" to MiniJson.str(listing.seller ?: ""),
                    "city" to MiniJson.str(listing.city ?: ""),
                    "attrs" to MiniJson.objOf(listing.attrs.mapValues { MiniJson.str(it.value) }),
                    "createdAt" to MiniJson.num(listing.createdAt)
                )
            }),
            "memory" to MiniJson.arr(snapshot.memory.map { item ->
                MiniJson.obj(
                    "id" to MiniJson.str(item.id),
                    "kind" to MiniJson.str(item.kind),
                    "text" to MiniJson.str(item.text),
                    "category" to MiniJson.str(item.category),
                    "tags" to MiniJson.strs(item.tags),
                    "importance" to MiniJson.num(item.importance),
                    "useCount" to MiniJson.num(item.useCount),
                    "lastUsedAt" to MiniJson.num(item.lastUsedAt),
                    "source" to MiniJson.str(item.source),
                    "createdAt" to MiniJson.num(item.createdAt)
                )
            }),
            "knowledge" to MiniJson.arr(snapshot.knowledge.map { item ->
                MiniJson.obj(
                    "id" to MiniJson.str(item.id),
                    "topic" to MiniJson.str(item.topic),
                    "question" to MiniJson.str(item.question),
                    "claim" to MiniJson.str(item.claim),
                    "category" to MiniJson.str(item.category),
                    "attribute" to MiniJson.str(item.attribute ?: ""),
                    "numericValue" to MiniJson.numOrNull(item.numericValue),
                    "unit" to MiniJson.str(item.unit ?: ""),
                    "url" to MiniJson.str(item.url),
                    "domain" to MiniJson.str(item.domain),
                    "confidence" to MiniJson.num(item.confidence),
                    "createdAt" to MiniJson.num(item.createdAt),
                    "lastVerifiedAt" to MiniJson.num(item.lastVerifiedAt),
                    "ttlDays" to MiniJson.num(item.ttlDays),
                    "sourceCount" to MiniJson.num(item.sourceCount),
                    "supersededBy" to MiniJson.str(item.supersededById ?: ""),
                    "tags" to MiniJson.strs(item.tags)
                )
            }),
            "logs" to MiniJson.arr(snapshot.logs.map { event ->
                MiniJson.obj(
                    "id" to MiniJson.num(event.id),
                    "at" to MiniJson.num(event.at),
                    "kind" to MiniJson.str(event.kind),
                    "actor" to MiniJson.str(event.actor),
                    "message" to MiniJson.str(event.message),
                    "detail" to MiniJson.str(event.detail)
                )
            }),
            "audits" to MiniJson.arr(snapshot.audits.map { audit ->
                MiniJson.obj(
                    "at" to MiniJson.num(audit.at),
                    "actor" to MiniJson.str(audit.actor),
                    "action" to MiniJson.str(audit.action),
                    "target" to MiniJson.str(audit.target),
                    "detail" to MiniJson.str(audit.detail)
                )
            }),
            "answers" to MiniJson.objOf(snapshot.answers.mapValues { MiniJson.str(it.value) }),
            "preferences" to MiniJson.objOf(snapshot.preferences.mapValues { MiniJson.str(it.value) })
        )
        val canonical = MiniJson.write(data, pretty = false)
        val digest = sha256(canonical)
        val envelope = MiniJson.obj(
            "schema" to MiniJson.str(schema),
            "createdAt" to MiniJson.num(createdAt),
            "appVersion" to MiniJson.str(appVersion),
            "sha256" to MiniJson.str(digest),
            "counts" to MiniJson.objOf(
                mapOf(
                    "packs" to MiniJson.num(snapshot.packs.size),
                    "listings" to MiniJson.num(snapshot.listings.size),
                    "memory" to MiniJson.num(snapshot.memory.size),
                    "knowledge" to MiniJson.num(snapshot.knowledge.size),
                    "logs" to MiniJson.num(snapshot.logs.size),
                    "audits" to MiniJson.num(snapshot.audits.size)
                )
            ),
            "data" to data
        )
        return MiniJson.write(envelope, pretty = true)
    }

    /** Verifies schema + checksum and returns the header, without trusting the payload. */
    fun inspect(json: String): Info {
        val root = parse(json)
        val schema = root["schema"]?.asStringOrNull() ?: throw BackupException("این فایل پشتیبان خوانا نیست: schema ندارد")
        if (schema !in ACCEPTED_SCHEMAS) throw BackupException("نسخهٔ پشتیبان پشتیبانی نمی‌شود: $schema")
        val data = root["data"] ?: throw BackupException("بلوک data وجود ندارد")
        val declared = root["sha256"]?.asStringOrNull() ?: throw BackupException("چک‌سام وجود ندارد")
        val actual = sha256(MiniJson.write(data, pretty = false))
        if (!actual.equals(declared, ignoreCase = true)) {
            throw BackupException("چک‌سام مطابق نیست: فایل ناقص یا تغییر‌یافته است")
        }
        val counts = root["counts"]?.fieldsOrEmpty?.mapValues { it.value.asIntOrNull() ?: 0 } ?: emptyMap()
        return Info(
            schema = schema,
            createdAt = root["createdAt"]?.asLongOrNull() ?: 0L,
            appVersion = root["appVersion"]?.asStringOrNull() ?: "?",
            counts = counts,
            sha256 = actual
        )
    }

    fun decode(json: String): Snapshot {
        inspect(json)
        val data = parse(json)["data"] ?: throw BackupException("بلوک data وجود ندارد")

        val packs = mutableListOf<MarketPack>()
        var activeId: String? = null
        for (entry in data["packs"]?.asObjects().orEmpty()) {
            val body = entry["body"]?.asStringOrNull() ?: continue
            val pack = runCatching { PackCodec.decode(body) }.getOrNull() ?: continue
            packs.add(pack)
            if (entry["active"]?.asBoolOrNull() == true) activeId = pack.id
        }
        if (activeId == null) activeId = packs.firstOrNull()?.id

        val listings = data["listings"]?.asObjects().orEmpty().map { row ->
            Listing(
                id = row["id"]?.asStringOrNull() ?: throw BackupException("آگهی بدون id"),
                title = row["title"]?.asStringOrNull() ?: "",
                titleNorm = row["titleNorm"]?.asStringOrNull() ?: "",
                priceRaw = row["priceRaw"]?.asStringOrNull() ?: "",
                priceIrt = row["priceIrt"]?.asLongOrNull(),
                priceNote = row["priceNote"]?.asStringOrNull() ?: "",
                source = row["source"]?.asStringOrNull() ?: "",
                url = row["url"]?.asStringOrNull()?.takeIf { it.isNotBlank() },
                seller = row["seller"]?.asStringOrNull()?.takeIf { it.isNotBlank() },
                city = row["city"]?.asStringOrNull()?.takeIf { it.isNotBlank() },
                attrs = row["attrs"]?.fieldsOrEmpty?.mapValues { it.value.asStringOrNull().orEmpty() } ?: emptyMap(),
                createdAt = row["createdAt"]?.asLongOrNull() ?: 0L
            )
        }

        val memory = data["memory"]?.asObjects().orEmpty().map { row ->
            MemoryItem(
                id = row["id"]?.asStringOrNull() ?: throw BackupException("یادسپاری بدون id"),
                kind = row["kind"]?.asStringOrNull() ?: "note",
                text = row["text"]?.asStringOrNull() ?: "",
                createdAt = row["createdAt"]?.asLongOrNull() ?: 0L,
                category = row["category"]?.asStringOrNull() ?: MemoryBank.CAT_GENERAL,
                tags = row["tags"]?.asStrings() ?: emptyList(),
                importance = row["importance"]?.asDoubleOrNull() ?: 0.5,
                useCount = row["useCount"]?.asIntOrNull() ?: 0,
                lastUsedAt = row["lastUsedAt"]?.asLongOrNull() ?: 0L,
                source = row["source"]?.asStringOrNull() ?: "user"
            )
        }

        val knowledge = data["knowledge"]?.asObjects().orEmpty().map { row ->
            KnowledgeItem(
                id = row["id"]?.asStringOrNull() ?: throw BackupException("دانسته بدون id"),
                topic = row["topic"]?.asStringOrNull() ?: "",
                question = row["question"]?.asStringOrNull() ?: "",
                claim = row["claim"]?.asStringOrNull() ?: "",
                category = row["category"]?.asStringOrNull() ?: MemoryBank.CAT_FACT,
                attribute = row["attribute"]?.asStringOrNull()?.takeIf { it.isNotBlank() },
                numericValue = row["numericValue"]?.asDoubleOrNull(),
                unit = row["unit"]?.asStringOrNull()?.takeIf { it.isNotBlank() },
                url = row["url"]?.asStringOrNull() ?: "",
                domain = row["domain"]?.asStringOrNull() ?: "",
                confidence = row["confidence"]?.asDoubleOrNull() ?: 0.5,
                createdAt = row["createdAt"]?.asLongOrNull() ?: 0L,
                lastVerifiedAt = row["lastVerifiedAt"]?.asLongOrNull() ?: 0L,
                ttlDays = row["ttlDays"]?.asIntOrNull() ?: KnowledgeBank.DEFAULT_TTL_DAYS,
                sourceCount = row["sourceCount"]?.asIntOrNull() ?: 1,
                supersededById = row["supersededBy"]?.asStringOrNull()?.takeIf { it.isNotBlank() },
                tags = row["tags"]?.asStrings() ?: emptyList()
            )
        }

        val logs = data["logs"]?.asObjects().orEmpty().map { row ->
            LogEvent(
                id = row["id"]?.asLongOrNull() ?: 0L,
                at = row["at"]?.asLongOrNull() ?: 0L,
                kind = row["kind"]?.asStringOrNull() ?: "info",
                actor = row["actor"]?.asStringOrNull() ?: "app",
                message = row["message"]?.asStringOrNull() ?: "",
                detail = row["detail"]?.asStringOrNull() ?: ""
            )
        }

        val audits = data["audits"]?.asObjects().orEmpty().map { row ->
            AuditEntry(
                at = row["at"]?.asLongOrNull() ?: 0L,
                actor = row["actor"]?.asStringOrNull() ?: "app",
                action = row["action"]?.asStringOrNull() ?: "",
                target = row["target"]?.asStringOrNull() ?: "",
                detail = row["detail"]?.asStringOrNull() ?: ""
            )
        }

        return Snapshot(
            packs = packs,
            activePackId = activeId,
            listings = listings,
            memory = memory,
            knowledge = knowledge,
            logs = logs,
            audits = audits,
            answers = data["answers"]?.fieldsOrEmpty?.mapValues { it.value.asStringOrNull().orEmpty() } ?: emptyMap(),
            preferences = data["preferences"]?.fieldsOrEmpty?.mapValues { it.value.asStringOrNull().orEmpty() } ?: emptyMap()
        )
    }

    private fun parse(json: String): Json =
        runCatching { MiniJson.parse(json) }.getOrElse { throw BackupException("JSON معتبر نیست: ${it.message}") }

    fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString()
    }
}
