package com.koodoreader.core.designsystem

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Verifies that [TypographyTokens] defaults are aligned with the desktop
 * reader config defaults extracted from:
 *   - sliderList/dropdownList.tsx  (fontSize 17, letterSpacing 0, margin 0,
 *     paraSpacing 0, scale 1)
 *   - dropdownList.tsx lineHeight default "" → 1.5 ratio
 *   - dropdownList.tsx textAlign default "" → "left"
 */
class TypographyTokensTest {

    // ─── Default value alignment ─────────────────────────────────────────────

    @Test
    fun `default fontSizeSp matches desktop slider default`() {
        assertEquals(17f, TypographyTokens().fontSizeSp)
    }

    @Test
    fun `default lineHeightRatio matches desktop dropdown default`() {
        // dropdownList.tsx lineHeight options: "" (=1.5), "1", "1.25", "1.5", "1.75", "2"
        assertEquals(1.5f, TypographyTokens().lineHeightRatio)
    }

    @Test
    fun `default letterSpacingPx matches desktop slider default`() {
        // sliderConfigs: letterSpacing range 0–20, default 0
        assertEquals(0f, TypographyTokens().letterSpacingPx)
    }

    @Test
    fun `default textAlign matches desktop dropdown default`() {
        // dropdownList.tsx textAlign: "" (=left), "Left", "Justify", "Right"
        assertEquals("left", TypographyTokens().textAlign)
    }

    @Test
    fun `default textIndentPx is zero`() {
        assertEquals(0, TypographyTokens().textIndentPx)
    }

    @Test
    fun `default paragraphSpacingBeforePx is zero`() {
        // paraSpacing slider range 0–120, default 0
        assertEquals(0, TypographyTokens().paragraphSpacingBeforePx)
    }

    @Test
    fun `default paragraphSpacingAfterPx is zero`() {
        assertEquals(0, TypographyTokens().paragraphSpacingAfterPx)
    }

    @Test
    fun `default margin fields are zero`() {
        val t = TypographyTokens()
        assertEquals(0, t.marginLeftPx)
        assertEquals(0, t.marginRightPx)
        assertEquals(0, t.marginTopPx)
        assertEquals(0, t.marginBottomPx)
    }

    // ─── Custom value construction ──────────────────────────────────────────

    @Test
    fun `custom values are preserved in copy`() {
        val custom = TypographyTokens(
            fontSizeSp = 24f,
            lineHeightRatio = 1.75f,
            letterSpacingPx = 2f,
            textIndentPx = 32,
            paragraphSpacingBeforePx = 16,
            paragraphSpacingAfterPx = 8,
            marginLeftPx = 20,
            marginRightPx = 20,
            marginTopPx = 10,
            marginBottomPx = 10,
            textAlign = "justify",
        )
        assertEquals(24f, custom.fontSizeSp)
        assertEquals(1.75f, custom.lineHeightRatio)
        assertEquals(2f, custom.letterSpacingPx)
        assertEquals(32, custom.textIndentPx)
        assertEquals(16, custom.paragraphSpacingBeforePx)
        assertEquals(8, custom.paragraphSpacingAfterPx)
        assertEquals(20, custom.marginLeftPx)
        assertEquals(20, custom.marginRightPx)
        assertEquals(10, custom.marginTopPx)
        assertEquals(10, custom.marginBottomPx)
        assertEquals("justify", custom.textAlign)
    }

    // ─── Boundary value handling ─────────────────────────────────────────────

    @Test
    fun `negative fontSizeSp is allowed (user override)`() {
        // No clamping — accept any value the user configures.
        val t = TypographyTokens(fontSizeSp = -5f)
        assertEquals(-5f, t.fontSizeSp)
    }

    @Test
    fun `zero lineHeightRatio is allowed`() {
        val t = TypographyTokens(lineHeightRatio = 0f)
        assertEquals(0f, t.lineHeightRatio)
    }

    @Test
    fun `very large letterSpacingPx is allowed`() {
        val t = TypographyTokens(letterSpacingPx = 1_000_000f)
        assertEquals(1_000_000f, t.letterSpacingPx)
    }

    @Test
    fun `large negative margin is allowed`() {
        // Desktop margin range includes –40, so negative margins must be allowed.
        val t = TypographyTokens(marginLeftPx = -40, marginRightPx = -40)
        assertEquals(-40, t.marginLeftPx)
        assertEquals(-40, t.marginRightPx)
    }

    // ─── Valid textAlign values ─────────────────────────────────────────────

    @Test
    fun `all desktop-aligned textAlign values are accepted`() {
        for (align in listOf("left", "center", "justify", "right")) {
            val t = TypographyTokens(textAlign = align)
            assertEquals(align, t.textAlign)
        }
    }

    @Test
    fun `non-standard textAlign value is accepted without validation`() {
        // We do not enforce valid values in the data class; callers must validate.
        val t = TypographyTokens(textAlign = "invalid-align")
        assertEquals("invalid-align", t.textAlign)
    }

    // ─── Companion constants ─────────────────────────────────────────────────

    @Test
    fun `KEY constants match expected ConfigService key names`() {
        assertEquals("fontSize", TypographyTokens.KEY_FONT_SIZE)
        assertEquals("lineHeight", TypographyTokens.KEY_LINE_HEIGHT)
        assertEquals("letterSpacing", TypographyTokens.KEY_LETTER_SPACING)
        assertEquals("margin", TypographyTokens.KEY_MARGIN)
        assertEquals("paraSpacing", TypographyTokens.KEY_PARA_SPACING)
        assertEquals("textAlign", TypographyTokens.KEY_TEXT_ALIGN)
    }

    @Test
    fun `VALID_TEXT_ALIGNS contains all desktop-aligned values`() {
        assertEquals(4, TypographyTokens.VALID_TEXT_ALIGNS.size)
        assertTrue(TypographyTokens.VALID_TEXT_ALIGNS.containsAll(listOf("left", "center", "justify", "right")))
    }
}
