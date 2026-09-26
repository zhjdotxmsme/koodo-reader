package com.koodoreader.core.designsystem

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Token-level invariants for the shell palettes.
 *
 * These are the checks that make "the design system is correct" a CI fact
 * rather than a matter of opinion. They run on the JVM with no screen, which is
 * the reason the tokens are plain `Long` values in a pure JVM module.
 */
class ColorTokensTest {

    @Test
    fun `light and dark declare exactly the same slots`() {
        val light = ColorTokens.LIGHT.slotPairs().map { it.first }
        val dark = ColorTokens.DARK.slotPairs().map { it.first }
        assertEquals(light, dark)
    }

    @Test
    fun `slot list covers every declared property`() {
        // 25 slots: brand (8), surfaces/text (4), elevation ramp (7),
        // variant (2), outline (2), error (2).
        assertEquals(25, ColorTokens.LIGHT.slotPairs().size)
        assertEquals(
            ColorTokens.LIGHT.slotPairs().size,
            ColorTokens.LIGHT.slotPairs().map { it.first }.toSet().size,
            "duplicate slot name in slotPairs()",
        )
    }

    @Test
    fun `every token is fully opaque`() {
        for (palette in ColorTokens.ALL) {
            assertEquals(emptyList<String>(), palette.requireOpaque())
        }
    }

    @Test
    fun `text pairs meet the WCAG AA normal-text ratio`() {
        for (palette in ColorTokens.ALL) {
            for ((label, fg, bg) in ColorTokens.textPairs(palette)) {
                val r = Contrast.ratio(fg, bg)
                assertTrue(
                    r >= Contrast.TEXT_MIN_RATIO,
                    "$label: ${Contrast.hex(fg)} on ${Contrast.hex(bg)} = " +
                        "%.2f:1, needs >= %.1f:1".format(r, Contrast.TEXT_MIN_RATIO),
                )
            }
        }
    }

    @Test
    fun `accent and outline pairs meet the WCAG non-text ratio`() {
        for (palette in ColorTokens.ALL) {
            for ((label, fg, bg) in ColorTokens.accentPairs(palette)) {
                val r = Contrast.ratio(fg, bg)
                assertTrue(
                    r >= Contrast.ACCENT_MIN_RATIO,
                    "$label: ${Contrast.hex(fg)} on ${Contrast.hex(bg)} = " +
                        "%.2f:1, needs >= %.1f:1".format(r, Contrast.ACCENT_MIN_RATIO),
                )
            }
        }
    }

    /**
     * Brand continuity: these four values are carried over from the previous
     * shell theme (`Theme.kt`), so the palette change must not quietly drop the
     * project's colours.
     */
    @Test
    fun `brand seeds are preserved from the previous shell theme`() {
        assertEquals(0xFF3A6EA5, ColorTokens.LIGHT.primary)
        assertEquals(0xFF6B8E23, ColorTokens.LIGHT.secondary)
        assertEquals(0xFFF8F6F2, ColorTokens.LIGHT.background)
        assertEquals(0xFF16181D, ColorTokens.DARK.background)
    }

    @Test
    fun `light and dark differ in every non-brand-critical slot`() {
        val light = ColorTokens.LIGHT.slotPairs().toMap()
        val dark = ColorTokens.DARK.slotPairs().toMap()
        val identical = light.filter { (k, v) -> dark[k] == v }.keys
        // background/surface legitimately share a value within each palette, but
        // no slot should be identical ACROSS palettes: that would mean one side
        // was never designed.
        assertEquals(emptySet<String>(), identical)
    }

    /**
     * The elevation ramp must be monotonic, otherwise "use a higher surface
     * instead of a shadow" produces a non-hierarchy. Light brightens downward
     * (Lowest is brightest); dark brightens upward.
     */
    @Test
    fun `light elevation ramp is monotonically darker`() {
        val ramp = with(ColorTokens.LIGHT) {
            listOf(
                surfaceContainerLowest, surfaceContainerLow, surfaceContainer,
                surfaceContainerHigh, surfaceContainerHighest,
            )
        }.map { Contrast.relativeLuminance(it) }
        for (i in 0 until ramp.size - 1) {
            assertTrue(ramp[i] > ramp[i + 1], "light ramp not descending at $i: $ramp")
        }
    }

    @Test
    fun `dark elevation ramp is monotonically lighter`() {
        val ramp = with(ColorTokens.DARK) {
            listOf(
                surfaceContainerLowest, surfaceContainerLow, surfaceContainer,
                surfaceContainerHigh, surfaceContainerHighest,
            )
        }.map { Contrast.relativeLuminance(it) }
        for (i in 0 until ramp.size - 1) {
            assertTrue(ramp[i] < ramp[i + 1], "dark ramp not ascending at $i: $ramp")
        }
    }

    /**
     * A divider is decorative, so no WCAG threshold applies — but an invisible
     * one is still a bug. Measured: light ~1.70:1, dark ~2.01:1.
     */
    @Test
    fun `hairline is perceptible against the surface it sits on`() {
        for (palette in ColorTokens.ALL) {
            val r = Contrast.ratio(palette.outlineVariant, palette.surfaceContainerLowest)
            assertTrue(
                r > 1.2,
                "outlineVariant on surfaceContainerLowest is nearly invisible: %.3f:1".format(r),
            )
            // ...and it must stay a hairline, not become a heavy border.
            assertTrue(r < Contrast.ACCENT_MIN_RATIO, "hairline is too strong: %.3f:1".format(r))
        }
    }
}
