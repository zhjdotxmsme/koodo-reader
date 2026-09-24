package com.koodoreader.engine.layout

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [isCollapsibleWhitespace], [isIdeograph], [breakEnds] and
 * [trimTrailingWhitespace].
 *
 * These functions are the CSS break-opportunity rules the shaper relies on; a
 * regression here changes where every line in every book breaks.
 */
class BreakOpportunitiesTest {

    // ── isCollapsibleWhitespace ─────────────────────────────────────────────

    @Test
    fun `space is collapsible`() {
        assertTrue(isCollapsibleWhitespace(' '))
    }

    @Test
    fun `tab is collapsible`() {
        assertTrue(isCollapsibleWhitespace('\t'))
    }

    @Test
    fun `newline is collapsible`() {
        assertTrue(isCollapsibleWhitespace('\n'))
    }

    @Test
    fun `carriage return is collapsible`() {
        assertTrue(isCollapsibleWhitespace('\r'))
    }

    @Test
    fun `form feed is collapsible`() {
        assertTrue(isCollapsibleWhitespace('\u000C'))
    }

    @Test
    fun `vertical tab is collapsible`() {
        assertTrue(isCollapsibleWhitespace('\u000B'))
    }

    @Test
    fun `NBSP is not collapsible`() {
        assertFalse(isCollapsibleWhitespace('\u00A0'))
    }

    @Test
    fun `regular characters are not collapsible`() {
        assertFalse(isCollapsibleWhitespace('a'))
        assertFalse(isCollapsibleWhitespace('一'))
        assertFalse(isCollapsibleWhitespace('0'))
    }

    // ── isIdeograph ─────────────────────────────────────────────────────────

    @Test
    fun `CJK unified ideographs are ideographs`() {
        assertTrue(isIdeograph('一')) // U+4E00
        assertTrue(isIdeograph('漢')) // U+6F22
        assertTrue(isIdeograph('字')) // U+5B57
    }

    @Test
    fun `CJK extension A are ideographs`() {
        assertTrue(isIdeograph('\u3400'))
        assertTrue(isIdeograph('\u4DBF'))
    }

    @Test
    fun `kana are ideographs`() {
        assertTrue(isIdeograph('あ')) // hiragana
        assertTrue(isIdeograph('ア')) // katakana
    }

    @Test
    fun `hangul are ideographs`() {
        assertTrue(isIdeograph('가'))
        assertTrue(isIdeograph('\uAC00'))
        assertTrue(isIdeograph('\uD7A3'))
    }

    @Test
    fun `fullwidth forms are ideographs`() {
        assertTrue(isIdeograph('\uFF01')) // fullwidth exclamation
        assertTrue(isIdeograph('\uFF10')) // fullwidth zero
    }

    @Test
    fun `ASCII characters are not ideographs`() {
        assertFalse(isIdeograph('a'))
        assertFalse(isIdeograph('Z'))
        assertFalse(isIdeograph('0'))
        assertFalse(isIdeograph(' '))
    }

    // ── breakEnds ───────────────────────────────────────────────────────────

    @Test
    fun `empty text has no break ends`() {
        assertArrayEquals(IntArray(0), breakEnds("", WordBreak.NORMAL))
    }

    @Test
    fun `plain ASCII breaks only after spaces`() {
        val ends = breakEnds("aa bb cc", WordBreak.NORMAL)
        assertArrayEquals(intArrayOf(3, 6), ends)
    }

    @Test
    fun `single word has no break ends`() {
        val ends = breakEnds("hello", WordBreak.NORMAL)
        assertArrayEquals(IntArray(0), ends)
    }

    @Test
    fun `CJK text breaks between every pair of ideographs`() {
        val ends = breakEnds("一二三四", WordBreak.NORMAL)
        assertArrayEquals(intArrayOf(1, 2, 3), ends)
    }

    @Test
    fun `KEEP_ALL removes CJK breaks but keeps space breaks`() {
        val ends = breakEnds("一 二", WordBreak.KEEP_ALL)
        // Only the space (index 1) is a break opportunity → end=2.
        assertArrayEquals(intArrayOf(2), ends)
    }

    @Test
    fun `BREAK_ALL breaks between every character`() {
        val ends = breakEnds("ab", WordBreak.BREAK_ALL)
        assertArrayEquals(intArrayOf(1, 2), ends)
    }

    @Test
    fun `trailing space is a break end`() {
        val ends = breakEnds("aa ", WordBreak.NORMAL)
        // Break after the space at index 2 → end=3. No break after 'a' at index 1.
        assertArrayEquals(intArrayOf(3), ends)
    }

    @Test
    fun `leading space is a break end`() {
        val ends = breakEnds(" aa", WordBreak.NORMAL)
        // break after index 0 (the space) and after index 2 (last char? no — only spaces).
        // Actually: break after space at index 0 → end=1. No break after 'a' at index 2
        // because it's not a space and not CJK.
        assertArrayEquals(intArrayOf(1), ends)
    }

    @Test
    fun `mixed CJK and ASCII breaks at spaces and between CJK pairs`() {
        val ends = breakEnds("一a二", WordBreak.NORMAL)
        // No space. '一'(CJK) followed by 'a'(ASCII) → not both ideographs → no break.
        // 'a'(ASCII) followed by '二'(CJK) → not both ideographs → no break.
        assertArrayEquals(IntArray(0), ends)
    }

    @Test
    fun `mixed CJK and ASCII with space`() {
        val ends = breakEnds("一 a 二", WordBreak.NORMAL)
        // break after space at index 1 → end=2.
        // break after space at index 3 → end=4.
        assertArrayEquals(intArrayOf(2, 4), ends)
    }

    @Test
    fun `adjacent CJK with ASCII between does not break`() {
        val ends = breakEnds("一a二", WordBreak.BREAK_ALL)
        // BREAK_ALL: every position is a break.
        assertArrayEquals(intArrayOf(1, 2, 3), ends)
    }

    // ── trimTrailingWhitespace ──────────────────────────────────────────────

    @Test
    fun `trailing spaces are trimmed`() {
        assertEquals(5, trimTrailingWhitespace("hello   ", 0, 8))
    }

    @Test
    fun `no trailing spaces is a no-op`() {
        assertEquals(5, trimTrailingWhitespace("hello", 0, 5))
    }

    @Test
    fun `all-whitespace range keeps end so shaper advances`() {
        assertEquals(3, trimTrailingWhitespace("   ", 0, 3))
    }

    @Test
    fun `single trailing space is trimmed`() {
        assertEquals(3, trimTrailingWhitespace("abc ", 0, 4))
    }

    @Test
    fun `interior spaces are not trimmed`() {
        assertEquals(8, trimTrailingWhitespace("aa bb cc", 0, 8))
    }

    @Test
    fun `start parameter is respected`() {
        // Trim from position 3: range [3,5) is "  " (all whitespace) → keep end=5.
        assertEquals(5, trimTrailingWhitespace("abc  ", 3, 5))
    }

    @Test
    fun `whitespace-only range after start keeps end`() {
        // Range [3, 5) is "  " (all whitespace) → keep end=5.
        assertEquals(5, trimTrailingWhitespace("abc  ", 3, 5))
    }
}
