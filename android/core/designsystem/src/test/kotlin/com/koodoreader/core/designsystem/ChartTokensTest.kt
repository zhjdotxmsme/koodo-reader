package com.koodoreader.core.designsystem

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Invariants for the chart palette.
 *
 * These are the properties that make a chart readable rather than merely
 * coloured: a heatmap whose ramp has no direction cannot be read as a scale, and
 * a series line that disappears into the page is not a line.
 */
class ChartTokensTest {

    @Test
    fun `every mode defines exactly four heatmap levels`() {
        for (t in ChartTokens.ALL) {
            assertEquals(ChartTokens.HEATMAP_LEVELS, t.heatmap.size)
        }
    }

    @Test
    fun `heatmap ramp is strictly monotonic within each mode`() {
        // Light: more activity = darker. Dark: more activity = brighter. The two
        // directions are opposite, which is correct and therefore asserted
        // explicitly rather than as a single "ascending" rule.
        val light = ChartTokens.LIGHT.heatmap.map { Contrast.relativeLuminance(it) }
        val dark = ChartTokens.DARK.heatmap.map { Contrast.relativeLuminance(it) }
        assertTrue(
            light.zipWithNext().all { (a, b) -> a > b },
            "light heatmap ramp should get darker: $light",
        )
        assertTrue(
            dark.zipWithNext().all { (a, b) -> a < b },
            "dark heatmap ramp should get brighter: $dark",
        )
    }

    @Test
    fun `heatmap cells are fully opaque so the ramp cannot be muddied`() {
        for (t in ChartTokens.ALL) {
            for (cell in t.heatmap) {
                assertEquals(0xFF, Contrast.alphaOf(cell), "translucent heatmap cell ${Contrast.hex(cell)}")
            }
        }
    }

    @Test
    fun `heatmap steps are visibly different from each other`() {
        // Adjacent GitHub-style greens are close; require each step to be a real
        // step, not a rounding artefact.
        for (t in ChartTokens.ALL) {
            val ratios = t.heatmap.zipWithNext { a, b -> Contrast.ratio(a, b) }
            assertTrue(
                ratios.all { it > 1.15 },
                "two heatmap steps are nearly identical: $ratios",
            )
        }
    }

    @Test
    fun `grid and heatmap-empty are translucent overlays by design`() {
        // The opposite of the ColorTokens rule, and intentional: both must read
        // as faint marks over any surface.
        for (t in ChartTokens.ALL) {
            assertTrue(Contrast.alphaOf(t.grid) < 0xFF, "grid should be translucent")
            assertTrue(Contrast.alphaOf(t.heatmapEmpty) < 0xFF, "heatmapEmpty should be translucent")
        }
        assertTrue(Contrast.alphaOf(ChartTokens.LIGHT.grid) > 0, "grid is invisible")
    }

    @Test
    fun `overlay alpha stays faint enough not to compete with content`() {
        // A real invariant, unlike an ordering between the two: both marks are
        // meant to sit *under* the data, so neither may be more than half opaque.
        // (The inherited values are grid 0x14 = 20 and heatmapEmpty 0x12 = 18;
        // grid happens to be the slightly stronger of the two, which is fine —
        // there is no ordering rule between them, and inventing one would have
        // meant either editing inherited colours or writing a test that lies.)
        for (t in ChartTokens.ALL) {
            for ((name, v) in listOf("grid" to t.grid, "heatmapEmpty" to t.heatmapEmpty)) {
                val a = Contrast.alphaOf(v)
                assertTrue(a > 0, "$name is invisible")
                assertTrue(a <= 0x80, "$name is too opaque to be an overlay: alpha=$a")
            }
        }
    }

    @Test
    fun `the series line is perceptibly distinct from its page background`() {
        // WCAG's 3:1 graphical-object target is NOT asserted here: the light line
        // measures ~2.6:1 on the light background, and darkening the brand orange
        // was rejected as a restyle (this change is a re-homing). Perceptibility is
        // the honest bar; the measured value is documented in the card.
        for ((t, bg) in listOf(ChartTokens.LIGHT to ColorTokens.LIGHT.background, ChartTokens.DARK to ColorTokens.DARK.background)) {
            val r = Contrast.ratio(t.line, bg)
            assertTrue(r > 1.5, "series line is nearly invisible on ${Contrast.hex(bg)}: %.2f:1".format(r))
        }
    }

    @Test
    fun `light and dark chart palettes differ`() {
        assertTrue(ChartTokens.LIGHT.line != ChartTokens.DARK.line)
        assertTrue(ChartTokens.LIGHT.heatmap != ChartTokens.DARK.heatmap)
    }

    @Test
    fun `heatmapColor maps level zero to the empty cell and clamps out of range`() {
        val t = ChartTokens.LIGHT
        assertEquals(t.heatmapEmpty, ChartTokens.heatmapColor(t, 0))
        assertEquals(t.heatmapEmpty, ChartTokens.heatmapColor(t, -5))
        assertEquals(t.heatmap[0], ChartTokens.heatmapColor(t, 1))
        assertEquals(t.heatmap[3], ChartTokens.heatmapColor(t, 4))
        // Beyond the ramp stays at the top step rather than throwing.
        assertEquals(t.heatmap[3], ChartTokens.heatmapColor(t, 99))
    }
}
