package com.koodoreader.reader.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the native reading-enhancement + typography settings that the
 * packaged reader never exposed. Defaults match desktop
 * `DesktopReaderConfig` (font 17px, line-height 1.5, margin 0).
 */
class ReaderFeatureStateTest {

    @Test
    fun `empty store is desktop defaults with enhancements off`() {
        val s = ReaderFeatureCodec.fromMap(emptyMap())
        assertEquals(1.0f, s.fontScale, 0.001f)
        assertEquals(1.5f, s.lineHeightRatio, 0.001f)
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
    }

    @Test
    fun `font scale maps to desktop 17px base`() {
        val doubled = ReaderFeatureCodec.fromMap(mapOf("fontScale" to "2"))
        assertEquals(34f, ReaderFeatureCodec.fontSizePx(doubled), 0.001f)
        val tiny = ReaderFeatureCodec.fromMap(mapOf("fontScale" to "0.1"))
        assertEquals(17f * 0.7f, ReaderFeatureCodec.fontSizePx(tiny), 0.001f)
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
}
