package com.koodoreader.reader.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the native reading-enhancement + typography settings that the
 * packaged reader never exposed. Defaults match desktop
 * `DesktopReaderConfig` (font 17px, line-height 1.6, margin 0).
 */
class ReaderFeatureStateTest {

    @Test
    fun `empty store is desktop defaults with enhancements off`() {
        val s = ReaderFeatureCodec.fromMap(emptyMap())
        assertEquals(1.0f, s.fontScale, 0.001f)
        assertEquals(1.6f, s.lineHeightRatio, 0.001f)
        assertEquals(0f, s.marginHorizontalPx, 0.001f)
        assertEquals(17f, ReaderFeatureCodec.fontSizePx(s), 0.001f)
        assertFalse(s.bionic)
        assertFalse(s.ruler)
        assertFalse(s.paragraphMode)
        assertFalse(s.speedReading)
        assertEquals(300, s.speedWpm)
        assertTrue(s.textRulesJson.isEmpty())
        assertEquals(1, s.columnsPerPage)
        assertEquals("", s.fontKey)
        assertFalse(s.doublePage)
        assertTrue(s.scrolled)
        assertFalse(s.customBookCssEnabled)
        assertTrue(s.customBookCss.isEmpty())
        // The stylesheet now carries the CJK typography: tracking, a flush
        // right edge, and the 10dp paragraph gap.
        val css = ReaderFeatureCodec.userStylesheet(s, density = 1f)
        assertTrue(css, css.contains("letter-spacing:0.5px;"))
        assertTrue(css, css.contains("text-align:justify;"))
        assertTrue(css, css.contains("margin-bottom:10px !important;"))
        assertEquals(ReaderPerformancePolicy.TIER_NORMAL, s.perfTier)
        assertEquals(0f, s.pdfCropMarginPx, 0.001f)
        assertFalse(s.pdfReflow)
    }

    @Test
    fun `reader font size is clamped to 14 through 22 px`() {
        val tiny = ReaderFeatureState(fontScale = 0.01f)
        val huge = ReaderFeatureState(fontScale = 9f)
        assertEquals(14f, ReaderFeatureCodec.fontSizePx(tiny), 0.001f)
        assertEquals(22f, ReaderFeatureCodec.fontSizePx(huge), 0.001f)
    }

    @Test
    fun `default paragraph gap is the CJK 10dp, not a multiple of the line height`() {
        val state = ReaderFeatureState()
        // 10dp → 30px at density 3.0.
        assertEquals(30f, ReaderFeatureCodec.effectiveParagraphSpacingPx(state, density = 3f), 0.001f)
        // The stylesheet is expressed in CSS px, which are dp inside a
        // WebView — so the gap does NOT scale with the display density.
        val css = ReaderFeatureCodec.userStylesheet(state, density = 3f)
        assertTrue(css, css.contains("margin-bottom:10px !important;"))
    }

    @Test
    fun `an explicit paragraph spacing wins over the CJK default`() {
        val state = ReaderFeatureState(paragraphSpacingPx = 12f)
        // 12dp: 12 physical px at density 1, 36 at density 3.
        assertEquals(36f, ReaderFeatureCodec.effectiveParagraphSpacingPx(state, density = 3f), 0.001f)
        assertEquals(12f, ReaderFeatureCodec.effectiveParagraphSpacingCssPx(state), 0.001f)
        assertTrue(ReaderFeatureCodec.userStylesheet(state, density = 3f).contains("margin-bottom:12px !important;"))
    }

    @Test
    fun `first line indent is presentation css only`() {
        val state = ReaderFeatureState(paragraphSpacingPx = 12f)
        val css = ReaderFeatureCodec.userStylesheet(state, firstLineIndent = true, density = 1f)
        assertTrue(css, css.contains("margin-bottom:12px !important;text-indent:2em !important;"))
    }

    @Test
    fun `line height and margin clamp to desktop slider ranges`() {
        val high = ReaderFeatureCodec.fromMap(
            mapOf("lineHeightRatio" to "9", "marginHorizontalPx" to "400"),
        )
        assertEquals(2f, ReaderFeatureCodec.clampedLineHeight(high), 0.001f)
        assertEquals(80f, ReaderFeatureCodec.clampedMargin(high), 0.001f)
        val low = ReaderFeatureCodec.fromMap(
            mapOf("lineHeightRatio" to "0.2", "marginHorizontalPx" to "-200"),
        )
        assertEquals(1f, ReaderFeatureCodec.clampedLineHeight(low), 0.001f)
        assertEquals(-40f, ReaderFeatureCodec.clampedMargin(low), 0.001f)
    }

    @Test
    fun `enhancement flags and rules survive a round trip`() {
        val original = ReaderFeatureState(
            fontScale = 1.2f,
            lineHeightRatio = 1.75f,
            marginHorizontalPx = 16f,
            bionic = true,
            ruler = true,
            rulerLineHeight = 4f,
            paragraphMode = true,
            speedReading = true,
            speedWpm = 450,
            textRulesJson = """[{"id":"1","pattern":"foo","replacement":"bar"}]""",
            columnsPerPage = 2,
            fontKey = "bundled:inter",
            doublePage = true,
            scrolled = false,
            customBookCss = "body { color: red; }",
            customBookCssEnabled = true,
            pdfCropMarginPx = 12f,
            pdfReflow = true,
        )
        val restored = ReaderFeatureCodec.fromMap(ReaderFeatureCodec.toMap(original))
        assertEquals(original.fontScale, restored.fontScale, 0.001f)
        assertEquals(original.lineHeightRatio, restored.lineHeightRatio, 0.001f)
        assertEquals(original.marginHorizontalPx, restored.marginHorizontalPx, 0.001f)
        assertTrue(restored.bionic)
        assertTrue(restored.ruler)
        assertEquals(4f, restored.rulerLineHeight, 0.001f)
        assertTrue(restored.paragraphMode)
        assertTrue(restored.speedReading)
        assertEquals(450, restored.speedWpm)
        assertTrue(restored.textRulesJson.contains("foo"))
        assertEquals(2, restored.columnsPerPage)
        assertEquals("bundled:inter", restored.fontKey)
        assertTrue(restored.doublePage)
        assertFalse(restored.scrolled)
        assertTrue(restored.customBookCssEnabled)
        assertEquals("body { color: red; }", restored.customBookCss)
        // The original sets no explicit paragraph spacing, so the CJK default
        // (10dp) applies.
        val css = ReaderFeatureCodec.userStylesheet(restored, density = 1f)
        assertTrue(css, css.contains("margin-bottom:10px !important;"))
        assertTrue(css, css.contains("body { color: red; }"))
        assertEquals(12f, restored.pdfCropMarginPx, 0.001f)
        assertTrue(restored.pdfReflow)
    }

    @Test
    fun `custom css disabled ignores stored css`() {
        val s = ReaderFeatureState(
            customBookCss = "body{color:blue}",
            customBookCssEnabled = false,
        )
        // The stylesheet now carries the CJK typography: tracking, a flush
        // right edge, and the 10dp paragraph gap.
        val css = ReaderFeatureCodec.userStylesheet(s, density = 1f)
        assertTrue(css, css.contains("letter-spacing:0.5px;"))
        assertTrue(css, css.contains("text-align:justify;"))
        assertTrue(css, css.contains("margin-bottom:10px !important;"))
    }

    @Test
    fun `paragraph spacing appends p margin rule to user stylesheet`() {
        val s = ReaderFeatureState(paragraphSpacingPx = 12f)
        val css = ReaderFeatureCodec.userStylesheet(s, density = 1f)
        assertTrue(css, css.contains("margin-bottom:12px !important;"))
        val both = ReaderFeatureState(
            paragraphSpacingPx = 8f,
            customBookCss = "body{color:red}",
            customBookCssEnabled = true,
        )
        val bothCss = ReaderFeatureCodec.userStylesheet(both, density = 1f)
        assertTrue(bothCss, bothCss.contains("margin-bottom:8px !important;"))
        assertTrue(bothCss, bothCss.contains("body{color:red}"))
    }

    @Test
    fun `prefs load whitelist includes every codec toMap key`() {
        val codecKeys = ReaderFeatureCodec.toMap(ReaderFeatureState()).keys
        assertTrue(codecKeys.contains(ReaderFeatureCodec.KEY_SCROLLED))
        assertTrue(codecKeys.contains(ReaderFeatureCodec.KEY_COLUMNS))
        val missing = codecKeys.filterNot { ReaderFeaturePrefs.KEYS.contains(it) }
        assertTrue("prefs KEYS missing $missing", missing.isEmpty())
    }

    @Test
    fun `columns per page clamp to the paginator range`() {
        assertEquals(1, ReaderFeatureCodec.clampedColumns(ReaderFeatureState(columnsPerPage = 0)))
        assertEquals(2, ReaderFeatureCodec.clampedColumns(ReaderFeatureState(columnsPerPage = 2)))
        assertEquals(8, ReaderFeatureCodec.clampedColumns(ReaderFeatureState(columnsPerPage = 99)))
    }

    @Test
    fun `new reader settings survive codec round trip`() {
        val state = ReaderFeatureState(
            paragraphSpacingPx = 12f,
            brightness = 0.4f,
            keepScreenOn = true,
            orientation = "landscape",
            customBackgroundArgb = 0xFF223344.toInt(),
        )
        assertEquals(state, ReaderFeatureCodec.fromMap(ReaderFeatureCodec.toMap(state)))
    }

    @Test
    fun `presentation defaults follow the system`() {
        val state = ReaderFeatureCodec.fromMap(emptyMap())
        assertEquals(-1f, state.brightness)
        assertEquals(false, state.keepScreenOn)
        assertEquals(ReaderFeatureCodec.ORIENTATION_SYSTEM, state.orientation)
        assertEquals(null, state.customBackgroundArgb)
        assertEquals(0f, state.paragraphSpacingPx)
    }

    @Test
    fun `invalid orientation falls back to system`() {
        val map = ReaderFeatureCodec.toMap(ReaderFeatureState()).toMutableMap()
        map[ReaderFeatureCodec.KEY_ORIENTATION] = "diagonal"
        assertEquals(
            ReaderFeatureCodec.ORIENTATION_SYSTEM,
            ReaderFeatureCodec.fromMap(map).orientation,
        )
    }

    @Test
    fun `paragraph spacing is clamped to its range`() {
        assertEquals(0f, ReaderFeatureCodec.clampedParagraphSpacing(ReaderFeatureState(paragraphSpacingPx = -3f)))
        assertEquals(48f, ReaderFeatureCodec.clampedParagraphSpacing(ReaderFeatureState(paragraphSpacingPx = 99f)))
    }

    // ─── Discrete sliders (P1-13) ─────────────────────────────────────────────

    @Test
    fun `letter spacing round trips through the codec`() {
        val state = ReaderFeatureState(letterSpacingSp = 0.05f)
        assertEquals(0.05f, ReaderFeatureCodec.fromMap(ReaderFeatureCodec.toMap(state)).letterSpacingSp, 0.001f)
    }

    @Test
    fun `letter spacing clamps to the supported range`() {
        assertEquals(
            ReaderFeatureCodec.LETTER_SPACING_MAX,
            ReaderFeatureCodec.clampedLetterSpacing(ReaderFeatureState(letterSpacingSp = 0.4f)),
            0.001f,
        )
        assertEquals(
            ReaderFeatureCodec.LETTER_SPACING_MIN,
            ReaderFeatureCodec.clampedLetterSpacing(ReaderFeatureState(letterSpacingSp = -0.4f)),
            0.001f,
        )
    }

    @Test
    fun `line height snaps to the 0_05 grid`() {
        assertEquals(1.15f, ReaderFeatureCodec.snappedLineHeight(1.13f), 0.001f)
        assertEquals(1.20f, ReaderFeatureCodec.snappedLineHeight(1.22f), 0.001f)
        assertEquals(ReaderFeatureCodec.LINE_HEIGHT_MIN, ReaderFeatureCodec.snappedLineHeight(0.5f))
        assertEquals(ReaderFeatureCodec.LINE_HEIGHT_MAX, ReaderFeatureCodec.snappedLineHeight(9f))
    }

    @Test
    fun `font scale snaps to whole pixel sizes`() {
        // 17px default: 17.34px rounds back to 17, 18.02px rounds up to 18 —
        // the slider must not leave the body on a fractional size, because a
        // fractional size makes the CJK cell grid and the glyph advances
        // disagree.
        assertEquals(1f, ReaderFeatureCodec.snappedFontScale(1.02f), 0.001f)
        assertEquals(18f / 17f, ReaderFeatureCodec.snappedFontScale(1.06f), 0.001f)
        assertEquals(14f, ReaderFeatureCodec.fontSizePx(ReaderFeatureState(
            fontScale = ReaderFeatureCodec.snappedFontScale(ReaderFeatureCodec.FONT_SCALE_MIN),
        )), 0.001f)
        assertEquals(22f, ReaderFeatureCodec.fontSizePx(ReaderFeatureState(
            fontScale = ReaderFeatureCodec.snappedFontScale(ReaderFeatureCodec.FONT_SCALE_MAX),
        )), 0.001f)
    }

    @Test
    fun `letter spacing reaches both rendering tracks`() {
        val wide = ReaderFeatureState(letterSpacingSp = 0.1f)
        val base = ReaderFeatureState()
        // WebView track: CSS px = dp, so no density in the number.
        val baseCss = ReaderFeatureCodec.userStylesheet(base, density = 1f)
        val wideCss = ReaderFeatureCodec.userStylesheet(wide, density = 1f)
        assertTrue(baseCss, baseCss.contains("letter-spacing:0.5px;"))
        assertTrue(wideCss, wideCss.contains("letter-spacing:0.6px;"))
        // Native track: px, so density scales it.
        val options = ReaderTypography.paginatorOptions(
            density = 3f, letterSpacingSp = 0.1f,
        )
        assertEquals((0.5f + 0.1f) * 3f, options.letterSpacingPx, 0.001f)
    }
}
