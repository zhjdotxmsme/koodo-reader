package com.koodoreader.reader.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class SelectionControllerTest {

    @Test
    fun `backward drag normalises anchors without losing source offsets`() {
        val state = SelectionController().start(10).moveEnd(3).finish()
        assertEquals(3, state.start)
        assertEquals(10, state.end)
        assertFalse(state.active)
    }

    @Test
    fun `forward drag keeps anchors`() {
        val state = SelectionController().start(2).moveEnd(9).finish()
        assertEquals(2, state.start)
        assertEquals(9, state.end)
    }

    @Test
    fun `start handle can be dragged independently`() {
        val state = SelectionController().start(10).moveEnd(20).moveStart(4).finish()
        assertEquals(4, state.start)
        assertEquals(20, state.end)
    }

    @Test
    fun `cancel clears the selection`() {
        val controller = SelectionController().start(5)
        controller.cancel()
        assertEquals(SelectionState(), controller.state)
    }
}
