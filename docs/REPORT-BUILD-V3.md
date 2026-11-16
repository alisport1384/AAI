# گزارش بیلد و انتشار نسخهٔ ۳ (۰.۳.۰)

تاریخ: ۲۰۲۶-۱۱-۱۶ · مخزن: <https://github.com/alisport1384/AAI> · شاخهٔ `main`

## ۱. نتیجهٔ CI

| مورد | مقدار |
|---|---|
| run id | **`43939378531`** |
| commit | `46ed3fb` — `feat(v0.3): the assistant researches on its own …` |
| نتیجه | ✅ **success** (هر ۹ گام: Checkout → JDK 17 → Gradle → SDK 35 → Unit tests → Assemble APK → Upload APK → Upload reports) |
| آزمون‌ها | **۱۲۳ اجرا، ۰ شکست** |
| artifact | `hushyar-debug-apk` — ۹٬۴۵۶٬۶۳۶ بایت |

## ۲. بازرسی APK واقعی (artifact CI، نه بیلد محلی)

```
$ aapt2 dump badging app-debug.apk
package: name='ir.aai.hushyar' versionCode='3' versionName='0.3.0'
         compileSdkVersion='35' platformBuildVersionName='15'
minSdkVersion:'26'
targetSdkVersion:'35'
uses-permission: name='android.permission.INTERNET'
uses-permission: name='ir.aai.hushyar.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
sha256: 419b45353d18f8cedb309e89c253430f531a52cfa0c5e9652e9fc6005568a709
```

مجوز شبکه همان یک مورد قبلی است (تنها برای GET به موتورهای عمومی)؛ مجوز دوم، مجوز
داخلی خودکار androidx است. هیچ مجوز ذخیره‌سازی/مکان/دوربین/میکروفون اضافه نشده.

## ۳. دو ایراد ساختِ بیلد (نه رفتاری)

| ایراد | علت | رفع |
|---|---|---|
| `The feature "break continue in inline lambdas" is experimental` | `?: run { skipped++; continue }` داخل `run` | تبدیل به `if (price == null) { … continue }` |
| `Platform declaration clash: setResearchDepth(String)` | `var researchDepth` با `private set` همان setter را می‌سازد | تابع به `chooseResearchDepth` تغییر کرد |

## ۴. باگ‌های واقعی که آزمون‌ها گرفتند (هفت مورد)

| # | آزمون/مسیر | رفتار غلط | حقیقت و اصلاح |
|---|---|---|---|
| ۱ | `numericExtractionUnderstandsPersianPrices` | «۵۰ میلیون تومان» با واحد `میلیون` برمی‌گشت | واژهٔ مقیاس بر واحد پول اولویت می‌گرفت → بازنویسی استخراج عدد با **اولویت واحد پول**؛ مقدار = عدد × مقیاس |
| ۲ | `changedNumberCorrectsAndKeepsHistory` | تغییر ۵۰→۶۵ میلیون «REFRESH» شد، نه «CORRECT» | مسیر «متن یکسان» زودتر از بررسی عدد اجرا می‌شد → **اول عدد، بعد متن**؛ مقدار قبلی حالا با `supersededBy` نگه داشته می‌شود |
| ۳ | `disagreeingTextIsRecordedAsConflict` | تناقض هرگز `CONFLICT` نمی‌شد (هم‌پوشانی واژه‌ها ≥ ۰.۵) | تشخیص با **قطبیت نفی** (ندارد/نیست/هیچ/… در برابر ادعای مثبت) + Jaccard ≥ ۰.۲ جایگزین شد |
| ۴ | `sameUrlIsNotFetchedTwice` | یک URL تکراری در یک SERP دو بار واکشی می‌شد | فیلتر «دیده‌شده» پیش از حلقه بود → بررسی به **داخل** حلقه منتقل شد (`seen.add` شرطی) |
| ۵ | `staleKnowledgeIsRefreshedAndCorrectedByFresherSource` | «بروزرسانی کن» هیچ کاری نمی‌کرد | ردیف کهنه به‌عنوان «دانش موجود» به حلقه داده می‌شد و حلقه به‌درستی می‌گفت «کافی است» → در مسیر بروزرسانی `known = emptyList()`؛ ضمناً حلقه اگر دانش موجود کافی باشد **هیچ درخواستی نمی‌فرستد** |
| ۶ | `webResearchAnswersWithTraceableCitations` | گام `web.fetch` برای صفحه‌های **پذیرفته‌شده** ثبت نمی‌شد | فقط صفحه‌های ردشده گام داشتند → یک گام `web.fetch` برای هر صفحهٔ پذیرفته‌شده اضافه شد |
| ۷ | چند آزمون پژوهش | صفحهٔ کوتاه (۱۷۰ نویسه) که قیمت را دقیق می‌گفت کنار گذاشته می‌شد | آستانهٔ «متن بی‌مصرف» از ۲۰۰ به **۱۲۰** نویسه آمد و مستند شد |

سه انتظار نادرست آزمون هم اصلاح شد (نه کد): بولت‌های پاسخ شامل خط وضعیت «— …» هستند،
بنابراین بررسی «عیناً از صفحه» فقط روی بولت‌های `W#` انجام می‌شود؛ و وقتی دانش موجود
کافی است، حلقه **صفر** دور اجرا می‌کند (این هدف است، نه باگ).

## ۵. تأیید «چیزی از بیرون مصرف نمی‌شود»

```
$ grep -rn "api[_-]key\|apiKey\|Bearer \|firebase\|analytics\|telemetry" app/src/main/java
app/src/main/java/ir/aai/hushyar/data/Net.kt:13: * No SDK, no key, no telemetry, no cookies persisted...
```

تنها تطبیق، همان کامنت است. تنها نقطهٔ شبکه: `data/Net.kt` (GET ساده، سقف ۸۰۰KB،
timeout ۱۲/۱۸ ثانیه، UA صادق، بدون پروکسی و بدون دور زدن محدودیت).

## ۶. آنچه هنوز تست نشده (صادقانه)

| مورد | دلیل |
|---|---|
| اجرا روی گوشی واقعی | این محیط دستگاه/شبیه‌ساز ندارد؛ آزمون‌ها JVM و بازرسی APK ایستا است |
| نرخ واقعی موفقیت موتورها | SERPها پویا و IP مرکز داده معمولاً ۴۲۹ می‌گیرد؛ کد برای همین حالت مسیر «امتناع + ذکر خطا» دارد و آزمونش هست |
| بروزرسانی زمان‌بندی‌شده در پس‌زمینه | پیاده نشده (بدون WorkManager)؛ بروزرسانی دستی یا هنگام پرسش کهنه انجام می‌گیرد |

## ۷. بازتولید

```bash
git clone https://github.com/alisport1384/AAI && cd AAI
gradle testDebugUnitTest assembleDebug   # ۱۲۳ آزمون، سپس APK
```
