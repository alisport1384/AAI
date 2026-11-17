package ir.aai.hushyar.core

/**
 * Just enough ELF reading to answer one question honestly: *is the native engine we shipped
 * aligned for this device's page size?*
 *
 * Why this exists: Android 15+ ships devices with 16 KB memory pages. A shared library linked
 * with the older 4 KB maximum page size cannot be loaded there — `dlopen` fails with a message
 * that never mentions alignment, so the app could only say "the engine did not load". Now it can
 * say which of the two it is, because those need completely different fixes.
 *
 * Only what is needed is parsed: the program headers, looking for `PT_LOAD`.
 */
object Elf {

    private const val PT_LOAD = 1L

    /** Byte order/class of the file. Everything we ship is little-endian. */
    data class Headers(val is64: Boolean, val littleEndian: Boolean, val machine: Int)

    /** The largest `p_align` among the loadable segments — the file's own page assumption. */
    fun maxLoadAlign(bytes: ByteArray): Long? {
        val header = headers(bytes) ?: return null
        if (!header.littleEndian) return null
        val phoff: Int
        val phentsize: Int
        val phnum: Int
        if (header.is64) {
            phoff = readInt(bytes, 32) ?: return null
            phentsize = readShort(bytes, 54) ?: return null
            phnum = readShort(bytes, 56) ?: return null
        } else {
            phoff = readInt(bytes, 28) ?: return null
            phentsize = readShort(bytes, 42) ?: return null
            phnum = readShort(bytes, 44) ?: return null
        }
        if (phentsize < if (header.is64) 56 else 32) return null
        var max = 0L
        for (i in 0 until phnum.coerceAtMost(128)) {
            val at = phoff + i * phentsize
            if (at < 0 || at + phentsize > bytes.size) break
            val type = readInt(bytes, at)?.toLong()?.and(0xffffffffL) ?: continue
            if (type != PT_LOAD) continue
            val align: Long = if (header.is64) {
                readLong(bytes, at + 48) ?: 0L
            } else {
                (readInt(bytes, at + 28)?.toLong() ?: 0L).and(0xffffffffL)
            }
            if (align > max) max = align
        }
        return max.takeIf { it > 0 }
    }

    fun headers(bytes: ByteArray): Headers? {
        if (bytes.size < 64) return null
        if (bytes[0] != 0x7f.toByte() || bytes[1] != 'E'.code.toByte() ||
            bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte()
        ) return null
        val is64 = when (bytes[4].toInt()) {
            2 -> true
            1 -> false
            else -> return null
        }
        val little = when (bytes[5].toInt()) {
            1 -> true
            2 -> false
            else -> return null
        }
        val machine = readShort(bytes, 18) ?: return null
        return Headers(is64, little, machine)
    }

    private fun readShort(bytes: ByteArray, at: Int): Int? {
        if (at < 0 || at + 2 > bytes.size) return null
        return (bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8)
    }

    private fun readInt(bytes: ByteArray, at: Int): Int? {
        if (at < 0 || at + 4 > bytes.size) return null
        return (bytes[at].toInt() and 0xff) or
            ((bytes[at + 1].toInt() and 0xff) shl 8) or
            ((bytes[at + 2].toInt() and 0xff) shl 16) or
            ((bytes[at + 3].toInt() and 0xff) shl 24)
    }

    private fun readLong(bytes: ByteArray, at: Int): Long? {
        val low = readInt(bytes, at)?.toLong()?.and(0xffffffffL) ?: return null
        val high = readInt(bytes, at + 4)?.toLong()?.and(0xffffffffL) ?: return null
        return (high shl 32) or low
    }
}
