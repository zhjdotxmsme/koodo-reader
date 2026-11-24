package com.koodoreader.core.designsystem

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShapeTokensTest {

    @Test
    fun `scale has the UX-Pro-Max steps plus the Material 3 base`() {
        // extraSmall, small, medium, large, extraLarge, capsule
        assertEquals(6, ShapeTokens.ALL.size)
    }

    @Test
    fun `named steps map to their scale positions`() {
        assertEquals(4, ShapeTokens.extraSmall)
        assertEquals(6, ShapeTokens.small)
        assertEquals(12, ShapeTokens.medium)
        assertEquals(16, ShapeTokens.large)
        assertEquals(28, ShapeTokens.extraLarge)
        assertEquals(999, ShapeTokens.capsule)
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
        // Cards use medium (12) and small controls use small (6) — the mapping
        // the library and settings components rely on.
        assertEquals(12, ShapeTokens.medium)
        assertTrue(ShapeTokens.ALL.contains(6))
    }

    @Test
    fun `capsule is a fully rounded pill value`() {
        assertTrue(ShapeTokens.capsule >= 100)
    }
}
