package ir.aai.hushyar.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Files the app owns: backups and Markdown logs. They live under private filesDir; ordinary
 * file managers cannot read them on modern Android. Export through the document picker, or use
 * run-as with adb on a debuggable build. No broad storage permission is needed.
 */
class Store(private val context: Context) {

    private val root: File = File(context.filesDir, "hushyar").apply { mkdirs() }
    val backupDir: File = File(root, "backups").apply { mkdirs() }
    val logDir: File = File(root, "logs").apply { mkdirs() }

    /** Where the optional on-device model lives (v0.7). Same private tree as everything else. */
    fun modelsDir(): File = File(root, "models").apply { mkdirs() }

    /** Optional HTTPS host and Keystore-sealed token; never included in backup.json. */
    val personalBackend: PrivateBackendSettings by lazy { PrivateBackendSettings(context) }

    data class SelectedText(val title: String, val body: String, val kind: String)

    /** Read ONLY a file the user selected. Binary/PDF and oversized files are refused, never uploaded. */
    fun selectedText(uri: Uri): SelectedText {
        val filename = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }?.take(120)?.takeIf { it.isNotBlank() } ?: "سند متنی"
        val kind = when (filename.substringAfterLast('.', "").lowercase()) {
            "md", "markdown" -> "markdown"
            "csv" -> "csv"
            "html", "htm" -> "html"
            "txt" -> "text"
            else -> when (context.contentResolver.getType(uri)?.lowercase()) {
                "text/plain" -> "text"
                "text/markdown" -> "markdown"
                "text/csv" -> "csv"
                "text/html" -> "html"
                else -> throw IllegalArgumentException("فقط فایل txt، md، csv یا html قابل نمایه‌سازی است؛ PDF/OCR در این نسخه نیست")
            }
        }
        val bytes = openInput(uri)?.use { input ->
            val data = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                require(data.size() + n <= 100_000) { "سند بیش از ۱۰۰ کیلوبایت است؛ آن را تقسیم کن" }
                data.write(buffer, 0, n)
            }
            data.toByteArray()
        } ?: throw IllegalArgumentException("فایل انتخاب‌شده باز نشد")
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val body = try { decoder.decode(ByteBuffer.wrap(bytes)).toString() }
        catch (_: Exception) { throw IllegalArgumentException("متن فایل UTF-8 معتبر نیست") }
        require(body.isNotBlank() && body.none { it.code < 32 && it != '\n' && it != '\r' && it != '\t' }) {
            "فایل متنی خالی یا ناخواناست"
        }
        return SelectedText(filename, body, kind)
    }

    fun stamp(now: Long = System.currentTimeMillis()): String {
        val fmt = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
        return fmt.format(java.util.Date(now))
    }

    fun writeLog(name: String, markdown: String): File {
        val file = File(logDir, "$name.md")
        file.writeText(markdown, Charsets.UTF_8)
        return file
    }

    /**
     * One ZIP with everything: the machine-readable backup plus the human-readable logs.
     * A restore only needs `backup.json` inside it; the Markdown is for the user's eyes.
     */
    fun writeBundle(stamp: String, backupJson: String, brainMarkdown: String, logMarkdown: String, memoryMarkdown: String): File {
        val file = File(backupDir, "hushyar-backup-$stamp.zip")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            fun entry(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry("backup.json", backupJson)
            entry("brain.md", brainMarkdown)
            entry("logs/hushyar-log.md", logMarkdown)
            entry("memory/memory.md", memoryMarkdown)
            entry("README.txt", readme(stamp))
        }
        // A plain JSON next to the ZIP: simplest possible path for an external tool.
        File(backupDir, "hushyar-backup-$stamp.json").writeText(backupJson, Charsets.UTF_8)
        return file
    }

    fun listBackups(): List<File> =
        backupDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()

    /** Reads a backup document from a ZIP bundle or a bare .json file. */
    fun readBackup(file: File): String = when {
        file.name.endsWith(".zip", ignoreCase = true) -> readFromZip(file.readBytes())
        else -> file.readText(Charsets.UTF_8)
    }

    /** Stream access for the model file the user saves/restores (v0.7.1). */
    fun openOutput(uri: Uri): java.io.OutputStream? =
        context.contentResolver.openOutputStream(uri, "wt")

    fun openInput(uri: Uri): java.io.InputStream? =
        context.contentResolver.openInputStream(uri)

    fun readBackup(uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("فایل خوانده نشد")
        return if (looksLikeZip(bytes)) readFromZip(bytes) else String(bytes, Charsets.UTF_8)
    }

    fun readFromZip(bytes: ByteArray): String {
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith("backup.json")) {
                    return zip.readBytes().toString(Charsets.UTF_8)
                }
                entry = zip.nextEntry
            }
        }
        throw IllegalArgumentException("در فایل ZIP، backup.json پیدا نشد")
    }

    private fun looksLikeZip(bytes: ByteArray): Boolean =
        bytes.size > 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()

    fun totalBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun inventory(): List<String> =
        root.walkTopDown().filter { it.isFile }
            .sortedBy { it.absolutePath }
            .map { it.relativeTo(root).path + " (" + it.length() / 1024 + " KB)" }
            .toList()

    private fun readme(stamp: String): String = buildString {
        appendLine("هوشیار — بستهٔ پشتیبان")
        appendLine("ساخته‌شده: $stamp")
        appendLine()
        appendLine("backup.json        — بازگردانی داده‌ها (پک بازار، آگهی‌ها، حافظه، گفت‌وگوها، لاگ، ممیزی، تنظیمات)")
        appendLine("فایل مدل محلی در پشتیبان نیست؛ در صورت نیاز آن را جداگانه به فایل ذخیره کن.")
        appendLine("brain.md           — دفتر هوش: حافظه، دانش وب، ممیزی و لاگ به زبان انسان")
        appendLine("logs/hushyar-log.md — لاگ فعالیت")
        appendLine("memory/memory.md    — حافظهٔ دسته‌بندی‌شده")
        appendLine()
        appendLine("بازگردانی: از داخل اپ، تب «دانش ← پشتیبان»، گزینهٔ «انتخاب فایل پشتیبان از حافظه».")
        appendLine("بررسی سلامت: چک‌سام SHA-256 داخل backup.json نوشته شده است.")
    }
}
