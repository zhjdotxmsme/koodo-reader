package com.koodoreader.feature.dictionary.mdx

/**
 * Big/little-endian integer readers for the MDict container format.
 *
 * PORT SOURCE — `node_modules/js-mdict@6.0.8/dist/esm/utils.js`:
 *  - `uint8BEtoNumber`   utils.js:117
 *  - `uint16BEtoNumber`  utils.js:125
 *  - `uint32BEtoNumber`  utils.js:139
 *  - `uint64BEtoNumber`  utils.js:154  (2^53 guard kept verbatim)
 *  - `b2n`               utils.js:216  (dispatch on byte width)
 *
 * `mdict-base.js` reads *every* structural field with `common.b2n(slice)` — the
 * byte width is `meta.numWidth` (8 for engine >= 2.0, else 4, see
 * mdict-base.js:384-391), which is why all of these take an explicit width.
 *
 * PERFORMANCE NOTE: unlike the JS port (which slices a `Buffer` per field), the
 * readers below work on a `(bytes, offset)` pair, so no intermediate copies are
 * made while walking the header / key-info / record-info tables.
 */
object Bytes {

    /** `utils.js:117` — single unsigned byte. */
    fun beUInt8(bytes: ByteArray, offset: Int): Long = (bytes[offset].toInt() and 0xFF).toLong()

    /** `utils.js:125` — unsigned 16 bit, big endian. */
    fun beUInt16(bytes: ByteArray, offset: Int): Long =
        (((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)).toLong()

    /** `utils.js:139` — unsigned 32 bit, big endian. */
    fun beUInt32(bytes: ByteArray, offset: Int): Long {
        var n = 0L
        for (i in 0 until 4) {
            n = n or (bytes[offset + i].toLong() and 0xFFL)
            if (i != 3) n = n shl 8
        }
        return n
    }

    /**
     * `utils.js:154` — unsigned 64 bit, big endian.
     *
     * The reference reads the value as a JS double and therefore throws as soon
     * as the high word could exceed 2^53 (`bytes[1] >= 0x20 || bytes[0] > 0`).
     * Keep the same guard so a corrupt file surfaces identically instead of
     * silently wrapping — real MDict files stay far below 2^53 bytes.
     */
    fun beUInt64(bytes: ByteArray, offset: Int): Long {
        if ((bytes[offset + 1].toInt() and 0xFF) >= 0x20 || (bytes[offset].toInt() and 0xFF) > 0) {
            throw MdictFormatException("uint64 larger than 2^53 at offset $offset, value may lose precision")
        }
        var high = 0L
        for (i in 0 until 3) {
            high = high or (bytes[offset + i].toLong() and 0xFFL)
            high = high shl 8
        }
        high = high or (bytes[offset + 3].toLong() and 0xFFL)
        high = (high and 0x001FFFFFL) * 0x100000000L
        high += (bytes[offset + 4].toLong() and 0xFFL) * 0x1000000L
        high += (bytes[offset + 5].toLong() and 0xFFL) * 0x10000L
        high += (bytes[offset + 6].toLong() and 0xFFL) * 0x100L
        high += bytes[offset + 7].toLong() and 0xFFL
        return high
    }

    /** Little-endian unsigned 32 bit — used for the 4-byte block compression type. */
    fun leUInt32(bytes: ByteArray, offset: Int): Long {
        var n = 0L
        for (i in 3 downTo 0) {
            n = (n shl 8) or (bytes[offset + i].toLong() and 0xFFL)
        }
        return n
    }

    /** `utils.js:216` (`b2n`) — read a `width`-byte big-endian number. */
    fun b2n(bytes: ByteArray, offset: Int, width: Int): Long = when (width) {
        1 -> beUInt8(bytes, offset)
        2 -> beUInt16(bytes, offset)
        4 -> beUInt32(bytes, offset)
        8 -> beUInt64(bytes, offset)
        else -> throw MdictFormatException("unsupported integer width $width")
    }
}

/** Raised for every structural violation the reference expresses with `assert`. */
class MdictFormatException(message: String) : RuntimeException(message)
