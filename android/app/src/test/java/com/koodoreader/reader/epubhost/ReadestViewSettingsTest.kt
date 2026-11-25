package com.koodoreader.reader.epubhost

import com.koodoreader.core.designsystem.ThemeKind
import com.koodoreader.reader.shell.ReaderFeatureState
import com.koodoreader.reader.shell.ReaderPerformancePolicy
import com.koodoreader.reader.shell.ReaderSpreadPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadestViewSettingsTest {

    @Test
    fun `night kind sets dark mode and overrideColor`() {
        val s = ReadestViewSettings.from(ThemeKind.NIGHT, ReaderFeatureState())
        assertTrue(s.isDarkMode)
        assertTrue(s.overrideColor)
        assertEquals("dark", s.colorScheme)
        // #121212 + a dimmed off-white: a pure-black page next to a bright
        // one smears the glyph edges (halation), which a night theme exists
        // to avoid.
        assertEquals("rgba(18, 18, 18,1)", s.bg)
        assertEquals("rgba(216, 214, 209,1)", s.fg)
    }

    @Test
    fun `fontScale 1 maps defaultFontSize to 17 divided by 1_25`() {
        val s = ReadestViewSettings.from(ThemeKind.DEFAULT, ReaderFeatureState(fontScale = 1.0f))
        assertEquals(17.0 / 1.25, s.defaultFontSize, 0.001)
        assertFalse(s.isDarkMode)
        assertEquals("light", s.colorScheme)
    }

    @Test
    fun `columns two and negative margin`() {
        val s = ReadestViewSettings.from(
            ThemeKind.PROTECT_EYE,
            ReaderFeatureState(columnsPerPage = 2, marginHorizontalPx = -8f),
        )
        assertEquals(2, s.maxColumnCount)
        assertEquals(0, s.marginPx)
        assertTrue(s.scrolled)
    }

    @Test
    fun `generic font keys pass through, others become serif`() {
        assertEquals("sans-serif", ReadestViewSettings.from(ThemeKind.DEFAULT, ReaderFeatureState(fontKey = "sans-serif")).defaultFont)
        assertEquals("serif", ReadestViewSettings.from(ThemeKind.DEFAULT, ReaderFeatureState(fontKey = "bundled:inter")).defaultFont)
    }

    @Test
    fun `custom book css maps to userStylesheet when enabled`() {
        val css = "p { margin: 0; }"
        val on = ReadestViewSettings.from(
            ThemeKind.DEFAULT,
            ReaderFeatureState(customBookCss = css, customBookCssEnabled = true),
        )
        assertTrue(on.userStylesheet, on.userStylesheet.contains("margin-bottom:10px !important;"))
        assertTrue(on.userStylesheet, on.userStylesheet.contains(css))
        val off = ReadestViewSettings.from(
            ThemeKind.DEFAULT,
            ReaderFeatureState(customBookCss = css, customBookCssEnabled = false),
        )
        assertTrue(off.userStylesheet, off.userStylesheet.contains("margin-bottom:10px !important;"))
        assertFalse(off.userStylesheet, off.userStylesheet.contains(css))
    }

    @Test
    fun `lite perf tier caps columns and inline size`() {
        val s = ReadestViewSettings.from(
            ThemeKind.DEFAULT,
            ReaderFeatureState(columnsPerPage = 2, perfTier = ReaderPerformancePolicy.TIER_LITE),
        )
        assertEquals(1, s.maxColumnCount)
        assertEquals(560, s.maxInlineSizePx)
    }

    @Test
    fun `text rules json passes through to snapshot`() {
        val json = """[{"id":"1","pattern":"x","replacement":"y"}]"""
        val s = ReadestViewSettings.from(ThemeKind.DEFAULT, ReaderFeatureState(textRulesJson = json))
        assertEquals(json, s.textRulesJson)
    }

    @Test
    fun `double page spread overrides scroll and columns`() {
        val s = ReadestViewSettings.from(
            ThemeKind.DEFAULT,
            ReaderFeatureState(doublePage = true, scrolled = true, columnsPerPage = 1),
        )
        assertFalse(s.scrolled)
        assertEquals(2, s.maxColumnCount)
        assertEquals(2, ReaderSpreadPolicy.foliateMaxColumnCount(ReaderFeatureState(doublePage = true)))
    }

    @Test
    fun `ui margin override is physical pixels and indent is css`() {
        val snapshot = ReadestViewSettings.from(
            ThemeKind.DEFAULT,
            ReaderFeatureState(),
            horizontalMarginPx = 60,
            firstLineIndent = true,
        )
        assertEquals(60, snapshot.marginPx)
        assertTrue(snapshot.userStylesheet, snapshot.userStylesheet.contains("text-indent:2em !important;"))
    }

    @Test
    fun `null ui margin keeps legacy margin mapping`() {
        val snapshot = ReadestViewSettings.from(
            ThemeKind.DEFAULT,
            ReaderFeatureState(marginHorizontalPx = -8f),
        )
        assertEquals(0, snapshot.marginPx)
    }

    @Test
    fun `signed tracking round-trips into the snapshot`() {
        // letterSpacingCssPx is the CJK base (0 sp) plus the reader's signed
        // adjustment, so both directions of the slider must survive into the
        // foliate bridge: negative tightens, positive loosens.
        val negative = ReadestViewSettings.from(
            ThemeKind.DEFAULT,
            ReaderFeatureState(letterSpacingSp = -0.05f, fontScale = 1.0f),
        )
        assertEquals(-0.05, negative.letterSpacingPx, 1e-6)
        val positive = ReadestViewSettings.from(
            ThemeKind.DEFAULT,
            ReaderFeatureState(letterSpacingSp = 0.1f, fontScale = 1.0f),
        )
        assertEquals(0.1, positive.letterSpacingPx, 1e-6)
        assertEquals(0.0, ReadestViewSettings.from(ThemeKind.DEFAULT, ReaderFeatureState()).letterSpacingPx, 0.0)
    }
}
