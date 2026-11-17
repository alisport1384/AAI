package ir.aai.hushyar.domain

/**
 * «کتابخانهٔ موتور روی این دستگاه بارگذاری نشد» — but *why*?
 *
 * That sentence was all v0.7.1 could say, and it is not actionable: the same sentence covers a
 * 32-bit phone, a 16 KB-page phone, an APK whose native library was stripped, and a genuinely
 * broken install. This object turns the facts the device will tell us into one verdict in Persian,
 * so the user (and the next build) knows which of them it is.
 *
 * Pure on purpose: the caller gathers the facts (`Build.SUPPORTED_ABIS`, page size, API level,
 * the library file, its ELF alignment) and this decides what they mean.
 */
object EngineDiagnosis {

    /** The ABIs this build actually ships. Must match `app/src/main/jniLibs/`. */
    val SHIPPED_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

    /**
     * Page alignment of each committed `libhushyar_llama.so`, per ABI.
     *
     * Measured, never guessed: the unit test `EngineDiagnosisTest` reads the real files in
     * `jniLibs/` and fails if these numbers drift, so a future relink cannot leave the diagnosis
     * lying. Kept per ABI because the three libraries are separate builds and may be relinked at
     * different times — a phone must be judged against *its own* library.
     */
    val SHIPPED_LIB_ALIGN: Map<String, Long> = mapOf(
        "arm64-v8a" to 16_384L,   // linked with -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON
        "armeabi-v7a" to 4096L,   // 32-bit devices use 4 KB pages; 16 KB devices are arm64-only
        "x86_64" to 16_384L
    )

    /** The alignment a phone will actually meet: the first shipped ABI it supports. */
    fun alignFor(abis: List<String>): Long? =
        abis.firstNotNullOfOrNull { abi -> SHIPPED_LIB_ALIGN[abi] }

    /** The loader says "not found" for a missing `.so`; everything else means it was there and failed. */
    fun libraryMissingFromError(error: String?): Boolean {
        val e = error?.lowercase() ?: return false
        return e.contains("couldn't find") || e.contains("cannot find") ||
            e.contains("not found") || e.contains("no such file")
    }

    data class Facts(
        /** `LlamaNative.loadFailure` — null when the library loaded fine. */
        val loadFailure: String?,
        /** `Build.SUPPORTED_ABIS`, best first. */
        val abis: List<String>,
        /** `Os.sysconf(SC_PAGE_SIZE)`; 0 when unknown. */
        val pageSizeBytes: Long,
        val apiLevel: Int,
        val deviceModel: String,
        /** Largest `PT_LOAD` alignment inside the `.so` this phone would load; null if unknown. */
        val libAlign: Long? = alignFor(abis),
        /** Whether the library file exists in the installed APK's native library directory. */
        val libPresent: Boolean = true
    ) {
        val engineLoaded: Boolean get() = loadFailure == null
        val pageSizeKb: Long get() = if (pageSizeBytes > 0) pageSizeBytes / 1024 else 0
    }

    enum class Verdict {
        /** The engine is fine; nothing to explain. */
        OK,

        /** No ABI the phone supports is shipped: the binary for this CPU was never built. */
        WRONG_ABI,

        /** The phone uses 16 KB pages, the library was linked for 4 KB pages. */
        PAGE_SIZE,

        /** The `.so` is not in the installed APK at all. */
        LIBRARY_MISSING,

        /** We cannot tell from the facts we have; the raw error is the only lead. */
        UNKNOWN
    }

    /**
     * Order matters. An unsupported ABI and a stripped APK both end in the loader saying
     * "couldn't find libhushyar_llama.so", but only one of them is fixed by re-downloading the
     * APK — so the architecture is judged first.
     */
    fun verdict(facts: Facts): Verdict = when {
        facts.engineLoaded -> Verdict.OK
        facts.abis.isNotEmpty() && facts.abis.none { it in SHIPPED_ABIS } -> Verdict.WRONG_ABI
        facts.pageSizeBytes > 0 && facts.libAlign != null && facts.pageSizeBytes > facts.libAlign -> Verdict.PAGE_SIZE
        !facts.libPresent -> Verdict.LIBRARY_MISSING
        else -> Verdict.UNKNOWN
    }

    /** What the user should do about it — never a guess dressed up as a fix. */
    fun advice(verdict: Verdict): String = when (verdict) {
        Verdict.OK -> "موتور روی این دستگاه کار می‌کند."
        Verdict.WRONG_ABI ->
            "این گوشی با معماری‌ای کار می‌کند که باینری ما برایش ساخته نشده. مدل روی این دستگاه " +
                "اجرا نمی‌شود تا نسخه‌ای برای این معماری ساخته شود؛ بقیهٔ هوش اپ (جستجو، یادگیری، " +
                "لایه‌بندی، پاسخ از شاهد) بدون مدل کامل کار می‌کند. نسخه‌ای که armeabi-v7a را هم " +
                "دارد باید جداگانه نصب شود."
        Verdict.PAGE_SIZE ->
            "گوشی از صفحه‌بندی ۱۶ کیلوبایتی استفاده می‌کند و کتابخانه با تراز ۴ کیلوبایت لینک شده " +
                "است. این با تغییر تنظیمات اپ درست نمی‌شود؛ کتابخانه باید دوباره و با تراز صفحهٔ " +
                "۱۶ کیلوبایت ساخته شود. تا آن موقع مدل روی این گوشی بارگذاری نمی‌شود."
        Verdict.LIBRARY_MISSING ->
            "فایل کتابخانه در نصب وجود ندارد؛ APK ناقص است یا نصب‌کنندهٔ آن کتابخانه‌های بومی را " +
                "حذف کرده. APK را از خروجی CI همین مخزن دوباره نصب کن."
        Verdict.UNKNOWN ->
            "از روی اطلاعات دستگاه نمی‌شود علت را قطعی گفت. متن خطای بالا و همین گزارش را بفرست " +
                "تا دقیقاً مشخص شود."
    }

    /** One copyable block; the raw numbers stay in it so a report is never just prose. */
    fun report(facts: Facts, extra: String? = null): String {
        val verdict = verdict(facts)
        val sb = StringBuilder()
        sb.appendLine("گزارش تشخیص موتور (v0.8)")
        sb.appendLine("دستگاه: ${facts.deviceModel} — اندروید API ${facts.apiLevel}")
        sb.appendLine("معماری‌های گوشی: ${facts.abis.joinToString("، ").ifBlank { "نامعلوم" }}")
        sb.appendLine("معماری‌های همراه اپ: ${SHIPPED_ABIS.joinToString("، ")}")
        sb.appendLine(
            "اندازهٔ صفحهٔ حافظه: " +
                (if (facts.pageSizeKb > 0) "${facts.pageSizeKb} کیلوبایت" else "نامعلوم")
        )
        sb.appendLine(
            "کتابخانه در نصب: " + (if (facts.libPresent) "هست" else "نیست") +
                " — تراز صفحه در فایل: " +
                (facts.libAlign?.let { if (it % 1024L == 0L) "${it / 1024} کیلوبایت" else "$it بایت" }
                    ?: "خوانده نشد")
        )
        sb.appendLine("خطای بارگذاری: " + (facts.loadFailure ?: "بدون خطا"))
        extra?.takeIf { it.isNotBlank() }?.let { sb.appendLine("یادداشت: ${it.take(400)}") }
        sb.appendLine("حکم: ${label(verdict)}")
        sb.appendLine(advice(verdict))
        return sb.toString().trim()
    }

    fun label(verdict: Verdict): String = when (verdict) {
        Verdict.OK -> "موتور سالم است"
        Verdict.WRONG_ABI -> "معماری پشتیبانی‌نشده"
        Verdict.PAGE_SIZE -> "ناسازگاری اندازهٔ صفحه (۱۶ در برابر ۴ کیلوبایت)"
        Verdict.LIBRARY_MISSING -> "کتابخانه در نصب نیست"
        Verdict.UNKNOWN -> "علت نامعلوم"
    }
}

/**
 * What the model screen is allowed to offer. Kept out of Compose so it can be tested:
 * an engine that cannot load is a reason to *warn*, never a reason to forbid the download —
 * the file is still worth having (it can be exported, restored, and used by a later build).
 */
object ModelUiRules {

    fun canDownload(phase: ModelPhase, busy: Boolean): Boolean = !busy &&
        (phase == ModelPhase.MISSING || phase == ModelPhase.ENGINE_ERROR)

    fun canExport(installed: Boolean): Boolean = installed

    /** Honest one-liner under the download button on a device whose engine cannot load. */
    fun engineWarning(verdict: EngineDiagnosis.Verdict): String = when (verdict) {
        EngineDiagnosis.Verdict.OK -> ""
        else ->
            "موتور روی این دستگاه بارگذاری نمی‌شود (" + EngineDiagnosis.label(verdict) + "). " +
                "دانلود همچنان فایل مدل را ذخیره می‌کند: می‌توانی آن را بیرون ببری و روی دستگاهی " +
                "که موتور دارد بازیابی کنی. بدون مدل هم جستجو، یادگیری عمیق و پاسخ از شاهد کار می‌کند."
    }
}
