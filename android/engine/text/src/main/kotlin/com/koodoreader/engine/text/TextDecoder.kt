package com.koodoreader.engine.text

/**
 * Turns raw bytes into a [String], replacing anything undecodable with U+FFFD.
 *
 * Mirrors the desktop's `TxtRender.parse()`
 * (`new TextDecoder(charset).decode(bytes)`) but never throws: `TextDecoder`
 * with a bogus label throws a `RangeError`, and the desktop has to wrap the
 * whole parse in try/catch. Here an unusable/unknown charset name degrades to
 * UTF-8 with replacement instead, so a corrupt `Book.charset` inherited from the
 * library database cannot take the reader down.
 */
object TextDecoder {

    /** Result of a decode, including what had to be patched up. */
    data class Result(
        val text: String,
        /** Charset actually used (after BOM/override resolution). */
        val charsetUsed: String,
        /** True when the BOM contradicted the requested [CharsetGuess]. */
        val bomOverrodeGuess: Boolean,
        /** Number of U+FFFD in [text]. */
        val replacementCount: Int,
    ) {
        /** True when the text was produced without any lossy replacement. */
        val clean: Boolean get() = replacementCount == 0
    }

    /**
     * Decodes [bytes], using [guess] when supplied.
     *
     * Rules, in order:
     *  1. a BOM always wins over [guess] (the bytes are self-describing, and the
     *     desktop's chardet does the same);
     *  2. otherwise [guess.charset] is used;
     *  3. with no guess at all, [CharsetDetector] is run first.
     *
     * The BOM bytes themselves are removed from the output, which is what
     * `TextDecoder` does for UTF-8/UTF-16/UTF-32 alike.
     */
    fun decode(bytes: ByteArray, guess: CharsetGuess? = null): String =
        decodeResult(bytes, guess).text

    /** As [decode], but returns the decoding diagnostics as well. */
    fun decodeResult(bytes: ByteArray, guess: CharsetGuess? = null): Result {
        if (bytes.isEmpty()) {
            return Result("", guess?.charset ?: Charsets.UTF_8, false, 0)
        }
        val bom = BomInfo.of(bytes)
        if (bom != null) {
            val text = bom.decode(bytes)
            val overrode = guess != null &&
                !guess.charset.equals(bom.charset, ignoreCase = true) &&
                !(bom.charset == Charsets.UTF_8 && guess.charset == Charsets.ASCII)
            return Result(text, bom.charset, overrode, text.count { it == Charsets.REPLACEMENT_CHAR })
        }

        val resolved = guess ?: CharsetDetector.detect(bytes)
        val text = decodeWith(resolved.charset, bytes, skip = resolved.bomLength)
        return Result(text, resolved.charset, false, text.count { it == Charsets.REPLACEMENT_CHAR })
    }

    /**
     * Decodes with an explicit charset name. `UTF-8` goes through our own
     * maximal-subpart decoder ([Utf8.decode]) so it stays bit-identical with
     * detection; everything else uses the JDK decoder in REPLACE mode.
     */
    fun decodeWith(charsetName: String, bytes: ByteArray, skip: Int = 0): String {
        val body = if (skip <= 0) bytes else bytes.copyOfRange(skip, bytes.size)
        if (body.isEmpty()) return ""
        if (isUtf8(charsetName)) return Utf8.decode(body)
        val charset = runCatching { java.nio.charset.Charset.forName(charsetName) }.getOrNull()
            ?: return Utf8.decode(bytes) // unknown label: same fallback as chardet's null
        val decoder = charset.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
        return runCatching { decoder.decode(java.nio.ByteBuffer.wrap(body)).toString() }
            .getOrElse { Utf8.decode(body) }
    }

    private fun isUtf8(name: String): Boolean {
        val n = name.replace("_", "-").lowercase()
        return n == "utf-8" || n == "utf8"
    }

    /** BOM sniffing + BOM-aware decoding, kept separate for testability. */
    internal object BomInfo {
        class Info(val charset: String, val length: Int) {
            fun decode(bytes: ByteArray): String = TextDecoder.decodeWith(charset, bytes, length)
        }

        fun of(bytes: ByteArray): Info? {
            fun starts(bom: ByteArray): Boolean {
                if (bytes.size < bom.size) return false
                for (i in bom.indices) if (bytes[i] != bom[i]) return false
                return true
            }
            if (starts(Charsets.Bom.UTF_32_LE)) return Info(Charsets.UTF_32_LE, 4)
            if (starts(Charsets.Bom.UTF_32_BE)) return Info(Charsets.UTF_32_BE, 4)
            if (starts(Charsets.Bom.UTF_8)) return Info(Charsets.UTF_8, 3)
            if (starts(Charsets.Bom.UTF_16_LE)) return Info(Charsets.UTF_16_LE, 2)
            if (starts(Charsets.Bom.UTF_16_BE)) return Info(Charsets.UTF_16_BE, 2)
            return null
        }
    }
}
