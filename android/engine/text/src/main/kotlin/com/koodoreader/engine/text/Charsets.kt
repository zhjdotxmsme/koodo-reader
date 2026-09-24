package com.koodoreader.engine.text

/**
 * Charset names, BOM tables and the byte ranges each legacy East-Asian encoding
 * is allowed to use.
 *
 * NATIVE PORT / REPLACEMENT of the desktop's `chardet` dependency
 * (`TxtRender.getMetadata()` in kookit calls `chardet.detect()` on the first
 * 4 KiB of the file; see `docs/android-native-migration.md` P5). The desktop
 * value is fed straight into `new TextDecoder(charset)`, and `Book.charset` is
 * persisted, so the *names* produced here must stay compatible with what the
 * desktop stored in the library database — hence the IANA-style names below
 * (`'utf-8'`, `'gbk'`, `'big5'`, `'shift_jis'`, `'euc-kr'`), which the JDK
 * understands verbatim.
 */
object Charsets {

    /** Byte-order marks, longest first so UTF-32 wins over UTF-16. */
    object Bom {
        val UTF_8: ByteArray = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val UTF_16_LE: ByteArray = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val UTF_16_BE: ByteArray = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
        val UTF_32_LE: ByteArray = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x00)
        val UTF_32_BE: ByteArray = byteArrayOf(0x00, 0x00, 0xFE.toByte(), 0xFF.toByte())
    }

    /** Canonical (java.nio) names emitted by [CharsetDetector]. */
    const val UTF_8 = "UTF-8"
    const val UTF_16_LE = "UTF-16LE"
    const val UTF_16_BE = "UTF-16BE"
    const val UTF_32_LE = "UTF-32LE"
    const val UTF_32_BE = "UTF-32BE"
    const val ASCII = "US-ASCII"
    const val GBK = "GBK"
    const val BIG5 = "Big5"
    const val SHIFT_JIS = "Shift_JIS"
    const val EUC_KR = "EUC-KR"

    /** Reported when nothing else looks plausible; byte-identical to ASCII. */
    const val FALLBACK = ASCII

    /** Decoding artefacts that never belong in an ebook. */
    const val REPLACEMENT_CHAR = '\uFFFD'

    /**
     * Legacy double-byte encodings we can score heuristically. Pure single-byte
     * code pages (`windows-1252` …) are intentionally absent: the previous
     * desktop behaviour fell back to UTF-8 for those and we keep that.
     */
    enum class Legacy(val charsetName: String) {
        GBK(Charsets.GBK),
        BIG5(Charsets.BIG5),
        SHIFT_JIS(Charsets.SHIFT_JIS),
        EUC_KR(Charsets.EUC_KR),
    }

    /**
     * Structural byte ranges of one legacy encoding: a lead byte is valid if it
     * is in `leadRanges`, a trailing byte if it is in `trailRanges`. Ranges are
     * inclusive. Single-byte (ASCII / half-width katakana) bytes are legal in
     * every legacy encoding and are handled by the detectors directly.
     */
    class ByteLayout(
        val leadRanges: List<IntRange>,
        val trailRanges: List<IntRange>,
    ) {
        fun isLead(b: Int): Boolean = leadRanges.any { b in it }
        fun isTrail(b: Int): Boolean = trailRanges.any { b in it }
    }

    /** GBK (CP936) / GB18030 2-byte subset. */
    val GBK_LAYOUT = ByteLayout(
        leadRanges = listOf(0x81..0xFE),
        trailRanges = listOf(0x40..0x7E, 0x80..0xFE),
    )

    /** Big5 (CP950). Trail bytes exclude 0x7F and 0x80..0xA0. */
    val BIG5_LAYOUT = ByteLayout(
        leadRanges = listOf(0x81..0xFE),
        trailRanges = listOf(0x40..0x7E, 0xA1..0xFE),
    )

    /** Shift_JIS (CP932): kana block 0xA1..0xDF is a valid single byte. */
    val SHIFT_JIS_LAYOUT = ByteLayout(
        leadRanges = listOf(0x81..0x9F, 0xE0..0xFC),
        trailRanges = listOf(0x40..0x7E, 0x80..0xFC),
    )

    /** EUC-KR (CP949 is a superset; lead bytes start at 0x81). */
    val EUC_KR_LAYOUT = ByteLayout(
        leadRanges = listOf(0x81..0xFE),
        trailRanges = listOf(0x41..0x5A, 0x61..0x7A, 0x81..0xFE),
    )

    fun layoutOf(legacy: Legacy): ByteLayout = when (legacy) {
        Legacy.GBK -> GBK_LAYOUT
        Legacy.BIG5 -> BIG5_LAYOUT
        Legacy.SHIFT_JIS -> SHIFT_JIS_LAYOUT
        Legacy.EUC_KR -> EUC_KR_LAYOUT
    }

    /** Trailing bytes that carry no information (0x00 is padding in Big5/UTF-16). */
    fun isInformative(b: Int): Boolean = b != 0x00 && b != 0x0A && b != 0x0D && b != 0x20 && b != 0x09

    private val ASCII_LETTERS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

    fun isAsciiLetter(c: Char): Boolean = c in ASCII_LETTERS

    fun isAscii(b: Int): Boolean = b in 0x00..0x7F
}
