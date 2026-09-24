package com.koodoreader.core.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FontItemTest {

    @Test
    fun `normalizeFontName mirrors desktop parity`() {
        // fontUtil.normalizeFontName: strip ext, then space/hyphen/dot -> underscore
        assertEquals("My_Font_Pro", NativeFontKeys.normalizeFontName("My Font-Pro.ttf"))
        assertEquals("霞鹜文楷", NativeFontKeys.normalizeFontName("霞鹜文楷.ttf"))
        assertEquals("Inter_Variable", NativeFontKeys.normalizeFontName("Inter.Variable.otf"))
        assertEquals("noext", NativeFontKeys.normalizeFontName("noext"))
    }

    @Test
    fun `font extension detection and import whitelist`() {
        assertEquals("ttf", NativeFontKeys.fontExtension("Some.Font.TTF"))
        assertEquals("otf", NativeFontKeys.fontExtension("a.otf"))
        assertEquals("ttf", NativeFontKeys.fontExtension("noext"))
        assertTrue(NativeFontKeys.isValidImportExt("TTF"))
        assertTrue(NativeFontKeys.isValidImportExt("otf"))
        assertFalse(NativeFontKeys.isValidImportExt("woff"))
        assertFalse(NativeFontKeys.isValidImportExt("woff2"))
    }

    @Test
    fun `config keys stay desktop-identical`() {
        assertEquals("fontList", NativeFontKeys.FONT_LIST_KEY)
        assertEquals("customFonts", NativeFontKeys.CUSTOM_FONTS_MAP)
        assertEquals("fonts", NativeFontKeys.FONT_DIR)
        assertEquals("font_", NativeFontKeys.LOCALFORAGE_PREFIX)
    }
}
