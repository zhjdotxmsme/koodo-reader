package com.koodoreader.engine.text

/**
 * Decodes a byte window of a [TextSource] into a `String` for a given
 * [java.nio.charset.Charset].
 *
 * ## Multi-byte boundary handling
 *
 * A windowed read can land in the middle of a multi-byte
 * character (e.g. a 3-byte UTF-8 sequence split across two 64 KiB
 * windows, or a GBK 2-byte pair split the same way). This class is
 * deliberately simple: `decodeWindow` decodes whatever bytes the
 * caller asks for. The scanning caller ([StreamingTextIndex]) is
 * responsible for making the window *safe*:
 *
 *  - For **UTF-8** it uses [Utf8] to find the first *complete*
 *    sequence and trims the partial tail before decoding (the
 *    partial bytes are re-issued at the start of the next window —
 *    the [Utf8.validate] check over the whole source still applies).
 *  - For **legacy CJK** encodings (GBK / Big5 / Shift_JIS / EUC-KR)
 *    the boundary is always "an even index within a pair run"; the
 *    scanner keeps the `lastByte` carry and splices it in front of
 *    the next window's bytes before decoding.
 *  - For **UTF-16** / **UTF-32** there is no partial-sequence
 *    problem at a 4-byte boundary (surrogates are 2 code units in
 *    UTF-16; a 4-byte-aligned window never splits a BMP code unit,
 *    and the [StreamingTextIndex] always reads an even number of
 *    bytes).
 *
 * This class is *not* a general-purpose incremental decoder; it is
 * the minimal "give me the text in this byte range" primitive that
 * [TextChapterLoader] needs.
 */
class WindowedTextDecoder(
    val charset: java.nio.charset.Charset,
) {
    /**
     * Decodes `[start, end)` bytes of [source] into a `String` under
     * [charset]. Returns an empty string when `start == end`.
     *
     * Throws when `start < 0` / `end > source.length` / `end < start` —
     * the caller is responsible for passing a valid, in-range window
     * (see the boundary handling in the class docs).
     */
    fun decodeWindow(
        source: TextSource,
        start: Long,
        end: Long,
    ): String {
        require(start >= 0) { "start=$start must be >= 0" }
        require(end >= start) { "end=$end < start=$start" }
        require(end <= source.length) { "end=$end exceeds source.length=${source.length}" }
        if (start == end) return ""
        val bytes = source.read(start, (end - start).toInt())
        return bytes.toString(charset)
    }
}

/**
 * Detects the byte range of a *complete* UTF-8 multi-byte sequence
 * starting at [offset] — returns the end exclusive, or [offset] when
 * the byte at [offset] is ASCII (a 1-byte sequence, end = offset + 1).
 *
 * Used by [StreamingTextIndex] to avoid splitting a multi-byte UTF-8
 * sequence across a 64 KiB window boundary. A trailing partial
 * sequence is carried over to the next window by *not* advancing the
 * offset past it on the current scan.
 */
object Utf8WindowHelper {
    /**
     * @return exclusive end of the UTF-8 sequence starting at [offset],
     *   or [offset] when the sample is empty.
     */
    fun sequenceEnd(sample: ByteArray, offset: Int): Int {
        if (offset >= sample.size) return sample.size
        val b0 = sample[offset].toInt() and 0xFF
        return when {
            b0 < 0x80 -> offset + 1
            b0 in 0xC2..0xDF -> offset + 2
            b0 in 0xE0..0xEF -> offset + 3
            b0 in 0xF0..0xF4 -> offset + 4
            else -> offset + 1 // malformed: emit a single U+FFFD and move on
        }
    }
}
