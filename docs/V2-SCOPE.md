# دامنهٔ نسخهٔ ۲ — چه چیزی ساخته شد و چه چیزی نه

نسخهٔ **۰.۲.۰** همان اپ را از «موتور تحلیل بازار» به **دستیار روی-دستگاه** ارتقا می‌دهد:
یاد می‌گیرد، فارسی و انگلیسی را می‌فهمد، در وب جستجو می‌کند، دادهٔ خودش را
تغییر می‌دهد، لاگ را به Markdown می‌نویسد و از همه‌چیز بک‌آپ می‌گیرد.

این سند تعهدنامه است: هر ردیف یا کد دارد، یا صریحاً گفته شده چرا ندارد.

## ۱) نگاشت درخواست‌ها به کد

| # | درخواست | پیاده‌سازی | آزمون |
|---|---|---|---|
| ۱ | یاد بگیرد، ذخیره کند، دسته‌بندی کند | `domain/MemoryBank.kt` (دسته، برچسب، اهمیت، simhash، ادغام، رتبه‌بندی BM25+recency)، جدول `memory` با ۶ ستون جدید | `MemoryTest` (۹ آزمون) |
| ۲ | فارسی را کامل بفهمد | `core/Lang.kt`: `LangDetect` (FA/EN/MIX/OTHER)، `Jalali`، `NumWords` (فارسی/انگلیسی، عدد و واژه)، `Translit`، `Lexicon` دوزبانه | `LangTest` (۸ آزمون) |
| ۳ | انگلیسی را کامل بفهمد | همان لایه + پاسخ انگلیسی بومی (`TemplatePhraser(lang="en")`) و جستجوی انگلیسی | `AgentTest.englishQuestionIsAnsweredInEnglish` |
| ۴ | فکر کند و جواب دهد | `domain/Agent.kt`: چرخهٔ تصمیم — دستور داده → کاتالوگ محلی → حافظه → وب → بازتاب (بازنویسی پرسش) → اعتبارسنجی → یادگیری؛ هر گام در `AgentStep` قابل مشاهده | `AgentTest` (۱۲ آزمون) |
| ۵ | در موتورهای جستجو بگردد | `domain/Search.kt`: ۱۰ آداپتور عمومی (Google، Bing، DuckDuckGo، Brave، Mojeek، Startpage، Yandex، ویکی‌پدیای fa/en، SearXNG) + `SerpParser` عمومی + ادغام RRF | `SearchTest` (۱۰ آزمون) |
| ۶ | فایل‌های داده را تغییر دهد | `Repos`: ویرایش/حذف `listing`، ویرایش/حذف `memory`، ادغام تکراری‌ها، بازیابی کامل؛ همه با ممیزی (`audit`) | `AgentTest` (save/forget/backup/recall) |
| ۷ | لاگ اپ را برای هوش به `.md` بنویسد | `domain/Markdown.kt` + جدول `event`؛ خروجی «دفتر هوش» و «لاگ فعالیت» با تاریخ جلالی | `BackupTest` (سه آزمون Markdown) |
| ۸ | از تمام اطلاعات بک‌آپ بگیرد | `domain/Backup.kt` + `data/AndroidTools.kt:BackupService`: ZIP شامل `backup.json` (پک، آگهی، حافظه، لاگ، ممیزی، مصاحبه، تنظیمات) + `brain.md` + لاگ md + حافظه md، با چک‌سام SHA-256 | `BackupTest` (۶ آزمون) |
| ۹ | بدون Play، بدون API بیرونی | `INTERNET` تنها مجوز جدید است؛ تنها خروجی شبکه `HttpUrlTransport` (GET ساده) | بازرسی `aapt2` در `docs/REPORT-BUILD-V2.md` |

## ۲) معماری افزوده‌شده

```
core/  Lang.kt  (LangDetect, Jalali, NumWords, Translit, Lexicon)
       Html.kt  (decodeEntities, toText, title, mainText, links)
       Http.kt  (HttpRequest/Response/Transport, HttpDefaults)
       Ranking.kt (RRF, BM25, idf, recency)
domain/ Search.kt   (Engines, SerpParser, PageExtractor, InjectionGuard, SearchCore)
        MemoryBank.kt (classify, tags, importance, duplicateOf, consolidate, rank, learnableFrom)
        Agent.kt    (AgentTools, Agent, CommandParser, Extractive)
        Backup.kt   (Snapshot, encode/inspect/decode, sha256)
        Markdown.kt (log, memory, answer, fullBrain)
data/   Net.kt (HttpUrlTransport) · Store.kt (فایل/زیپ) · AndroidTools.kt (+ BackupService)
```

قاعده‌های لایه‌بندی حفظ شده‌اند: `core` و `domain` هیچ import اندرویدی ندارند
(به‌جز `java.security`/`java.util` که روی JVM هم هستند)، بنابراین تمام چرخهٔ هوش
با JUnit روی JVM آزمون می‌شود؛ `data` و `ui` فقط سیم‌کشی‌اند.

## ۳) تصمیم‌های فنی و دلیلشان

1. **استخراج، نه تولید.** پاسخ وب با «جملات عیناً موجود در صفحه» ساخته می‌شود
   (`Extractive`). نتیجه: هر عدد در پاسخ، آدرس منبع دارد و توهم ساختاری ممکن نیست.
   آزمون `webResearchAnswersWithTraceableCitations` این را چک می‌کند.
2. **وب = داده، نه دستور.** `InjectionGuard` الگوهای تزریق دستور (فارسی/انگلیسی،
   توکن‌های نقش، تلاش برای خواندن رمز) را نشانه‌گذاری می‌کند و صفحهٔ آلوده
   از شواهد حذف می‌شود (`trust="hostile"`).
3. **بدون دور زدن محدودیت.** UA صادق، بدون پروکسی، بدون CAPTCHA؛ `403/429`
   موتور را در «سرمایهٔ خاموشی» (cooldown) می‌گذارد و نتیجه به کاربر گزارش می‌شود.
   هیچ پاسخی از موتورهای ناموفق جعل نمی‌شود؛ امتناع می‌کنیم.
4. **حافظه قابل بازرسی است.** هر رکورد دسته، برچسب، اهمیت، منبع و شناسه دارد و
   از صفحهٔ «داده» قابل ویرایش/حذف است. یادگیری خودکار فقط ترجیح‌ها و قواعد
   کاربر + واقعیت‌های با اطمینان ≥۰.۷۰ و استناد را نگه می‌دارد (`learnableFrom`).
5. **بازیابی اتمیک.** `Repos.restore` در یک تراکنش همه‌چیز را جایگزین می‌کند؛
   فایل بدون چک‌سام درست، حتی باز نمی‌شود.
6. **هیچ وابستگی جدید.** `HttpURLConnection` جای OkHttp؛ JSON، HTML و Markdown
   دست‌نویس. دلیل: بیلد ۲GB/۲هسته‌ای و زنجیرهٔ تأمین کوچک‌تر.

## ۴) انحراف‌های صادقانه (چیزی که **نیست**)

| مورد | وضعیت | دلیل |
|---|---|---|
| مدل زبانی محلی (GGUF) | نیست | حجم ۱–۲ گیگابایت، RAM و CPU گوشی؛ لایهٔ `Phraser` آمادهٔ اتصال است |
| OCR / بینایی | نیست | خارج از دامنهٔ این نسخه؛ `Tesseract` گزینهٔ بعدی است |
| صیقل‌دهی شباهت با `fuzzywuzzy`-گونه الگوریتم‌های سنگین | نیست | همان Jaccard+cos+simbhash v0.1 کافی بود |
| SQLite FTS5 برای حافظه | نیست | رتبه‌بندی BM25 در Kotlin روی حداکثر ۸۰۰ رکورد انجام می‌شود؛ سریع‌تر از ساخت جدول مجازی و قابل آزمون |
| جستجو در «تمام» موتورهای دنیا | ۱۰ آداپتور | بقیه یا کلید می‌خواهند (نقض قید «بدون API»)، یا JS لازم دارند، یا بسته شده‌اند (Bing API، Google CSE) |
| موتورهای فارسی (Parsijoo/Yooz) | نیست | اثربخشی HTML آن‌ها در آزمون‌های عمومی ۱۵–۴۶٪ گزارش شده؛ آداپتور اضافه‌کردن هزینهٔ نگهداری بی‌بازده است — `EngineSpec` یک ردیف داده است اگر لازم شد |
| دسترسی به `/data/data` اپ‌های دیگر | نیست | بدون root ممکن نیست؛ اپ صریحاً امتناع می‌کند و مسیر Shizuku را در طراحی نگه داشته، اما در این نسخه پیاده نشده |

## ۵) چک‌لیست پذیرش

| معیار | وضعیت |
|---|---|
| ۹۲ آزمون واحد، ۰ شکست | ✅ (`docs/REPORT-BUILD-V2.md`) |
| CI سبز روی GitHub Actions | ✅ |
| `versionName 0.2.0` / `versionCode 2` | ✅ بازرسی `aapt2` |
| `INTERNET` در manifest و در APK | ✅ |
| بدون سرویس ابری/کلید/تلمتری | ✅ بازرسی کد و manifest |
| `docs/` در مخزن | ✅ |
