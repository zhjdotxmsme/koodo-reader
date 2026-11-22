package com.koodoreader.core.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

class BookCardTierTest {

    @Test
    fun `narrow tiles are compact`() {
        assertEquals(BookCardTier.COMPACT, BookCardTier.forWidthDp(104f))
        assertEquals(BookCardTier.COMPACT, BookCardTier.forWidthDp(139.9f))
    }

    @Test
    fun `wide tiles are regular`() {
        assertEquals(BookCardTier.REGULAR, BookCardTier.forWidthDp(140f))
        assertEquals(BookCardTier.REGULAR, BookCardTier.forWidthDp(400f))
    }

    @Test
    fun `degenerate widths stay compact instead of throwing`() {
        assertEquals(BookCardTier.COMPACT, BookCardTier.forWidthDp(0f))
        assertEquals(BookCardTier.COMPACT, BookCardTier.forWidthDp(-1f))
        assertEquals(BookCardTier.COMPACT, BookCardTier.forWidthDp(Float.NaN))
    }
}
