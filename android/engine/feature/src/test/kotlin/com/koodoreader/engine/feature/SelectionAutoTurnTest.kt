package com.koodoreader.engine.feature

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SelectionAutoTurnTest {

    private val width = 400f
    private val height = 800f

    private fun input(
        pointerY: Float,
        selectionTop: Float = pointerY,
        selectionBottom: Float = pointerY,
        dragDx: Float = 0f,
        dragDy: Float = 0f,
        pointerX: Float = 200f,
        viewportWidth: Float = width,
        viewportHeight: Float = height,
    ) = SelectionAutoTurnInput(
        pointerX = pointerX,
        pointerY = pointerY,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
        selectionTop = selectionTop,
        selectionBottom = selectionBottom,
        dragDx = dragDx,
        dragDy = dragDy,
    )

    @Test
    fun `disabled config never turns`() {
        val decision = SelectionAutoTurn.decide(
            input(790f),
            SelectionAutoTurnConfig(enabled = false),
        )
        assertFalse(decision.turn)
        assertEquals(TurnDirection.NONE, decision.direction)
        assertEquals(TurnReason.DISABLED, decision.reason)
    }

    @Test
    fun `degenerate viewport never turns`() {
        val decision = SelectionAutoTurn.decide(input(400f, viewportHeight = 0f))
        assertFalse(decision.turn)
        assertEquals(TurnReason.INVALID_VIEWPORT, decision.reason)

        val flat = SelectionAutoTurn.decide(input(0f, viewportWidth = 0f))
        assertFalse(flat.turn)
        assertEquals(TurnReason.INVALID_VIEWPORT, flat.reason)
    }

    @Test
    fun `reaching the bottom edge turns next`() {
        val decision = SelectionAutoTurn.decide(input(pointerY = 780f, selectionTop = 700f))
        assertTrue(decision.turn)
        assertEquals(TurnDirection.NEXT, decision.direction)
        assertEquals(TurnReason.EDGE_END, decision.reason)
    }

    @Test
    fun `reaching the top edge turns prev`() {
        val decision = SelectionAutoTurn.decide(
            input(pointerY = 10f, selectionTop = 10f, selectionBottom = 60f),
        )
        assertTrue(decision.turn)
        assertEquals(TurnDirection.PREV, decision.direction)
        assertEquals(TurnReason.EDGE_START, decision.reason)
    }

    @Test
    fun `either end of the selection arms the turn`() {
        // pointer is far from the edge, but the selection bottom is not
        val decision = SelectionAutoTurn.decide(
            input(pointerY = 400f, selectionTop = 300f, selectionBottom = 770f),
        )
        assertEquals(TurnDirection.NEXT, decision.direction)
        assertEquals(TurnReason.EDGE_END, decision.reason)
    }

    @Test
    fun `threshold boundary is inclusive`() {
        val threshold = 48f
        // exactly at the threshold → armed
        val atEdge = SelectionAutoTurn.decide(
            input(pointerY = height - threshold, selectionTop = 700f),
            SelectionAutoTurnConfig(edgeThresholdPx = threshold),
        )
        assertEquals(TurnDirection.NEXT, atEdge.direction)

        // one pixel further in, with no drag → idle
        val idle = SelectionAutoTurn.decide(
            input(pointerY = height - threshold - 1f, selectionTop = 700f),
            SelectionAutoTurnConfig(edgeThresholdPx = threshold),
        )
        assertFalse(idle.turn)
        assertEquals(TurnReason.NONE, idle.reason)
    }

    @Test
    fun `zero threshold only fires exactly on the edge`() {
        val config = SelectionAutoTurnConfig(edgeThresholdPx = 0f)
        assertEquals(
            TurnDirection.NEXT,
            SelectionAutoTurn.decide(input(pointerY = height), config).direction,
        )
        assertFalse(SelectionAutoTurn.decide(input(pointerY = height - 1f), config).turn)
    }

    @Test
    fun `drag direction decides when no edge is near`() {
        val upward = SelectionAutoTurn.decide(
            input(pointerY = 400f, selectionTop = 300f, dragDy = -30f),
        )
        assertTrue(upward.turn)
        assertEquals(TurnDirection.NEXT, upward.direction)
        assertEquals(TurnReason.DRAG_TOWARD_END, upward.reason)

        val downward = SelectionAutoTurn.decide(
            input(pointerY = 400f, selectionTop = 300f, dragDy = 30f),
        )
        assertTrue(downward.turn)
        assertEquals(TurnDirection.PREV, downward.direction)
        assertEquals(TurnReason.DRAG_TOWARD_START, downward.reason)
    }

    @Test
    fun `drag below the minimum distance is ignored`() {
        val config = SelectionAutoTurnConfig(minDragPx = 24f)

        val tooSmall = SelectionAutoTurn.decide(
            input(pointerY = 400f, selectionTop = 300f, dragDy = -23f),
            config,
        )
        assertFalse(tooSmall.turn)
        assertEquals(TurnReason.NONE, tooSmall.reason)

        // exactly the minimum is enough
        val atMinimum = SelectionAutoTurn.decide(
            input(pointerY = 400f, selectionTop = 300f, dragDy = -24f),
            config,
        )
        assertTrue(atMinimum.turn)
        assertEquals(TurnDirection.NEXT, atMinimum.direction)
    }

    @Test
    fun `horizontal drags never turn the page`() {
        val decision = SelectionAutoTurn.decide(
            input(pointerY = 400f, selectionTop = 300f, dragDx = 200f, dragDy = 0f),
        )
        assertFalse(decision.turn)
        assertEquals(TurnDirection.NONE, decision.direction)
        assertEquals(TurnReason.NONE, decision.reason)
    }

    @Test
    fun `ties resolve towards the nearer edge`() {
        // symmetric situation: both distances are exactly the threshold (48)
        val decision = SelectionAutoTurn.decide(
            input(pointerY = 48f, viewportHeight = 96f),
            SelectionAutoTurnConfig(edgeThresholdPx = 48f),
        )
        assertTrue(decision.turn)
        assertEquals(TurnDirection.NEXT, decision.direction)
        assertEquals(TurnReason.EDGE_END, decision.reason)
    }

    @Test
    fun `edge distance helper`() {
        assertEquals(10f, SelectionAutoTurn.edgeDistance(10f, 800f), 1e-4f)
        assertEquals(10f, SelectionAutoTurn.edgeDistance(790f, 800f), 1e-4f)
        assertEquals(400f, SelectionAutoTurn.edgeDistance(400f, 800f), 1e-4f)
        assertEquals(Float.MAX_VALUE, SelectionAutoTurn.edgeDistance(10f, 0f), 0f)
    }

    @Test
    fun `defaults match the p6 tuning`() {
        val config = SelectionAutoTurnConfig()
        assertTrue(config.enabled)
        assertEquals(48f, config.edgeThresholdPx, 1e-4f)
        assertEquals(24f, config.minDragPx, 1e-4f)
        assertEquals(TurnDirection.NONE, SelectionAutoTurnDecision.IDLE.direction)
    }
}
