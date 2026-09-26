package com.koodoreader.core.designsystem

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpaceTokensTest {

    @Test
    fun `scale is strictly ascending with no gaps or duplicates`() {
        for (i in 0 until SpaceTokens.ALL.size - 1) {
            assertTrue(
                SpaceTokens.ALL[i] < SpaceTokens.ALL[i + 1],
                "spacing scale not strictly ascending at $i: ${SpaceTokens.ALL}",
            )
        }
        assertEquals(SpaceTokens.ALL.size, SpaceTokens.ALL.toSet().size, "duplicate step")
    }

    @Test
    fun `scale spans 4 to 32 on a 4dp grid`() {
        assertEquals(4, SpaceTokens.ALL.first())
        assertEquals(32, SpaceTokens.ALL.last())
        for (v in SpaceTokens.ALL) {
            assertEquals(0, v % 4, "$v is off the 4dp grid")
        }
    }

    @Test
    fun `named steps map to their scale positions`() {
        assertEquals(4, SpaceTokens.xs)
        assertEquals(8, SpaceTokens.sm)
        assertEquals(12, SpaceTokens.md)
        assertEquals(16, SpaceTokens.lg)
        assertEquals(24, SpaceTokens.xl)
        assertEquals(32, SpaceTokens.xxl)
    }

    /**
     * The invariant that keeps the scale meaningful: a named application point
     * may not pick a value off the scale. The previous library list used 10dp,
     * which is exactly the kind of magic number this token exists to prevent.
     */
    @Test
    fun `every named constant is a member of the scale`() {
        for (v in SpaceTokens.NAMED) {
            assertTrue(
                SpaceTokens.ALL.contains(v),
                "named spacing constant $v is not on the scale ${SpaceTokens.ALL}",
            )
        }
    }

    @Test
    fun `documented application points keep their values`() {
        assertEquals(16, SpaceTokens.SCREEN_HORIZONTAL)
        assertEquals(8, SpaceTokens.LIST_GAP)
        assertEquals(12, SpaceTokens.GRID_GAP_HORIZONTAL)
        assertEquals(16, SpaceTokens.GRID_GAP_VERTICAL)
    }
}
