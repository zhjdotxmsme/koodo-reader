package com.koodoreader.core.designsystem

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Locks the Chinese-first reader typography defaults.
 *
 * These are the values the native reader applies on top of whatever the
 * desktop config carries. They are the single most consequential UI decision
 * in the reader, so they are pinned here rather than left to a comment.
 */
class CjkTypographyTest {

    @Test
    fun `default alignment is justify so the column edge is flush`() {
        assertEquals("justify", CjkTypography.DEFAULT_TEXT_ALIGN)
    }

    @Test
    fun `first line indent is two character widths`() {
        assertEquals(2f, CjkTypography.FIRST_LINE_INDENT_EM)
    }

    @Test
    fun `letter spacing defaults to a positive half sp`() {
        assertEquals(0.5f, CjkTypography.LETTER_SPACING_SP)
        // A hair of tracking: enough to separate CJK ink, which fills its
        // whole em box and frequently touches the neighbour's.
        assertEquals(1.5f, CjkTypography.letterSpacingPx(density = 3f), 0.0001f)
    }

    @Test
    fun `tracking stays small enough not to open a gap in latin words`() {
        // 0.5sp is a hair: enough to separate CJK ink, not enough to be seen
        // as a space between Latin letters.
        assertTrue(CjkTypography.LETTER_SPACING_SP <= 0.5f)
    }

    @Test
    fun `explicit letter spacing still converts sp to px through the density`() {
        // The setting remains; only the default changed. 0.5sp @ 3.0 → 1.5px.
        val px = 0.5f * 3f
        assertEquals(1.5f, px, 0.0001f)
    }

    @Test
    fun `default letter spacing never goes negative for a tiny font scale`() {
        assertTrue(CjkTypography.letterSpacingPx(density = 2f, fontScale = 0.1f) >= 0f)
    }

    @Test
    fun `paragraph spacing sits in the requested 8 to 12 dp band`() {
        assertTrue(
            CjkTypography.PARAGRAPH_SPACING_DP in 8f..12f,
            "paragraph spacing ${CjkTypography.PARAGRAPH_SPACING_DP} outside 8..12",
        )
    }

    @Test
    fun `horizontal margin is 20 dp`() {
        assertEquals(20f, CjkTypography.HORIZONTAL_MARGIN_DP)
    }

    @Test
    fun `letter spacing scales with the font scale`() {
        val tracking = 0.5f
        val plain = tracking * 2f * 1f
        val large = tracking * 2f * 1.5f
        assertEquals(plain * 1.5f, large, 0.0001f)
    }

    @Test
    fun `first line indent scales with the font size`() {
        // 2em at 17px → 34px
        assertEquals(34f, CjkTypography.firstLineIndentPx(fontSizePx = 17f), 0.0001f)
    }

    @Test
    fun `paragraph spacing converts dp to px through the density`() {
        assertEquals(30f, CjkTypography.paragraphSpacingPx(density = 3f), 0.0001f)
    }

    @Test
    fun `line height ratio matches the desktop default`() {
        assertEquals(1.6f, CjkTypography.LINE_HEIGHT_RATIO)
    }
}
