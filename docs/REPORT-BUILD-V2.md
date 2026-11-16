# گزارش بیلد و انتشار نسخهٔ ۲ (۰.۲.۰)

تاریخ: ۲۰۲۶-۱۱-۱۶ · مخزن: <https://github.com/alisport1384/AAI> · شاخهٔ `main`

## ۱. خط به خط: چه اتفاقی افتاد

| گام | دستور / اقدام | نتیجه |
|---|---|---|
| ۱ | کامپایل محلی (`compileDebugKotlin`) | شکست: ۲۰ خطای کامپایل (فهرست در بخش ۳) |
| ۲ | رفع خطاها + کامپایل دوباره | ✅ سبز |
| ۳ | `testDebugUnitTest` | شکست: ۶ آزمون از ۹۲ (بخش ۴) |
| ۴ | رفع باگ‌های واقعی + اجرای دوباره | ✅ **۹۲ آزمون، ۰ شکست** |
| ۵ | `assembleDebug` | ✅ APK محلی ۹٬۸۰۷٬۰۶۷ بایت |
| ۶ | `git push origin main` (`4651c0a`) | ✅ push موفق |
| ۷ | GitHub Actions run **`43931452633`** | ✅ **success** |
| ۸ | دانلود artifact `hushyar-debug-apk` و بازرسی `aapt2` | ✅ بسته/نسخه/مجوزها تأیید شد |

## ۲. نتیجهٔ CI

```
run id      : 43931452633
commit      : 4651c0a  (feat(v0.2): on-device assistant …)
نتیجه       : success   (job: Build & test)
آزمون‌ها    : 92 اجرا، 0 شکست  (JUnit روی JVM، بدون شبیه‌ساز)
artifact    : hushyar-debug-apk  —  9,391,950 بایت
```

گام‌های workflow: `Checkout → JDK 17 → Gradle 8.9 → SDK 35 → testDebugUnitTest → assembleDebug → upload APK`.

## ۳. خطاهای کامپایل و علتشان (شفاف)

| خطا | علت واقعی | رفع |
|---|---|---|
| `Unresolved reference 'pow'` | `kotlin.math.pow` تابع الحاقی است، نه فراخوانی مستقیم | `import kotlin.math.pow` و `0.5.pow(x)` |
| `memorySearch` نوع بازگشتی ناسازگار | قرارداد `AgentTools` قدیمی `MemoryItem` بود ولی `Agent` به `score`/`item` نیاز داشت | امضای قرارداد به `List<MemoryHit>` تغییر کرد |
| `asStrings` / `asObjects` «Function invocation expected» | در Kotlin این‌ها تابع‌اند نه property | پرانتز اضافه شد |
| `Unresolved reference 'item'/'score'` (۷ خطا) | پیامد خطای بالا | با رفع قرارداد برطرف شد |

درس: هیچ‌کدام از این‌ها «باگ رفتاری» نبودند؛ ترجمهٔ نادرست قرارداد به کد. به همین دلیل
قبل از push کامپایل و آزمون محلی اجرا شد، نه بعد از آن.

## ۴. شش آزمون شکست‌خورده = سه باگ و سه انتظار نادرست

| آزمون | شکست | حقیقت | تصمیم |
|---|---|---|---|
| `NumWords.parse("۱٫۲۵ میلیون")` | `null` | پارسر فقط واژه‌ها را می‌خواند، رقم‌ها را نه | **باگ واقعی** → مسیر «رقم + واژهٔ مقیاس» اضافه شد (با حذف جداکنندهٔ هزارگان) |
| `NumWords.parse("بیست میلیون")` | `20` به‌جای `20000000` | واژه‌های مقیاس فارسی در جدول نبودند | **باگ واقعی** → `scaleWords` به مسیر واژه‌ای اضافه شد |
| `CommandParser.parse("بک‌آپ بگیر")` | دستور شناخته نشد → رفت به مسیر وب | نیم‌فاصله (`U+200C`) الگوی «بک‌آپ» را می‌شکست | **باگ واقعی** → تطبیق روی «کاوش هم‌طول» (ZWNJ→فاصله) و برش payload از متن اصلی با `MatchGroup.range` |
| `LangDetect.detect("۱۲۳")` | `FA` به‌جای `OTHER` | رقم‌های فارسی در بازهٔ `0600–06FF` هستند | انتظار آزمون غلط بود؛ آزمون اصلاح شد و رفتار مستند شد |
| `Markdown.log` شامل «۱۴۰۲» | نبود | `Jalali.format` رقم لاتین می‌دهد | انتظار آزمون غلط بود؛ به `1402` اصلاح شد |
| بولت‌های پاسخ وب عیناً در صفحه | نبود | `TemplatePhraser` پیشوند `W1: ` می‌گذارد | انتظار آزمون غلط بود؛ آزمون پیشوند را جدا می‌کند |

سه باگ اول در مسیرهایی بودند که کاربر واقعاً لمس می‌کند (ذخیره، دستور صوتی/متنی، عدد
فارسی). همه با تغییر کد رفع شدند، نه با تضعیف آزمون.

## ۵. بازرسی APK واقعی (artifact CI، نه بیلد محلی)

```
$ aapt2 dump badging app-debug.apk
package: name='ir.aai.hushyar' versionCode='2' versionName='0.2.0'
         compileSdkVersion='35' platformBuildVersionName='15'
minSdkVersion:'26'
targetSdkVersion:'35'
uses-permission: name='android.permission.INTERNET'
uses-permission: name='ir.aai.hushyar.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
application-label:'هوشیار'
sha256: cdba3a81d3ca7c4c311a4aa0cf3ae729e4b98591f129c7a8a2d55c6b738b8962   (9,391,950 بایت)
```

- مجوز `INTERNET` **عمداً** اضافه شده است: تنها کاربردش GET به موتورهای جستجوی عمومی است
  (`data/Net.kt` — تنها نقطهٔ شبکه در کل کدبیس).
- مجوز دوم، مجوز داخلی خودکار androidx است (نه مجوز کاربر).
- هیچ مجوز ذخیره‌سازی، مکان، مخاطبین، دوربین یا میکروفون وجود ندارد؛ بک‌آپ در
  `filesDir` خصوصی اپ نوشته می‌شود.

## ۶. تأیید نبودِ سرویس بیرونی

```
$ grep -rn "api[_-]key\|apiKey\|Bearer \|firebase\|analytics\|telemetry" app/src/main/java
app/src/main/java/ir/aai/hushyar/data/Net.kt:13: * No SDK, no key, no telemetry, no cookies persisted...
```
تنها تطبیق، یک **کامنت** است که همان تعهد را توضیح می‌دهد؛ هیچ کلید/توکن/SDK بیرونی
در کد وجود ندارد.

تنها منبع شبکه: `HttpUrlTransport.get` با UA صادق `Hushyar/0.2 (+on-device research)`،
بدون کوکی ماندگار، بدون پروکسی، بدون جعل هویت، سقف ۸۰۰ کیلوبایت در هر پاسخ،
timeout ۱۲/۱۸ ثانیه.

## ۷. آنچه در این نسخه تست نشده (صادقانه)

| مورد | دلیل |
|---|---|
| اجرای APK روی گوشی واقعی | این محیط شبیه‌ساز/دستگاه ندارد؛ آزمون‌ها JVM هستند و بازرسی APK ایستا است |
| موفقیت موتورهای جستجو در عمل | SERPها پویا هستند و IP مرکز داده اغلب ۴۲۹ می‌گیرد؛ کد برای همین حالت «امتناع با ذکر خطا» دارد و آزمون `searchRecordsFailuresAndDoesNotRetryForever` آن را پوشش می‌دهد |
| SAF برای انتخاب فایل بک‌آپ | فقط از نظر کامپایل پوشش دارد؛ نیاز به دستگاه دارد (سناریوی ۶ در `docs/TESTING.md`) |

## ۸. بازتولید

```bash
git clone https://github.com/alisport1384/AAI && cd AAI
# JDK 17 + Android SDK 35
gradle testDebugUnitTest assembleDebug     # 92 آزمون، سپس APK
```
