package ir.aai.hushyar

import ir.aai.hushyar.core.PriceParser
import ir.aai.hushyar.core.PriceRules
import ir.aai.hushyar.core.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextTest {

    @Test
    fun foldsArabicLettersAndDropsDiacritics() {
        // ك -> ک, diacritics dropped, ZWNJ removed
        assertEquals("کتاب", Text.normalize("كِتَابْ"))
    }

    @Test
    fun foldsPersianAndArabicDigits() {
        assertEquals("1234567890", Text.asciiDigits("۱۲۳۴۵۶۷۸۹۰"))
        assertEquals("1234", Text.asciiDigits("١٢٣٤"))
    }

    @Test
    fun zwnjDoesNotChangeTheIndexForm() {
        assertEquals(Text.normalize("می‌روم"), Text.normalize("میروم"))
    }

    @Test
    fun tokensSplitOnPunctuation() {
        val tokens = Text.tokens("iPhone 13 Pro، ۱۲۸ گیگ")
        assertTrue(tokens.contains("iphone"))
        assertTrue(tokens.contains("13"))
        assertTrue(tokens.contains("128"))
        assertTrue(tokens.contains("گیگ"))
    }

    @Test
    fun contentTokensRemoveStopwords() {
        val tokens = Text.contentTokens("قیمت این گوشی چقدر است")
        assertTrue(tokens.contains("قیمت"))
        assertTrue(tokens.contains("گوشی"))
        assertTrue(!tokens.contains("این"))
        assertTrue(!tokens.contains("است"))
    }

    @Test
    fun persianNumberFormatting() {
        assertEquals("۱٬۲۵۰٬۰۰۰", Text.num(1_250_000))
        assertEquals("1,250,000", Text.numEn(1_250_000))
    }

    @Test
    fun slugIsAsciiOrHashed() {
        assertEquals("mobile-phones-2", Text.slug("Mobile Phones 2"))
        assertTrue(Text.slug("بازار تست").startsWith("x"))
    }
}

class PriceTest {

    @Test
    fun persianDigitsWithThousandsSeparator() {
        val parsed = PriceParser.parse("۱٫۲۵۰٫۰۰۰ تومان")
        assertEquals(1_250_000L, parsed.irt)
        assertEquals(false, parsed.ambiguous)
    }

    @Test
    fun dotThousandsSeparator() {
        assertEquals(2_500_000L, PriceParser.parse("2.500.000").irt)
        assertEquals(2_500_000L, PriceParser.parse("۲.۵۰۰.۰۰۰ تومان").irt)
    }

    @Test
    fun millionWord() {
        assertEquals(1_200_000L, PriceParser.parse("۱٫۲ میلیون تومان").irt)
    }

    @Test
    fun rialIsConvertedToToman() {
        assertEquals(12_500L, PriceParser.parse("125000 ریال").irt)
    }

    @Test
    fun smallNumberWithoutUnitIsRefused() {
        val parsed = PriceParser.parse("۱۲۵۰")
        assertNull(parsed.irt)
        assertTrue(parsed.ambiguous)
    }

    @Test
    fun blankAndGarbageAreRefused() {
        assertNull(PriceParser.parse("").irt)
        assertNull(PriceParser.parse("تماس بگیرید").irt)
    }

    @Test
    fun packUnitScaleAppliesWhenNoCurrencyWord() {
        val parsed = PriceParser.parse("۱٫۲۵۰", PriceRules(unitScale = 1_000.0))
        assertEquals(1_250_000L, parsed.irt)
    }

    @Test
    fun currencyWordWinsOverPackScale() {
        val parsed = PriceParser.parse("1250000 تومان", PriceRules(unitScale = 1_000.0))
        assertEquals(1_250_000L, parsed.irt)
    }
}
