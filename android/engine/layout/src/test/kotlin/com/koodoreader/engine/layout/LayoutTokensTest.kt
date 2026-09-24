package com.koodoreader.engine.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Unit tests for [LayoutTokens] and [DesktopReaderConfig].
 *
 * The token object is the engine's only input from the settings layer; these
 * tests pin the arithmetic (margins, columns, gaps) and the desktop-config
 * parsing so a regression in either shows up immediately.
 */
class LayoutTokensTest {

    // ── content box arithmetic ──────────────────────────────────────────────

    @Test
    fun `content box subtracts margins`() {
        val t = LayoutTokens(
            viewportWidthPx = 100f,
            viewportHeightPx = 100f,
            marginHorizontalPx = 10f,
            marginVerticalPx = 20f,
        )
        assertEquals(80f, t.contentWidthPx, EPS)
        assertEquals(60f, t.contentHeightPx, EPS)
    }

    @Test
    fun `negative margin expands content box`() {
        val t = LayoutTokens(
            viewportWidthPx = 100f,
            viewportHeightPx = 100f,
            marginHorizontalPx = -20f,
            marginVerticalPx = -20f,
        )
        assertEquals(140f, t.contentWidthPx, EPS)
        assertEquals(140f, t.contentHeightPx, EPS)
    }

    @Test
    fun `single column fills content width`() {
        val t = LayoutTokens(
            viewportWidthPx = 100f,
            viewportHeightPx = 100f,
            marginHorizontalPx = 10f,
            marginVerticalPx = 10f,
        )
        assertEquals(80f, t.columnWidthPx(1), EPS)
    }

    @Test
    fun `two columns split content width evenly`() {
        val t = LayoutTokens(
            viewportWidthPx = 100f,
            viewportHeightPx = 100f,
            marginHorizontalPx = 10f,
            marginVerticalPx = 10f,
        )
        assertEquals(40f, t.columnWidthPx(2), EPS)
    }

    @Test
    fun `column gap reduces column width`() {
        val t = LayoutTokens(
            viewportWidthPx = 100f,
            viewportHeightPx = 100f,
            marginHorizontalPx = 10f,
            marginVerticalPx = 10f,
            columnGapPx = 10f,
        )
        // contentWidth = 80, gap = 10 → each column = (80 - 10) / 2 = 35.
        assertEquals(35f, t.columnWidthPx(2), EPS)
    }

    @Test
    fun `column left positions account for gap`() {
        val t = LayoutTokens(
            viewportWidthPx = 100f,
            viewportHeightPx = 100f,
            marginHorizontalPx = 10f,
            marginVerticalPx = 10f,
            columnGapPx = 10f,
        )
        assertEquals(10f, t.columnLeftPx(0, 2), EPS)
        // col 1 left = margin + colWidth + gap = 10 + 35 + 10 = 55.
        assertEquals(55f, t.columnLeftPx(1, 2), EPS)
    }

    // ── validation ──────────────────────────────────────────────────────────

    @Test
    fun `zero viewport width throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            LayoutTokens(viewportWidthPx = 0f, viewportHeightPx = 100f)
        }
    }

    @Test
    fun `zero viewport height throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            LayoutTokens(viewportWidthPx = 100f, viewportHeightPx = 0f)
        }
    }

    @Test
    fun `zero font size throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            LayoutTokens(viewportWidthPx = 100f, viewportHeightPx = 100f, fontSizePx = 0f)
        }
    }

    @Test
    fun `negative letter spacing throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            LayoutTokens(viewportWidthPx = 100f, viewportHeightPx = 100f, letterSpacingPx = -1f)
        }
    }

    @Test
    fun `margin beyond negative half viewport throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            LayoutTokens(viewportWidthPx = 100f, viewportHeightPx = 100f, marginHorizontalPx = -60f)
        }
    }

    // ── defaults ────────────────────────────────────────────────────────────

    @Test
    fun `defaults uses desktop font size and line height`() {
        val t = LayoutTokens.defaults(100f, 100f)
        assertEquals(DesktopReaderConfig.FONT_SIZE_DEFAULT, t.fontSizePx, EPS)
        assertEquals(DesktopReaderConfig.LINE_HEIGHT_DEFAULT, t.lineHeightMultiple, EPS)
        assertEquals(0f, t.marginHorizontalPx, EPS)
        assertEquals(0f, t.marginVerticalPx, EPS)
    }

    // ── DesktopReaderConfig parsing ─────────────────────────────────────────

    @Test
    fun `fontSizeOf falls back to default on null`() {
        assertEquals(17f, DesktopReaderConfig.fontSizeOf(null), EPS)
    }

    @Test
    fun `fontSizeOf parses numeric string`() {
        assertEquals(20f, DesktopReaderConfig.fontSizeOf("20"), EPS)
    }

    @Test
    fun `fontSizeOf clamps to slider max`() {
        assertEquals(80f, DesktopReaderConfig.fontSizeOf("999"), EPS)
    }

    @Test
    fun `fontSizeOf clamps to slider min`() {
        assertEquals(13f, DesktopReaderConfig.fontSizeOf("1"), EPS)
    }

    @Test
    fun `lineHeightOf empty string means default`() {
        assertEquals(1.5f, DesktopReaderConfig.lineHeightOf(""), EPS)
    }

    @Test
    fun `lineHeightOf clamps to max`() {
        assertEquals(2f, DesktopReaderConfig.lineHeightOf("3"), EPS)
    }

    @Test
    fun `marginOf preserves negative values`() {
        assertEquals(-40f, DesktopReaderConfig.marginOf("-40"), EPS)
    }

    @Test
    fun `marginOf clamps to slider bounds`() {
        assertEquals(80f, DesktopReaderConfig.marginOf("999"), EPS)
        assertEquals(-40f, DesktopReaderConfig.marginOf("-999"), EPS)
    }

    @Test
    fun `tokensOf reads only known keys`() {
        val tokens = DesktopReaderConfig.tokensOf(
            mapOf(
                "fontSize" to "20",
                "margin" to "10",
                "lineHeight" to "1.5",
                "letterSpacing" to "2",
                "paraSpacing" to "8",
                "textAlign" to "Justify",
                "fontFamily" to "serif",       // ignored
                "themeColor" to "dark",        // ignored
            ),
            viewportWidthPx = 100f,
            viewportHeightPx = 100f,
        )
        assertEquals(20f, tokens.fontSizePx, EPS)
        assertEquals(10f, tokens.marginHorizontalPx, EPS)
        assertEquals(10f, tokens.marginVerticalPx, EPS)
        assertEquals(1.5f, tokens.lineHeightMultiple, EPS)
        assertEquals(2f, tokens.letterSpacingPx, EPS)
        assertEquals(8f, tokens.paraSpacingPx, EPS)
        assertEquals(TextAlign.JUSTIFY, tokens.textAlign)
    }

    @Test
    fun `tokensOf defaults textAlign to DEFAULT for empty string`() {
        val tokens = DesktopReaderConfig.tokensOf(
            mapOf("textAlign" to ""),
            viewportWidthPx = 100f,
            viewportHeightPx = 100f,
        )
        assertEquals(TextAlign.DEFAULT, tokens.textAlign)
    }

    @Test
    fun `tokensOrNull returns defaults for null config`() {
        val tokens = DesktopReaderConfig.tokensOrNull(null, 100f, 100f)
        assertEquals(DesktopReaderConfig.FONT_SIZE_DEFAULT, tokens!!.fontSizePx, EPS)
    }

    @Test
    fun `tokensOrNull returns null for unusable config`() {
        // Negative viewport width fails LayoutTokens validation.
        val tokens = DesktopReaderConfig.tokensOrNull(
            emptyMap<Any, Any>(),
            viewportWidthPx = -1f,
            viewportHeightPx = 100f,
        )
        assertEquals(null, tokens)
    }

    // ── TextAlign enum ──────────────────────────────────────────────────────

    @Test
    fun `TextAlign fromDesktop is case-insensitive`() {
        assertEquals(TextAlign.LEFT, TextAlign.fromDesktop("left"))
        assertEquals(TextAlign.JUSTIFY, TextAlign.fromDesktop("Justify"))
        assertEquals(TextAlign.RIGHT, TextAlign.fromDesktop("RIGHT"))
        assertEquals(TextAlign.DEFAULT, TextAlign.fromDesktop(""))
        assertEquals(TextAlign.DEFAULT, TextAlign.fromDesktop(null))
    }

    companion object {
        private const val EPS = 0.01f
    }
}
