package com.koodoreader.engine.mobi

/**
 * Small big-endian byte reader used by the MOBI header / EXTH parsers.
 *
 * Every accessor is bounds-checked and returns a neutral default instead of
 * throwing `IndexOutOfBoundsException`, so callers can keep degradation
 * decisions (null vs. typed exception) in one place.
 */
internal class ByteReader(private val bytes: ByteArray, val start: Int = 0, val end: Int = bytes.size) {

    val length: Int get() = end - start

    fun has(offset: Int, size: Int): Boolean =
        offset >= 0 && size >= 0 && start + offset + size <= end

    fun u8(offset: Int): Int =
        if (has(offset, 1)) bytes[start + offset].toInt() and 0xFF else 0

    fun u16(offset: Int): Int = (u8(offset) shl 8) or u8(offset + 1)

    /** Unsigned 32-bit read widened to [Long]; `0` when out of bounds. */
    fun u32(offset: Int): Long {
        if (!has(offset, 4)) return 0L
        val p = start + offset
        return ((bytes[p].toLong() and 0xFF) shl 24) or
            ((bytes[p + 1].toLong() and 0xFF) shl 16) or
            ((bytes[p + 2].toLong() and 0xFF) shl 8) or
            (bytes[p + 3].toLong() and 0xFF)
    }

    fun u32AsInt(offset: Int): Int = u32(offset).toInt()

    fun ascii(offset: Int, size: Int): String =
        if (has(offset, size)) String(bytes, start + offset, size, Charsets.US_ASCII) else ""

    fun matches(offset: Int, magic: String): Boolean =
        ascii(offset, magic.length) == magic

    fun slice(offset: Int, size: Int): ByteArray =
        if (has(offset, size)) bytes.copyOfRange(start + offset, start + offset + size) else ByteArray(0)

    /** Bytes from [offset] to the end of the window (never throws). */
    fun tail(offset: Int): ByteArray =
        if (offset >= length) ByteArray(0) else bytes.copyOfRange(start + offset, end)

    /** Decodes a range with the supplied charset, clamped to the window. */
    fun decode(offset: Int, size: Int, charset: java.nio.charset.Charset): String {
        if (size <= 0 || offset < 0 || offset >= length) return ""
        val from = start + offset
        val to = minOf(from + size, end)
        return String(bytes, from, to - from, charset)
    }
}
