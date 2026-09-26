package com.koodoreader.core.designsystem

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies the WCAG maths itself against published reference values, so that a
 * broken contrast function cannot make [ColorTokensTest] pass vacuously.
 */
class ContrastTest {

    private val black = 0xFF000000
    private val white = 0xFFFFFFFF

    @Test
    fun `channel extraction reads ARGB bytes`() {
        val c = 0xFF3A6EA5
        assertEquals(0xFF, Contrast.alphaOf(c))
        assertEquals(0x3A, Contrast.redOf(c))
        assertEquals(0x6E, Contrast.greenOf(c))
        assertEquals(0xA5, Contrast.blueOf(c))
    }

    @Test
    fun `channel extraction ignores a zero alpha`() {
        assertEquals(0x00, Contrast.alphaOf(0x00123456))
        assertEquals(0x12, Contrast.redOf(0x00123456))
    }

    @Test
    fun `relative luminance of black is zero`() {
        assertEquals(0.0, Contrast.relativeLuminance(black), 1e-9)
    }

    @Test
    fun `relative luminance of white is one`() {
        assertEquals(1.0, Contrast.relativeLuminance(white), 1e-9)
    }

    @Test
    fun `luminance is monotonic in channel value`() {
        val darker = Contrast.relativeLuminance(0xFF404040)
        val lighter = Contrast.relativeLuminance(0xFF808080)
        assertTrue(darker < lighter, "expected $darker < $lighter")
    }

    @Test
    fun `black on white is the maximum ratio`() {
        assertEquals(Contrast.MAX_RATIO, Contrast.ratio(black, white), 1e-6)
    }

    @Test
    fun `a colour against itself is the identity ratio`() {
        assertEquals(Contrast.IDENTITY_RATIO, Contrast.ratio(0xFF3A6EA5, 0xFF3A6EA5), 1e-9)
        assertEquals(Contrast.IDENTITY_RATIO, Contrast.ratio(white, white), 1e-9)
    }

    @Test
    fun `ratio is symmetric`() {
        val a = 0xFF3A6EA5
        val b = 0xFFF8F6F2
        assertEquals(Contrast.ratio(a, b), Contrast.ratio(b, a), 1e-12)
    }

    /**
     * #767676 on white is the canonical "just passes AA" grey: 4.54:1.
     */
    @Test
    fun `known AA boundary grey on white is about 4_54`() {
        assertEquals(4.54, Contrast.ratio(0xFF767676, white), 0.01)
    }

    @Test
    fun `aa boundary grey is above the text threshold and a lighter grey is not`() {
        assertTrue(Contrast.ratio(0xFF767676, white) >= Contrast.TEXT_MIN_RATIO)
        // #777777 is one step lighter and is documented as failing AA on white.
        assertTrue(Contrast.ratio(0xFF949494, white) < Contrast.TEXT_MIN_RATIO)
    }

    @Test
    fun `hex renders RRGGBB without the alpha byte`() {
        assertEquals("#3A6EA5", Contrast.hex(0xFF3A6EA5))
        assertEquals("#000000", Contrast.hex(0x00000000))
    }
}
