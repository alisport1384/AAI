package ir.aai.hushyar

import ir.aai.hushyar.core.Html
import ir.aai.hushyar.core.Jalali
import ir.aai.hushyar.core.Lang
import ir.aai.hushyar.core.LangDetect
import ir.aai.hushyar.core.Lexicon
import ir.aai.hushyar.core.NumWords
import ir.aai.hushyar.core.Ranking
import ir.aai.hushyar.core.Text
import ir.aai.hushyar.core.Translit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LangTest {

    @Test
    fun languageDetectionSeparatesPersianEnglishAndMixed() {
        assertEquals(Lang.FA, LangDetect.detect("قیمت این گوشی چقدر است؟"))
        assertEquals(Lang.EN, LangDetect.detect("what is the price of this phone"))
        assertEquals(Lang.MIX, LangDetect.detect("قیمت گوشی Samsung S24 چقدر است؟"))
        assertEquals(Lang.OTHER, LangDetect.detect("123 456"))
        assertEquals(Lang.FA, LangDetect.detect("۱۲۳"))
        assertEquals("fa", LangDetect.tag(Lang.FA))
        assertEquals("en", LangDetect.tag(Lang.EN))
    }

    @Test
    fun jalaliConversionMatchesKnownDates() {
        // 2024-03-20 == 1403/01/01
        assertEquals(Triple(1403, 1, 1), Jalali.fromGregorian(2024, 3, 20))
        // 2023-11-14 == 1402/08/23
        assertEquals(Triple(1402, 8, 23), Jalali.fromGregorian(2023, 11, 14))
        val formatted = Jalali.format(1_700_000_000_000L)
        assertTrue(formatted.startsWith("1402/"))
        assertTrue(formatted.contains("/"))
    }

    @Test
    fun numberWordsParseBothLanguages() {
        assertEquals(1_250_000L, NumWords.parse("۱٫۲۵ میلیون"))
        assertEquals(20_000_000L, NumWords.parse("بیست میلیون تومان"))
        assertEquals(1_500_000L, NumWords.parse("1.5 million"))
        assertNull(NumWords.parse("قیمت نامعلوم"))
        assertNotNull(NumWords.parse("۵۰"))
        assertTrue(NumWords.parse("سه هزار") in 2_000L..4_000L)
    }

    @Test
    fun transliterationIsStableAndLossyOnlyWhereExpected() {
        val latin = Translit.faToLatin("گوشی سامسونگ")
        assertTrue(latin.isNotEmpty())
        assertTrue(latin.none { it in 'آ'..'ی' })
        assertTrue(Translit.faToLatin("گوشی").all { it in 'a'..'z' || it == ' ' || it == '-' })
    }

    @Test
    fun lexiconExpandsBothDirections() {
        val expanded = Lexicon.expand(Text.contentTokens("قیمت گوشی ارزان"))
        assertTrue(expanded.contains("price"))
        assertTrue(expanded.contains("phone"))
        val english = Lexicon.expand(Text.contentTokens("price of laptop"))
        assertTrue(english.any { it == "قیمت" })
        assertTrue(Lexicon.packTerms(listOf("گارانتی")).contains("warranty"))
    }

    @Test
    fun htmlEntityDecodingCoversPersianContent() {
        assertEquals("قیمت & کیفیت <ok>", Html.decodeEntities("قیمت &amp; کیفیت &lt;ok&gt;"))
        assertEquals("\u00A0".replace('\u00A0', ' '), Html.decodeEntities("&nbsp;"))
        assertEquals("س", Html.decodeEntities("&#1587;"))
    }

    @Test
    fun htmlTextStripsScriptsAndKeepsParagraphs() {
        val html = "<div>سلام</div><script>var x=1</script><p>دنیا</p>"
        val text = Html.toText(html)
        assertTrue(text.contains("سلام"))
        assertTrue(text.contains("دنیا"))
        assertTrue(!text.contains("var x"))
        assertEquals("عنوان", Html.title("<html><head><title>عنوان</title></head></html>"))
        assertNull(Html.title("<html></html>"))
    }

    @Test
    fun rankingFusionAndBm25BehaveSensibly() {
        val fused = Ranking.rrf(listOf(listOf("a", "b", "c"), listOf("b", "a")))
        assertTrue(fused.first().second > fused.last().second)
        val corpus = listOf(
            Text.contentTokens("قیمت گوشی سامسونگ"),
            Text.contentTokens("قیمت لپ تاپ لنوو"),
            Text.contentTokens("آب و هوا")
        )
        val idf = Ranking.idf(corpus)
        val score = Ranking.bm25(Text.contentTokens("قیمت گوشی"), corpus[0], idf)
        val other = Ranking.bm25(Text.contentTokens("قیمت گوشی"), corpus[2], idf)
        assertTrue(score > other)
        assertTrue(Ranking.recency(0L, 30L * 86_400_000L) > 0.4)
        assertTrue(Ranking.recency(0L, 30L * 86_400_000L) < 0.6)
    }
}
