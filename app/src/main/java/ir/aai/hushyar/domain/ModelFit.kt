package ir.aai.hushyar.domain

import ir.aai.hushyar.core.GgufHeader
import ir.aai.hushyar.core.Text

/**
 * v0.11.2 — «مدل‌های دیگر خطای بارگذاری دارند.»
 *
 * گزارش کاربر: در گوشی، مدل ۸۰۶ مگابایتی بارگذاری شد ولی سه مدل ۱٫۱ و ۱٫۱ و ۲ گیگابایتی با
 * پیام «llama_model_load_from_file failed» رد شدند. آن پیام از سمت موتور بومی است و هر علتی را
 * در یک جمله جمع می‌کند، پس تصمیم گرفتیم **اندازه بگیریم و بگوییم**، نه حدس بزنیم:
 *
 *  * سربرگ GGUF خودِ فایل خوانده می‌شود ([ir.aai.hushyar.core.Gguf]) تا معلوم شود فایل سالم است؛
 *  * حافظهٔ کل و آزاد دستگاه، بیتی‌بودن پردازه، فضای دیسک و معماری‌های گوشی خوانده می‌شود؛
 *  * نتیجه یک [FitVerdict] است و متن آن همهٔ عددهای اندازه‌گیری‌شده را با خود دارد.
 *
 * هیچ عددی در این فایل «واقعیت اندازه‌گیری‌شده» جا زده نمی‌شود: هر جا تخمین است، در متن هم
 * «برآورد» نوشته می‌شود.
 */
data class DeviceMemory(
    /** `MemTotal` از `/proc/meminfo` — کل حافظهٔ فیزیکی گوشی. */
    val totalBytes: Long,
    /** `MemAvailable` از `/proc/meminfo` — آزاد در همان لحظه. */
    val availBytes: Long,
    /** کم‌حافظه‌ای بر اساس همان عددها: آزاد کمتر از یک‌هشتم کل. */
    val lowMemory: Boolean,
    /** `Process.is64Bit()` — اگر نادرست باشد، پردازه ۳۲بیتی است. */
    val is64Bit: Boolean,
    /** فضای آزاد پوشهٔ مدل‌ها. */
    val freeDiskBytes: Long,
    /** `Build.SUPPORTED_ABIS` برای گزارش. */
    val abis: List<String> = emptyList()
) {
    companion object {
        /** همهٔ صفر: وقتی اندازه‌گیری ممکن نشد؛ در این حالت هیچ‌چیز «کافی نیست» گفته نمی‌شود. */
        val UNKNOWN = DeviceMemory(0, 0, false, true, 0, emptyList())
    }
}

enum class FitVerdict {
    /** با اندازه‌های همین دستگاه جا می‌شود. */
    FITS,

    /** جا می‌شود ولی تنگ؛ ممکن است کند شود یا سیستم اپ را ببندد. */
    TIGHT,

    /** پردازه ۳۲بیتی است و فایل از آن بزرگ‌تر که یک نقشهٔ حافظهٔ مطمئن بشود. */
    THIRTY_TWO_BIT,

    /** حافظهٔ آزاد برای برآوردِ نیاز کافی نیست. */
    LOW_RAM,

    /** برای دانلود (یا نگه‌داشتن) فایل، فضای دیسک کافی نیست. */
    NO_DISK,

    /** معماری مدل در فهرست معماری‌های موتور همراهِ اپ نیست. */
    UNKNOWN_ARCH,

    /** اندازه‌گیری نشد؛ داوری نمی‌کنیم. */
    UNKNOWN
}

object ModelFit {

    const val MB = 1024L * 1024L

    /**
     * معماری‌هایی که موتور همراهِ اپ می‌شناسد.
     *
     * اندازه‌گیری‌شده، نه ادعا: تست `GgufAndFitTest` رشتهٔ هر یک از این نام‌ها را داخل خودِ
     * `libhushyar_llama.so`ی تعهدشده در `jniLibs/` می‌گردد و اگر نبود، تست می‌شکند.
     */
    val SUPPORTED_ARCH: Set<String> =
        setOf("llama", "qwen2", "qwen3", "gemma", "gemma3", "phi3", "mistral", "deepseek2")

    /**
     * برآورد حافظهٔ لازم برای بارگذاری.
     *
     * چرا نصفِ فایل + ۳۲۰ مگابایت: وزن‌ها با `mmap` نقشه می‌شوند و یک‌جا در RAM کپی نمی‌شوند
     * (این را در `llama_jni.cpp` با `LLAMA_LOAD_MODE_MMAP` گذاشته‌ایم)، پس همهٔ فایل لازم نیست؛
     * ولی نیمی از صفحه‌ها در عمل خوانده می‌شوند و کش محاسبه و KV هم روی آن می‌آید. ۳۲۰ مگابایت
     * هم برای همان کش و متادیتا است. **برآورد است**، نه اندازه‌گیری، و در متن هم همین نوشته می‌شود.
     */
    fun estimatedNeedBytes(fileBytes: Long): Long = fileBytes / 2 + 320 * MB

    /** سقف مطمئن یک نقشهٔ حافظه در پردازهٔ ۳۲بیتی؛ بالاتر از این، شکست تجربه‌شده است. */
    const val SAFE_32BIT_BYTES = 900L * MB

    fun verdict(
        device: DeviceMemory,
        fileBytes: Long,
        installed: Boolean,
        header: GgufHeader? = null
    ): FitVerdict {
        if (header != null && !header.architectureKnown) return FitVerdict.UNKNOWN_ARCH
        if (header?.architecture != null && header.architecture !in SUPPORTED_ARCH) {
            return FitVerdict.UNKNOWN_ARCH
        }
        if (!installed && device.freeDiskBytes in 1 until fileBytes + 64 * MB) return FitVerdict.NO_DISK
        if (device.totalBytes <= 0 && device.freeDiskBytes <= 0) return FitVerdict.UNKNOWN
        if (!device.is64Bit && fileBytes > SAFE_32BIT_BYTES) return FitVerdict.THIRTY_TWO_BIT
        val free = if (device.availBytes > 0) device.availBytes else device.totalBytes
        val need = estimatedNeedBytes(fileBytes)
        return when {
            free in 1 until need -> FitVerdict.LOW_RAM
            free in 1 until need + need / 4 -> FitVerdict.TIGHT
            else -> FitVerdict.FITS
        }
    }

    fun mb(bytes: Long): Long = bytes / MB

    /** Persian digits for every number that reaches the user (the app is Persian-only). */
    private fun fa(bytes: Long): String = Text.num(mb(bytes))

    /** One sentence with the numbers it was decided from — never a verdict without evidence. */
    fun explain(verdict: FitVerdict, device: DeviceMemory, spec: ModelSpec): String {
        val file = fa(spec.bytes) + " مگابایت"
        val mem = if (device.totalBytes > 0) {
            "حافظهٔ آزاد " + fa(device.availBytes) + " از " + fa(device.totalBytes) + " مگابایت"
        } else {
            "حافظه اندازه‌گیری نشد"
        }
        val disk = "فضای آزاد " + fa(device.freeDiskBytes) + " مگابایت"
        val need = fa(ModelFit.estimatedNeedBytes(spec.bytes))
        return when (verdict) {
            FitVerdict.FITS ->
                "«${spec.title}»: فایل $file، $mem، $disk، پردازه ${bits(device)} — " +
                    "روی این دستگاه جا می‌شود."
            FitVerdict.TIGHT ->
                "«${spec.title}»: فایل $file، $mem، پردازه ${bits(device)} — تنگ است. " +
                    "برآورد نیاز $need مگابایت است؛ ممکن است بارگذاری شود ولی کند باشد یا سیستم اپ را ببندد."
            FitVerdict.THIRTY_TWO_BIT ->
                "«${spec.title}»: فایل $file و پردازهٔ این اپ **۳۲بیتی** است ($mem). " +
                    "یک پردازهٔ ۳۲بیتی نمی‌تواند نقشهٔ حافظهٔ یک گیگابایت به بالا را با خیال راحت باز " +
                    "کند؛ سقف مطمئن $safe32 مگابایت است. علت شکست بارگذاری همین است و با تنظیمات اپ " +
                    "درست نمی‌شود — مدلی تا $safe32 مگابایت انتخاب کن یا نسخهٔ ۶۴بیتی اپ را نصب کن."
            FitVerdict.LOW_RAM ->
                "«${spec.title}»: فایل $file، $mem و برآورد نیاز $need مگابایت. حافظهٔ آزاد کافی " +
                    "نیست؛ چند اپ را ببند و دوباره «بارگذاری در حافظه» را بزن."
            FitVerdict.NO_DISK ->
                "«${spec.title}»: برای دانلود $file، فضای آزاد " + fa(device.freeDiskBytes) +
                    " مگابایت است؛ جا نیست."
            FitVerdict.UNKNOWN_ARCH ->
                "«${spec.title}»: معماری این فایل در موتور همراهِ اپ شناخته نمی‌شود. " +
                    "موتور این معماری‌ها را می‌شناسد: ${SUPPORTED_ARCH.sorted().joinToString("، ")}."
            FitVerdict.UNKNOWN ->
                "«${spec.title}»: اندازه‌های دستگاه خوانده نشد، پس داوری نمی‌کنیم؛ فایل $file است."
        }
    }

    private val safe32: String get() = Text.num(mb(SAFE_32BIT_BYTES))

    /**
     * بیشترین حجم فایلی که با اندازه‌های همین دستگاه منطقی است.
     *
     * برای پردازهٔ ۳۲بیتی سقف ثابت ۹۰۰ مگابایت؛ برای ۶۴بیتی از برآورد نیاز برمی‌گردیم
     * (حجم فایل ≈ دو برابرِ حافظهٔ آزاد منهای ۳۲۰ مگابایت). صفر یعنی «اندازه‌گیری نشد».
     */
    fun maxUsableBytes(device: DeviceMemory): Long {
        if (device.totalBytes <= 0 && device.availBytes <= 0) return 0
        val byBits = if (device.is64Bit) Long.MAX_VALUE / 4 else SAFE_32BIT_BYTES
        val avail = if (device.availBytes > 0) device.availBytes else device.totalBytes
        val byRam = (avail - 320 * MB) * 2
        if (byRam <= 0) return 0
        return minOf(byBits, byRam)
    }

    /** بزرگ‌ترین مدل فهرست که روی این دستگاه بارگذاری‌شدنی است. */
    fun bestFor(device: DeviceMemory, specs: List<ModelSpec>): ModelSpec? =
        specs.filter { verdict(device, it.bytes, installed = true) == FitVerdict.FITS }
            .maxByOrNull { it.bytes }

    /** یک جملهٔ کوتاه: «این گوشی تا چه حجمی را می‌تواند بارگذاری کند». */
    fun capacityLine(device: DeviceMemory): String {
        val max = maxUsableBytes(device)
        if (max <= 0) return "سقف حجم مدل برای این گوشی اندازه‌گیری نشد."
        return "سقف این گوشی: مدلی تا حدود " + fa(max) + " مگابایت (پردازه " + bits(device) +
            "، " + fa(device.availBytes) + " مگابایت حافظهٔ آزاد). بزرگ‌تر از این، موتور بارگذاری نمی‌کند."
    }

    /** The verdict as the user should read it — never an English enum name in a Persian app. */
    fun verdictLabel(verdict: FitVerdict): String = when (verdict) {
        FitVerdict.FITS -> "جا می‌شود"
        FitVerdict.TIGHT -> "تنگ است"
        FitVerdict.THIRTY_TWO_BIT -> "پردازهٔ ۳۲بیتی"
        FitVerdict.LOW_RAM -> "حافظهٔ آزاد کم"
        FitVerdict.NO_DISK -> "فضای دیسک کم"
        FitVerdict.UNKNOWN_ARCH -> "معماری ناشناخته"
        FitVerdict.UNKNOWN -> "اندازه‌گیری نشد"
    }

    val SAFE_32BIT_MB_TEXT: Long get() = mb(SAFE_32BIT_BYTES)

    private fun bits(device: DeviceMemory): String = if (device.is64Bit) "۶۴بیتی" else "۳۲بیتی"

    /**
     * یک خط کوتاه برای فهرست مدل‌ها، پیش از آن‌که کاربر یک گیگابایت داده خرج کند.
     * فقط وقتی گفته می‌شود که چیزی برای گفتن باشد.
     */
    fun advisory(device: DeviceMemory, spec: ModelSpec, installed: Boolean): String? {
        val verdict = verdict(device, spec.bytes, installed)
        return when (verdict) {
            FitVerdict.FITS, FitVerdict.UNKNOWN -> null
            FitVerdict.TIGHT -> "روی این دستگاه تنگ است: " + fa(estimatedNeedBytes(spec.bytes)) +
                " مگابایت برآورد نیاز، " + fa(device.availBytes) + " مگابایت آزاد."
            FitVerdict.THIRTY_TWO_BIT -> "روی این دستگاه بارگذاری نمی‌شود: پردازهٔ اپ ۳۲بیتی است و " +
                "فایل " + fa(spec.bytes) + " مگابایتی بزرگ‌تر از سقف مطمئن $safe32 مگابایت است."
            FitVerdict.LOW_RAM -> "روی این دستگاه بارگذاری نمی‌شود: " + fa(device.availBytes) +
                " مگابایت حافظهٔ آزاد است و برآورد نیاز " + fa(estimatedNeedBytes(spec.bytes)) +
                " مگابایت."
            FitVerdict.NO_DISK -> "جا نیست: " + fa(device.freeDiskBytes) + " مگابایت آزاد، " +
                fa(spec.bytes) + " مگابایت لازم."
            FitVerdict.UNKNOWN_ARCH -> "معماری این فایل در موتور همراه اپ شناخته نمی‌شود."
        }
    }

    /**
     * گزارش کاملِ یک شکست بارگذاری: اول فایل، بعد دستگاه، بعد تصمیم.
     * این همان متنی است که باید جای «not a GGUF file / file missing» بنشیند.
     */
    fun failureReport(device: DeviceMemory, spec: ModelSpec, header: GgufHeader?): String {
        val fileLine = if (header != null) {
            header.describe() +
                (if (header.singleFile) "" else " — ولی فایل چندتکه است (split.count=${header.splitCount}).")
        } else {
            "سربرگ GGUF خوانده نشد؛ فایل ممکن است ناقص یا مال مدل دیگری باشد."
        }
        val verdict = verdict(device, spec.bytes, installed = true, header = header)
        return buildString {
            appendLine(fileLine)
            appendLine(explain(verdict, device, spec))
            append(
                "موتور پیام کلی «llama_model_load_from_file failed» می‌دهد؛ عددهای بالا از خود فایل " +
                    "و از گوشی خوانده شده‌اند."
            )
        }
    }
}
