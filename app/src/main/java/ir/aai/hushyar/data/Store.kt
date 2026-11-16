package ir.aai.hushyar.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Files the app owns: backups and Markdown logs. Everything lives under filesDir so the user
 * can pull it out with any file manager (or adb) without the app having any storage permission.
 */
class Store(private val context: Context) {

    private val root: File = File(context.filesDir, "hushyar").apply { mkdirs() }
    val backupDir: File = File(root, "backups").apply { mkdirs() }
    val logDir: File = File(root, "logs").apply { mkdirs() }

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
        appendLine("backup.json        — بازگردانی کامل (پک بازار، آگهی‌ها، حافظه، لاگ، ممیزی، تنظیمات)")
        appendLine("brain.md           — دفتر هوش: حافظه، ممیزی و لاگ به زبان انسان")
        appendLine("logs/hushyar-log.md — لاگ فعالیت")
        appendLine("memory/memory.md    — حافظهٔ دسته‌بندی‌شده")
        appendLine()
        appendLine("بازگردانی: از داخل اپ، بخش «داده»، گزینهٔ بازیابی از فایل.")
        appendLine("بررسی سلامت: چک‌سام SHA-256 داخل backup.json نوشته شده است.")
    }
}
