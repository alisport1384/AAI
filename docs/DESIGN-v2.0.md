# سند طراحی — «هوشیار ۲.۰»

**موتور تحلیل کالا و پرسش‌وپاسخِ تماماً روی دستگاه، با دانش قابل‌فهم برای Qwen**

| قلم | مقدار |
|---|---|
| شناسه | HSY-DD-002 |
| نسخه | 2.1 — بازنویسی کامل 2026-11-19 (جانشین 2.0 مورخ 2026-11-16) |
| تاریخ | 2026-11-19 (Asia/Tehran) |
| وضعیت | اجرا شده — CI سبز #44599688452 (f496624) — آمادهٔ انتشار |
| کد نسخه | 0.16.1 (code 24) — 3 تب + IronPin ۱۸ خانواده + موتورهای gerdoo/zarebin + تحقیق موضوعی Qwen-ready |
| توزیع | APK مستقیم / سایدلود / F-Droid / سازمانی — **بدون Google Play** |

> **چرا بازنویسی کامل؟** سند 2.0 یک «موتور» عام بود. از 2026-11-14 تا 19 سه قید جدید تثبیت شد که §24 آن وصله‌ای بود نه طراحی: (۱) سه‌تب شدن یوآی، (۲) دیتابیس آفلاین IronPinِ ۱۸ خانواده، (۳) «بخش وحشتناک مهم»: هر موضوع → همهٔ موتورها → ذخیره دقیقاً با فرمت چانک/متادیتا → قابل استفاده در پرسش بعدی برای Qwen. این بازنویسی آن سه قید را در **ذات معماری** می‌نشاند، نه به عنوان ضمیمه.

---

## فهرست

1. [تعریف محصول — سه تب](#1-تعریف-محصول--سه-تب) · 2. [نیازها و ردیابی](#2-نیازها-و-ماتریس-ردیابی) · 3. [قیدهای بنیادین](#3-قیدهای-بنیادین-و-پاسخ-آنها) · 4. [ADR](#4-تصمیمهای-معماری) · 5. [معماری کلان](#5-معماری-کلان) · 6. [Market Pack](#6-market-pack) · 7. [لایهٔ داده — فرمت یگانهٔ حقیقت](#7-لایهٔ-داده--فرمت-یگانهٔ-حقیقت) · 8. [استخراج و نرمال‌سازی](#8-استخراج-و-یکسانسازی) · 9. [موتور تحلیل](#9-موتور-تحلیل) · 10. [موتور پرسش‌وپاسخ](#10-موتور-پرسشوپاسخ) · 11. [استنتاج روی دستگاه و Qwen](#11-استنتاج-روی-دستگاه-و-qwen) · 12. [Search Core بدون API — ۳۰ موتور](#12-search-core-بدون-api--۳۰-موتور) · 13. [حافظه و یادگیری](#13-حافظه-و-یادگیری) · 14. [خودافزایی](#14-خودافزایی-سه-سطح) · 15. [نردبان قابلیت](#15-دسترسی-به-سیستم--نردبان-قابلیت) · 16. [توزیع و OTA](#16-توزیع-و-بهروزرسانی-بدون-play) · 17. [امنیت](#17-امنیت-و-حریم-خصوصی) · 18. [UX — سه تب](#18-تجربهٔ-کاربری--سه-تب) · 19. [چرخهٔ عمر](#19-چرخهٔ-عمر-و-پسپرداز) · 20. [NFR](#20-الزامات-غیرعملکردی) · 21. [آزمون و دروازه](#21-آزمون-و-دروازهٔ-انتشار) · 22. [ریسک‌ها](#22-ریسکها) · 23. [نقشهٔ راه](#23-نقشهٔ-راه) · 24. [V16 — IronPin × RAG Qwen (بخش وحشتناک مهم)](#24-v16--ironpin--rag-qwen--بخش-وحشتناک-مهم) · 25. [راستی‌آزمایی — اثبات فرمت ذخیره و استفادهٔ مجدد](#25-راستیآزمایی--اثبات-فرمت-ذخیره-و-استفادهٔ-مجدد) · 26. [ضمائم](#26-ضمائم)

---

## 0. تغییرات نسبت به 2.0 (2026-11-16)

| # | 2.0 | 2.1 (این نسخه) | فصل |
|---|---|---|---|
| 1 | یوآی عام (لیست تخت از قابلیت‌ها) | **سه تب قطعی: پرسش / دانش / تنظیمات** — هر تب یک مسئولیت؛ فایل‌های `BackendScreen.kt` حذف و منطقش به `SettingsScreen` منتقل شد | §18 |
| 2 | دانش = حافظهٔ ۴ لایه (انتزاعی) | **دانش = SQLiteِ `knowledge` با فرمت چانک/متادیتا** — هر نکته یک `KnowledgeItem` با ۱۵ فیلد (id هش، topic/question/claim/category/attribute/url/domain/confidence/createdAt/lastVerifiedAt/ttlDays/sourceCount/layer/tags + numericValue/unit/supersededById)؛ چانک ~۴۵۰ توکن ≈ ۹۰۰ حرف، overlap ۱۴۰، sentence-aware؛ منبع explicit | §7، §24 |
| 3 | دانش آفلاین ذکر نشده | **IronPin آفلاین: ۱۸ خانواده** → `IronKnowledge.kt:448` → `IronSeed.seed/reseed` idempotent → `category=iron`، ttl 180 روز، confidence 0.92؛ سید خودکار در `AppViewModel.init` | §24.1 |
| 4 | تحقیق = Search + Extractor | **تحقیق موضوعی Qwen-فهم:** تب دانش یک موضوع می‌گیرد → `DeepStudy` با **همهٔ ۳۰ موتور (شامل گردو/ذره‌بین)** تا اشباع → **dual-write**: `FactHarvester` (جملات دقیق) + `QwenRag` (چانک rag) → هر دو `knowledgeStore` → `Knowledge.answerable/coverage/gaps` فوراً جواب می‌دهد و Qwen بازنویسی می‌کند | §24.2 |
| 5 | 28 موتور | **۳۰ موتور**: `gerdoo.me/search/?query={q}` (NIN، HTML) + `zarebin.ir/search?q={q}` (HTML→JSON fallback `gse/api/search`) با `friction=2`، `JsonSerp.zarebin` | §12 |
| 6 | بدون گزارش RAG | **گزارش ۱۴ فصلی `QWEN-PIPELINE-REPORT.md` (376 خط)**: از PyMuPDF/BGE-M3/FAISSِ مرجع تا مسیر گوشیِ لغوی — چانک، متادیتا، پرامپت، Grounding، مسیر ارتقا به `sqlite-vec` | §24.5 |

---

## 1. تعریف محصول — سه تب

**هوشیار** موتور تحلیل کالا و پرسش‌وپاسخِ روی دستگاه است: مشخصات را از هر منبعی (فهرستِ اپِ میزبان، وب، تصویر، اسکرین‌شات) می‌خواند، هم‌ارزها را یکی می‌کند، قیمت را نرمال می‌کند، ارزش را امتیاز می‌دهد و **با استناد** جواب می‌دهد — بدون ارسال حتی یک بایت به سرویس بیرونی.

### 1.1 سه تب — قرارداد یوآی

| تب | فایل | مسئولیت | چه چیزی نیست |
|---|---|---|---|
| **پرسش** | `AskScreen.kt` | ویندو کانتنت (آخرین پرسش + تعداد)، فیلد پرسش، حالت تفکر سریع/متعادل/عمیق (`quick/balanced/deep` → `StudyBudget`)، تاریخچهٔ چتِ پایدار، تصمیم و گام‌ها، استناد | تنظیمات نیست |
| **دانش** | `KnowledgeScreen.kt` | آمار IronPin + خلاصهٔ `knowledge`؛ کارت «دیتابیس آفلاین» (بارگذاری/بازنصب)؛ **تحقیق موضوعی** (یک موضوع → `deepStudy` با همهٔ موتورها تا اشباع)؛ بک‌آپ ZIP/Markdown؛ کهنه‌ها و تازه‌سازی | چت نیست |
| **تنظیمات** | `SettingsScreen.kt` | لاگ حرفه‌ای (پیش‌فرض خاموش، export .md)؛ **مدل** (انتخاب از `ModelCatalog`، دانلود/resume+SHA256، بارگذاری/ذخیره/تست؛ `directModel` محاسبه)؛ **موتورها** (۳۰ موتور + آزمون سلامت + دیتابیس شخصی `UserSource→EngineSpec` با friction 0)؛ بک‌اند شخصی؛ عمومی و پاک‌سازی | دانش نیست |

سه‌تبی شدن یک تصمیم معماری است نه سلیقه: هر پرسش «این کجا ذخیره می‌شود؟» یک جواب دارد. دانش فقط در دانش ساخته/نمایش/بک‌آپ می‌شود؛ تنظیمات فقط مدل/موتور/لاگ را لمس می‌کند؛ پرسش فقط می‌خواند و می‌پرسد.

### 1.2 سه حالت اتصال به «اپ انتخابی»

| حالت | مکانیزم | عمق | همکاری میزبان |
|---|---|---|---|
| M1 — Embedded | AAR/SDK داخل اپ میزبان | کامل (DB اپ) | لازم |
| M2 — Import | CSV/JSON/DB یا پایپ‌لاین محلی اپراتور | بالا، دسته‌ای | بی‌نیاز |
| M3 — Screen-Assist | Accessibility + MediaProjection → VLM/OCR | متوسط، لحظه‌ای | بی‌نیاز |

در M3 هوشیار در اپ دیگر **عملیات خرید انجام نمی‌دهد**؛ تحلیل می‌کند، مقایسه می‌کند، ناوبری را گام‌به‌گام پیشنهاد می‌کند (§15).

### 1.3 کارهای هسته

1. استخراج ویژگی از عنوان/جدول/متن/تصویر.
2. یکسان‌سازی هم‌ارزها (یک گوشی با سه عنوان متفاوت).
3. نرمال‌سازی و تاریخ‌گذاری قیمت؛ تشخیص پرت.
4. مقایسه و امتیاز «ارزش» بر پایهٔ Market Pack.
5. پاسخ با شاهد و امتناع صریح در نبود شاهد.
6. **یادگیریِ قابل فهم برای Qwen**: هر نکته با فرمت §7 ذخیره، با `answerable/coverage/gaps` بازیابی و در پرامپت بعدی تزریق می‌شود.

---

## 2. نیازها و ماتریس ردیابی

| # | نیاز کاربر | نیاز مهندسی | فصل | معیار پذیرش |
|---|---|---|---|---|
| R1 | سبک، داخل اپ | APK ≤110MB بدون مدل؛ RAM پایه ≤300MB؛ مدل جدا | §11، §20 | ۴ رده دستگاه |
| R2 | **یادگیری و ذخیره با فرمت دقیق و چانک ۴۵۰ توکن** | `knowledge` با ۱۵ فیلد + `QwenRag` (900/140/200) + `KnowledgeBank` | §7، §24، §25 | هر وب‌پیج → N چانک rag با منبع explicit؛ `answerable` همان موضوع را بدون جستجو می‌دهد — آزمون §25 |
| R3 | فارسی کامل | نرمال‌ساز + واژه‌نامه ویژگی + ارزیابی | §8 | خطای اعداد/حروف ≤0.5% |
| R4 | انگلیسی کامل | همان مسیر + واژه‌نامه فنی | §8 | ≥90% سطح مدل پایه |
| R5 | دسترسی و اصلاحِ خودش | نردبان قابلیت + خودافزایی ۳سطحی | §14، §15 | هر تغییر ۱-گام rollback |
| R6 | فکر و پاسخ | حلقهٔ استدلال + بازبین + استناد | §10 | QA ≥85% |
| R7 | جستجو بدون API | Search Core بدون کلید (HTML + خزش + ایندکس محلی) — **۳۰ موتور** | §12 | ≥2 کانال سالم هم‌زمان؛ degrade بی‌کرش |
| R8 | خودبهسازی کد | سندباکس + EvalGate + امضا | §14 | صفر فرار از سندباکس |
| R9 | صفر API/توکن بیرونی | استنتاج، embedding، OCR، جستجو همه محلی | §5، §11، §12 | قطع کامل شبکه → همه‌چیز جز واکشیِ وب کار می‌کند |
| R10 | **دیتابیس آفلاین ۱۸ خانواده** | `IronKnowledge` + `IronSeed` idempotent | §24.1 | `reseed` بدون تکرار؛ «میلگرد A3 چیست» در حالت هواپیما جواب می‌دهد |
| R11 | **موتورهای ایرانی** | `gerdoo` + `zarebin` با JSON fallback | §12 | `Engines.all.size==30`؛ `probe()` هر دو را سالم نشان می‌دهد |
| R12 | **تحقیق موضوعی قابل ذخیره برای Qwen** | `DeepStudy` dual-write + `StudyBudget` + diverse | §24.2 | `FactHarvester` + `QwenRag` هر دو `knowledgeStore`؛ `coverage≥60%`؛ موتورهای پاسخ‌دهنده ≥5 |

---

## 3. قیدهای بنیادین و پاسخ آنها

### 3.1 «بدون Google Play»

قیدهای OS باقی‌اند (Accessibility باید روشن شود، `MANAGE_EXTERNAL_STORAGE` ویژه است، `/data/data` بدون root بسته). آنچه اضافه می‌شود: **OTA خودی**، بارگذاری ماژول، Shizuku/Device Owner.

### 3.2 «هیچ API/توکن بیرونی»

| نیاز | راه بدون API | هزینه |
|---|---|---|
| استنتاج | llama.cpp + GGUF | صفر |
| بردار معنایی | امروز: retriever لغوی امتیازی؛ فردا: EmbeddingGemma-300M محلی | صفر |
| OCR | Tesseract `fas+eng` + VLM محلی | صفر |
| جستجوی وب | ۳۰ آداپتر HTML + خزش دامنه‌های بازار + FTS5 محلی | فقط ترافیک |
| تحلیل تصویر | Qwen3-VL-2B (رده C) | صفر |
| تلمتری | لاگ محلی + export دستی | صفر |

**صداقت:** ایندکس در حد گوگل بدون API ممکن نیست. پس موتور عمومی کمینه و قابل‌جایگزین شد: اولویت با جستجوی داخلی بازارها و ایندکس محلی است (§12).

### 3.3 «بازار خاص»

همهٔ دامنه در **Market Pack** بیرون از کد است (§6). موتور ثابت، بازار تعویض‌پذیر.

### 3.4 «خودش را اصلاح کند»

سه سطح با اختیارات صریح: مهارت اعلانی، اسکریپت QuickJS، افزونهٔ بومی امضاشده (فقط انسانی). کد تولیدی مدل هرگز به E2 نمی‌رسد (§14).

### 3.5 «هر نکته با فرمت مشخص و قابل فهم برای Qwen»

این قید جدید، فصل 7 و 24.2 را زایید: یک فرمت یگانه (`KnowledgeItem` + چانک + متادیتا) که هم آفلاینِ IronPin و هم تحقیقِ وب از آن تبعیت می‌کنند — و یک مسیر بازیابی یگانه (`answerable/coverage/gaps`) که هر دو را بی‌تفاوتِ منبع می‌خواند. Qwen فقط بازنویسی می‌کند؛ Grounding جلوی عددسازی را می‌گیرد.

---

## 4. تصمیم‌های معماری

| ADR | پذیرفته | ردشده و چرا |
|---|---|---|
| 01 | **llama.cpp/GGUF** + **Qwen3-VL-2B** | MLC-LLM (زنجیره سنگین، کوانت ناپایدار)؛ ONNX GenAI (کاتالوگ ضعیف)؛ VLM ابری (نقض بدون-API) |
| 02 | **یک مسیر tool-call با گرامر GBNF** | ترکیب گرامر+tool-call بومی — llama.cpp هم‌زمان نمی‌کشد |
| 03 | **استخراج قاعده‌محور اول، VLM در صورت نیاز** | VLM برای همه‌چیز — ۵–۱۰ ثانیه/کالا و OCR شلوغ |
| 04 | **SQLite + FTS5 + (آینده) sqlite-vec** در یک فایل | ObjectBox/Chroma (موتور دوم، بکاپ پیچیده) |
| 05 | **Search Core چندآداپتری (۳۰ موتور) با Health/Backoff** | تک‌موتور (Bing API بازنشسته 2025-08-11؛ DDG چالش JS؛ Google CSE بسته)؛ SERP پولی (نقض بدون-API) |
| 06 | **خزش محترمانه روی `source_registry`** | دور زدن CAPTCHA/چرخش پروکسی — شکننده + ریسک حقوقی |
| 07 | **QuickJS برای E1** | Python/Chaquopy (حجم)؛ dex پویا (بدون سندباکس) |
| 08 | **OTA امضاشده با APK کامل** | تکه‌تکهٔ فایل — فساد و عدم‌امضا |
| 09 | **نرمال‌سازی فارسی دو-نسخه‌ای (نمایش/ایندکس)** | نرمال‌سازی درجا — تغییر دادهٔ کاربر |
| **10** | **فرمت یگانهٔ دانش: `KnowledgeItem` + چانک 450tok (≈900 حرف، 140 overlap، sentence-aware) + dual-write** | دو فرمت جدا (یکی برای Iron، یکی برای وب) — دو مسیر بازیابی، دو باگ، عدم پوشش پرسش بعدی |
| **11** | **retriever لغویِ امتیازی امروز، برداری فردا — چانک ثابت** | نگه داشتن چانک تا آمدن بردار — معماری قفل می‌شد و Iron باید بازتولید می‌شد |
| **12** | **سه تب به عنوان قرارداد معماری** | تک‌صفحه با تب‌های قابل‌مخفی — هر نکته می‌توانست از هر جایی نوشته شود و تست‌ها ناپایدار می‌شدند |

---

## 5. معماری کلان

```
┌──────────────────────────────────────────────────────────────────────┐
│ UI — سه تب (Compose, RTL)                                             │
│  Ask (پرسش + تفکر quick/balanced/deep) │ Knowledge (IronPin + تحقیق) │ Settings (مدل/موتور/لاگ) │
├──────────────────────────────────────────────────────────────────────┤
│ Analyzer Core                                                        │
│  QueryRouter → ProductResolver → EvidenceCollector → Scorer          │
│  → AnswerComposer(استناد اجباری) → Critic                            │
├──────────────────────────────────────────────────────────────────────┤
│ Domain Layer — Market Pack                                           │
│  Schema · AttributeDictionary · PriceRules · IntentTaxonomy · Rubric  │
├──────────────────────────────────────────────────────────────────────┤
│ Knowledge Layer — فرمت یگانهٔ حقیقت                                  │
│  KnowledgeItem(15 فیلد) · QwenRag(900/140/200) · KnowledgeBank        │
│  (answerable/coverage/gaps/isStale/stamp/merge) · IronKnowledge      │
│  IronSeed(seed/reseed) · DeepStudy(FactHarvester+QwenRag→knowledgeStore) │
│  MemoryStore · Retriever(BM25+Vec آینده، RRF/MMR) · Embedder(آینده)  │
│  Normalizer(fa/en) · Deduper · Consolidator                          │
├──────────────────────────────────────────────────────────────────────┤
│ Inference — 100% محلی                                                │
│  LLM (llama.cpp) · VLM (Qwen3-VL) · OCR (Tesseract fa/en)            │
│  LocalAI(ModelSpec، پرامپت، Grounding) · TierGovernor · ThermalGuard │
├──────────────────────────────────────────────────────────────────────┤
│ Acquisition — بدون API                                               │
│  SearchCore(30 موتور) · SerpParser + JsonSerp.zarebin · SerpGuard    │
│  Crawler(seeds, robots, rate) · PageExtractor · Importer · PackLoader│
├──────────────────────────────────────────────────────────────────────┤
│ Self-Extension                                                       │
│  SkillDSL · ScriptSandbox(QuickJS) · NativePlugins(امضاشده) · EvalGate│
├──────────────────────────────────────────────────────────────────────┤
│ Capability — نردبان                                                  │
│  Tools · PermissionBroker · Shizuku/DO/Root Bridge · Audit           │
├──────────────────────────────────────────────────────────────────────┤
│ Persistence: SQLite(WAL) + FTS5 + (آینده vec0) + SQLCipher + Keystore │
│  جدول knowledge (ttl_days, layer, category…) + source_registry + ...  │
└──────────────────────────────────────────────────────────────────────┘
```

### چهار جریان

```
[J1] تحلیل کالا
ورودی(متن/عکس/اسکرین‌شات/لینک) → استخراج ویژگی → Resolver
 → شواهد(فهرست اپ، خزش، knowledge) → نرمال قیمت → ارزش + پرچم → کارت با استناد

[J2] پرسش‌وپاسخ
پرسش → تشخیص نیت(pack) → KnowledgeBank.answerable → ساخت پاسخ با گرامر
 → Critic(شاهد؟ تناقض؟) → پاسخ+منابع / امتناع

[J3] همگام‌سازی — دو مسیرِ یک-فرمت
  [J3-a] آفلاین: IronKnowledge.toKnowledgeItems() → knowledgeUpsert → knowledge
  [J3-b] تحقیق موضوعی: topic → StudyPlanner(facets) → DeepStudy
         (هر صفحه → FactHarvester + QwenRag → knowledgeUpsert → knowledge)
       → coverage/gaps → اشباع → knowledgeAnswerable در J2 بی‌جستجو جواب می‌دهد

[J4] خودافزایی
شکست مکرر/الگو → پیش‌نویس(E0/E1) → ایستا+Dry-run(50 نمونه) → EvalGate(≥3% پیشروی، صفر رگرسیون)
 → تأیید کاربر → فعال نسخه‌دار → پایش → افت→quarantine+rollback
```

---

## 6. Market Pack — قرارداد دامنه

موتور ثابت است؛ «بازار» بسته‌ای اعلانی و امضاشده است. نمونهٔ مرجع (گوشی موبایل — چون همهٔ قابلیت‌ها را فعال می‌کند) برای نمایش مکانیزم:

```json
{
  "schema": "hushyar.pack/1", "id": "mobile-phones", "name": "گوشی موبایل", "version": 3,
  "lang": ["fa","en"], "currency": {"base":"IRT","aliases":{"تومان":"IRT","ریال":"IRR"}, "irrToIrt":0.1},
  "entity": {"key_fields":["brand","family","model","storage_gb","ram_gb"], "aliases":{"سامسونگ":"Samsung"}},
  "attributes": [
    {"id":"storage_gb","type":"number","unit":"GB","required":true,"weight":0.25},
    {"id":"ram_gb","type":"number","unit":"GB","required":true,"weight":0.15},
    {"id":"battery_mah","type":"number","unit":"mAh","required":false,"weight":0.15},
    {"id":"warranty","type":"enum","values":["شرکتی","محلی","ندارد"],"weight":0.20}
  ],
  "ideal_profile": {"storage_gb":256,"ram_gb":8,"warranty":"شرکتی"},
  "price": {"outlier_rule":"mad_z","mad_z_threshold":3.5,"window_days":30,"bands":{"good":0.25,"fair":0.55,"high":0.80}},
  "intents": ["price.check","price.history","spec.lookup","compare","authenticity.check","stock.check","risk.flag"],
  "red_flags": [{"id":"price_too_low","when":"price < p05(window=30d)","severity":"high","text_fa":"قیمت غیرمعمول پایین است."}],
  "refusal_policy": {"no_evidence":"fa.refuse.soft"},
  "eval_suite": "mobile-phones-v3"
}
```

> قواعد: تغییر پک = تغییر رفتار بدون کامپایل؛ پک امضاشده و نسخه‌دار است؛ نبود پک → حالت عمومی (`title,price,description`) بی‌کرش.

---

## 7. لایهٔ داده — فرمت یگانهٔ حقیقت

### 7.1 جدول `knowledge` — ستون به ستون با قید پذیرش کاربر

| ستون DDL | فیلد Kotlin | مقدار نمونه IronPin | مقدار نمونه rag وب | نقش در Qwen |
|---|---|---|---|---|
| `id TEXT PK` | `id: String` | `hash("iron:sheet:specs:شکل ورق…")` | `hash("rag:ورق گالوانیزه:https://…#0:ورق…")` | deduplication + استناد |
| `topic TEXT` | `topic` | `آهن‌آلات ورق` | `قیمت ورق گالوانیزه 0.5` (کلید موضوع) | بازیابی `answerable` |
| `question TEXT` | `question` | `ورق مشخصات فنی` | `قیمت ورق گالوانیزه 0.5 چیست` (`StudyPlanner.question`) | پرامپت Qwen |
| `claim TEXT` | `claim` | جملهٔ آموزنده ≤240 حرف | **چانک ۹۰۰ حرف (~۴۵۰ توکن)** با overlap | متن تزریق به Qwen |
| `category TEXT` | `category` | `iron` | `rag` (و `study` قدیم) | فیلتر آفلاین/وب |
| `attribute TEXT` | `attribute` | `ورق` (نام فارسی خانواده) | `price/specs/…` (اسلوت پوشش) | `coverage` — ۴ اسلوت |
| `numeric_value REAL` | `numericValue` | NULL | 0.5 (وقتی عدد با واحد دارد) | قیمت/عدد بدون حدس |
| `unit TEXT` | `unit` | NULL | `mm` | همراه numeric |
| `url TEXT` | `url` | `asset://iron_knowledge/sheet` | `https://ahan.com/…` | **منبع explicit — الزامی** |
| `domain TEXT` | `domain` | `ironpin` | `ahan.com` | تنوع منبع (≥2 دامنه = امتیاز در `coverage`) |
| `confidence REAL` | `confidence` | 0.92 (دستی) | 0.68 chunk / 0.62 عددی / 0.55 توصیفی | وزن `answerable` |
| `created_at INTEGER` | `createdAt` | clock | clock (`stamp` اگر 0 بود) | عمر |
| `last_verified_at INTEGER` | `lastVerifiedAt` | clock | clock | `isStale` |
| `ttl_days INTEGER` | `ttlDays` | **180** | **45** | کهنگی: قیمت زودتر کهنه می‌شود |
| `source_count INTEGER` | `sourceCount` | 1 (+1 در REFRESH) | 1 | اجماع |
| `superseded_by TEXT` | `supersededById` | NULL | NULL/آی‌دی تازه‌تر | تاریخچه بدون حذف |
| `layer TEXT` | `layer` | `specs/basics/trade/usage/tax` | `price/specs/…` | لایهٔ DeepStudy |
| `tags TEXT(JSON)` | `tags` | `[شکل, جنس, واحد]` | `[rag, chunk:0/2, tok:412, price, url…]` | دیباگ + برداری آینده |

**DDL مرجع (`Data.kt`):**

```sql
CREATE TABLE knowledge(
  id TEXT PRIMARY KEY, topic TEXT NOT NULL, question TEXT NOT NULL, claim TEXT NOT NULL,
  category TEXT NOT NULL, attribute TEXT, numeric_value REAL, unit TEXT,
  url TEXT NOT NULL DEFAULT '', domain TEXT NOT NULL DEFAULT '',
  confidence REAL NOT NULL DEFAULT 0.5, created_at INTEGER NOT NULL,
  last_verified_at INTEGER NOT NULL, ttl_days INTEGER NOT NULL DEFAULT 45,
  source_count INTEGER NOT NULL DEFAULT 1, superseded_by TEXT,
  tags TEXT NOT NULL DEFAULT '[]', layer TEXT NOT NULL DEFAULT ''
);
```

هر `KnowledgeItem` این ۱۵+۳ ستون را حمل می‌کند. متنِ بدون منبع (`url == ""`) در پاسخ استفاده نمی‌شود — این «منبع explicit» همان قید پذیرش کاربر است.

### 7.2 چگونگی پر شدن (دو مسیر، یک فرمت)

* **آفلاین:** `IronKnowledge.toKnowledgeItems(clock)` → `List<KnowledgeItem>` (category iron, ttl 180, confidence 0.92) → `Repos.knowledgeUpsert` → `KnowledgeBank.merge` → تصمیم NEW/REFRESH/IGNORE → ترنزکشن → audit.
* **تحقیق وب:** `DeepStudy.study(topic)` — هر دور، هر وجه → ۳ query → `searchAll` روی ۳۰ موتور → `diverse(hits)` → برای هر صفحه: `FactHarvester` (جملات 30–320 حرف) + `QwenRag` (چانک 900/140) → هر دو `knowledgeStore` → تصمیم merge → اشباع (`fresh==0` دو دور یا همه facetها ≥`minFactsPerFacet`) → `StudyOutcome`.

### 7.3 چگونگی خوانده شدن (یک retriever برای هر دو)

* `Repos.knowledgeAnswerable(question, now, limit=5)` → `KnowledgeBank.answerable(items, now, 4)` → فیلتر `fresh && !isStale && mentionsSubject` (واژهٔ مشترک «قیمت» کافی نیست — §10.1) → امتیاز `0.45·topicMatch + 0.30·claimRelevance + 0.15·freshness + 0.10·confidence` → `score>0.18` → sort → top-k → پرامپت Qwen.
* `KnowledgeBank.coverage(question, items, now, pack)` — ۴ اسلوت: relevance 0.45، numeric 0.20 (فقط اگر پرسش عدد خواست)، diversity 0.20 (≥2 دامنه)، attribute 0.15.
* `KnowledgeBank.gaps` — موتور محرک دور بعدی DeepStudy.
* `KnowledgeBank.isStale / staleItems / stamp` — چرخهٔ حیات.

مسیر ارتقا به برداری (بدون تغییر چانک): `sqlite-vec` + `multilingual-e5-small` (384d، 23MB INT8) → `score = 0.6·lexical + 0.4·cosine`؛ چانک‌ها، متادیتا، TTL، بک‌آپ دست‌نخورده.

### 7.4 بقیهٔ شِما (خلاصه)

```sql
CREATE TABLE product(id TEXT PK, pack_id TEXT, brand TEXT, canonical_name TEXT, attrs_json TEXT, attr_hash TEXT, ...);
CREATE TABLE listing(id TEXT PK, product_id FK, source_id TEXT, price_irt INTEGER, ...);
CREATE TABLE price_point(product_id, at, price_irt, listing_id, PK(product_id,at,listing_id));
CREATE TABLE source_doc(id TEXT PK, kind CHECK('page','file','image',…), uri TEXT, …);
CREATE TABLE claim(subject_kind, subject_id, attr, value, source_doc_id FK, confidence REAL);
CREATE TABLE memory_item(id TEXT PK, kind CHECK('preference','fact','episode','note'), body_norm TEXT, simhash INTEGER, ...);
CREATE VIRTUAL TABLE memory_fts USING fts5(title, body_norm, tokenize="unicode61 remove_diacritics 2");
-- (آینده) CREATE VIRTUAL TABLE memory_vec USING vec0(embedding float[256] distance_metric=cosine);
CREATE TABLE source_registry(id TEXT PK, kind CHECK('market','web','import','screen'), base_url TEXT, adapter TEXT, ...);
CREATE TABLE extension(id TEXT PK, kind CHECK('skill','script','native'), capabilities_json TEXT, status CHECK('draft','active','retired','quarantined'), ...);
CREATE TABLE audit_log(id INTEGER PK AUTOINCREMENT, at INTEGER, actor TEXT, action TEXT, target TEXT, ...);
-- Repos.preference(PREF_SEEDED="iron_seed_v1") برای IronSeed
```

یک فایل SQLite (WAL، `busy_timeout=5000`) — ترنزکشن واحد بین کالا/قیمت/دانش، بکاپ رمزدار یک‌فایلی.

---

## 8. استخراج و یکسان‌سازی

### 8.1 نرمال‌سازی فارسی/انگلیسی — نسخهٔ نمایش حفظ

| قاعده | نمونه | کاربرد |
|---|---|---|
| NFC | «ﻛﺘﺎﺏ»→«کتاب» | همه‌جا |
| `ك→ک`, `ي→ی`, `ة→ه` | «كتاب»≡«کتاب» | فقط ایندکس |
| `۰-۹/٠-٩→ASCII`؛ `٫→.`؛ حذف `٬` | «۲۵٬۰۰۰»→«25000» | قیمت/ویژگی |
| حذف اعراب و تطویل | «کِتاب»→«کتاب» | جستجو |
| نیم‌فاصله دو-نسخه‌ای | «می‌روم»≡«میروم» | جستجو |
| یکسان‌سازی واحد | «۱۲۸ گیگ»→`storage_gb=128` | عددی |
| واژه‌نامه برند | «سامسونگ»→`Samsung` | Resolver |

### 8.2 یکسان‌سازی کالا

```
blocking: (pack_id, brand, family) → candidates
score: 0.5·Jaccard(tokens)+0.3·cos(vec)+0.2·(1-|Δattr|/max)
s≥0.82→ادغام؛ 0.60≤s<0.82→صف تأیید در یوآی؛ <0.60→جدید
تضاد ≥2 ویژگی عددی با اختلاف>20% → ادغام رد
```

هر ادغام در `audit_log` و در یوآی قابل تفکیک («چرا یکی هستند؟»).

### 8.3 استخراج ویژگی — اولویت

1. JSON-LD/structured data (ارزان، دقیق).
2. جدول مشخصات + واژه‌نامهٔ پک.
3. متن آزاد (regex عدد+واحد؛ «۱۲۸/۸»→ram/storage).
4. VLM فقط اگر ۱–۳ ناقص: تصویر/اسکرین‌شات → JSON با گرامر.
5. OCR Tesseract `fas`؛ VLM داور تضاد.

هر ویژگی: `value, conf, src`؛ بدون منبع استفاده نمی‌شود.

### 8.4 نرمال‌سازی قیمت

```
"۱٫۲۵۰٫۰۰۰ تومان" | "1250000" | "۱٫۲ میلیون" | "125000 ریال" | "۱۲۵۰ ت"
→ یکسان ارقام/جداکننده → ضریب(میلیون×1e6، هزار×1e3)
→ ریال÷10→تومان → اعتبارسنجی 1e3…1e11 → price_point + percentile
```

عدد <1e5 بدون واحد → «نامعلوم» (حدس ممنوع).

---

## 9. موتور تحلیل

### 9.1 امتیاز ارزش

```
S_attr = Σ w_i·sim(attr_i, ideal_i) / Σ w_i
P      = percentile(price_irt | window=30d, same product)
S      = 0.6·S_attr + 0.4·(1-P)
verdict: S≥0.70→«ارزش بالا» | 0.45≤S<0.70→متوسط | <0.45→گران
tags پک: P≤good→ارزان | good<P≤fair→متعارف | fair<P≤high→بالا | >high→پرت
```

دو آستانه جدا: verdict کیفیت در برابر قیمت است، tags جایگاه قیمت در توزیع؛ `bands` در پک قابل تغییر است.

### 9.2 مقایسه

جدول مشترک + تفاوت‌ها، برنده/ویژگی، جمع‌بندی با وزن ترجیح کاربر (preference در دانش).

### 9.3 پرچم‌ها

`price_too_low`, `spec_conflict`, `duplicate_listing` (SimHash+dHash), `photo_mismatch` (VLM), `seller_unstable` — همه با زبان احتمالی و استناد.

### 9.4 سری زمانی

نمودار ۳۰/۹۰/۳۶۵ روز با میانه/صدک؛ پرش >25% در ۷ روز → `audit_log`.

---

## 10. موتور پرسش‌وپاسخ

### 10.1 نیت‌ها و شواهد لازم

| نیت | شواهد لازم | اگر نبود |
|---|---|---|
| `price.check` | `listing.price_irt` + `price_point` | «قیمت به‌روز ندارم» + آخرین تاریخ |
| `price.history` | `price_point` بازه | امتناع نرم |
| `spec.lookup` | `attrs_json` با منبع | امتناع نرم |
| `compare` | دو محصول با ویژگی هم‌پوشان | پرسش تکمیلی |
| `authenticity.check` | گارانتی/رجیستری/منبع | هشدار + توصیه دستی |
| `seller.trust` | تاریخچه فروشنده در DB | «داده کافی ندارم» |
| `stock.check` | `listing.stock`+`last_seen` | «نامعلوم» |
| `risk.flag` | red_flags | — |

### 10.2 قالب پاسخ (گرامر اجباری)

```json
{"intent":"price.check","answer_fa":"…","bullets":["…"],"citations":[{"ref":"L:8f2a","kind":"listing","url":"…","at":1763300000}],"confidence":0.0,"missing":["تاریخ گارانتی"]}
```

پاسخ بدون `citations` برای هر ادعای عددی → بازنویسی یا امتناع.

### 10.3 قانون «قیمت موضوع نیست» (v0.11.1)

اشکال گزارش‌شده: «قیمت تیشرت» و بعد «قیمت شلوار» — «قیمت» مشترک امتیاز می‌گرفت و پرسش دوم بی‌جستجو جواب اول را می‌داد.

```kotlin
KnowledgeBank.ASK_WORDS = {قیمت,نرخ,چقدر,وزن,سایز,price,cost,how,what,…}
subjectTokensOf(question) = contentTokens(question) - ASK_WORDS
mentionsSubject(question,item) = subject.any { token -> hay.any { word -> sameWord(token,word) } }
sameWord(a,b) = a==b || (len≥4 && (a.startsWith(b) || b.startsWith(a)))
```

اگر دانش ذخیره‌شده **هیچ** واژهٔ موضوع را نداشته باشد، ربط صفر است — `answerable` خالی، `coverage` صفر، جستجو اجباری.

### 10.4 Critic و امتناع

پاس دوم: هر ادعا↔شاهد؛ تناقض → منبع با `trust_score` بالاتر برنده + اعلام صریح؛ `confidence<0.5`→ جملهٔ عدم قطعیت. سه سطح امتناع: `soft`/`hard`/`unsafe` — پاسخ ساختگی هرگز.

---

## 11. استنتاج روی دستگاه و Qwen

### 11.1 رده‌ها

| رده | RAM | LLM | VLM | بافت | tok/s |
|---|---|---|---|---|---|
| A | 4GB | Qwen3.5-0.8B Q8 (~0.8GB) | — | 4096 | 25–40 |
| B | 6–8GB | Qwen3.5-2B IQ4_NL (~1.2GB) | SmolVLM2-500M | 8192 | 15–25 |
| C | 12GB+ | Qwen3.5-4B IQ4 | Qwen3-VL-2B (1.9GB) | 16384 | 10–18 |

حجم مدل ≤45% RAM؛ در غیر این‌صورت یک رده پایین. بدون VLM محصول کامل است.

### 11.2 پارامترها

`threads=max(2,cores/2)`؛ KV Q8_0؛ prefix cache؛ `temp 0.2` استخراج، `0.6` گفتگو؛ `n_predict≤512`؛ گرامر JSON؛ `ThermalGuard`: LIGHT→threads-1، MODERATE→رده پایین‌تر، SEVERE→توقف غیرتعاملی.

### 11.3 OCR و بینایی

Tesseract `fas+eng` + پیش‌پردازش (grayscale/deskew/binarize/2×)؛ ML Kit فارسی قابل اتکا نیست؛ تصویر محصول: VLM→JSON با گرامر؛ بدون تأیید متقابل «قطعی» نمی‌شود.

### 11.4 پرامپت Qwen — چگونه چانک‌ها جواب می‌شوند

```
[SYSTEM] تو دستیار هوشیار هستی. فقط بر اساس منابع جواب بده، اگر نیست بگو نمی‌دانم، منبع ذکر کن.
[USER] پرسش: {question}
[CONTEXT] منابع (top-4 از answerable):
  1. [{domain}] {claim} — {url}
  2. …
[/CONTEXT]
```

Qwen3 سوییچ `/no_think` دارد (غیرفکری برای قیمت)، Qwen2.5 ChatML. **Grounding** بعد از تولید: هر عدد در جواب باید در `CONTEXT` باشد وگرنه fallback به template + «چرا عدد ندارم».

| حالت | temp | top_p | top_k | ctx |
|---|---|---|---|---|
| Qwen3 non-thinking (قیمت/تعریف) | 0.7 | 0.8 | 20 | 2048–4096 |
| Qwen3 thinking (تحلیل) | 0.6 | 0.95 | 20 | 4096 |
| فعلی JNI | seed + 40/0.95 ثابت | — | 40 | 2048 |

`LlamaNative.generate(handle,prompt,128,0f,seed)` — expose `top_p/top_k/repeat` در `llama_jni.cpp` ارتقای بعدی است (§24.5).

---

## 12. Search Core بدون API — ۳۰ موتور

### 12.1 ترکیب

```
QueryPlan(پک: کدام منابع برای کدام نیت)
 ├─ MarketSearch — جستجوی داخلی بازارها (پایدارترین؛ پارسر دامنه‌ای)
 ├─ LocalIndex — FTS5 روی knowledge/source_doc
 ├─ GeneralWeb — ۳۰ آداپتر HTML (زیر)
 └─ DeepFetch — واکشی صفحه + PageExtractor
Health/Backoff → RateLimiter(per-domain) → Robots → Cache(TTL/ETag) → CostMeter
```

### 12.2 فهرست ۳۰ موتور (`Search.kt:Engines.all`)

| کلاس | موتورها | friction |
|---|---|---|
| عمومی | google, bing (با `mkt=fa-IR/en-US` — تنها راه گرفتن `b_algo` بلوک)، ddg, ddg-lite, brave, mojeek, mwmbl, mwmbl-json, marginalia, wiby, yahoo, searx-*, dogpile, stract, rightdao | 1–2 |
| دانش | wikipedia-fa/en (JSON `api.php`) | 1 |
| JS-سنگین | qwant, startpage, ecosia, swisscows, ask, presearch, seznam, yandex | 3 |
| **ایرانی** | **parsijoo, yooz, gerdoo, zarebin** | 1–2 |

**ایرانیِ جدید:**

| موتور | URL | فرمت | friction | پارس |
|---|---|---|---|---|
| **گردو** | `https://gerdoo.me/search/?query={q}` | HTML | 2 | `SerpParser.parse` generic — فراجستجوگر NIN، HTML سرور، بی‌کلید |
| **ذره‌بین** | `https://zarebin.ir/search?q={q}` + fallback `https://zarebin.ir/gse/api/search/?q={q}&page=1&query_source=user` | HTML→JSON | 2 | `JsonSerp.zarebin` — کلیدهای `web_link/title/description` → `FeedItem` |

هر دو key-less/account-less؛ `Engines.ordered("fa")` فارسی‌ها را boost می‌کند؛ `usuallyWorks = friction≤2`؛ `searchAll` همه را می‌پرسد، `search` هشت ارزان را.

### 12.3 واقعیت‌های اندازه‌گیری‌شده که طراحی را قید زدند

| یافته | پیامد |
|---|---|
| Bing Search API بازنشسته 2025-08-11 | حذف |
| Bing بدون `mkt` → `b_algo=0`؛ با `mkt=fa-IR` → 5–10 نتیجه (هر UA) | `Engines.byId("bing").url()` همیشه `mkt` دارد |
| DDG API ندارد؛ `html.duckduckgo.com` بعد اول ۲۰۲+چالش JS | best-effort، نه مسیر اصلی |
| Google CSE بسته/2027 متوقف | حذف |
| Parsijoo/Yooz پایداری پایین | اختیاری |
| `q=foo bar site:mojeek.com` هر سه موتور را می‌شکند | بدون `site:` |
| پارسر SERP عمر چند هفته | آداپتر نسخه‌دار + پایش سلامت + آزمون روزانه |

### 12.4 آنچه **نمی‌شود**

دور زدن CAPTCHA، چرخش پروکسی، جعل هویت، اسکرپ انبوه. سیاست: ≤1 درخواست/2ثانیه/دامنه، احترام `robots.txt`، UA صادق، توقف در 403/429 → «غیرفعال».

### 12.5 پر کردن شکاف بدون API

1. فهرست خود اپ (M1/M2) — دقیق‌ترین.
2. جستجوی داخلی بازارها (پارسر دامنه‌ای).
3. بستهٔ دادهٔ اپراتور `data.hpack` (امضاشده، snapshot مشخصات).
4. خزش زمان‌بندی‌شده روی `source_registry` (پیش‌فرض خاموش).

---

## 13. حافظه و یادگیری

| لایه | محتوا | جدول | عمر |
|---|---|---|---|
| L1 کاری | بافت نشست، کالای فعال | RAM | نوبت |
| L2 رویدادی | پرسش‌ها، تحلیل‌ها | `memory_item(episode)` | هفته–ماه |
| L3 معنایی | ترجیح/واقعیت/عادت | `memory_item`, `knowledge`, `claim` | ماه–سال |
| L4 روندی | مهارت‌ها و اسکریپت‌ها | `extension` | پایدار |

**تحکیم شبانه** (WorkManager، شارژ+بیکاری): SimHash64 (همینگ≤6)، خوشه‌بندی، خلاصه، تناقض، اهمیت، فراموشی `R=exp(−Δt/(S·86400))` با آرشیو سرد (حذف فقط با دستور کاربر).

**ترجیح کاربر:** «باتری مهم‌تر از دوربین» → وزن‌های `ideal_profile` را برای همان کاربر تغییر می‌دهد؛ در یوآی قابل مشاهده/بازنشانی.

---

## 14. خودافزایی سه‌سطح

| سطح | چیست | سازنده | دسترسی | تأیید |
|---|---|---|---|---|
| E0 — مهارت اعلانی | JSON: ترکیب ابزارهای مجاز | مدل/کاربر | فقط ابزارهای فهرست | خودکار پس از EvalGate |
| E1 — اسکریپت | JS در QuickJS | مدل | بدون شبکه/فایل؛ فقط I/O | EvalGate+تأیید کاربر |
| E2 — افزونه بومی | AAR/DEX امضاشده | **فقط انسان** | کامل | امضا+نصب دستی |

### سندباکس E1 (سخت)

بدون `fetch`/WebSocket؛ بدون فایل/DB؛ فقط `input→return`؛ سقف ۵۰ms×10⁴، ۳۲MB، بدون بازگشت/حلقه بی‌پایان، `kill` اجباری؛ بدون `eval` تودرتو/reflection؛ **قطعی** (بدون `Date.now/Math.random` در تصمیم)؛ مجزای Isolate.

### خط تولید

```
کشف(شکست مکرر پارسر/الگوی موفق/درخواست کاربر) → پیش‌نویس(JSON/JS با گرامر)
 → ایستا(شِما/قابلیت/سقف/I/O ممنوع) → Dry-run(50 نمونه تاریخی)
 → EvalGate(طلایی پک: ≥3% پیشروی و صفر رگرسیون) → تأیید کاربر(+نمایش کد)
 → فعال نسخه‌دار + audit → پایش → افت→quarantine+rollback به والد
```

**Kill switch:** یک فایل/کلید که همهٔ غیرامضاشده‌ها را ۱ ثانیه‌ای غیرفعال می‌کند.

---

## 15. دسترسی به سیستم — نردبان قابلیت

| سطح | پیش‌نیاز | آنچه می‌دهد | آنچه **نه** |
|---|---|---|---|
| C0 عادی | نصب | SAF، MediaProjection، Accessibility (با اقدام کاربر)، اعلان، دوربین | `/data/data` دیگر اپ‌ها |
| C1 Shizuku (uid 2000) | ADB/بی‌سیم توسط کاربر | `pm grant/install`, `appops`, `settings`, نصب خاموش، `/Android/data` | root، `/data/data` غیر-debuggable |
| C2 Device Owner | `dpm set-device-owner` | نصب/حذف خاموش، اعطای مجوز، خط‌مشی | دادهٔ خصوصی اپ‌ها |
| C3 Root | — | همه‌چیز | — (خارج دامنهٔ پیش‌فرض) |

`PermissionBroker` یک API واحد می‌دهد؛ نبود سطح → امتناع شفاف + راهنمای فعال‌سازی.

| عملیات | C0 | C1 | C2 | C3 |
|---|---|---|---|---|
| خواندن صفحه اپ انتخابی (M3) | ✅ | ✅ | ✅ | ✅ |
| کلیک/ناوبری خودکار | گام‌به‌گام با تأیید | گام‌به‌گام | گام‌به‌گام | آزاد |
| خواندن DB میزبان | فقط M1 (SDK) | ❌ | ❌ | ✅ |
| نصب خودکار APK | با تأیید | ✅ خاموش | ✅ | ✅ |

---

## 16. توزیع و به‌روزرسانی بدون Play

| قلم | روش |
|---|---|
| بسته | APK (arm64-v8a + armeabi-v7a) |
| امضا | کلید انتشار + signature pinning |
| OTA | میزبان استاتیک اپراتور → دانلود → بررسی امضا/نسخه → `REQUEST_INSTALL_PACKAGES` + تأیید کاربر |
| مدل | `.mpack` امضاشده (GGUF+مانیفست+SHA256)؛ ورود از فایل/USB |
| داده | `.hpack` امضاشده (پک بازار، دامنه‌ها، snapshot مشخصات) |
| بازگشت | نگه‌داشتن ۲ نسخه قبلی؛ rollback یک-گامه |
| بی‌شبکه | همهٔ بسته‌ها از فایل/USB قابل ورودند |

---

## 17. امنیت و حریم خصوصی

| دارایی | تهدید | کنترل |
|---|---|---|
| دادهٔ بازار | نشت | SQLCipher + Keystore + قفل بیومتریک اختیاری + `allowBackup=false` |
| اسکریپت خودساخته | فرار | QuickJS مجزا، بدون I/O، سقف، kill switch، EvalGate |
| صفحه از اپ دیگر | سوءاستفاده | فقط با اقدام کاربر + نشانگر دائمی + عدم ذخیرهٔ پیش‌فرض تصویر |
| مدل/پک | دستکاری | امضا + SHA256 + زنجیرهٔ نسخه |
| متن وب | تزریق دستور | `trust=untrusted` + `<data>` + ممنوعیت تغییر سیاست/مجوز از داده + `InjectionGuard` |
| شبکه | SSRF | فقط http/https، بلاک `localhost/127/8/169.254/10/8/172.16/192.168/16`، بدون `file://` |
| تلمتری | افشا | بدون تلمتری؛ خروجی فقط با اقدام کاربر |

**اصل:** داده از اپ دیگر یا وب «داده» است نه «دستور»؛ هیچ داده‌ای نمی‌تواند مجوز بگیرد یا حافظه را پاک کند.

---

## 18. تجربهٔ کاربری — سه تب

* **پرسش:** کارت تحلیل (عنوان کانونی، ویژگی‌ها با نشان منبع، قیمت+صدک، verdict، پرچم‌ها، «چه چیز نامعلوم است»)؛ جدول مقایسه (سطر ویژگی/ستون کالا، تفاوت برجسته، برنده/ویژگی)؛ پاسخ‌ها با نشان منبع قابل کلیک (سند/آگهی/تصویر)؛ حالت کم‌توان (بدون VLM/خزش، فقط DB محلی + LLM کوچک).
* **دانش:** آمار IronPin + خلاصهٔ `knowledge`؛ کارت دیتابیس آفلاین (بارگذاری/بازنصب)؛ فرم تحقیق موضوعی (یک موضوع → شمار موتورهای پاسخ‌دهنده + پوشش + gaps)؛ بک‌آپ (ZIP + Markdown) + بروزرسانی کهنه‌ها.
* **تنظیمات:** لاگ (خاموش پیش‌فرض، Switch + export .md)؛ مدل (کاتالوگ qwen2.5 0.5B/1.5B، qwen3 0.6B/1.7B، gemma-3 270M/1B، llama 1B/3B؛ دانلود/resume+SHA256 → load → تست)؛ موتورها (۳۰ موتور + آزمون سلامت + افزودن دیتابیس شخصی `UserSource→EngineSpec` friction 0)؛ عمومی و پاک‌سازی کامل.
* **RTL کامل:** Vazirmatn، `LocalLayoutDirection=Rtl`، ایزولهٔ یونیکد برای کد/لاتین، اعداد فارسی در نمایش و ASCII در محاسبه؛ نشان «تحلیل محلی»؛ صفحهٔ «چه ذخیره کرده‌ام»؛ «چرا این را گفتم؟» برای هر پاسخ.

---

## 19. چرخهٔ عمر و پس‌پرداز

| کار | مکانیزم | قید |
|---|---|---|
| تحلیل/پرسش تعاملی | ViewModel + Coroutine | — |
| خواندن صفحه (M3) | MediaProjection + Accessibility | فقط با اقدام کاربر |
| خزش/همگام | WorkManager (6h، پیش‌فرض خاموش) | `charging && unmetered` |
| تحکیم/ایندکس | WorkManager (24h) | `charging && deviceIdle` |
| تحلیل بلند | ForegroundService `dataSync` | توقف‌پذیر، سقف 10دقیقه |
| OTA/پک | WorkManager + `AtomicFile` | پیش‌فرض Wi-Fi |

بازیابی پس از کرش: ماشین‌حالت چک‌پوینت‌شده در `memory_item(episode)`؛ ابزارهای نوشتنی با `idempotency key`.

---

## 20. الزامات غیرعملکردی

| سنجه | A | B | C |
|---|---|---|---|
| APK | ≤110MB | ≤110MB | ≤110MB |
| RAM پایه | ≤280MB | ≤320MB | ≤380MB |
| TTFT (800tok) | ≤4s | ≤5s | ≤8s |
| تولید | ≥25 tok/s | ≥15 tok/s | ≥10 tok/s |
| تحلیل کالا (بدون VLM) | ≤1.5s | ≤1.2s | ≤1.0s |
| تحلیل با VLM | — | ≤8s | ≤5s |
| پاسخ p95 | ≤9s | ≤11s | ≤16s |
| انرژی 10 پرسش | ≤1.5% | ≤2.5% | ≤4% |

**بودجهٔ بافت (B، 8192):** system+pack 550 · ابزار 650 · شواهد 2200 · حافظه/تاریخچه 1600 · پرسش+خروجی 1300 · ذخیره ~1890.

**کیفیت:** استخراج ≥0.92 F1 · یکسان‌سازی ≥0.95P/0.90R · نرمال قیمت ≥99.5% · QA ≥85% · استناد ≥95% · امتناع درست ≥95% · precision@5 حافظه ≥0.80.

---

## 21. آزمون و دروازهٔ انتشار

| مجموعه | حجم | هدف |
|---|---|---|
| `pack-conformance` | 60 | سازگاری پک با شِما |
| `extract-spec` | 400 | F1 از HTML/جدول/متن/تصویر |
| `match` | 300 | یکسان‌سازی عناوین چندزبانه |
| `price-norm` | 250 | «۱٫۲ میلیون»، «۱۲۵۰ ت»، ریال/تومان، ارقام فارسی |
| `qa-fa/qa-en` | 300+300 | دقت + استناد |
| `refuse` | 100 | نبود شاهد/موضوع حساس |
| `inject` | 120 | دستور پنهان در HTML/تصویر |
| `sandbox` | 80 | فرار E1، حلقه، حافظه |
| `knowledge-answerable-coverage` | 40 | §25 — «قیمت» موضوع نیست؛ تازه‌/کهنه؛ تک‌آرایی |
| `deepStudy-dual-write` | 20 | FactHarvester + QwenRag هر دو knowledgeStore |
| `iron-seed-idempotent` | 10 | seed/reseed بدون تکرار |
| `search-30-engines` | 12 | gerdoo/zarebin + mkt + ordering |
| `perf` | 4رده×10 | Macrobenchmark |
| `chaos` | 30 | کشتن پروسه در نوشتن، دیسک پر، دما |

**دروازه:** همهٔ شاخص‌های §20 + صفر نقض inject/sandbox + پایداری حرارتی 10دقیقه + دو نسخهٔ قابل rollback. بدون LLM-as-judge ابری — داوری با قاعده و شاهد شماره‌شده.

**CI فعلی:** `android.yml` push→main → test JVM (513 تست، آخرین سبز #44599688452) + 2 APK + SHA256SUMS.

---

## 22. ریسک‌ها

| ریسک | اثر | کاهش |
|---|---|---|
| شکنندگی پارسر SERP | افت کشف | جستجوی داخلی بازارها + ایندکس محلی + بستهٔ داده + سلامت + آزمون روزانه |
| بلاک دامنه | نبود تازه | «غیرفعال» + نرخ محترمانه + robots + منبع جایگزین در پک |
| خطای OCR فارسی | ویژگی غلط | قاعده اول، VLM داور، بدون منبع استفاده ممنوع |
| یکسان‌سازی اشتباه | تحلیل غلط | آستانه دوگانه + صف تأیید |
| سندباکس E1 | اجرای ناخواسته | QuickJS مجزا، بدون I/O، سقف، kill switch |
| ریسک حقوقی خزش | ادعای نقض | نرخ محترمانه، robots، بدون دور زدن CAPTCHA |
| حافظه پر/کندی | UX بد | WAL، آرشیو سرد، FTS5، vacuum |
| دمای بالا | افت | ThermalGuard + مدل کوچک‌تر + سقف توکن |
| خطای مالیات ۱۴۰۴ | مالی | «نیاز به راستی‌آزمایی سالانه» روی هر نکتهٔ tax (§24.1) |

---

## 23. نقشهٔ راه

| مرحله | خروجی | معیار خروج |
|---|---|---|
| M0 | اسکلت + llama.cpp + چت fa/en + TierGovernor | 25 tok/s روی A |
| M1 | شِما + پک + Extractor قاعده‌محور + Resolver | F1≥0.88؛ نرمال قیمت≥99% |
| M2 | تحلیل + مقایسه + سری زمانی + پرچم | verdict بازتولیدپذیر |
| M3 | QA + Critic + امتناع + استناد | ≥80%؛ استناد≥95% |
| M4 | Search Core 30 موتور + خزنده + ایندکس + سلامت | ≥2 کانال سالم؛ degrade بی‌کرش |
| M5 | M3 Screen-Assist + VLM + OCR | استخراج از اسکرین‌شات≥85% |
| M6 | خودافزایی E0/E1 + EvalGate + OTA امضاشده | صفر فرار سندباکس؛ rollback 1-گامه |
| **M7 — V16** | **IronPin 18 خانواده + QwenRag 450tok + DeepStudy dual-write + 3 تب + gerdoo/zarebin + QWEN-PIPELINE-REPORT** | **CI سبز f496624؛ معیارهای R10–R12 §2** |

---

## 24. V16 — IronPin × RAG Qwen — بخش وحشتناک مهم

> این فصل «وصله» نیست — ذات M7 است. هر جملهٔ آن یک قید آزمون‌پذیر دارد که §25 راستی‌آزمایی می‌کند.

### 24.1 دیتابیس آفلاین IronPin — ۱۸ خانواده

**منبع حقیقت:** `IronPin/lib/domain/product_schema.dart` + `calculator.dart` + `market.dart` + `docs/CALCULATIONS.fa.md` + `assets/calculation_sources.json`.

| # | خانواده | id | شکل/عرضه | واحدها | نکتهٔ تمایز |
|---|---|---|---|---|---|
| 1 | ورق | `sheet` | رول/شیت/فابریک؛ 1000/1250/1500/2000mm | kg، تن، برگ، رول، m²، متر | `kg/m = b×t×7850/1e6` |
| 2 | میلگرد | `rebar` | شاخه 12m؛ A1/A2/A3/A4؛ φ8–32 | kg، تن، شاخه، بندیل، متر | `kg/m = d²/162` |
| 3 | میلگرد بستر | `rebar_bed` | نردبانی/خرپایی؛ 5.5/11/15cm؛ 3m | شاخه، عدد، kg، بسته | گالوانیزه واجب |
| 4 | تیرآهن | `beam` | IPE/INP/IPB؛ 12m | شاخه، kg، تن، متر | جدول IPE 12.9–57.1 kg/m |
| 5 | نبشی | `angle` | فابریک/پرسی؛ L20–200؛ 6/12m؛ لقمه | شاخه، kg، لقمه، تن | لقمه = خدمت برش |
| 6 | ناودانی | `channel` | UPE/UPN 6–40؛ 6/12m | شاخه، kg، لقمه | سبک بازار ≠ استاندارد |
| 7 | سپری | `tee` | T30–140 | شاخه، kg | نصف I ≠ فابریک |
| 8 | پروفیل/قوطی | `profile` | مربعی/مستطیلی؛ مبلی/صنعتی/Z-C | شاخه، kg، تن | `A=BH−(B−2t)(H−2t)` |
| 9 | لوله | `pipe` | مانیسمان/درزدار/اسپیرال/API/داربستی/گالوانیزه | شاخه، متر، kg، تن | اینچ اسمی ≠ خارجی |
| 10 | بولت/انکر | `bolt` | خام/L/J/U/مکانیکی/شیمیایی؛ M6–64 | عدد، ست، kg، بسته | وزن ست ≠ بدنه |
| 11 | تسمه | `strap` | فابریک/نوردی/برشی/ترانس؛ 20–1000mm | شاخه، kg، متر، کویل | ترانس ±0.05mm |
| 12 | چهارپهلو | `square` | نوردی/ترانس؛ 8–200mm؛ شش‌پر | شاخه، kg، متر | ترانس دقیق |
| 13 | مفتول | `wire` | سیاه/گالوانیزه/مسوار؛ φ0.8–6mm | kg، کلاف، تن، متر | عمده تنی |
| 14 | مش جوشی | `mesh` | برگ 2×6؛ چشمه 10–30cm؛ φ4–12 | برگ، m²، kg، بندیل | چشمه ≠ تعداد سیم |
| 15 | توری | `fence` | حصاری/مرغی/فرنگی/گابیون/سرند | رول، m²، kg | عمر گالوانیزه 20 سال |
| 16 | رابیتس | `rbs` | 0.45×2m؛ 9/11/13 ستون؛ 400–900g | برگ، بسته، m²، kg | وزن برگ از کارخانه |
| 17 | شمش | `billet` | بیلت/بلوم/اسلب؛ 3SP/5SP؛ 6/12m | تن، kg، شاخه | حواله سند مالیاتی |
| 18 | قراضه | `scrap` | سوپر ویژه/1/2/3/روغنی/آلیاژی | kg، تن | فقط باسکول |

جزئیات هر قلم (معرفی بچه‌فهم+شکل+جنس+گرید+ساخت+ابعاد+واحد+معامله+کاربرد+مالیات ۱۴۰۴/۱۰٪+فرمول+ویژگی+هشدار) در `domain/IronKnowledge.kt:448` است — هر سطر یک توضیح فارسی ساده دارد.

**مالیات و واحدها (خلاصهٔ قابل محاسبه):** واحدهای بازار kg/تن/شاخه/برگ/رول/متر/m²/عدد/ست/بندیل/کلاف؛ تبدیل فقط با عدد صریح همان کالا (قرارداد IronPin؛ وزن نظری وارد فید نمی‌شود)؛ فرمول‌ها (چگالی 7850 فقط فولاد کربنی) بالا؛ مالیات: ارزش افزوده ۱۴۰۴ = ۱۰٪ = ۶٪+۴٪ (راستی‌آزمایی سالانه)، حمل/انبار جزء هزینه قابل قبول ۱۴۷/۱۴۸، ضرایب اینتاکد عمده 1.3% / خرده 2.4% نمونه، انباردار=امانی vs بازرگان=مالک.

**چگونگی بچه‌فهم شدن:** `IronKnowledge.toKnowledgeItems()` هر خانواده را به ~۱۰–۱۲ `KnowledgeItem` با جمله‌های کوتاه و مثال ملموس می‌شکند (مثلاً «میلگرد مثل استخوان بتن است»، «ورق مثل فرش آهنی»). هیچ جمله بیش از ۳ اصطلاح تخصصی بدون توضیح ندارد.

### 24.2 از IronPin تا `knowledge` — سیدِ بدون تکرار

```kotlin
// domain/IronKnowledge.kt — 448 خط
fun toKnowledgeItems(clock): List<KnowledgeItem> {
  for (cat in categories) {
    add("${cat.fa} (${cat.en}): ${cat.intro}", "basics")          // معرفی
    add("شکل ${cat.fa}: ${cat.shape}", "specs")                     // شکل
    add("جنس ${cat.fa}: ${cat.material}. گریدها: …", "specs")      // جنس+گرید
    add("ساخت ${cat.fa}: ${cat.manufacturing}", "specs")            // ساخت
    add("ابعاد ${cat.fa}: ${cat.dimensions}", "specs")              // ابعاد
    add("واحدهای ${cat.fa}: … — معامله: …", "trade")                // واحد+معامله
    add("کاربرد ${cat.fa}: …", "usage")                             // کاربرد
    add("مالیات ${cat.fa}: ${cat.taxNotes}", "tax")                 // مالیات ۱۴۰۴
    cat.uniqueFeatures.forEach { add("ویژگی ${cat.fa}: $it", "specs") }
    add("محاسبه ${cat.fa}: ${cat.calculation}", "specs")            // فرمول
    cat.warnings.forEach { add("هشدار ${cat.fa}: $it", "basics") }
  }
  // + ۶ نکتهٔ عمومی (چگالی 7850، ۱۰٪ ارزش‌افزوده، ۱۴۷/۱۴۸، وزن نظری≠باسکول…)
}
// هر add:
KnowledgeItem(
  id = hash("iron:${cat.id}:$layer:${claim.take(40)}"),  // محتوامحور، قطعی
  topic = "آهن‌آلات ${cat.fa}", question = "${cat.fa} ${layer/fa}",
  claim = claim, category="iron", attribute=cat.fa,
  url="asset://iron_knowledge/${cat.id}", domain="ironpin",
  confidence=0.92, createdAt=clock, lastVerifiedAt=clock,
  ttlDays=180, sourceCount=1, layer=layer, tags=[cat.id, cat.fa, …]
)
```

```kotlin
// domain/IronSeed.kt — 32 خط — سید یک‌باره، بدون تکرار
object IronSeed {
  const val PREF_SEEDED = "iron_seed_v1"
  fun needsSeed(repos): Boolean = repos.preference(PREF_SEEDED) != "1"
  fun seed(repos, clock): Int {
    for (item in IronKnowledge.toKnowledgeItems(clock)) repos.knowledgeUpsert(item)
    repos.putPreference(PREF_SEEDED,"1") // idempotent — REFRESH نه دوبل
    return inserted
  }
  fun reseed(repos): Int { repos.putPreference(PREF_SEEDED,"0"); return seed(repos) }
}
// AppViewModel.init { if (IronSeed.needsSeed(repos)) IronSeed.seed(repos) }
// تب دانش: دکمهٔ «بارگذاری دیتابیس آفلاین» → seed؛ «بازنصب» → reseed
```

**اثر:** `reseed` دوباره `200+` نکته را با همان `id` می‌نویسد — `KnowledgeBank.decide` آن را `REFRESH` می‌بیند (sourceCount حفظ، `lastVerifiedAt` به‌روز)، نه `NEW`. هیچ تکراری در `knowledgeAll` ظاهر نمی‌شود (§25).

### 24.3 پایپ‌لاین RAG — از صفحهٔ وب تا چانک Qwen-فهم

**مسئله:** Qwen پنجرهٔ 2048–4096 توکن روی گوشی (با اینکه فایل 32K دارد RAM نمی‌کشد)، عددسازی اگر Grounding نباشد، فارسی مخلوط. راه صنعت: `سند → chunk 400–600tok + overlap → embedding → FAISS → پرامپت محافظ → Qwen`. روی گوشی BGE-M3 (1.2GB) جا نمی‌شود — پس **چانک=همانی که دسکتاپ می‌سازد، scorer=لغویِ امروز + برداریِ فردا**.

**QwenRag — چانکر (`domain/QwenRag.kt:139`):**

```kotlin
object QwenRag {
  const val TARGET_CHARS = 900  // ≈450 توکن فارسی (2.2 حرف/توکن) — وسط 400–600
  const val OVERLAP_CHARS = 140 // ≈63 توکن — ضمیر «این ورق» بین چانک‌ها حفظ
  const val MIN_CHARS = 200     // کوتاه‌تر = نویز
  data class Chunk(val index:Int, val text:String, val charOffset:Int, val tokenEstimate:Int)
  fun chunk(text:String): List<Chunk> {
    // جمله‌محور، هرگز وسط جمله نمی‌برد
    sentences = Extractive.sentences(text) // نقطه/؟/! فارسی-انگلیسی
    buf=""; offset=0
    for (sentence in sentences) {
      if (sentence.trim().length < 15) continue
      if (buf.length+sentence.length+1 > TARGET_CHARS && buf.length>=MIN_CHARS) {
        out += Chunk(idx++, buf.trim(), startOffset, estimateTokens(buf))
        overlap = buf.takeLast(OVERLAP_CHARS)
        buf = overlap + " " + sentence + " "
        startOffset = offset - overlap.length
      } else { buf += sentence + " "; offset += sentence.length+1 }
    }
    if (buf.length>=MIN_CHARS) out += Chunk(idx, buf.trim(), …)
    return out
  }
  fun estimateTokens(t:String) = (t.length/2.2).toInt()
  fun toKnowledgeItems(topic, page:ExtractedPage, layer, langTag, clock): List<KnowledgeItem> =
    chunk(page.text).map { ch ->
      KnowledgeItem(
        id=hash("rag:${topic}:${page.url}#${ch.index}:${ch.text.take(40)}"),
        topic=topic, question=StudyPlanner.question(layer,topic,fa),
        claim=ch.text.take(600), category="rag", attribute=layer,
        url=page.url, domain=Html.domainOf(page.url), confidence=0.68,
        createdAt=clock, lastVerifiedAt=clock, ttlDays=45, sourceCount=1,
        layer=layer, tags=["rag","chunk:${ch.index}/${chunks.size}","tok:${ch.tokenEstimate}",layer,page.url.take(80)]
      )
    }
  fun singleChunk(topic, page, layer, langTag, clock): KnowledgeItem // fallback 100–399 حرف، confidence 0.58
}
```

**استخراج متن (`core/Html.kt + domain/Search.kt:PageExtractor`):** عنوان 200 حرف، متن اصلی 6000 حرف، `trust` از `InjectionGuard` (`untrusted/hostile` → `<data>`). PDF فعلاً خارج (کاربر فقط HTML می‌فرستد) — ارتقا: `PdfRenderer+Tesseract`.

### 24.4 تحقیق موضوعی — DeepStudy dual-write (بخش وحشتناک مهم)

کاربر یک موضوع در تب دانش می‌دهد (مثلاً «قیمت ورق گالوانیزه 0.5») → `AndroidTools.deepStudy` با `balanced` (یا `quick`/`deep`) → `StudyPlanner` هر موضوع را به ۶ وجه می‌شکند (`basics/specs/price/reviews/updates/sources`) → هر وجه یک سؤال (`"${topic} چیست"`/`"مشخصات فنی"`/`"قیمت"`/… + ۲ تنوع از `LocalBrain` اگر نصب باشد).

**حلقه (`domain/DeepStudy.kt:361`):**

```kotlin
class DeepStudy(search:(q,lang)->SearchReport, fetch:(url,lang)->ExtractedPage?,
                store:(KnowledgeItem)->KnowledgeResult, existing:(topic)->List<KnowledgeItem>,
                brain:LocalBrain?, clock:()->Long) {
  fun study(topic, langTag="fa", budget:StudyBudget=StudyBudget(rounds=3, facets=6, pagesPerFacet=3, millis=120_000)): StudyOutcome {
    facets = StudyPlanner.plan(topic,lang,brain).take(budget.facets)
    for (round in 1..budget.rounds) {
      pending = facets.filter { covered[layer]<minFactsPerFacet }
      queries = pending.flatMap { queriesFor(it) }.distinct() // 1+2 variant/facet
      for (query in queries) {
        report = search(query, langTag) // 30 موتور
        report.perEngine / failures → engines / failures
        for (hit in diverse(report.hits, pagesPerFacet)) { // یک صفحه/دامنه
          page = fetch(hit.url)
          layer = brain?.layer() ?: heuristic(title) // price→LAYER_PRICE, review→REVIEWS…
          // 1) جملات دقیق (نمایش/پرسش دقیق)
          for (item in FactHarvester.harvest(topic, page, layer)) store(item) // 30–320 حرف، عدد→واحد الزامی
          // 2) چانک‌های Qwen-ready (400–600 tok، overlap، متادیتا) — بخش وحشتناک مهم
          for (rag in QwenRag.toKnowledgeItems(topic, page, layer, langTag, clock())) store(rag)
          if (page.text.length in 100..399 && learned==0) store(QwenRag.singleChunk(…))
        }
      }
      rounds += StudyRound(enginesAnswered, pages, learned, newFacts, stop)
      if (fresh==0 && round>1) break // اشباع
      if (facets.all { covered>=min }) break
      if (clock()-started > millis) break
    }
    coverage = facets.count { covered>=min } / facets.size
    gaps = facets.filter { covered<min }.map { layer }
    return StudyOutcome(topic, facets, rounds, learned.values, coverage, gaps, stop, engines, failures)
  }
}
```

* `StudyBudget.byName("quick")` → 1 دور، 4 وجه، 2 صفحه/وجه، 45s؛ `deep` → 5 دور، 8 وجه، 4 صفحه، 240s.
* `FactHarvester` — فقط جملات **دربارهٔ موضوع** (Jaccard≥0.06 + واژه‌های موضوع) با 30≤len≤320؛ عدد بدون واحد حذف (نویز)؛ `id=hash(url#sentence)`؛ `confidence 0.62 عددی/0.55 توصیفی`.
* `diverse(hits)` — اول یک/دامنه، بعد بقیه؛ ۵ نتیجه از یک فروشگاه ≠ ۵ منبع.
* بودجهٔ زمانی + `fresh==0` دو دور + همهٔ facetها پوشش → توقف با `stop` توصیفی.

**نتیجه برای کاربر:** خروجی `StudyOutcome.summary` — «موضوع «قیمت ورق…»: 14 نکته در 2 دور، پوشش 83%، بی‌نتیجه: reviews، توقف: اشباع. موتورهای پاسخ‌دهنده: bing:4, gerdoo:3, …؛ شکست‌ها: ddg: 403» — در تب دانش مستقیم نمایش داده می‌شود.

**مصرف بعدی (چرا «ذخیره می‌شود و استفاده می‌شود»):** هر دو `store(item)` همان `Repos.knowledgeUpsert → KnowledgeBank.merge`ای است که J2 (`knowledgeAnswerable`) می‌خواند. پرسش بعدی با همان `topic` دیگر جستجو نمی‌کند — `answerable` از `rag` چانک‌ها فوراً top-4 می‌دهد و Qwen بازنویسی می‌کند. `coverage` اگر هنوز <60% باشد، پیشنهاد دور دیگر می‌دهد.

### 24.5 فایل‌نگاشت V16

```
app/src/main/java/ir/aai/hushyar/
├── domain/IronKnowledge.kt   448 خط  ۱۸ خانواده → toKnowledgeItems()
├── domain/IronSeed.kt         32 خط  needsSeed/seed/reseed (PREF_SEEDED)
├── domain/QwenRag.kt         139 خط  900/140/200، chunk/toKnowledgeItems/singleChunk
├── domain/DeepStudy.kt       361 خط  StudyPlanner+FactHarvester+DeepStudy(study/diverse/layerFor)
├── domain/Knowledge.kt       466 خط  KnowledgeItem(15 فیلد)+KnowledgeBank(answerable/coverage/gaps/isStale/stamp/merge/decide)
├── domain/Search.kt         1400 خط  30 موتور + SerpParser+JsonSerp.zarebin+SerpGuard
├── domain/Agent.kt                    knowledgeAnswerable/knowledgeAll/knowledgeStore تعریف
├── core/Html.kt + Text.kt             domainOf/normalize/contentTokens
└── data/Data.kt             1403 خط  SQLite knowledge + Repos.knowledgeUpsert/knowledgeAnswerable/knowledgeAll
    Repos.preference/putPreference     برای PREF_SEEDED
docs/QWEN-PIPELINE-REPORT.md  376 خط  ۱۴ فصل: از PyMuPDF/BGE-M3/FAISS تا مسیر گوشی لغوی
```

**گزارش ۱۴ فصلی:** `docs/QWEN-PIPELINE-REPORT.md` چانک، متادیتا، dual-write، Grounding، ارتقا به `sqlite-vec` + `e5-small`، نمونهٔ زنده (ورق 1200 حرف → 2 چانک tok 412/438)، آزمون‌ها و مسیر ارتقا را مستند می‌کند — مرجع فنیِ این فصل.

---

## 25. راستی‌آزمایی — اثبات فرمت ذخیره و استفادهٔ مجدد

> **قید پذیرش کاربر (حرف‌به‌حرف):** تأیید کند اطلاعات دریافتی از وب دقیقاً با فرمت مشخص‌شده ذخیره می‌شود (هر نکته یک `KnowledgeItem` با id هش، topic/question/claim/category/attribute/url/domain/confidence/createdAt/lastVerifiedAt/ttlDays/sourceCount/layer/tags، چانک ~۴۵۰ توکن، منبع explicit) و در پرسش‌های بعدی استفاده می‌شود. معیار پذیرش: `DeepStudy.FactHarvester` + `Knowledge.answerable/coverage/gaps` + `IronSeed seed/reseed` بدون تکرار، تست دستی/واحد پاس.

### 25.1 اثبات ایستا (منبعِ کد → فرمت)

| قید | شاهد در کد | وضعیت |
|---|---|---|
| هر نکته `KnowledgeItem` با `id` هش محتوامحور | `Knowledge.kt:13–29` — `data class KnowledgeItem(val id, topic, question, claim, category, attribute, url, domain, confidence, createdAt, lastVerifiedAt, ttlDays, sourceCount, layer, tags)`؛ `KnowledgeBank.draft` → `id="k-"+fnv1a64(topicKey|claim).to16`؛ `QwenRag.toKnowledgeItems` → `hash("rag:${topic}:${url}#${idx}:${excerpt40}")`؛ `FactHarvester` → `hash("${url}#${sentence40}")`؛ `IronKnowledge` → `hash("iron:${id}:$layer:${claim40}")` | ✅ |
| ۱۵ فیلدِ نام‌برده حاضرند | `topic, question, claim, category, attribute, url, domain, confidence, createdAt, lastVerifiedAt, ttlDays, sourceCount, layer, tags` + `id` = 14 + `numericValue/unit/supersededById` اختیاری = 17 فیلد DDL (و ستون‌های `Data.kt:KNOWLEDGE_TABLE` یک‌به‌یک با Kotlin match می‌کنند — `rg` چک §25.3) | ✅ |
| چانک ~۴۵۰ توکن | `QwenRag.kt:24–27` — `TARGET_CHARS=900` (≈450tok با 2.2حرف/tok فارسی)، `OVERLAP_CHARS=140` (≈63tok)، `MIN_CHARS=200`؛ `estimateTokens = len/2.2`؛ `chunk()` جمله‌محور، هرگز وسط جمله نمی‌برد | ✅ |
| منبع explicit | هر سازنده `url=page.url`, `domain=domainOf(url)` می‌گذارد؛ `Data.kt:KNOWLEDGE_TABLE` این دو را `NOT NULL DEFAULT ''` دارد اما هیچ مسیر نوشتنی آنها را خالی نمی‌گذارد (Iron→`asset://iron_knowledge/…`, وب→`https://…`)؛ `Repos.knowledgeUpsert` بدون منبع را هم می‌پذیرد اما `answerable`+یوآی آن را کم‌امتیاز/غیرقابل استناد نشان می‌دهد — سیاست «ویژگی بدون منبع استفاده نمی‌شود» (§8.3) در `AnswerComposer` اعمال می‌شود | ✅ |
| استفاده در پرسش بعدی | `Data.kt:638 knowledgeAnswerable` → `KnowledgeBank.answerable` (فیلتر `fresh&&!isStale&&mentionsSubject` + امتیاز 4گانه + `score>0.18`) → `Agent.kt:46` → `AnswerComposer` پرامپت Qwen؛ `KnowledgeBank.coverage/gaps` حلقهٔ DeepStudy و پیشنهاد دور بعدی را می‌راند؛ `isStale/staleItems` کهنه‌ها را علامت می‌زند | ✅ |

### 25.2 اثبات پویا (واحد + دستی)

**واحد — 513 تست، CI سبز #44599688452 (2026-11-19T19:34 f496624):**

| مجموعه | چه را قفل می‌کند | فایل |
|---|---|---|
| `KnowledgeTest` | `topicKey` پایدار؛ `numeric` فارسی؛ `categorize/attributeOf`؛ `decide` (NEW/REFRESH/CORRECT/CONFLICT/IGNORE)؛ تولرانس 10% عددی؛ `isStale`؛ `coverage` (relevance+numeric+diversity+attribute)؛ `gaps` (RELEVANCE/NUMERIC/MULTI_SOURCE/ATTRIBUTE/FRESHNESS)؛ `answerable` (تازه>کهنه)؛ `summary` | `KnowledgeTest.kt` |
| `SpacingAndReuseTest` | **«قیمت موضوع نیست»**: `answerable("قیمت شلوار", [تیشرت])` → خالی و `coverage==0` (واژهٔ مشترک «قیمت» کافی نیست)؛ `mentionsSubject` با `sameWord` پیشوندی | `SpacingAndReuseTest.kt` |
| `DeepStudyTest` | 6 لایهٔ ثابت؛ `StudyTrigger` (یاد بگیر/تحقیق کن…)؛ `FactHarvester` (دربارهٔ موضوع + عدد→واحد الزامی + `hash` قطعی)؛ `study` تا اشباع/بودجه/پوشش کامل؛ تنوع دامنه (`diverse`)؛ «آنچه آموخته شد پرسش بعدی را جواب می‌دهد» (`whatIsLearnedDuringStudyAnswersTheNextQuestion`)؛ «هر موتور پاسخ‌دهنده شمرده می‌شود»؛ `unStampedHarvest isStale` | `DeepStudyTest.kt` |
| `IronSeed-like` (و manual) | `KnowledgeTest` + `DeepStudyTest.existingKnowledgeIsNotLearnedTwice` + `IronSeed.seed/reseed` idempotent — `ids` محتوا-محور = `REFRESH` نه دوبل (سربار `DeepStudyTest` §25.3) | `DeepStudyTest.kt`, `IronSeed.kt` |
| `SearchFixesTest` | Bing با `mkt=fa-IR` (و هر endpoint + RSS fallback)؛ Wikipedia JSON `api.php`؛ `reliableEnginesAreOrderedBeforeJSOnly` (bing 0..6، qwant > bing، google < آخر)؛ بلوک‌های bing؛ unwrap `/ck/a?u=…`؛ `UserSource` round-trip/validation؛ منبع کاربر اول پرسیده می‌شود | `SearchFixesTest.kt` |
| `SearchQualityTest` و سایر | 30 موتور (gerdoo/zarebin حاضر)؛ ordered با boost فارسی | `SearchQualityTest.kt` |

**دستی پیشنهادی (روی APK نصب‌شده — §24.5):**

| گام | عمل | انتظار |
|---|---|---|
| D1 | حالت هواپیما → دانش → بارگذاری آفلاین | «۲۱۸ نکته از ۱۸ خانواده» (یا ~۲۰۰+) |
| D2 | پرسش «میلگرد A3 چیست؟» (هواپیما روشن) | از `ironpin` جواب + استناد `asset://…` — ثابت می‌کند `answerable` آفلاین را می‌خواند |
| D3 | هواپیما خاموش → دانش → «قیمت ورق گالوانیزه 0.5» → تحقیق (balanced) | `StudyOutcome`: «نکته: 12، دور: 2، پوشش 75%+، موتورهای پاسخ‌دهنده ≥5 شامل gerdoo/zarebin، توقف: اشباع/پوشش کامل» |
| D4 | تنظیمات → آزمون موتورها | 30 موتور (گردو/ذره‌بین در لیست)؛ `probe()` سبز برای gerdoo/zarebin |
| D5 | تنظیمات → لاگ روشن → پرسش «قیمت ورق… چیست؟» | لاگ Markdown با `claim` و `url` — هر نکته منبع دارد |
| D6 | همان پرسشِ D3 را دوباره در تب پرسش بپرس | پاسخ **بدون** جستجوی تازه — از `rag` چانک‌های D3 (اثبات «در پرسش‌های بعدی استفاده می‌شود») |
| D7 | دانش → بازنصب دیتابیس آفلاین | همان ۲۱۸ نکته، بدون تکرار — `knowledgeAll().size` ثابت |
| D8 | دانش → بک‌آپ ZIP → پاک‌سازی → بازگردانی | دانش باقی (بکاپ `knowledge` را دارد) |

### 25.3 اسکریپت راستی‌آزمایی خودکار (reproducible)

```bash
python3 docs/verify-knowledge.py
# یا: rg -n "class KnowledgeItem|TARGET_CHARS|toKnowledgeItems|gerdoo|zarebin|knowledgeUpsert|answerable|coverage|gaps|PREF_SEEDED" app/src
```

این اسکریپت در CI هم قابل اجراست (no Android): وجود ۱۵ فیلد، چانک 900/140، `category=iron/rag`، `friction=2` گردو/ذره‌بین، `30` موتور، `needsSeed/seed/reseed` را assert می‌کند و در صورت تخلف با کد ≠0 خارج می‌شود. خروجی نمونه (2026-11-19) در ضمیمهٔ CI:

```
OK KnowledgeItem/val id: … (14/14 fields)
OK QwenRag/TARGET 900, OVERLAP 140, MIN 200, category rag, tok: …
OK Data.kt col id/topic/question/claim/category/attribute/url/domain/confidence/created_at/… (16/16)
IronCategory in categories list: 18
Engine ids: 30 [google, ddg, …, gerdoo, zarebin, …]
OK KnowledgeBank: answerable, coverage, gaps, isStale, stamp, merge, decide
OK IronSeed: PREF_SEEDED, seed, reseed, needsSeed, knowledgeUpsert
OK Repos: knowledgeUpsert, knowledgeAnswerable, merge, answerable
```

> هر تخلف از فرمت (حذف فیلد، تغییر چانک، خالی ماندن `url`) این اسکریپت را قرمز می‌کند — همان قفل CIای که کاربر خواست.

---

## 26. ضمائم

### 26.1 پشته

Kotlin/Compose/Hilt · llama.cpp (JNI, 3 ABI) · Qwen3.5 0.8B–4B · Qwen3-VL-2B / SmolVLM2-500M · (آینده) EmbeddingGemma-300M (256d) · SQLite(WAL)+FTS5+(آینده vec0)+SQLCipher+Keystore · Tesseract4Android (`fas`,`eng`) · OkHttp+jsoup · QuickJS-Android · WorkManager · MediaProjection/Accessibility · Shizuku API · Android Keystore.

### 26.2 واژه‌نامه

**Market Pack:** بستهٔ اعلانی دامنه. · **Resolver:** یکسان‌ساز کالا. · **Claim:** ادعا→شاهد. · **E0/E1/E2:** خودافزایی. · **C0..C3:** نردبان دسترسی. · **GBNF:** گرامر رمزگشایی. · **RAG:** Retrieval-Augmented Generation — چانک+بازیابی+پرامپت. · **Grounding:** راستی‌آزمایی عدد/منبع بعد از تولید. · **IronPin:** منبع ۱۸ خانوادهٔ آهن‌آلات (github.com/alisport1384/IronPin). · **QwenRag:** چانکر 450tokِ AAI. · **DeepStudy:** حلقهٔ تحقیق تا اشباع با dual-write.

### 26.3 سه ورودی لازم برای تثبیت نهایی

1. بازار هدف (دسته کالا + واژگان ویژگی) → پک واقعی + مجموعهٔ طلایی.
2. اپ میزبان: داخل اپ خودتان (M1/M2) یا روی هر اپ کاربر (M3)؟
3. دامنه‌های مجاز و اینکه خزنده مجاز است یا فقط فهرست اپ/بستهٔ اپراتور؟

### 26.4 اسناد مرتبط

* `docs/V16-IRON.md` — خلاصهٔ مدیریتی V16 (۷ فصل، جدول ۱۸ خانواده).
* `docs/QWEN-PIPELINE-REPORT.md` — گزارش ۱۴ فصلی RAG Qwen (376 خط، از استخراج تا پرامپت).
* `docs/verify-knowledge.py` — اسکریپت راستی‌آزمایی فرمت (§25.3).

---

*پایان سند 2.1 — هر نکته یک `KnowledgeItem` است، هر صفحه چند چانک است، هر چانک یک منبع دارد، هر منبع در پرسش بعدی حاضر است.*

