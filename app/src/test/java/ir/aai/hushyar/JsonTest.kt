package ir.aai.hushyar

import ir.aai.hushyar.core.Json
import ir.aai.hushyar.core.JsonException
import ir.aai.hushyar.core.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonTest {

    @Test
    fun roundTripNested() {
        val original = MiniJson.obj(
            "name" to MiniJson.str("بازار تست"),
            "version" to MiniJson.num(3),
            "ok" to MiniJson.bool(true),
            "items" to MiniJson.arr(listOf(MiniJson.str("a"), MiniJson.num(1.5))),
            "nested" to MiniJson.obj("k" to MiniJson.str("v"), "empty" to MiniJson.arr(emptyList()))
        )
        val text = MiniJson.write(original, pretty = true)
        val parsed = MiniJson.parse(text)
        assertEquals(original, parsed)
    }

    @Test
    fun escapesAreHandled() {
        val messy = "خط \"دوم\"\nبا tab\tو بک‌اسلش \\ و یونیکد \u06F1"
        val json = MiniJson.write(MiniJson.obj("s" to MiniJson.str(messy)))
        val back = MiniJson.parse(json)["s"]?.asStringOrNull()
        assertEquals(messy, back)
    }

    @Test
    fun numbersKeepIntegerForm() {
        val text = MiniJson.write(MiniJson.obj("i" to MiniJson.num(42), "d" to MiniJson.num(0.25)))
        assertTrue(text.contains("42"))
        assertTrue(text.contains("0.25"))
        assertEquals(42L, MiniJson.parse(text)["i"]?.asLongOrNull())
    }

    @Test(expected = JsonException::class)
    fun invalidJsonThrows() {
        MiniJson.parse("{ \"a\": }")
    }

    @Test
    fun missingKeyReturnsNull() {
        val parsed = MiniJson.parse("""{"a":1}""")
        assertEquals(null, parsed["b"])
        assertEquals(Json.Null, MiniJson.parse("""{"a":null}""")["a"])
    }
}
