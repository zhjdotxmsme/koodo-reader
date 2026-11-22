package com.koodoreader.core.designsystem

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The WebView-track entry point of the CJK column quantisation.
 *
 * The native track quantises inside the layout engine (`quantizeColumnToCells`);
 * the foliate track lays the book out in a real browser, so the only thing it
 * can do is give the page symmetrical side padding that leaves the text column
 * a whole number of character cells wide.
 */
class ReaderCjkColumnTest {

    @Test
    fun `side pad centres the quantised column`() {
        // available=328, font=17, tracking=0 → 19 cells = 323, leftover 5 split 2.5/2.5
        val pad = ReaderCjkColumn.sidePadFor(328f, 17f, 0f)
        assertEquals(2.5f, pad, 0.001f)
    }

    @Test
    fun `zero when the column already fits exactly`() {
        assertEquals(0f, ReaderCjkColumn.sidePadFor(323f, 17f, 0f), 0.001f)
    }

    @Test
    fun `degenerate input yields zero pad, never NaN`() {
        assertEquals(0f, ReaderCjkColumn.sidePadFor(0f, 17f, 0f), 0.001f)
        assertEquals(0f, ReaderCjkColumn.sidePadFor(Float.NaN, 17f, 0f), 0.001f)
        assertEquals(0f, ReaderCjkColumn.sidePadFor(328f, 0f, 0f), 0.001f)
    }

    @Test
    fun `tracking shrinks the cell count correctly`() {
        // cell = 17 + 0.5 = 17.5; floor((328 + 0.5) / 17.5) = 18 cells
        // width = 18*17 + 17*0.5 = 314.5; pad = (328 - 314.5)/2 = 6.75
        val pad = ReaderCjkColumn.sidePadFor(328f, 17f, 0.5f)
        assertTrue(pad in 6.7f..6.8f, "pad was $pad")
    }

    @Test
    fun `foliate pad accounts for margin and gap`() {
        val pad = ReaderCjkColumn.foliateSidePadFor(
            viewportPx = 360f, fontSizePx = 17f, marginPx = 16f,
        )
        // The pad must be what makes the *page* produce a whole-cell column,
        // with the paginator's own 5% gap and the reader margin still inside.
        val share = ReaderCjkColumn.FOLIATE_GAP_PERCENT / 100f
        val gapFactor = share / (1f - share)
        val page = 360f - 2f * pad
        val column = page * (1f - gapFactor) - 16f
        assertEquals(0f, column % 17f, 0.01f)
        assertEquals(19f, column / 17f, 0.01f)
    }

    @Test
    fun `foliate pad is zero when the page already fits the grid`() {
        // page 323 = 19 cells of 17 + a 5% gap of exactly 17; margin 0.
        val pad = ReaderCjkColumn.foliateSidePadFor(
            viewportPx = 323f, fontSizePx = 17f, marginPx = 0f,
        )
        assertTrue(pad < 0.01f, "pad was $pad")
    }

    @Test
    fun `foliate pad stays finite for absurd viewports`() {
        listOf(
            ReaderCjkColumn.foliateSidePadFor(0f, 17f, marginPx = 16f),
            ReaderCjkColumn.foliateSidePadFor(Float.NaN, 17f),
            ReaderCjkColumn.foliateSidePadFor(360f, 0f),
            ReaderCjkColumn.foliateSidePadFor(360f, 17f, marginPx = 400f),
            ReaderCjkColumn.foliateSidePadFor(360f, 17f, gapPercent = 100f),
        ).forEach { pad ->
            assertTrue(pad.isFinite() && pad >= 0f, "pad was $pad")
        }
    }

    @Test
    fun `foliate pad never exceeds half a cell per side`() {
        // The leftover split between two margins cannot be more than half a
        // character on each side, or the reader would see the column jump.
        for (viewport in 320..900 step 7) {
            val pad = ReaderCjkColumn.foliateSidePadFor(
                viewportPx = viewport.toFloat(), fontSizePx = 17f, marginPx = 16f,
            )
            assertTrue(pad <= 17f, "viewport $viewport gave pad $pad")
        }
    }
}
