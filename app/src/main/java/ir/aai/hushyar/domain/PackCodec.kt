package ir.aai.hushyar.domain

import ir.aai.hushyar.core.Json
import ir.aai.hushyar.core.JsonException
import ir.aai.hushyar.core.MiniJson
import ir.aai.hushyar.core.PriceRules

/**
 * Pack <-> JSON. Packs are the only thing a user needs to import/export to swap markets,
 * so the wire format is stable, readable and versioned (`schema: hushyar.pack/1`).
 */
object PackCodec {

    fun toJson(pack: MarketPack): Json = MiniJson.obj(
        "schema" to MiniJson.str(pack.schema),
        "id" to MiniJson.str(pack.id),
        "name" to MiniJson.str(pack.name),
        "version" to MiniJson.num(pack.version),
        "langs" to MiniJson.strs(pack.langs),
        "categories" to MiniJson.strs(pack.categories),
        "sources" to MiniJson.strs(pack.sources),
        "refusalNote" to MiniJson.str(pack.refusalNote),
        "createdAt" to MiniJson.num(pack.createdAt),
        "priceRules" to MiniJson.obj(
            "currency" to MiniJson.str(pack.priceRules.currency),
            "unitScale" to MiniJson.num(pack.priceRules.unitScale),
            "madZ" to MiniJson.num(pack.priceRules.madZ),
            "goodBand" to MiniJson.num(pack.priceRules.goodBand),
            "fairBand" to MiniJson.num(pack.priceRules.fairBand),
            "highBand" to MiniJson.num(pack.priceRules.highBand)
        ),
        "attributes" to MiniJson.arr(pack.attributes.map { a ->
            MiniJson.obj(
                "id" to MiniJson.str(a.id),
                "label" to MiniJson.str(a.label),
                "type" to MiniJson.str(a.type.name),
                "unit" to MiniJson.str(a.unit ?: ""),
                "weight" to MiniJson.num(a.weight),
                "required" to MiniJson.bool(a.required),
                "synonyms" to MiniJson.strs(a.synonyms)
            )
        }),
        "intents" to MiniJson.arr(pack.intents.map { i ->
            MiniJson.obj(
                "id" to MiniJson.str(i.id),
                "label" to MiniJson.str(i.label),
                "keywords" to MiniJson.strs(i.keywords)
            )
        }),
        "redFlags" to MiniJson.arr(pack.redFlags.map { f ->
            MiniJson.obj(
                "id" to MiniJson.str(f.id),
                "severity" to MiniJson.str(f.severity),
                "text" to MiniJson.str(f.text)
            )
        })
    )

    fun encode(pack: MarketPack, pretty: Boolean = true): String =
        MiniJson.write(toJson(pack), pretty)

    fun decode(text: String): MarketPack {
        val root = try {
            MiniJson.parse(text)
        } catch (e: JsonException) {
            throw JsonException("JSON نامعتبر: ${e.message}")
        }
        val name = root["name"]?.asStringOrNull()?.trim().orEmpty()
        val id = root["id"]?.asStringOrNull()?.trim()
            ?: ir.aai.hushyar.core.Text.slug(name.ifEmpty { "market" })

        val rulesJson = root["priceRules"]
        val rules = PriceRules(
            currency = rulesJson?.get("currency")?.asStringOrNull() ?: "IRT",
            unitScale = rulesJson?.get("unitScale")?.asDoubleOrNull() ?: 1.0,
            madZ = rulesJson?.get("madZ")?.asDoubleOrNull() ?: 3.5,
            goodBand = rulesJson?.get("goodBand")?.asDoubleOrNull() ?: 0.25,
            fairBand = rulesJson?.get("fairBand")?.asDoubleOrNull() ?: 0.55,
            highBand = rulesJson?.get("highBand")?.asDoubleOrNull() ?: 0.80
        )

        val attributes = root["attributes"]?.itemsOrEmpty.orEmpty().mapIndexed { index, item ->
            val typeName = item["type"]?.asStringOrNull()?.uppercase() ?: "TEXT"
            AttributeSpec(
                id = item["id"]?.asStringOrNull() ?: "attr_${index + 1}",
                label = item["label"]?.asStringOrNull() ?: "ویژگی ${index + 1}",
                type = runCatching { AttrType.valueOf(typeName) }.getOrDefault(AttrType.TEXT),
                unit = item["unit"]?.asStringOrNull()?.takeIf { it.isNotBlank() },
                weight = item["weight"]?.asDoubleOrNull() ?: 0.1,
                required = item["required"]?.asBoolOrNull() ?: false,
                synonyms = item["synonyms"]?.asStrings().orEmpty()
            )
        }

        val intents = root["intents"]?.itemsOrEmpty.orEmpty().mapIndexed { index, item ->
            IntentSpec(
                id = item["id"]?.asStringOrNull() ?: "intent_${index + 1}",
                label = item["label"]?.asStringOrNull() ?: "پرسش ${index + 1}",
                keywords = item["keywords"]?.asStrings().orEmpty()
            )
        }

        val flags = root["redFlags"]?.itemsOrEmpty.orEmpty().mapIndexed { index, item ->
            RedFlagSpec(
                id = item["id"]?.asStringOrNull() ?: "flag_${index + 1}",
                severity = item["severity"]?.asStringOrNull() ?: "medium",
                text = item["text"]?.asStringOrNull() ?: ""
            )
        }

        return MarketPack(
            id = id,
            name = name,
            version = root["version"]?.asIntOrNull() ?: 1,
            langs = root["langs"]?.asStrings().orEmpty().ifEmpty { listOf("fa") },
            categories = root["categories"]?.asStrings().orEmpty(),
            attributes = attributes,
            intents = intents,
            redFlags = flags,
            sources = root["sources"]?.asStrings().orEmpty(),
            priceRules = rules,
            refusalNote = root["refusalNote"]?.asStringOrNull() ?: "",
            createdAt = root["createdAt"]?.asLongOrNull() ?: 0L,
            schema = root["schema"]?.asStringOrNull() ?: MarketPack.SCHEMA
        )
    }
}
