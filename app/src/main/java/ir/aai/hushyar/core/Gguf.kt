package ir.aai.hushyar.core

import java.io.File
import java.io.RandomAccessFile

/**
 * v0.11.2 — «فایل سالم است یا موتور مقصر است؟»
 *
 * موتور بومی هر خطای بارگذاری را در یک جمله خلاصه می‌کند:
 * «llama_model_load_from_file failed (file missing, truncated or not a GGUF file)».
 * این جمله سه احتمال کاملاً متفاوت را در یک خط جمع می‌کند و کاربر را بی‌جواب می‌گذارد. پس
 * سربرگ GGUF را **خودمان** می‌خوانیم: چهار بایت جادویی، نسخه، شمار تانسورها، شمار جفت‌های
 * کلید/مقدار، و معماری مدل. اگر این‌ها درست باشند، فایل سالم است و علت جای دیگری است.
 *
 * فقط بلوک متادیتا خوانده می‌شود (چند مگابایت اول)، هرگز وزن‌ها؛ پس روی گوشی هم سریع است.
 */
data class GgufHeader(
    val version: Int,
    val tensorCount: Long,
    val kvCount: Long,
    /** `general.architecture` — مثلاً `qwen2`، `qwen3`، `gemma3`، `llama`. */
    val architecture: String?,
    /** `general.name` — نامی که سازنده گذاشته است. */
    val name: String?,
    /** `split.count` — اگر بیشتر از ۱ باشد، فایل تک‌تکه نیست و موتور آن را نمی‌پذیرد. */
    val splitCount: Int
) {
    val architectureKnown: Boolean get() = architecture != null && architecture.isNotBlank()
    val singleFile: Boolean get() = splitCount <= 1

    /** One honest sentence about the file itself, with its real numbers. */
    fun describe(): String =
        "فایل GGUF سالم است: نسخهٔ $version، معماری ${architecture ?: "نامعلوم"}، " +
            "${Txt.num(tensorCount)} تانسور" + (name?.let { "، نام «$it»" } ?: "")
}

sealed class GgufRead {
    data class Ok(val header: GgufHeader) : GgufRead()
    data class Bad(val reason: String) : GgufRead()
}

object Gguf {

    private const val MAGIC = "GGUF"

    /** Sanity caps: a real metadata block is a few MB; anything bigger is not a GGUF we know. */
    private const val MAX_META_BYTES = 64L * 1024 * 1024
    private const val MAX_STRING_BYTES = 4L * 1024 * 1024
    private const val MAX_ITEMS = 8_000_000L

    /** Reads the header and the metadata keys we care about. Never touches the weights. */
    fun read(file: File): GgufRead {
        if (!file.isFile) return GgufRead.Bad("فایل وجود ندارد")
        val length = file.length()
        if (length < 24) return GgufRead.Bad("فایل کوچک‌تر از سربرگ GGUF است (${length} بایت)")

        return try {
            RandomAccessFile(file, "r").use { raf ->
                val magic = ByteArray(4)
                raf.readFully(magic)
                if (String(magic, Charsets.US_ASCII) != MAGIC) {
                    val head = String(magic, Charsets.ISO_8859_1).filter { it.code in 32..126 }
                    return GgufRead.Bad(
                        "چهار بایت آغاز فایل «${MAGIC}» نیست" +
                            (if (head.isNotBlank()) " (با «$head» شروع می‌شود)" else "") +
                            "؛ این فایل وزن مدل نیست."
                    )
                }
                val version = raf.readIntLe()
                if (version !in 2..3) {
                    return GgufRead.Bad(
                        "نسخهٔ GGUF این فایل $version است؛ موتور فقط نسخهٔ ۲ و ۳ را می‌خواند."
                    )
                }
                val tensorCount = raf.readLongLe()
                val kvCount = raf.readLongLe()
                if (tensorCount <= 0 || tensorCount > MAX_ITEMS) {
                    return GgufRead.Bad("شمار تانسورها معتبر نیست ($tensorCount).")
                }
                if (kvCount < 0 || kvCount > MAX_ITEMS) {
                    return GgufRead.Bad("شمار کلیدهای متادیتا معتبر نیست ($kvCount).")
                }

                var architecture: String? = null
                var name: String? = null
                var splitCount = 1
                var index = 0L
                val metaStart = raf.filePointer
                while (index < kvCount) {
                    if (raf.filePointer >= length) return truncated(length)
                    if (raf.filePointer - metaStart > MAX_META_BYTES) {
                        // More than 64 MB of metadata is not a file we trust; stop reading but
                        // report what we already saw instead of reading forever.
                        break
                    }
                    val key = raf.readString(MAX_STRING_BYTES)
                        ?: return GgufRead.Bad("کلید متادیتا در جای ${index + 1} خوانده نشد.")
                    val type = raf.readIntLe()
                    when (key) {
                        "general.architecture" -> architecture = raf.readStringValue(type, MAX_STRING_BYTES)
                        "general.name" -> name = raf.readStringValue(type, MAX_STRING_BYTES)
                        "split.count" -> splitCount = raf.readLongValue(type)?.toInt() ?: 1
                        else -> if (!raf.skipValue(type)) return truncated(length)
                    }
                    index++
                }
                GgufRead.Ok(
                    GgufHeader(
                        version = version,
                        tensorCount = tensorCount,
                        kvCount = kvCount,
                        architecture = architecture,
                        name = name,
                        splitCount = splitCount
                    )
                )
            }
        } catch (e: java.io.EOFException) {
            truncated(file.length())
        } catch (t: Throwable) {
            GgufRead.Bad("خواندن سربرگ ناموفق بود: ${t.message ?: t::class.java.simpleName}")
        }
    }

    private fun truncated(length: Long): GgufRead.Bad =
        GgufRead.Bad("فایل ناقص است: متادیتا تا انتهای فایل (${Txt.num(length)} بایت) ادامه دارد.")

    // ---- little-endian readers (GGUF is LE on every platform) ----

    private fun RandomAccessFile.readIntLe(): Int {
        val b = ByteArray(4)
        readFully(b)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or
            ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 24)
    }

    private fun RandomAccessFile.readLongLe(): Long {
        val b = ByteArray(8)
        readFully(b)
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[i].toLong() and 0xFF)
        return v
    }

    private fun RandomAccessFile.readString(max: Long): String? {
        val n = readLongLe()
        if (n < 0 || n > max || n > length() - filePointer) return null
        val bytes = ByteArray(n.toInt())
        readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    /** Reads a string when the declared type is string, otherwise steps over the value. */
    private fun RandomAccessFile.readStringValue(type: Int, max: Long): String? {
        if (type == GGUF_STRING) return readString(max)
        skipValue(type)
        return null
    }

    private fun RandomAccessFile.readLongValue(type: Int): Long? {
        if (type == GGUF_UINT32) return readIntLe().toLong() and 0xFFFFFFFFL
        if (type == GGUF_UINT64) return readLongLe()
        skipValue(type)
        return null
    }

    /** Steps over one metadata value of [type]. Returns false when the file ends too early. */
    private fun RandomAccessFile.skipValue(type: Int): Boolean {
        val fixed = when (type) {
            GGUF_UINT8, GGUF_INT8, GGUF_BOOL -> 1
            GGUF_UINT16, GGUF_INT16 -> 2
            GGUF_UINT32, GGUF_INT32, GGUF_FLOAT32 -> 4
            GGUF_UINT64, GGUF_INT64, GGUF_FLOAT64 -> 8
            GGUF_STRING -> -1
            GGUF_ARRAY -> -2
            else -> return false
        }
        return when (fixed) {
            1, 2, 4, 8 -> seekForward(fixed.toLong())
            -1 -> readString(MAX_STRING_BYTES) != null
            else -> {
                val itemType = readIntLe()
                val count = readLongLe()
                if (count < 0 || count > MAX_ITEMS) return false
                if (itemType == GGUF_STRING) {
                    var i = 0L
                    while (i < count) {
                        if (readString(MAX_STRING_BYTES) == null) return false
                        i++
                    }
                    true
                } else {
                    val size = fixedSizeOf(itemType) ?: return false
                    seekForward(size * count)
                }
            }
        }
    }

    private fun fixedSizeOf(type: Int): Long? = when (type) {
        GGUF_UINT8, GGUF_INT8, GGUF_BOOL -> 1L
        GGUF_UINT16, GGUF_INT16 -> 2L
        GGUF_UINT32, GGUF_INT32, GGUF_FLOAT32 -> 4L
        GGUF_UINT64, GGUF_INT64, GGUF_FLOAT64 -> 8L
        else -> null
    }

    private fun RandomAccessFile.seekForward(bytes: Long): Boolean {
        if (bytes < 0 || filePointer + bytes > length()) return false
        seek(filePointer + bytes)
        return true
    }

    private const val GGUF_UINT8 = 0
    private const val GGUF_INT8 = 1
    private const val GGUF_UINT16 = 2
    private const val GGUF_INT16 = 3
    private const val GGUF_UINT32 = 4
    private const val GGUF_INT32 = 5
    private const val GGUF_FLOAT32 = 6
    private const val GGUF_BOOL = 7
    private const val GGUF_STRING = 8
    private const val GGUF_ARRAY = 9
    private const val GGUF_UINT64 = 10
    private const val GGUF_INT64 = 11
    private const val GGUF_FLOAT64 = 12
}
