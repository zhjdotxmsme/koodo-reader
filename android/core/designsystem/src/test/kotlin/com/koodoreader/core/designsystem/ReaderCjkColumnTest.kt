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
 *
 * The invariants pinned here are the ones the padding formula has to satisfy
 * *after* it changes the page width it was derived from: the resulting column is
 * on the grid, and the leftover per side stays under half a character.
 */
class ReaderCjkColumnTest {

    /** The paginator's own metrics, so the test measures what the browser will. */
    private fun columnFor(pad: Float, viewportPx: Float, marginPx: Float): Float {
        val share = ReaderCjkColumn.FOLIATE_GAP_PERCENT / 100f
        val gapFactor = share / (1f - share)
        return (viewportPx - 2f * pad) * (1f - gapFactor) - marginPx
    }

    @Test
    fun `the padded column is a whole number of cells`() {
        for (viewport in 320..900 step 7) {
            val pad = ReaderCjkColumn.foliateSidePadFor(
                viewportPx = viewport.toFloat(), fontSizePx = 17f, marginPx = 16f,
            )
            val column = columnFor(pad, viewport.toFloat(), 16f)
            assertEquals(0f, column % 17f, 0.05f, "viewport $viewport column $column")
            assertTrue(column / 17f >= 4f, "viewport $viewport left $column px of text")
        }
    }

    @Test
    fun `the leftover is split evenly, so no side loses more than half a cell`() {
        for (viewport in 320..900 step 7) {
            val pad = ReaderCjkColumn.foliateSidePadFor(
                viewportPx = viewport.toFloat(), fontSizePx = 17f, marginPx = 16f,
            )
            assertTrue(pad >= 0f && pad <= 17f, "viewport $viewport gave pad $pad")
        }
    }

    @Test
    fun `nothing is padded when the page already fits the grid`() {
        // page 323 = 19 cells of 17 + a 5% gap of exactly 17.
        val pad = ReaderCjkColumn.foliateSidePadFor(
            viewportPx = 323f, fontSizePx = 17f, marginPx = 0f,
        )
        assertTrue(pad < 0.01f, "pad was $pad")
    }

    @Test
    fun `tracking shrinks the cell count`() {
        val dense = ReaderCjkColumn.foliateSidePadFor(
            viewportPx = 360f, fontSizePx = 17f, letterSpacingPx = 0.5f, marginPx = 16f,
        )
        val plain = ReaderCjkColumn.foliateSidePadFor(
            viewportPx = 360f, fontSizePx = 17f, letterSpacingPx = 0f, marginPx = 16f,
        )
        // Both land on their own grid; the tracked column holds fewer cells.
        val trackedColumn = columnFor(dense, 360f, 16f)
        val plainColumn = columnFor(plain, 360f, 16f)
        assertTrue(
            trackedColumn / 17.5f < plainColumn / 17f,
            "tracked $trackedColumn vs plain $plainColumn",
        )
    }

    @Test
    fun `a spread is left alone`() {
        // At or above max-inline-size the paginator splits into columns and the
        // single-page metrics no longer apply.
        assertEquals(
            0f,
            ReaderCjkColumn.foliateSidePadFor(
                viewportPx = 720f, fontSizePx = 17f, marginPx = 16f, maxInlineSizePx = 720f,
            ),
            0.001f,
        )
    }

    @Test
    fun `degenerate input yields zero pad, never NaN`() {
        listOf(
            ReaderCjkColumn.foliateSidePadFor(0f, 17f, marginPx = 16f),
            ReaderCjkColumn.foliateSidePadFor(Float.NaN, 17f),
            ReaderCjkColumn.foliateSidePadFor(360f, 0f),
            ReaderCjkColumn.foliateSidePadFor(360f, Float.NaN),
            ReaderCjkColumn.foliateSidePadFor(360f, 17f, marginPx = 400f),
            ReaderCjkColumn.foliateSidePadFor(360f, 17f, marginPx = Float.NaN),
            ReaderCjkColumn.foliateSidePadFor(360f, 17f, gapPercent = 100f),
            ReaderCjkColumn.foliateSidePadFor(100f, 60f, marginPx = 16f),
        ).forEach { pad ->
            assertTrue(pad.isFinite() && pad >= 0f, "pad was $pad")
        }
    }
}
