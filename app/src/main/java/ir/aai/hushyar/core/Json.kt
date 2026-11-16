package ir.aai.hushyar.core

/**
 * Minimal dependency-free JSON (parse + write).
 * v0.1 must behave identically on the JVM (unit tests) and on Android, without a
 * serialization plugin and without org.json (which is a throwing stub under unit tests).
 */
class JsonException(message: String) : Exception(message)

sealed class Json {
    data class Str(val value: String) : Json()
    data class Num(val value: Double) : Json()
    data class Bool(val value: Boolean) : Json()
    object Null : Json()
    data class Arr(val items: List<Json>) : Json()
    data class Obj(val fields: Map<String, Json>) : Json()

    fun asStringOrNull(): String? = (this as? Str)?.value
    fun asDoubleOrNull(): Double? = (this as? Num)?.value
    fun asLongOrNull(): Long? = (this as? Num)?.value?.toLong()
    fun asIntOrNull(): Int? = (this as? Num)?.value?.toInt()
    fun asBoolOrNull(): Boolean? = (this as? Bool)?.value

    val itemsOrEmpty: List<Json> get() = (this as? Arr)?.items ?: emptyList()
    val fieldsOrEmpty: Map<String, Json> get() = (this as? Obj)?.fields ?: emptyMap()

    fun asStrings(): List<String> = itemsOrEmpty.mapNotNull { it.asStringOrNull() }
    fun asObjects(): List<Map<String, Json>> = itemsOrEmpty.map { it.fieldsOrEmpty }

    operator fun get(key: String): Json? = (this as? Obj)?.fields?.get(key)
}

object MiniJson {

    fun parse(text: String): Json {
        val p = Parser(text)
        p.skipWs()
        if (p.eof()) throw JsonException("empty input")
        val v = p.parseValue()
        p.skipWs()
        if (!p.eof()) throw JsonException("trailing content at ${p.pos}")
        return v
    }

    fun write(value: Json, pretty: Boolean = false): String {
        val sb = StringBuilder()
        writeValue(value, sb, pretty, 0)
        return sb.toString()
    }

    fun obj(vararg pairs: Pair<String, Json>): Json = Json.Obj(linkedMapOf(*pairs))
    fun objOf(map: Map<String, Json>): Json = Json.Obj(LinkedHashMap(map))
    fun arr(items: List<Json>): Json = Json.Arr(items)
    fun str(v: String): Json = Json.Str(v)
    fun num(v: Number): Json = Json.Num(v.toDouble())
    fun numOrNull(v: Number?): Json = if (v == null) Json.Null else Json.Num(v.toDouble())
    fun bool(v: Boolean): Json = Json.Bool(v)
    fun strs(values: List<String>): Json = Json.Arr(values.map { Json.Str(it) })

    private fun writeValue(v: Json, sb: StringBuilder, pretty: Boolean, depth: Int) {
        when (v) {
            is Json.Str -> writeString(v.value, sb)
            is Json.Num -> writeNumber(v.value, sb)
            is Json.Bool -> sb.append(if (v.value) "true" else "false")
            Json.Null -> sb.append("null")
            is Json.Arr -> {
                if (v.items.isEmpty()) { sb.append("[]"); return }
                sb.append('[')
                v.items.forEachIndexed { i, item ->
                    if (i > 0) sb.append(',')
                    newline(sb, pretty, depth + 1)
                    writeValue(item, sb, pretty, depth + 1)
                }
                newline(sb, pretty, depth)
                sb.append(']')
            }
            is Json.Obj -> {
                if (v.fields.isEmpty()) { sb.append("{}"); return }
                sb.append('{')
                var first = true
                for ((k, value) in v.fields) {
                    if (!first) sb.append(',')
                    first = false
                    newline(sb, pretty, depth + 1)
                    writeString(k, sb)
                    sb.append(':')
                    if (pretty) sb.append(' ')
                    writeValue(value, sb, pretty, depth + 1)
                }
                newline(sb, pretty, depth)
                sb.append('}')
            }
        }
    }

    private fun newline(sb: StringBuilder, pretty: Boolean, depth: Int) {
        if (!pretty) return
        sb.append('\n')
        repeat(depth) { sb.append("  ") }
    }

    private fun writeNumber(d: Double, sb: StringBuilder) {
        if (d.isNaN() || d.isInfinite()) { sb.append("null"); return }
        val asLong = d.toLong()
        if (d == asLong.toDouble() && kotlin.math.abs(d) < 1e15) sb.append(asLong.toString())
        else sb.append(d.toString())
    }

    private fun writeString(s: String, sb: StringBuilder) {
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) sb.append("\\u").append(ch.code.toString(16).padStart(4, '0')) else sb.append(ch)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var pos = 0

        fun eof(): Boolean = pos >= s.length

        fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun parseValue(): Json {
            skipWs()
            if (eof()) throw JsonException("unexpected end of input")
            return when (val c = s[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> Json.Str(parseString())
                't' -> { expect("true"); Json.Bool(true) }
                'f' -> { expect("false"); Json.Bool(false) }
                'n' -> { expect("null"); Json.Null }
                else -> if (c == '-' || c.isDigit()) parseNumber()
                else throw JsonException("unexpected '$c' at $pos")
            }
        }

        private fun expect(literal: String) {
            if (!s.startsWith(literal, pos)) throw JsonException("expected $literal at $pos")
            pos += literal.length
        }

        private fun parseObject(): Json {
            pos++
            val map = LinkedHashMap<String, Json>()
            skipWs()
            if (!eof() && s[pos] == '}') { pos++; return Json.Obj(map) }
            while (true) {
                skipWs()
                if (eof() || s[pos] != '"') throw JsonException("expected key at $pos")
                val key = parseString()
                skipWs()
                if (eof() || s[pos] != ':') throw JsonException("expected ':' at $pos")
                pos++
                map[key] = parseValue()
                skipWs()
                if (eof()) throw JsonException("unterminated object")
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return Json.Obj(map) }
                    else -> throw JsonException("expected ',' or '}' at $pos")
                }
            }
        }

        private fun parseArray(): Json {
            pos++
            val list = ArrayList<Json>()
            skipWs()
            if (!eof() && s[pos] == ']') { pos++; return Json.Arr(list) }
            while (true) {
                list.add(parseValue())
                skipWs()
                if (eof()) throw JsonException("unterminated array")
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return Json.Arr(list) }
                    else -> throw JsonException("expected ',' or ']' at $pos")
                }
            }
        }

        private fun parseString(): String {
            pos++
            val sb = StringBuilder()
            while (true) {
                if (eof()) throw JsonException("unterminated string")
                val c = s[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (eof()) throw JsonException("unterminated escape")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw JsonException("bad unicode escape")
                                val hex = s.substring(pos, pos + 4)
                                pos += 4
                                sb.append(hex.toInt(16).toChar())
                            }
                            else -> throw JsonException("bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun parseNumber(): Json {
            val start = pos
            if (!eof() && s[pos] == '-') pos++
            while (!eof() && s[pos].isDigit()) pos++
            if (!eof() && s[pos] == '.') {
                pos++
                while (!eof() && s[pos].isDigit()) pos++
            }
            if (!eof() && (s[pos] == 'e' || s[pos] == 'E')) {
                pos++
                if (!eof() && (s[pos] == '+' || s[pos] == '-')) pos++
                while (!eof() && s[pos].isDigit()) pos++
            }
            val raw = s.substring(start, pos)
            val d = raw.toDoubleOrNull() ?: throw JsonException("bad number '$raw'")
            return Json.Num(d)
        }
    }
}
