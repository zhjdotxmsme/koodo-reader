package com.koodoreader.engine.text

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Encoding detection + decoding + normalisation.
 *
 * Every sample is built programmatically with `String.toByteArray(charset)` so
 * the tests do not depend on any binary fixture, and each legacy sample is
 * checked to be *invalid* UTF-8 — otherwise the strict-UTF-8 fast path would
 * short-circuit the heuristic under test.
 */
class CharsetDetectorTest {

    // `Charsets` in this module is our own name table, so the JDK charsets need
    // to be reached through the fully-qualified `StandardCharsets` class.
    private val ascii = java.nio.charset.StandardCharsets.US_ASCII
    private val utf8 = java.nio.charset.StandardCharsets.UTF_8

    // ------------------------------------------------------------- helpers ---

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun enc(text: String, charset: String): ByteArray {
        val cs = java.nio.charset.Charset.forName(charset)
        val result = text.toByteArray(cs)
        // Round-trip guard: fail loudly if the test data is not representable.
        assertEquals(text, String(result, cs), "sample not representable in $charset")
        return result
    }

    private fun assertDetected(expected: String, sample: ByteArray, minConfidence: Double = 0.5) {
        val guess = CharsetDetector.detect(sample)
        assertEquals(expected, guess.charset, "guess=$guess reasons=${guess.reasons}")
        assertTrue(
            guess.confidence >= minConfidence,
            "confidence ${guess.confidence} < $minConfidence for $guess (${guess.reasons})",
        )
    }

    /** The heuristic only runs when the sample is not well-formed UTF-8. */
    private fun assertNotValidUtf8(sample: ByteArray) {
        assertTrue(
            !Utf8.isValid(sample),
            "fixture is valid UTF-8, so it would take the strict-UTF-8 path",
        )
    }

    private val simplified = "第一章　风起云涌\n" +
        "这是一个关于少年与江湖的故事，故事发生在很久以前的中原大地，人们过着平静的生活。\n" +
        "他从小就很聪明，喜欢读书和练剑，师门上下都很喜欢这个孩子，说他将来必有出息。\n" +
        "那一天，他在山中遇到了一个受伤的老人，老人把一件东西交给了他，从此一切都变了。\n" +
        "江湖上的人都说，那件东西关系到整个武林的命运，很多人为了它丢了性命。\n"

    private val traditional = "第一章　風起雲湧\n" +
        "這是一個關於少年與江湖的故事，故事發生在很久以前的中原大地，人們過著平靜的生活。\n" +
        "他從小就很聰明，喜歡讀書和練劍，師門上下都很喜歡這個孩子，說他將來必有出息。\n" +
        "那一天，他在山中遇到了一個受傷的老人，老人把一件東西交給了他，從此一切都變了。\n" +
        "江湖上的人都說，那件東西關係到整個武林的命運，很多人為了它丟了性命。\n"

    private val japanese = "第一章　風の谷\n" +
        "むかしむかし、あるところに、おじいさんとおばあさんがすんでいました。\n" +
        "ふたりはとてもなかよく、まいにち たのしくくらしていました。\n" +
        "あるひ、おじいさんは やまへ しばかりに、おばあさんは かわへ せんたくにいきました。\n" +
        "これはにほんごのぶんしょうです。かたかなもまじっています。\n"

    private val korean = "第一章 이야기\n" +
        "옛날 옛적에 한 마을에 착한 나무꾼이 살았습니다. 그는 매일 산에 가서 나무를 했습니다.\n" +
        "어느 날 나무꾼은 숲 속에서 다친 사슴 한 마리를 만났습니다. 사슴은 그에게 말을 걸었습니다.\n" +
        "이것은 한국어로 쓴 글입니다. 사람들은 모두 그 이야기를 좋아했습니다.\n"

    // --------------------------------------------------------------- BOM ----

    @Test
    @DisplayName("UTF-8 BOM is detected authoritatively and stripped on decode")
    fun utf8Bom() {
        val sample = bytes(0xEF, 0xBB, 0xBF) + "你好，世界\n第二章\n".toByteArray(utf8)
        val guess = CharsetDetector.detect(sample)
        assertEquals(Charsets.UTF_8, guess.charset)
        assertEquals(3, guess.bomLength)
        assertTrue(guess.confidence >= 0.95)
        val result = TextDecoder.decodeResult(sample)
        assertEquals("你好，世界\n第二章\n", result.text)
        assertTrue(result.clean)
    }

    @Test
    @DisplayName("UTF-16LE BOM is detected and decoded to the original text")
    fun utf16LeBom() {
        val text = "中文示例 text 123\n"
        val sample = enc("\uFEFF$text", "UTF-16LE")
        assertDetected(Charsets.UTF_16_LE, sample, 0.9)
        assertEquals(text, TextDecoder.decode(sample))
    }

    @Test
    @DisplayName("UTF-16BE BOM is detected and decoded to the original text")
    fun utf16BeBom() {
        val text = "中文示例 text 456\n"
        val sample = enc("\uFEFF$text", "UTF-16BE")
        assertDetected(Charsets.UTF_16_BE, sample, 0.9)
        assertEquals(text, TextDecoder.decode(sample))
    }

    @Test
    @DisplayName("UTF-32LE/BE BOMs are detected (optional requirement)")
    fun utf32Bom() {
        val text = "abc中文"
        // java.nio's UTF-32 encoder treats a leading U+FEFF as a byte-order mark
        // and does NOT emit it, so the BOM is prepended explicitly here.
        val le = bytes(0xFF, 0xFE, 0x00, 0x00) + text.toByteArray(java.nio.charset.Charset.forName("UTF-32LE"))
        val be = bytes(0x00, 0x00, 0xFE, 0xFF) + text.toByteArray(java.nio.charset.Charset.forName("UTF-32BE"))
        assertDetected(Charsets.UTF_32_LE, le, 0.9)
        assertDetected(Charsets.UTF_32_BE, be, 0.9)
        assertEquals(text, TextDecoder.decode(le))
        assertEquals(text, TextDecoder.decode(be))
    }

    // -------------------------------------------------------------- UTF-8 ----

    @Test
    @DisplayName("Valid UTF-8 text without BOM is detected as UTF-8")
    fun plainUtf8() {
        val sample = "第一章 风起\n他走进屋子，看见桌上放着一封信。\n".toByteArray(utf8)
        assertDetected(Charsets.UTF_8, sample, 0.9)
        assertEquals(
            "第一章 风起\n他走进屋子，看见桌上放着一封信。\n",
            TextDecoder.decode(sample),
        )
    }

    @Test
    @DisplayName("ASCII-only text is reported as US-ASCII with high confidence")
    fun asciiOnly() {
        val sample = "Chapter 1\nThe quick brown fox jumps over the lazy dog.\n".toByteArray(ascii)
        val guess = CharsetDetector.detect(sample)
        assertEquals(Charsets.ASCII, guess.charset)
        assertTrue(guess.confidence >= 0.9, "confidence=${guess.confidence}")
    }

    @Test
    @DisplayName("Empty input falls back to UTF-8 instead of throwing")
    fun emptyInput() {
        val guess = CharsetDetector.detect(ByteArray(0))
        assertEquals(Charsets.UTF_8, guess.charset)
        assertEquals("", TextDecoder.decode(ByteArray(0)))
    }

    @Test
    @DisplayName("Strict UTF-8 validation rejects overlong forms, surrogates and 0xFF")
    fun utf8Validation() {
        // Overlong '/' encoded in two bytes.
        assertTrue(!Utf8.isValid(bytes(0xC0, 0xAF)))
        // UTF-16 surrogate D800 encoded as UTF-8 (ED A0 80).
        assertTrue(!Utf8.isValid(bytes(0xED, 0xA0, 0x80)))
        // 0xFF is never legal in UTF-8.
        assertTrue(!Utf8.isValid(bytes(0x48, 0x69, 0xFF, 0x21)))
        // 4-byte sequence above U+10FFFF (F4 90 80 80).
        assertTrue(!Utf8.isValid(bytes(0xF4, 0x90, 0x80, 0x80)))
        // A well-formed 4-byte sequence (U+1F600) is accepted.
        assertTrue(Utf8.isValid(bytes(0xF0, 0x9F, 0x98, 0x80)))
        val v = Utf8.validate(bytes(0x48, 0x69, 0xFF, 0x21))
        assertEquals(2, v.errorOffset)
        assertEquals(2, v.codePoints)
    }

    @Test
    @DisplayName("Invalid UTF-8 bytes decode to U+FFFD rather than throwing")
    fun invalidUtf8Degrades() {
        // 41 | FF (stray) | 42 | C3 28 (truncated 2-byte lead, 0x28 is not a
        // continuation byte) | 43 → one U+FFFD per malformed byte.
        val sample = bytes(0x41, 0xFF, 0x42, 0xC3, 0x28, 0x43)
        val decoded = TextDecoder.decode(sample)
        assertEquals(6, decoded.length, decoded.map { "U+%04X".format(it.code) }.toString())
        assertEquals("A\uFFFDB\uFFFD(C", decoded)
        assertEquals(
            listOf(0x41, 0xFFFD, 0x42, 0xFFFD, 0x28, 0x43),
            decoded.map { it.code },
        )
        assertEquals(2, TextDecoder.decodeResult(sample).replacementCount)
    }

    // ------------------------------------------------------------ legacy ----

    @Test
    @DisplayName("GBK simplified-Chinese sample is detected as GBK")
    fun gbkSample() {
        val sample = enc(simplified, "GBK")
        assertNotValidUtf8(sample)
        assertDetected(Charsets.GBK, sample, 0.55)
        assertEquals(simplified, TextDecoder.decode(sample, CharsetDetector.detect(sample)))
    }

    @Test
    @DisplayName("Big5 traditional-Chinese sample is detected as Big5")
    fun big5Sample() {
        val sample = enc(traditional, "Big5")
        assertNotValidUtf8(sample)
        assertDetected(Charsets.BIG5, sample, 0.55)
        assertEquals(traditional, TextDecoder.decode(sample, CharsetDetector.detect(sample)))
    }

    @Test
    @DisplayName("Shift_JIS kana-heavy sample is detected as Shift_JIS")
    fun shiftJisSample() {
        val sample = enc(japanese, "Shift_JIS")
        assertNotValidUtf8(sample)
        assertDetected(Charsets.SHIFT_JIS, sample, 0.55)
        assertEquals(japanese, TextDecoder.decode(sample, CharsetDetector.detect(sample)))
    }

    @Test
    @DisplayName("EUC-KR hangul sample is detected as EUC-KR")
    fun eucKrSample() {
        val sample = enc(korean, "EUC-KR")
        assertNotValidUtf8(sample)
        assertDetected(Charsets.EUC_KR, sample, 0.45)
        assertEquals(korean, TextDecoder.decode(sample, CharsetDetector.detect(sample)))
    }

    @Test
    @DisplayName("Detection is scoped to the probe window like the desktop's 4 KiB chunk")
    fun probeWindowIsRespected() {
        val prefix = enc("第一章 简体中文\n他走进屋子，看见桌上放着一封信。\n", "GBK")
        // Pure-ASCII tail: it never changes the verdict, and must not be read.
        val sample = prefix + "Chapter 99 ascii tail\n".toByteArray(ascii)
        val guess = CharsetDetector.detect(sample, probeBytes = prefix.size)
        assertEquals(Charsets.GBK, guess.charset, "guess=$guess")
    }

    @Test
    @DisplayName("Unknown charset names fall back to UTF-8 instead of throwing")
    fun unknownCharsetName() {
        val sample = "hello world".toByteArray(utf8)
        assertEquals("hello world", TextDecoder.decodeWith("no-such-charset", sample))
        assertEquals("hello world", TextDecoder.decode(sample, CharsetGuess("x-mac-roman", 0.9)))
    }

    @Test
    @DisplayName("Sample shorter than the statistics floor never claims a legacy verdict")
    fun tooShortForStatistics() {
        val guess = CharsetDetector.detect(bytes(0xB5, 0xDA))
        assertTrue(
            guess.charset == Charsets.UTF_8 || guess.confidence < CharsetDetector.MIN_LEGACY_CONFIDENCE,
            "guess=$guess",
        )
    }

    @Test
    @DisplayName("A BOM overrides a conflicting guess")
    fun bomOverridesGuess() {
        val text = "中文"
        val sample = enc("\uFEFF$text", "UTF-16LE")
        val result = TextDecoder.decodeResult(sample, CharsetGuess(Charsets.GBK, 0.9))
        assertEquals(text, result.text)
        assertEquals(Charsets.UTF_16_LE, result.charsetUsed)
        assertTrue(result.bomOverrodeGuess)
    }

    // ------------------------------------------------------- normalisation ---

    @Test
    @DisplayName("CRLF / CR / LF are all normalised to LF")
    fun lineEndingsUnified() {
        val input = "a\r\nb\rc\nd"
        assertEquals("a\nb\nc\nd", TextNormalizer.normalize(input))
        // Disabled: mixed endings survive untouched.
        assertEquals(
            input,
            TextNormalizer.normalize(input, TextNormalizer.Options(unifyLineEndings = false)),
        )
    }

    @Test
    @DisplayName("Leading BOMs (including doubled ones) are removed")
    fun bomStripped() {
        assertEquals("abc", TextNormalizer.normalize("\uFEFFabc"))
        assertEquals("abc", TextNormalizer.normalize("\uFEFF\uFEFFabc"))
        // Only *leading* BOMs: a ZWNBSP mid-text is a different concern.
        assertEquals("a\uFEFFb", TextNormalizer.normalize("a\uFEFFb", TextNormalizer.Options(stripZeroWidth = false)))
        assertEquals("ab", TextNormalizer.normalize("a\uFEFFb"))
    }

    @Test
    @DisplayName("Control characters are removed, tab/newline kept")
    fun controlCharsRemoved() {
        val input = "a\u0000b\u0007c\u001Fd\u007Fe\tf\ng"
        assertEquals("abcde\tf\ng", TextNormalizer.normalize(input))
    }

    @Test
    @DisplayName("Optional whitespace collapsing folds spaces and blank-line runs")
    fun whitespaceCollapsing() {
        val opts = TextNormalizer.Options(collapseSpaces = true, collapseBlankLines = true)
        assertEquals("a b", TextNormalizer.normalize("a     b", opts))
        assertEquals("a\n\nb", TextNormalizer.normalize("a\n\n\n\n\nb", opts))
        assertEquals("a\n\nb", TextNormalizer.normalize("a\n   \n\nb", opts))
        // Off by default: a reader's paragraph spacing must not be invented.
        assertEquals("a     b", TextNormalizer.normalize("a     b"))
    }

    @Test
    @DisplayName("Exotic spaces (NBSP, ideographic space) become U+0020; zero-width are dropped")
    fun exoticSpacesAndZeroWidth() {
        assertEquals("a b", TextNormalizer.normalize("a\u00A0b"))
        assertEquals("a b", TextNormalizer.normalize("a\u3000b"))
        assertEquals("ab", TextNormalizer.normalize("a\u200Bb"))
        assertEquals("ab", TextNormalizer.normalize("a\uFEFFb", TextNormalizer.Options(stripBom = false)))
    }

    @Test
    @DisplayName("cleanText matches the desktop textProcessor implementation")
    fun cleanTextParity() {
        assertEquals("第一章", TextNormalizer.cleanText("  第一章  "))
        assertEquals("abc", TextNormalizer.cleanText("a\tb\nc"))
        assertEquals("title", TextNormalizer.cleanText("==title--__++"))
        assertEquals(100, TextNormalizer.cleanText("x".repeat(150)).length)
    }
}
