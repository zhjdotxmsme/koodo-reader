package com.koodoreader.core.designsystem

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShapeTokensTest {

    @Test
    fun `scale has exactly the five Material 3 steps`() {
        assertEquals(5, ShapeTokens.ALL.size)
    }

    @Test
    fun `scale matches the Material 3 defaults`() {
        // Keeping these equal means `:core:ui` can build its Compose Shapes from
        // these numbers without a translation table.
        assertEquals(ShapeTokens.MATERIAL3_DEFAULTS, ShapeTokens.ALL)
    }

    @Test
    fun `named steps map to their scale positions`() {
        assertEquals(4, ShapeTokens.extraSmall)
        assertEquals(8, ShapeTokens.small)
        assertEquals(12, ShapeTokens.medium)
        assertEquals(16, ShapeTokens.large)
        assertEquals(28, ShapeTokens.extraLarge)
    }

    @Test
    fun `scale is strictly ascending`() {
        for (i in 0 until ShapeTokens.ALL.size - 1) {
            assertTrue(
                ShapeTokens.ALL[i] < ShapeTokens.ALL[i + 1],
                "shape scale not ascending at $i: ${ShapeTokens.ALL}",
            )
        }
    }

    @Test
    fun `every step is positive`() {
        assertTrue(ShapeTokens.ALL.all { it > 0 })
    }

    @Test
    fun `documented card and chip radii are on the scale`() {
        // Cards/sheets use large (16) and chips use small (8) — the mapping the
        // library and settings components rely on.
        assertTrue(ShapeTokens.ALL.contains(16))
        assertTrue(ShapeTokens.ALL.contains(8))
    }
}
