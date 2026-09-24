package com.koodoreader.engine.mobi

/**
 * PalmDOC (a.k.a. "PalmDOC LZ77") decompressor — compression type 2, used by
 * essentially every MOBI6 / AZW file and by the KF8 text records.
 *
 * Opcode table (one byte at a time):
 * ```
 * 0x00        literal 0x00
 * 0x01..0x08  copy the next n bytes verbatim
 * 0x09..0x7F  that byte is itself a literal (ASCII)
 * 0x80..0xBF  two-byte LZ77 back reference:
 *               pair = (byte << 8) | nextByte
 *               dist = (pair >> 3) & 0x7FF       -> 1..2047
 *               len  = (nextByte & 0x07) + 3     -> 3..10
 * 0xC0..0xFF  a space followed by (byte XOR 0x80)
 * ```
 * Distance and length share the pair (`0x8000 | distance << 3 | (length - 3)`)
 * exactly as libmobi / mobiunpack implement it. Back references only ever reach
 * into the *current* record, so the output is built in one growing buffer.
 */
object PalmDoc {

    /** Sanity cap so a corrupt stream cannot allocate unbounded memory. */
    const val MAX_OUTPUT = 32 * 1024 * 1024

    /**
     * Decompresses one PalmDOC text record in full.
     *
     * @throws MalformedMobiException when the stream ends mid-opcode, a back
     *   reference reaches before the start of the output, or the output would
     *   exceed [MAX_OUTPUT].
     */
    fun decompress(input: ByteArray): ByteArray = decompress(input, 0, input.size)

    fun decompress(input: ByteArray, offset: Int, size: Int): ByteArray =
        decompress(input, offset, size, MAX_OUTPUT, strict = true)

    /**
     * Decompresses a record but stops as soon as [limit] bytes have been
     * produced, and treats a damaged/over-long trailer as end-of-stream.
     *
     * MOBI text records are frequently followed by index or trailer bytes that
     * the decompressor would read as opcodes. This entry point is what lets a
     * well-formed book whose trailer is not a valid opcode stream still be read:
     * it keeps the valid prefix instead of failing.
     */
    fun decompressAtMost(input: ByteArray, limit: Int): ByteArray {
        if (limit <= 0) return ByteArray(0)
        return decompress(input, 0, input.size, limit, strict = false)
    }

    private fun decompress(
        input: ByteArray,
        offset: Int,
        size: Int,
        limit: Int,
        strict: Boolean,
    ): ByteArray {
        if (offset < 0 || size < 0 || offset + size > input.size) {
            throw MalformedMobiException(
                "MobiCapture: PalmDOC slice $offset+$size outside ${input.size} bytes"
            )
        }
        val end = offset + size
        val cap = if (limit >= MAX_OUTPUT) MAX_OUTPUT else limit
        var buffer = ByteArray(minOf(maxOf(size * 2 + 16, 64), cap))
        var outLen = 0
        var i = offset

        fun grow(extra: Int) {
            if (outLen + extra <= buffer.size) return
            var grown = buffer.size
            while (grown < outLen + extra) grown *= 2
            if (grown > MAX_OUTPUT) {
                throw MalformedMobiException(
                    "MobiCapture: PalmDOC output exceeds $MAX_OUTPUT bytes (corrupt stream?)"
                )
            }
            buffer = buffer.copyOf(grown)
        }

        fun push(b: Byte) {
            if (outLen >= cap) return
            grow(1)
            buffer[outLen++] = b
        }

        while (i < end && outLen < cap) {
            val c = input[i].toInt() and 0xFF
            i++
            when {
                c == 0x00 -> push(0)

                c <= 0x08 -> { // 0x01..0x08: literal run of n bytes
                    if (i + c > end) {
                        if (!strict) return buffer.copyOf(outLen)
                        throw MalformedMobiException(
                            "MobiCapture: PalmDOC literal run of $c bytes truncated at $i"
                        )
                    }
                    val room = minOf(c, cap - outLen)
                    if (room > 0) {
                        grow(room)
                        System.arraycopy(input, i, buffer, outLen, room)
                        outLen += room
                    }
                    i += c
                }

                c <= 0x7F -> push(c.toByte())

                c <= 0xBF -> { // 0x80..0xBF: LZ77 back reference
                    if (i >= end) {
                        if (!strict) return buffer.copyOf(outLen)
                        throw MalformedMobiException(
                            "MobiCapture: PalmDOC back reference truncated at $i"
                        )
                    }
                    val next = input[i].toInt() and 0xFF
                    i++
                    val pair = (c shl 8) or next
                    // PalmDOC packs both values into the pair:
                    //   pair = 0x8000 | (distance << 3) | (length - 3)
                    val distance = (pair shr 3) and 0x7FF
                    val length = (next and 0x07) + 3
                    if (distance <= 0 || distance > outLen) {
                        if (!strict) return buffer.copyOf(outLen)
                        throw MalformedMobiException(
                            "MobiCapture: PalmDOC back reference distance $distance " +
                                "exceeds output $outLen"
                        )
                    }
                    val room = minOf(length, cap - outLen)
                    if (room > 0) {
                        grow(room)
                        var from = outLen - distance
                        var remaining = room
                        while (remaining-- > 0) {
                            // Byte-by-byte so overlapping (RLE-style) copies work.
                            buffer[outLen++] = buffer[from++]
                        }
                    }
                }

                else -> { // 0xC0..0xFF: space + (c XOR 0x80)
                    push(' '.code.toByte())
                    push((c xor 0x80).toByte())
                }
            }
        }
        return buffer.copyOf(minOf(outLen, cap))
    }

    /**
     * Trims a MOBI text record's trailing NUL padding.
     *
     * The record layout is `<compressed stream><optional trailer>`; the trailer
     * must not be fed to the decompressor, but its encoding is only decodable
     * from the end of the record and writers disagree on it. This is therefore
     * deliberately conservative — only NUL padding is dropped. The authoritative
     * bound is the header's `textLength`, applied by [MobiParser].
     */
    fun stripExtras(record: ByteArray): ByteArray {
        if (record.isEmpty()) return record
        var end = record.size
        while (end > 0 && record[end - 1] == 0.toByte()) end--
        if (end == 0) return ByteArray(0)
        return record.copyOfRange(0, end)
    }
}
