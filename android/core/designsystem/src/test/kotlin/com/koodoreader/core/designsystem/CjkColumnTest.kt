package com.koodoreader.core.designsystem

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the CJK column quantisation maths.
 *
 * The contract: the produced width is an exact multiple of the cell advance,
 * and the leftover is split evenly between the two margins.
 */
class CjkColumnTest {

    @Test
    fun `column is an exact multiple of the character advance`() {
        // 360dp of usable width at a 17dp font → 21 whole characters.
        val fit = CjkColumn.fit(availablePx = 360f, fontSizePx = 17f)

        assertEquals(21, fit.cells)
        assertEquals(357f, fit.widthPx, 0.0001f)
        assertEquals(1.5f, fit.sidePadPx, 0.0001f)
        // Invariant: column + 2 pads == available width.
        assertEquals(360f, fit.widthPx + 2 * fit.sidePadPx, 0.0001f)
    }

    @Test
    fun `tracking is part of the cell`() {
        val fit = CjkColumn.fit(availablePx = 360f, fontSizePx = 17f, letterSpacingPx = 1.5f)

        assertEquals(18.5f, fit.cellPx, 0.0001f)
        assertEquals(19, fit.cells)
        // Tracking sits BETWEEN characters, so a run of 19 is
        // 19 × 17 + 18 × 1.5 = 350 — not 19 × 18.5 = 351.5.
        assertEquals(350f, fit.widthPx, 0.0001f)
    }

    @Test
    fun `a tracked run is measured by its gaps, not by whole cells`() {
        // The off-by-one that matters: the column must be the width the
        // engine will actually measure, otherwise every line ends one
        // tracking value short and the justification engine has to spread the
        // remainder into the glyph gaps.
        val fit = CjkColumn.fit(availablePx = 360f, fontSizePx = 17f, letterSpacingPx = 1.5f)
        val measured = fit.cells * 17f + (fit.cells - 1) * 1.5f
        assertEquals(fit.widthPx, measured, 0.0001f)
    }

    @Test
    fun `an exact fit leaves no padding`() {
        val fit = CjkColumn.fit(availablePx = 340f, fontSizePx = 17f)

        assertEquals(20, fit.cells)
        assertEquals(0f, fit.sidePadPx, 0.0001f)
    }

    @Test
    fun `a column too narrow to quantise is left untouched`() {
        val fit = CjkColumn.fit(availablePx = 40f, fontSizePx = 17f, minCells = 4)

        assertEquals(40f, fit.widthPx, 0.0001f)
        assertEquals(0f, fit.sidePadPx, 0.0001f)
        assertTrue(fit.cells < 4)
    }

    @Test
    fun `degenerate inputs never produce NaN`() {
        listOf(
            CjkColumn.fit(availablePx = 360f, fontSizePx = 0f),
            CjkColumn.fit(availablePx = 0f, fontSizePx = 17f),
            CjkColumn.fit(availablePx = -10f, fontSizePx = 17f),
            CjkColumn.fit(availablePx = Float.NaN, fontSizePx = 17f),
        ).forEach { fit ->
            assertTrue(!fit.widthPx.isNaN() && fit.widthPx >= 0f, "width ${fit.widthPx}")
            assertTrue(!fit.sidePadPx.isNaN() && fit.sidePadPx >= 0f, "pad ${fit.sidePadPx}")
        }
    }
}
