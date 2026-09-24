package com.koodoreader.engine.gesture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TapZoneTest {

    private val rule = TapControlRule()
    private val vpW = 300f
    private val vpH = 600f

    @Test
    fun `left zone triggers prev page`() {
        // x in [0, 1/3 * vpW) → column 0 → zone 1, 4, 7
        // Zone 1 (top-left): action = prev
        assertEquals(TapAction.PREV_PAGE, resolveTapAction(10f, 10f, vpW, vpH, rule))
        // Zone 4 (mid-left): action = prev
        assertEquals(TapAction.PREV_PAGE, resolveTapAction(10f, 300f, vpW, vpH, rule))
    }

    @Test
    fun `right zone triggers next page`() {
        // x in [2/3 * vpW, vpW) → column 2 → zone 3, 6, 9
        // Zone 3 (top-right): action = next
        assertEquals(TapAction.NEXT_PAGE, resolveTapAction(290f, 10f, vpW, vpH, rule))
        // Zone 6 (mid-right): action = next
        assertEquals(TapAction.NEXT_PAGE, resolveTapAction(290f, 300f, vpW, vpH, rule))
    }

    @Test
    fun `center zone triggers none`() {
        // x in [1/3, 2/3) * vpW → column 1 → zone 2, 5, 8
        // Zone 5 (center): action = none
        assertEquals(TapAction.NONE, resolveTapAction(150f, 300f, vpW, vpH, rule))
    }

    @Test
    fun `zero viewport returns none`() {
        assertEquals(TapAction.NONE, resolveTapAction(50f, 50f, 0f, 0f, rule))
    }

    @Test
    fun `tapPageTurnCode mapping`() {
        assertEquals(0, tapPageTurnCode(10f, 10f, vpW, vpH, rule), "left should be 0 (prev)")
        assertEquals(1, tapPageTurnCode(290f, 10f, vpW, vpH, rule), "right should be 1 (next)")
        assertEquals(-1, tapPageTurnCode(150f, 300f, vpW, vpH, rule), "center should be -1 (none)")
    }

    @Test
    fun `top row maps to top zones`() {
        // Top-center (zone 2) is in defaultPrevZones → PREV_PAGE
        assertEquals(TapAction.PREV_PAGE, resolveTapAction(150f, 100f, vpW, vpH, rule))
    }

    @Test
    fun `bottom-left zone 7 is prev`() {
        assertEquals(TapAction.PREV_PAGE, resolveTapAction(10f, 590f, vpW, vpH, rule))
    }

    @Test
    fun `bottom-right zone 9 is next`() {
        assertEquals(TapAction.NEXT_PAGE, resolveTapAction(290f, 590f, vpW, vpH, rule))
    }

    @Test
    fun `custom rule can change zones`() {
        val customRule = TapControlRule(
            zonePrev = setOf(1),
            zoneNext = setOf(9),
        )
        // Center (zone 5) should now be NONE since it's in neither set
        assertEquals(TapAction.NONE, resolveTapAction(150f, 300f, vpW, vpH, customRule))
        // Zone 1 (top-left) is now the only prev zone
        assertEquals(TapAction.PREV_PAGE, resolveTapAction(10f, 10f, vpW, vpH, customRule))
        // Zone 9 (bottom-right) is now the only next zone
        assertEquals(TapAction.NEXT_PAGE, resolveTapAction(290f, 590f, vpW, vpH, customRule))
    }
}