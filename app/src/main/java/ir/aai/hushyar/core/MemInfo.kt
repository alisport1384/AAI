package ir.aai.hushyar.core

/**
 * v0.11.2 — «چقدر حافظه آزاد است؟» بدون دسترسی به Context.
 *
 * برای این‌که بتوانیم علت واقعی شکست بارگذاری مدل را بگوییم، به عدد حافظه نیاز داریم.
 * `ActivityManager` به `Context` نیاز دارد و `ViewModel` ما عمداً آن را ندارد؛ ولی
 * `/proc/meminfo` برای هر اپی خواندنی است و همان دو عددی را می‌دهد که لازم داریم:
 *
 *  * `MemTotal` — کل حافظهٔ فیزیکی
 *  * `MemAvailable` — برآورد کرنل از حافظه‌ای که یک اپ تازه می‌تواند بگیرد
 *
 * تحلیل متن از خواندن فایل جدا شده است تا در تست JVM هم آزموده شود.
 */
object MemInfo {

    data class Snapshot(val totalBytes: Long, val availableBytes: Long) {
        val known: Boolean get() = totalBytes > 0
    }

    /** پارس خالص: هر خط «کلید: مقدار kB» است. */
    fun parse(text: String): Snapshot? {
        var total = 0L
        var available = -1L
        var free = 0L
        for (line in text.lineSequence()) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim().substringBefore(' ').toLongOrNull() ?: continue
            when (key) {
                "MemTotal" -> total = value * 1024
                "MemAvailable" -> available = value * 1024
                "MemFree" -> free = value * 1024
            }
        }
        if (total <= 0) return null
        // Kernels older than 3.14 have no MemAvailable; MemFree is a lower bound, so use it only
        // as a fallback and never pretend it is the same number.
        val avail = if (available >= 0) available else free
        return Snapshot(total, avail)
    }

    /** Reads the live numbers; null when the system does not expose them. */
    fun read(): Snapshot? = runCatching {
        parse(java.io.File("/proc/meminfo").readText())
    }.getOrNull()

    fun snapshot(): Snapshot = read() ?: Snapshot(0, 0)
}
