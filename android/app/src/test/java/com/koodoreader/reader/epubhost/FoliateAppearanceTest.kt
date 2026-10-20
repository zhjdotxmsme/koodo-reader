package com.koodoreader.reader.epubhost

import com.koodoreader.core.designsystem.ThemeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoliateAppearanceTest {

    @Test
    fun `night theme uses dark color-scheme`() {
        val opts = FoliateAppearance.opts(
            bg = "rgba(44, 47, 49,1)",
            fg = "rgba(255,255,255,1)",
            fontSizePx = 17,
            dark = true,
        )
        assertEquals("dark", opts.colorScheme)
        assertFalse(opts.invertImages)
    }

    @Test
    fun `day and eye-protection stay on light color-scheme`() {
        val day = FoliateAppearance.opts(
            bg = "rgba(255,255,255,1)",
            fg = "rgba(0,0,0,1)",
            fontSizePx = 17,
            dark = false,
        )
        assertEquals("light", day.colorScheme)
        val eye = FoliateAppearance.opts(
            bg = "rgba(197, 231, 207,1)",
            fg = "rgba(54, 80, 62,1)",
            fontSizePx = 18,
            dark = false,
            lineHeight = 1.75f,
            marginPx = 24f,
        )
        assertEquals("light", eye.colorScheme)
        assertEquals(1.75, eye.lineHeight, 0.001)
        assertEquals(24, eye.marginPx)
    }

    @Test
    fun `theme chips map to desktop i18n keys`() {
        assertEquals("Default", FoliateAppearance.themeLabelKey(ThemeKind.DEFAULT))
        assertEquals("Eye protection", FoliateAppearance.themeLabelKey(ThemeKind.PROTECT_EYE))
        assertEquals("Night", FoliateAppearance.themeLabelKey(ThemeKind.NIGHT))
    }

    @Test
    fun `in-reader appearance sheet is always offered`() {
        assertTrue(FoliateAppearance.showInReaderSheet(useFoliate = true))
        assertTrue(FoliateAppearance.showInReaderSheet(useFoliate = false))
    }
}
