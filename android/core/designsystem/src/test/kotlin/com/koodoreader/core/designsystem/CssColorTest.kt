package com.koodoreader.core.designsystem

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Covers the two colour spellings the desktop writes, including the exact
 * values from the built-in presets and the malformed inputs a user-editable
 * config can contain.
 */
class CssColorTest {

    @Test
    fun `parses the three built-in preset colours`() {
        // Values from ThemeSpec's presets, i.e. the real desktop strings.
        assertEquals(0xFFFFFFFF, CssColor.parse("rgba(255,255,255,1)"))
        assertEquals(0xFFFF0000, CssColor.parse("rgba(255,0,0,1)"))
        assertEquals(0xFFC5E7CF, CssColor.parse("rgba(197, 231, 207,1)"))
        assertEquals(0xFF36503E, CssColor.parse("rgba(54, 80, 62,1)"))
        assertEquals(0xFF2C2F31, CssColor.parse("rgba(44, 47, 49,1)"))
        assertEquals(0xFF000000, CssColor.parse("rgba(0,0,0,1)"))
    }

    @Test
    fun `spaces inside rgba are optional and tolerated`() {
        val tight = CssColor.parse("rgba(197,231,207,1)")
        val loose = CssColor.parse("rgba( 197 , 231 , 207 , 1 )")
        assertEquals(tight, loose)
    }

    @Test
    fun `rgb without alpha is opaque`() {
        assertEquals(0xFF3A6EA5, CssColor.parse("rgb(58,110,165)"))
    }

    @Test
    fun `hex six digit is parsed`() {
        assertEquals(0xFF123456, CssColor.parse("#123456"))
        assertEquals(0xFFABCDEF, CssColor.parse("#abcdef"))
    }

    @Test
    fun `hex three digit is expanded`() {
        assertEquals(0xFFAABBCC, CssColor.parse("#abc"))
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        assertEquals(0xFF123456, CssColor.parse("  #123456 \n"))
    }

    @Test
    fun `fully transparent alpha is preserved`() {
        assertEquals(0x00123456, CssColor.parse("rgba(18,52,86,0)"))
    }

    @Test
    fun `half alpha is rounded to the nearest byte`() {
        // 0.5 * 255 = 127.5 -> 128 with round-half-up.
        assertEquals(0x80123456, CssColor.parse("rgba(18,52,86,0.5)"))
    }

    @Test
    fun `alpha above one is clamped to opaque`() {
        assertEquals(0xFF123456, CssColor.parse("rgba(18,52,86,2)"))
    }

    @Test
    fun `a negative alpha is malformed and falls back`() {
        // The rgba pattern does not accept a sign, so this is treated as
        // malformed input rather than silently clamped to transparent.
        assertEquals(CssColor.FALLBACK, CssColor.parse("rgba(18,52,86,-1)"))
    }

    @Test
    fun `channels above 255 are clamped rather than rejected`() {
        // The desktop config is user-editable JSON; a clamped colour beats a
        // crash in the middle of rendering a book.
        assertEquals(0xFFFFFFFF, CssColor.parse("rgba(300,300,300,1)"))
    }

    @Test
    fun `null blank null-word and undefined all fall back`() {
        assertEquals(CssColor.FALLBACK, CssColor.parse(null))
        assertEquals(CssColor.FALLBACK, CssColor.parse(""))
        assertEquals(CssColor.FALLBACK, CssColor.parse("   "))
        assertEquals(CssColor.FALLBACK, CssColor.parse("null"))
        assertEquals(CssColor.FALLBACK, CssColor.parse("undefined"))
    }

    @Test
    fun `malformed values fall back instead of throwing`() {
        for (bad in listOf("#12", "#12345", "rgb(1,2)", "hsl(1,2,3)", "red", "rgba(a,b,c,1)", "#GGGGGG")) {
            assertEquals(CssColor.FALLBACK, CssColor.parse(bad), "expected fallback for '$bad'")
        }
    }

    @Test
    fun `fallback is opaque black`() {
        assertEquals(0xFF000000, CssColor.FALLBACK)
    }

    @Test
    fun `toHex6 drops the alpha byte`() {
        assertEquals("#123456", CssColor.toHex6(0xFF123456))
        assertEquals("#123456", CssColor.toHex6(0x00123456))
    }
}
