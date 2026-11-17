package ir.aai.hushyar

import ir.aai.hushyar.core.HttpRequest
import ir.aai.hushyar.core.HttpResponse
import ir.aai.hushyar.core.HttpTransport
import ir.aai.hushyar.domain.CaseStore
import ir.aai.hushyar.domain.Engines
import ir.aai.hushyar.domain.QuestionReader
import ir.aai.hushyar.domain.SearchCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.10 — «از سؤال دوم، هوش دیگر جستجوی آنلاین دقیق نمی‌کند و جوابش اشتباه است».
 *
 * دو علت واقعی داشت و هر دو اینجا قفل می‌شوند:
 *
 * 1. استراحت ثابتِ ۱۰ دقیقه‌ای: یک بار ۴۲۹ از سؤال اول، موتورهای خوب را تا سؤال دهم خاموش
 *    می‌کرد و سؤال دوم بی‌جستجو جواب می‌گرفت. حالا استراحت پله‌ای است و اگر همه خواب باشند
 *    دو موتور پیش‌آزمون می‌شوند: جستجو هیچ‌وقت کاملاً خاموش نمی‌شود.
 * 2. پروندهٔ پاسخ قبلی (case) نباید به کالای دیگر جواب بدهد؛ پاسخِ «تیرآهن ۱۸» هرگز پاسخِ
 *    «ورق ۲ میل» نیست، حتی اگر هر دو «قیمت» بخواهند.
 */
class SearchRecoveryTest {

    private class StatusTransport(private val status: Int, val asked: MutableList<String> = mutableListOf()) : HttpTransport {
        override fun get(request: HttpRequest): HttpResponse {
            asked += request.url
            return HttpResponse(request.url, status, if (status == 200) "" else "busy", error = if (status == 200) null else "http $status")
        }
    }

    @Test
    fun aSecondSearchIsNeverSilencedByTheFirstOne() {
        var clock = 1_000_000L
        val transport = StatusTransport(429)
        // یک موتور، تا حالت «همه در استراحت» واقعاً اتفاق بیفتد.
        val core = SearchCore(
            transport, engines = listOf(Engines.byId("bing")!!),
            pacing = 0L, sleep = {}, defaultEngines = 1, clock = { clock }
        )

        core.search("قیمت تیرآهن ۱۸", "fa")
        assertTrue("سؤال اول باید واقعاً پرسیده باشد", transport.asked.isNotEmpty())

        val afterFirst = transport.asked.size
        val second = core.search("قیمت ورق ۲ میل", "fa")
        assertTrue(
            "سؤال دوم نباید بی‌جستجو بماند حتی وقتی همه در استراحت‌اند",
            transport.asked.size > afterFirst
        )
        assertTrue(
            "گزارش باید بگوید پیش‌آزمون انجام شد",
            second.notes.containsKey(SearchCore.ENGINE_PROBE_NOTE)
        )
    }

    @Test
    fun restGrowsWithRepeatedOffensesAndThenFreesTheEngine() {
        var clock = 5_000_000L
        val transport = StatusTransport(429)
        val single = listOf(Engines.byId("bing")!!)
        val core = SearchCore(transport, engines = single, pacing = 0L, sleep = {}, defaultEngines = 1, clock = { clock })

        core.search("پرسش یک", "fa")
        val firstRest = core.restingEngines(clock).values.first()
        assertEquals("اولین استراحت ۴۵ ثانیه است", SearchCore.FIRST_REST_MS, firstRest)

        // پیش‌آزمون یک بار دیگر خطا می‌دهد → استراحت دو برابر می‌شود
        core.search("پرسش دو", "fa")
        val secondRest = core.restingEngines(clock).values.first()
        assertTrue("استراحت دوم باید بلندتر از اولی باشد ($firstRest → $secondRest)", secondRest > firstRest)

        clock += SearchCore.MAX_REST_MS + 1
        assertTrue("بعد از سقف استراحت باید آزاد شود", core.restingEngines(clock).isEmpty())
    }

    @Test
    fun oneBlockedEngineDoesNotStopTheOthers() {
        val transport = object : HttpTransport {
            val asked = mutableListOf<String>()
            override fun get(request: HttpRequest): HttpResponse {
                asked += request.url
                return if (request.url.contains("google.com")) {
                    HttpResponse(request.url, 429, "busy", error = "http 429")
                } else {
                    HttpResponse(
                        request.url, 200,
                        """<html><body><a href="https://example.com/a">قیمت تیرآهن ۱۸ امروز</a>
                           <p>قیمت هر کیلو تیرآهن ۱۸ در بازار ۴۲ هزار تومان است.</p></body></html>"""
                    )
                }
            }
        }
        val core = SearchCore(transport, pacing = 0L, sleep = {}, defaultEngines = 4)
        val report = core.search("قیمت تیرآهن ۱۸", "fa")
        assertTrue("بقیهٔ موتورها باید جواب بدهند", report.hits.isNotEmpty())
        assertTrue("گوگل باید واقعاً پرسیده شده باشد", transport.asked.any { it.contains("google.com") })
        assertTrue("خطای گوگل باید گزارش شود", report.failures.keys.any { it == "google" })
    }

    @Test
    fun anOldCaseNeverAnswersADifferentProduct() {
        val now = 1_700_000_000_000L
        val beamModel = QuestionReader.read("قیمت تیرآهن ۱۸", "fa")
        val cases = CaseStore.learn(
            existing = emptyList(),
            model = beamModel,
            question = "قیمت تیرآهن ۱۸",
            answer = "قیمت هر کیلو تیرآهن ۱۸ حدود ۴۲ هزار تومان است.",
            source = null,
            now = now
        )
        assertTrue("پرونده باید ذخیره شده باشد", cases.isNotEmpty())

        val sheetModel = QuestionReader.read("قیمت ورق ۲ میل", "fa")
        val reused = CaseStore.find(cases, sheetModel, "قیمت ورق ۲ میل", now)
        assertNull("پاسخ تیرآهن نباید به ورق نسبت داده شود", reused)
    }

    @Test
    fun theSameQuestionIsStillReusedQuickly() {
        val now = 1_700_000_000_000L
        val model = QuestionReader.read("قیمت تیرآهن ۱۸", "fa")
        val cases = CaseStore.learn(
            emptyList(), model, "قیمت تیرآهن ۱۸",
            "قیمت هر کیلو تیرآهن ۱۸ حدود ۴۲ هزار تومان است.", null, now
        )
        assertNotNull(
            "همان پرسش باید از پرونده جواب بگیرد (کندی نکتهٔ ما نبود؛ دقت بود)",
            CaseStore.find(cases, model, "قیمت تیرآهن ۱۸", now)
        )
    }

    @Test
    fun engineRestIsReportedInSecondsForTheUi() {
        var clock = 9_000_000L
        val core = SearchCore(StatusTransport(403), engines = listOf(Engines.byId("mojeek")!!), pacing = 0L, sleep = {}, clock = { clock })
        core.search("پرسش", "fa")
        val rest = core.restingEngines(clock)
        assertEquals(1, rest.size)
        assertTrue(rest.values.first() in 1..SearchCore.MAX_REST_MS)
    }
}
