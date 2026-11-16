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

    const val SCHEMA: String = "hushyar.backup/4"

    /** Older snapshots stay restorable: v0.2 files simply carry no knowledge block. */
    val ACCEPTED_SCHEMAS: Set<String> = setOf(SCHEMA, "hushyar.backup/3", "hushyar.backup/2")

    data class Snapshot(
        val packs: List<MarketPack> = emptyList(),
        val activePackId: String? = null,
        val listings: List<Listing> = emptyList(),
        val memory: List<MemoryItem> = emptyList(),
        val knowledge: List<KnowledgeItem> = emptyList(),
        /** v0.5: answered-question cases, proven queries, source trust, plan cursor. */
        val cases: List<QaCase> = emptyList(),
        val playbook: List<PlayEntry> = emptyList(),
        val domains: List<DomainStat> = emptyList(),
        val plans: List<PlanProgress> = emptyList(),
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
            "cases" to MiniJson.arr(snapshot.cases.map { case ->
                MiniJson.obj(
                    "id" to MiniJson.str(case.id),
                    "topicKey" to MiniJson.str(case.topicKey),
                    "ask" to MiniJson.str(case.ask),
                    "subject" to MiniJson.strs(case.subject),
                    "question" to MiniJson.str(case.question),
                    "answer" to MiniJson.str(case.answer),
                    "url" to MiniJson.str(case.url),
                    "domain" to MiniJson.str(case.domain),
                    "capturedAt" to MiniJson.num(case.capturedAt),
                    "lastVerifiedAt" to MiniJson.num(case.lastVerifiedAt),
                    "ttlDays" to MiniJson.num(case.ttlDays),
                    "hits" to MiniJson.num(case.hits)
                )
            }),
            "playbook" to MiniJson.arr(snapshot.playbook.map { entry ->
                MiniJson.obj(
                    "topicKey" to MiniJson.str(entry.topicKey),
                    "query" to MiniJson.str(entry.query),
                    "wins" to MiniJson.num(entry.wins),
                    "losses" to MiniJson.num(entry.losses),
                    "lastWinAt" to MiniJson.num(entry.lastWinAt)
                )
            }),
            "domains" to MiniJson.arr(snapshot.domains.map { stat ->
                MiniJson.obj(
                    "domain" to MiniJson.str(stat.domain),
                    "wins" to MiniJson.num(stat.wins),
                    "losses" to MiniJson.num(stat.losses)
                )
            }),
            "plans" to MiniJson.arr(snapshot.plans.map { plan ->
                MiniJson.obj(
                    "goal" to MiniJson.str(plan.goal),
                    "states" to MiniJson.arr(plan.states.values.map { state ->
                        MiniJson.obj(
                            "id" to MiniJson.str(state.id),
                            "status" to MiniJson.str(state.status.name),
                            "lastRunAt" to MiniJson.num(state.lastRunAt),
                            "learned" to MiniJson.num(state.learned),
                            "queriesDone" to MiniJson.num(state.queriesDone)
                        )
                    })
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
                    "cases" to MiniJson.num(snapshot.cases.size),
                    "playbook" to MiniJson.num(snapshot.playbook.size),
                    "plans" to MiniJson.num(snapshot.plans.size),
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

        val cases = data["cases"]?.asObjects().orEmpty().map { row ->
            QaCase(
                id = row["id"]?.asStringOrNull() ?: throw BackupException("پرونده بدون id"),
                topicKey = row["topicKey"]?.asStringOrNull() ?: "",
                ask = row["ask"]?.asStringOrNull() ?: "FACT",
                subject = row["subject"]?.asStrings() ?: emptyList(),
                question = row["question"]?.asStringOrNull() ?: "",
                answer = row["answer"]?.asStringOrNull() ?: "",
                url = row["url"]?.asStringOrNull() ?: "",
                domain = row["domain"]?.asStringOrNull() ?: "",
                capturedAt = row["capturedAt"]?.asLongOrNull() ?: 0L,
                lastVerifiedAt = row["lastVerifiedAt"]?.asLongOrNull() ?: 0L,
                ttlDays = row["ttlDays"]?.asIntOrNull() ?: CaseStore.DEFAULT_TTL_DAYS,
                hits = row["hits"]?.asIntOrNull() ?: 0
            )
        }

        val playbook = data["playbook"]?.asObjects().orEmpty().map { row ->
            PlayEntry(
                topicKey = row["topicKey"]?.asStringOrNull() ?: "",
                query = row["query"]?.asStringOrNull() ?: "",
                wins = row["wins"]?.asIntOrNull() ?: 0,
                losses = row["losses"]?.asIntOrNull() ?: 0,
                lastWinAt = row["lastWinAt"]?.asLongOrNull() ?: 0L
            )
        }

        val domains = data["domains"]?.asObjects().orEmpty().map { row ->
            DomainStat(
                domain = row["domain"]?.asStringOrNull() ?: "",
                wins = row["wins"]?.asIntOrNull() ?: 0,
                losses = row["losses"]?.asIntOrNull() ?: 0
            )
        }

        val plans = data["plans"]?.asObjects().orEmpty().map { row ->
            val goal = row["goal"]?.asStringOrNull() ?: ""
            val states = row["states"]?.asObjects().orEmpty().mapNotNull { state ->
                val id = state["id"]?.asStringOrNull() ?: return@mapNotNull null
                LayerState(
                    id = id,
                    status = runCatching { LayerStatus.valueOf(state["status"]?.asStringOrNull() ?: "PENDING") }
                        .getOrDefault(LayerStatus.PENDING),
                    lastRunAt = state["lastRunAt"]?.asLongOrNull() ?: 0L,
                    learned = state["learned"]?.asIntOrNull() ?: 0,
                    queriesDone = state["queriesDone"]?.asIntOrNull() ?: 0
                )
            }.associateBy { it.id }
            PlanProgress(goal, states)
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
            cases = cases,
            playbook = playbook,
            domains = domains,
            plans = plans,
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
