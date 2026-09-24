package com.koodoreader.engine.text

/**
 * Hand-rolled, strictly-conforming UTF-8 codec.
 *
 * Why not `java.nio.charset.StandardCharsets.UTF_8`?
 *  - `::newDecoder().onMalformedInput(REPLACE)` replaces a whole *sequence*
 *    with U+FFFD, whereas the WHATWG decoder used by the desktop
 *    (`TextDecoder`, which `TxtRender.parse()` calls) replaces *maximal
 *    subparts* — the two disagree on how many U+FFFD a truncated multi-byte
 *    sequence produces. Since chapter offsets are anchored on the decoded
 *    string, drift here would shift every page break;
 *  - implementing it locally keeps the strict-validation used by
 *    [CharsetDetector] (reject overlong forms, surrogates, > U+10FFFF) and the
 *    lenient decoding used by [TextDecoder] in one place, so detection and
 *    decoding can never disagree about what "valid UTF-8" means.
 *
 * Reference: WHATWG Encoding Standard §"UTF-8 decoder" (the same algorithm as
 * `TextDecoder`), which is what the web engine effectively relies on.
 */
object Utf8 {

    /** Result of a strict validation pass. */
    data class Validation(
        /** True when the whole input is well-formed UTF-8. */
        val valid: Boolean,
        /** Offset of the first offending byte, or -1 when [valid]. */
        val errorOffset: Int = -1,
        /** Human-readable reason for the first failure ("" when valid). */
        val reason: String = "",
        /** Number of decoded code points (cheap by-product of validation). */
        val codePoints: Int = 0,
        /** True when every code point is < 0x80. */
        val asciiOnly: Boolean = true,
    )

    /**
     * Strictly validates [bytes] against the UTF-8 grammar.
     *
     * This is the single most useful signal for ebook files: real-world TXT/MD
     * produced by modern tooling is UTF-8, and the old `chardet` shortcut
     * (`accept if the first 4 KiB decode without error`) is only sound because
     * GBK/Big5 streams almost always produce an invalid sequence early. We keep
     * that behaviour but make the check exact.
     */
    fun validate(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): Validation {
        var i = from
        var codePoints = 0
        var asciiOnly = true
        while (i < to) {
            val b0 = bytes[i].toInt() and 0xFF
            if (b0 < 0x80) {
                i++
                codePoints++
                continue
            }
            asciiOnly = false
            val need: Int
            var cp: Int
            var minCp: Int
            when {
                b0 in 0xC2..0xDF -> {
                    need = 1; cp = b0 and 0x1F; minCp = 0x80
                }
                b0 in 0xE0..0xEF -> {
                    need = 2; cp = b0 and 0x0F; minCp = 0x800
                }
                b0 in 0xF0..0xF4 -> {
                    need = 3; cp = b0 and 0x07; minCp = 0x10000
                }
                b0 in 0xC0..0xC1 -> return Validation(
                    false, i, "overlong 2-byte lead 0x%02X".format(b0), codePoints, asciiOnly
                )
                b0 in 0xF5..0xFF -> return Validation(
                    false, i, "invalid lead byte 0x%02X".format(b0), codePoints, asciiOnly
                )
                else -> return Validation(
                    false, i, "stray continuation byte 0x%02X".format(b0), codePoints, asciiOnly
                )
            }
            if (i + need >= to) {
                return Validation(
                    false, i, "truncated ${need + 1}-byte sequence", codePoints, asciiOnly
                )
            }
            for (k in 1..need) {
                val bk = bytes[i + k].toInt() and 0xFF
                if (bk !in 0x80..0xBF) {
                    return Validation(
                        false, i + k, "bad continuation byte 0x%02X".format(bk), codePoints, asciiOnly
                    )
                }
                cp = (cp shl 6) or (bk and 0x3F)
            }
            if (cp < minCp) {
                return Validation(false, i, "overlong encoding", codePoints, asciiOnly)
            }
            if (cp in 0xD800..0xDFFF) {
                return Validation(false, i, "UTF-16 surrogate in UTF-8", codePoints, asciiOnly)
            }
            if (cp > 0x10FFFF) {
                return Validation(false, i, "code point above U+10FFFF", codePoints, asciiOnly)
            }
            i += need + 1
            codePoints++
        }
        return Validation(true, -1, "", codePoints, asciiOnly)
    }

    /** Convenience: is [bytes] well-formed UTF-8? */
    fun isValid(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): Boolean =
        validate(bytes, from, to).valid

    /**
     * Decodes with WHATWG maximal-subpart replacement: every malformed byte is
     * emitted as U+FFFD, keeping the decoder in sync with [validate] so the two
     * never disagree. Never throws.
     */
    fun decode(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): String {
        val out = StringBuilder(to - from)
        var i = from
        while (i < to) {
            val b0 = bytes[i].toInt() and 0xFF
            if (b0 < 0x80) {
                out.append(b0.toChar())
                i++
                continue
            }
            val need: Int
            var cp: Int
            val minCp: Int
            when {
                b0 in 0xC2..0xDF -> {
                    need = 1; cp = b0 and 0x1F; minCp = 0x80
                }
                b0 in 0xE0..0xEF -> {
                    need = 2; cp = b0 and 0x0F; minCp = 0x800
                }
                b0 in 0xF0..0xF4 -> {
                    need = 3; cp = b0 and 0x07; minCp = 0x10000
                }
                else -> {
                    out.append(Charsets.REPLACEMENT_CHAR)
                    i++
                    continue
                }
            }
            // A continuation byte that is not 0x80..0xBF means the sequence is
            // truncated here: emit one U+FFFD and re-examine that byte next.
            var ok = true
            for (k in 1..need) {
                if (i + k >= to) {
                    ok = false; break
                }
                val bk = bytes[i + k].toInt() and 0xFF
                if (bk !in 0x80..0xBF) {
                    ok = false; break
                }
                cp = (cp shl 6) or (bk and 0x3F)
            }
            if (ok && cp in minCp..0x10FFFF && cp !in 0xD800..0xDFFF) {
                out.appendCodePoint(cp)
                i += need + 1
            } else {
                out.append(Charsets.REPLACEMENT_CHAR)
                i++
            }
        }
        return out.toString()
    }

    /** Encodes [s] as UTF-8 (used by the tests and by BOM-tolerant writers). */
    fun encode(s: String): ByteArray =
        s.toByteArray(java.nio.charset.StandardCharsets.UTF_8)
}
