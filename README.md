# هوشیار (AAI)

[![Android CI](https://github.com/alisport1384/AAI/actions/workflows/android.yml/badge.svg)](https://github.com/alisport1384/AAI/actions/workflows/android.yml)

دستیار **روی-دستگاه** برای یک بازار که **کاربر خودش معرفی می‌کند**: یاد می‌گیرد،
فارسی و انگلیسی را می‌فهمد، در وب جستجو می‌کند، دادهٔ خودش را ویرایش می‌کند،
لاگ را به Markdown می‌نویسد و از همه‌چیز بک‌آپ می‌گیرد.

- هیچ بازاری در برنامه کامپایل نشده است؛ بازار داده است (`MarketPack` ↔ JSON).
- هیچ API، توکن، SDK ابری یا تلمتری وجود ندارد.
- **تنها منفذ بیرونی: جستجوی وب** (GET ساده به موتورهای عمومی). ذخیره، استدلال،
  حافظه و پاسخ‌سازی همه محلی‌اند.
- پرسش‌ها فقط با **شاهد** پاسخ داده می‌شوند؛ در نبود شاهد، صریح «نمی‌دانم».

## وضعیت

| مورد | v0.1.0 | v0.2.0 |
|---|---|---|---|
| ساخت خودکار (GitHub Actions) | ✅ سبز — ۴۶ آزمون (`docs/REPORT-BUILD.md`) | ✅ سبز — ۹۲ آزمون (`docs/REPORT-BUILD-V2.md`) |
| مجوزها | بدون `INTERNET` | `INTERNET` + مجوز داخلی androidx |
| بازار | مصاحبه → `MarketPack` ↔ JSON | همان + کار کردن بدون بازار |
| تحلیل | قیمت، ویژگی، آمار، امتیاز ارزش، هشدار | همان |
| پرسش‌وپاسخ | نیت + شاهد + استناد + امتناع | + حافظه، + جستجوی وب چند-موتوره، + استخراج شاهد از صفحه |
| حافظه | فقط ثبت رخداد | دسته‌بندی، برچسب، اهمیت، ادغام تکراری، رتبه‌بندی BM25، ویرایش/حذف دستی |
| جستجوی وب | — | ۱۰ موتور، ادغام RRF، بازتاب پرسش، `InjectionGuard` |
| داده | افزودن/حذف آگهی | ویرایش رکورد، حذف حافظه، ادغام، پاک‌سازی کامل |
| لاگ | audit در SQLite | + جدول `event` و خروجی `.md` با تاریخ جلالی |
| بک‌آپ | — | ZIP + JSON با چک‌سام SHA-256، بازیابی اتمیک |
| مدل زبانی | نیست (`Phraser` آماده) | نیست — پاسخ‌ها استخراجی/قالبی و قابل ردیابی |

## ساخت

```bash
# پیش‌نیاز: JDK 17، Android SDK (platform 35، build-tools 35)
gradle testDebugUnitTest assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

CI روی هر push همین دو کار را انجام می‌دهد و APK را به‌عنوان artifact می‌گذارد.

## ساختار

```
app/src/main/java/ir/aai/hushyar/
├─ core/    Json · Text · Price · Similarity · Ids · Lang · Html · Http · Ranking
├─ domain/  Models · PackCodec · PackValidator · Interview · SelfModel · Analysis · Qa
│           Search (Engines/SerpParser/PageExtractor/InjectionGuard) · MemoryBank
│           Agent (AgentTools/CommandParser/Extractive) · Backup · Markdown
├─ data/    Db · Repos · CsvImporter · Net (HttpUrlTransport) · Store (zip/md)
│           AndroidTools (AgentTools + BackupService)
├─ ui/      AppRoot · Screens · Theme                     (Compose، RTL)
├─ AppViewModel.kt
└─ MainActivity.kt · HushyarApp.kt
```

| لایه | اجازهٔ import اندروید |
|---|---|
| `core` | ندارد — JVM خالص |
| `domain` | ندارد — JVM خالص (کل هوش اینجا آزمون می‌شود) |
| `data` | دارد (SQLite، فایل، شبکه) |
| `ui` | دارد (Compose) |

قاعدهٔ لایه‌بندی: `core` و `domain` هیچ import اندرویدی ندارند، پس تمام منطق با JUnit روی JVM آزمون می‌شود.

## مستندات

| سند | محتوا |
|---|---|
| [`docs/DESIGN-v2.0.md`](docs/DESIGN-v2.0.md) | سند طراحی مرجع (معماری، داده، امنیت، نقشهٔ راه) |
| [`docs/DESIGN-v1.0.md`](docs/DESIGN-v1.0.md) | نسخهٔ اول سند (تاریخی) |
| [`docs/V2-SCOPE.md`](docs/V2-SCOPE.md) | نگاشت ۹ درخواست به کد + انحراف‌های صادقانهٔ v0.2 |
| [`docs/V1-SCOPE.md`](docs/V1-SCOPE.md) | دامنهٔ v0.1 و انحراف‌های فنی آن |
| [`docs/TESTING.md`](docs/TESTING.md) | سناریوی تست دستی + آزمون تعویض بازار |
| [`docs/REPORT-BUILD-V2.md`](docs/REPORT-BUILD-V2.md) | گزارش بیلد v0.2، خروجی آزمون‌ها، بازرسی APK |
| [`docs/REPORT-BUILD.md`](docs/REPORT-BUILD.md) | گزارش بیلد v0.1 |

## اصولی که در کد قابل بازرسی‌اند

1. **بدون حدس:** هر عدد در پاسخ به یک آگهی/سند ارجاع دارد (`Critic`)، و ویژگی نامعلوم «نامعلوم» می‌ماند.
2. **بازار = داده:** تعویض بازار فقط JSON است.
3. **قطعیت:** همهٔ منطق تحلیل و پاسخ، تابع خالص ورودی است؛ همان ورودی، همان خروجی.
4. **حریم خصوصی:** داده در SQLite محلی، بدون پشتیبان‌گیری ابری (`allowBackup=false`)، بدون تلمتری.
5. **وب فقط داده است:** هر صفحه پیش از استفاده برای شواهد، از نظر «تزریق دستور» بازرسی
   می‌شود (`InjectionGuard`)؛ صفحهٔ آلوده هرگز شاهد نمی‌شود.
6. **هر عمل قابل بازبینی است:** تغییر داده، جستجو و پاسخ در جدول `event` و `audit`
   ثبت می‌شود و به `brain.md` درمی‌آید.

## نمونهٔ استفاده

```
ذخیره کن: سقف بودجه‌ام ۲۰ میلیون تومان است     →  در حافظه ثبت شد (دستهٔ بودجه)
قیمت گوشی سامسونگ گلکسی S24 چقدر است؟          →  کاتالوگ محلی، با استناد به آگهی‌ها
بهترین مارک چای سبز برای فشار خون چیست؟        →  کاتالوگ محلی ندارد → جستجوی وب چند-موتوره
                                                →  شاهد استخراجی با آدرس منبع
چه چیزی یادت مانده؟                             →  خلاصهٔ حافظه + رکوردهای مهم
بک‌آپ بگیر                                      →  ZIP + JSON با چک‌سام، شامل brain.md
```

## بک‌آپ

`/data/data/ir.aai.hushyar/files/hushyar/backups/`

| فایل | محتوا |
|---|---|
| `hushyar-backup-<تاریخ>.zip` | `backup.json` (بازگردانی کامل) + `brain.md` + `logs/hushyar-log.md` + `memory/memory.md` |
| `hushyar-backup-<تاریخ>.json` | همان سند بازگردانی، بیرون از زیپ |

بازیابی از سه راه: انتخاب فایل (SAF)، فهرست بک‌آپ‌های روی دستگاه، یا چسباندن متن JSON.
فایل بدون چک‌سام معتبر باز نمی‌شود و بازیابی در یک تراکنش انجام می‌گیرد.
