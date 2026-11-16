# هوشیار (AAI)

[![Android CI](https://github.com/alisport1384/AAI/actions/workflows/android.yml/badge.svg)](https://github.com/alisport1384/AAI/actions/workflows/android.yml)

موتور تحلیل کالا و پرسش‌وپاسخ، **تماماً روی دستگاه**، برای یک بازار که **کاربر خودش معرفی می‌کند**.

- هیچ بازاری در برنامه کامپایل نشده است.
- هیچ API، توکن یا سرویس بیرونی مصرف نمی‌شود؛ نسخهٔ ۰.۱ حتی مجوز شبکه ندارد.
- پرسش‌ها فقط با **شاهد** پاسخ داده می‌شوند؛ در نبود شاهد، صریح «نمی‌دانم».

## وضعیت

| مورد | v0.1.0 |
|---|---|
| ساخت خودکار (GitHub Actions) | ✅ سبز — ۴۶ آزمون، APK ساخته و بازرسی شد (`docs/REPORT-BUILD.md`) |
| مجوزهای APK | فقط مجوز داخلی androidx — **بدون `INTERNET`** |
| بازار | مصاحبهٔ زمان اجرا → `MarketPack` ↔ JSON (تعویض بازار بدون کامپایل) |
| تحلیل | نرمال‌سازی قیمت، استخراج ویژگی، یکسان‌سازی کالا، آمار قیمت، امتیاز ارزش، هشدارها |
| پرسش‌وپاسخ | تشخیص نیت + شاهد + استناد + Critic + امتناع |
| مدل زبانی | در ۰.۱ نیست؛ نقطهٔ اتصال `Phraser` آماده است |

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
├─ core/        Json · Text · Price · Similarity · Ids        (بدون وابستگی اندروید)
├─ domain/      Models · PackCodec · PackValidator · Interview
│               SelfModel · Analysis · Qa                      (منطق خالص، آزمون‌پذیر)
├─ data/        Db · Repos · CsvImporter                       (SQLite)
├─ ui/          AppRoot · Screens · Theme                      (Compose، RTL)
├─ AppViewModel.kt
└─ MainActivity.kt · HushyarApp.kt
```

قاعدهٔ لایه‌بندی: `core` و `domain` هیچ import اندرویدی ندارند، پس تمام منطق با JUnit روی JVM آزمون می‌شود.

## مستندات

| سند | محتوا |
|---|---|
| [`docs/DESIGN-v2.0.md`](docs/DESIGN-v2.0.md) | سند طراحی مرجع (معماری، داده، امنیت، نقشهٔ راه) |
| [`docs/DESIGN-v1.0.md`](docs/DESIGN-v1.0.md) | نسخهٔ اول سند (تاریخی) |
| [`docs/V1-SCOPE.md`](docs/V1-SCOPE.md) | دقیقاً چه چیزی پیاده شده، چه چیزی نه، و انحراف‌های فنی |
| [`docs/TESTING.md`](docs/TESTING.md) | سناریوی تست دستی + آزمون تعویض بازار |
| [`docs/REPORT-BUILD.md`](docs/REPORT-BUILD.md) | گزارش بیلد و نتیجهٔ CI |

## اصولی که در کد قابل بازرسی‌اند

1. **بدون حدس:** هر عدد در پاسخ به یک آگهی/سند ارجاع دارد (`Critic`)، و ویژگی نامعلوم «نامعلوم» می‌ماند.
2. **بازار = داده:** تعویض بازار فقط JSON است.
3. **قطعیت:** همهٔ منطق تحلیل و پاسخ، تابع خالص ورودی است؛ همان ورودی، همان خروجی.
4. **حریم خصوصی:** داده در SQLite محلی، بدون پشتیبان‌گیری ابری (`allowBackup=false`)، بدون تلمتری.
