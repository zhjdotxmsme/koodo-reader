package com.koodoreader.reader.epubhost

import com.koodoreader.core.designsystem.ThemeKind
import com.koodoreader.reader.shell.ReaderFeatureState
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
        assertEquals("rgba(44, 47, 49,1)", s.bg)
        assertEquals("rgba(255,255,255,1)", s.fg)
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
}
