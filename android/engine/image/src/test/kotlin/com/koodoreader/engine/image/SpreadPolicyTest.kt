package com.koodoreader.engine.image

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 单页/双页排版策略：封面单独一页、成对分组、末页落单、RTL 视觉顺序。 */
class SpreadPolicyTest {

    private val double = SpreadConfig()

    private fun spread(index: Int, pageCount: Int = 10): Spread =
        SpreadPolicy.spreadAt(index, pageCount, double)

    @Test
    fun `cover stays single and the rest pair up`() {
        assertEquals(listOf(0), spread(0).pages)
        assertEquals(listOf(1, 2), spread(1).pages)
        assertEquals(listOf(1, 2), spread(2).pages, "同一跨页的两页落到同一组")
        assertEquals(listOf(3, 4), spread(3).pages)
        assertEquals(listOf(5, 6), spread(5).pages)
        assertFalse(spread(0).isDouble)
        assertTrue(spread(1).isDouble)
    }

    @Test
    fun `a trailing single page degrades to single`() {
        // 6 页 = 封面 + 1,2 + 3,4 + 落单的 5
        val last = SpreadPolicy.spreadAt(5, 6, double)
        assertEquals(listOf(5), last.pages)
        assertFalse(last.isDouble)
        assertTrue(SpreadPolicy.isLast(5, 6, double))
        assertFalse(SpreadPolicy.isLast(3, 6, double))
    }

    @Test
    fun `cover can share a spread when coverAsSingle is off`() {
        val config = double.copy(coverAsSingle = false)
        assertEquals(listOf(0, 1), SpreadPolicy.spreadAt(1, 6, config).pages)
        assertEquals(listOf(2, 3), SpreadPolicy.spreadAt(2, 6, config).pages)
        assertEquals(0, SpreadPolicy.spreadAt(0, 6, config).start)
    }

    @Test
    fun `rtl reverses the visual order but not the page numbers`() {
        val rtl = double.copy(direction = ReadingDirection.RTL)
        val spread = SpreadPolicy.spreadAt(1, 6, rtl)

        assertEquals(listOf(1, 2), spread.pages)
        assertEquals(listOf(2, 1), spread.visualOrder)
    }

    @Test
    fun `single page mode is a pure passthrough`() {
        val single = double.copy(doublePage = false)
        assertEquals(listOf(3), SpreadPolicy.spreadAt(3, 10, single).pages)
        assertEquals(4, SpreadPolicy.next(3, 10, single))
        assertEquals(2, SpreadPolicy.previous(3, 10, single))
    }

    @Test
    fun `next and previous step by a whole spread`() {
        assertEquals(1, SpreadPolicy.next(0, 10, double), "封面之后是 1-2")
        assertEquals(3, SpreadPolicy.next(1, 10, double))
        assertEquals(0, SpreadPolicy.previous(1, 10, double))
        assertEquals(1, SpreadPolicy.previous(3, 10, double))
        assertEquals(0, SpreadPolicy.previous(0, 10, double), "首页之前还是首页")

        assertTrue(SpreadPolicy.isFirst(0, 10, double))
        assertFalse(SpreadPolicy.isFirst(2, 10, double), "1-2 跨页还能退回封面")
    }

    @Test
    fun `indexes are clamped and empty archives are safe`() {
        assertEquals(listOf(9), SpreadPolicy.spreadAt(99, 10, double).pages)
        assertTrue(SpreadPolicy.spreadAt(0, 0, double).pages.isEmpty())
        assertEquals(0, SpreadPolicy.next(0, 0, double))
        assertEquals(0, SpreadPolicy.previous(0, 0, double))
    }
}
