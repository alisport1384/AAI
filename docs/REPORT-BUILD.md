# گزارش بیلد و تأیید — v0.1.0

## ۱. وضعیت

| قلم | مقدار |
|---|---|
| وضعیت CI | ✅ **سبز** (همهٔ گام‌ها `success`) |
| اجرا (تأیید کد) | [run 43926740031](https://github.com/alisport1384/AAI/actions/runs/43926740031) — کامیت `aa692ee` |
| اجرای مستندات | [run 43927262522](https://github.com/alisport1384/AAI/actions/runs/43927262522) — کامیت `448080d` ✅ سبز |
| زمان اجرا | ۲۰۲۶-۱۱-۱۶ ۱۸:۱۴:۳۳ → ۱۸:۱۶:۵۳ (≈ ۲ دقیقه ۲۰ ثانیه) |
| آزمون واحد | ۴۶ آزمون، ۰ شکست |
| artifact | `hushyar-debug-apk` — ۹٬۲۳۷٬۱۴۷ بایت (فشرده) |

گام‌های job «Build & test»: Checkout → JDK 17 → Gradle 8.9 → نصب پکیج‌های SDK → `testDebugUnitTest` → `assembleDebug` → آپلود APK → آپلود گزارش آزمون‌ها. همه `success`.

## ۲. تأیید خروجی APK (نه فقط «بیلد شد»)

فایل `app-debug.apk` از artifact همین اجرا دانلود و با `aapt2 dump badging` بازرسی شد:

```
package: name='ir.aai.hushyar' versionCode='1' versionName='0.1.0'
compileSdkVersion='35'
minSdkVersion:'26'
targetSdkVersion:'35'
application-label:'هوشیار'
uses-permission: name='ir.aai.hushyar.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
```

نتیجهٔ بازرسی — سه ادعای سند در همین خروجی قابل سنجش است:

1. **هیچ مجوز شبکه‌ای وجود ندارد** (`android.permission.INTERNET` در فهرست نیست). پس «صفر API/توکن» در این نسخه یک واقعیت قابل بازرسی است، نه شعار.
2. تنها مجوز موجود، مجوز **داخلی خودکارِ** `androidx.core` برای broadcast گیرنده‌های صادرنشده است؛ نه دسترسی کاربر.
3. `minSdk 26` = اندروید ۸ به بالا؛ `targetSdk 35`.

## ۳. شکست‌ها و اصلاح‌ها (شفاف)

| # | مشکل | ریشه | اصلاح |
|---|---|---|---|
| 1 | `BUILD FAILED`: `HTTP 429 Too Many Requests` از Maven Central | سه بیلد هم‌زمان از همان برکهٔ IP، محدودسازی نرخ مخزن | افزودن آینهٔ Maven Central (Google) + `repository.max.retries=6` با backoff + `concurrency` برای لغو اجرای موازی همان ref |
| 2 | ۱ آزمون شکست: `AnalysisTest.missingAttributesAreReportedNotGuessed` | **باگ واقعی در منطق، نه در آزمون:** با یک مشاهدهٔ قیمت، صدک `1.0` گزارش می‌شد (ادعای بی‌پایه) | `PriceStatsCalculator` صدک را فقط با ≥۲ مشاهده محاسبه می‌کند؛ در غیر این صورت «نامعلوم» |
| 3 | ساخت محلی در محفظهٔ ۲ گیگی: ناپدید شدن daemon گریدل | کمبود حافظه/هسته | `kotlin.compiler.execution.strategy=in-process`، سقف heap کوچک‌تر، غیرفعال‌کردن parallel |

مورد ۲ نمونهٔ دقیق همان چیزی است که در سند به‌عنوان اصل «بدون شاهد، عدد نگو» آمده: آزمون، ادعای بی‌شاهد را گرفت و کد اصلاح شد.

## ۴. آنچه CI تأیید می‌کند و آنچه تأیید نمی‌کند

**تأیید می‌کند:** کامپایل کامل Kotlin/Compose، اجرای ۴۶ آزمون واحد منطق (نرمال‌سازی فارسی، قیمت، پک، تحلیل، پرسش‌وپاسخ، Critic)، ساخت APK قابل‌نصب، محتوای مجوزهای APK.

**تأیید نمی‌کند:** اجرای واقعی روی دستگاه/شبیه‌ساز (تست instrumentation در CI نیست؛ اجرای امولاتور روی runner بدون KVM کند و شکننده است). مسیر تست دستی در `docs/TESTING.md` آمده و باید یک‌بار روی گوشی انجام شود.

## ۵. بازتولید محلی

```bash
# JDK 17 + Android SDK (platform 35, build-tools 35)
gradle testDebugUnitTest assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## ۶. نکتهٔ امنیتی لازم

توکن GitHub که در گفتگو فرستاده شد باید **باطل/چرخش شود**؛ در تاریخچهٔ گفتگو باقی است. توکن فقط برای push استفاده شد و در هیچ فایلی از مخزن نوشته نشد (فایل اعتبارنامه بیرون از مخزن و بیرون از snapshot است).
