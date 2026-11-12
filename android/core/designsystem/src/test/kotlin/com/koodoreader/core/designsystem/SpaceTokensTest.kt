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
    fun `base scale matches the third round contract`() {
        assertEquals(listOf(4, 8, 16, 24, 32), SpaceTokens.ALL)
        assertEquals(16, SpaceTokens.md)
        assertEquals(24, SpaceTokens.lg)
        assertEquals(32, SpaceTokens.xl)
    }

    @Test
    fun `semantic values cover shelf reader and lists`() {
        assertEquals(12, SpaceTokens.GRID_GAP_HORIZONTAL)
        assertEquals(16, SpaceTokens.GRID_GAP_VERTICAL)
        assertEquals(12, SpaceTokens.LIST_ITEM_VERTICAL)
        assertEquals(20, SpaceTokens.READER_HORIZONTAL_DEFAULT)
        assertEquals(listOf(12, 16, 20, 24), SpaceTokens.READER_HORIZONTAL_OPTIONS)
        assertEquals(56, SpaceTokens.TOP_BAR_HEIGHT)
    }
}
