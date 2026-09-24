package com.koodoreader.engine.mobi

/**
 * EXTH ("Extended Header") metadata block.
 *
 * ```text
 * "EXTH" | totalLength (u32) | recordCount (u32) | record*
 * record := type (u32) | length (u32, includes the 8-byte header) | data
 * ```
 *
 * The block is walked defensively: a record whose length is smaller than its
 * own header, or that overruns the block, terminates parsing (the metadata read
 * so far is kept) instead of throwing. Only the outer container parse is
 * strict, because a broken EXTH block still leaves a perfectly readable book.
 */
data class Exth(
    val records: List<Record>,
    /** Text charset used for string values (from the MOBI header). */
    val charset: java.nio.charset.Charset = Charsets.UTF_8,
) {

    data class Record(val type: Int, val data: ByteArray) {
        val length: Int get() = data.size + 8

        /** Payload decoded as text, trimmed of a trailing NUL. */
        fun text(charset: java.nio.charset.Charset): String {
            if (data.isEmpty()) return ""
            var end = data.size
            while (end > 0 && data[end - 1] == 0.toByte()) end--
            return String(data, 0, end, charset)
        }

        /** Payload as a big-endian u32 (EXTH offsets/boundaries), or `null`. */
        fun intValue(): Int? {
            if (data.size < 4) return null
            return ((data[0].toInt() and 0xFF) shl 24) or
                ((data[1].toInt() and 0xFF) shl 16) or
                ((data[2].toInt() and 0xFF) shl 8) or
                (data[3].toInt() and 0xFF)
        }

        override fun equals(other: Any?): Boolean =
            this === other || (other is Record && type == other.type && data.contentEquals(other.data))

        override fun hashCode(): Int = type * 31 + data.contentHashCode()
    }

    fun first(type: Int): Record? = records.firstOrNull { it.type == type }

    fun text(type: Int): String? =
        first(type)?.text(charset)?.takeIf { it.isNotBlank() }

    fun texts(type: Int): List<String> =
        records.filter { it.type == type }.map { it.text(charset) }.filter { it.isNotBlank() }

    fun int(type: Int): Int? = first(type)?.intValue()

    /** `null` when the block is absent (`exthStart == null`). */
    companion object {
        fun read(record0: ByteArray, start: Int?, end: Int?, charset: java.nio.charset.Charset): Exth? {
            if (start == null) return null
            val limit = minOf(end ?: record0.size, record0.size)
            if (start + 12 > limit) return null
            val r = ByteReader(record0, start, limit)
            if (!r.matches(0, "EXTH")) return null
            val declaredLength = r.u32AsInt(4)
            val declaredCount = r.u32AsInt(8)
            val windowEnd = if (declaredLength in 12..(limit - start)) {
                start + declaredLength
            } else {
                limit
            }
            val body = ByteReader(record0, start + 12, windowEnd)
            val out = ArrayList<Record>(maxOf(declaredCount, 0).coerceAtMost(256))
            var p = 0
            var index = 0
            while (index < declaredCount && body.has(p, 8)) {
                val type = body.u32AsInt(p)
                val length = body.u32AsInt(p + 4)
                if (length < 8 || !body.has(p, length)) break
                out.add(Record(type, body.slice(p + 8, length - 8)))
                p += length
                index++
            }
            return Exth(out, charset)
        }
    }
}
