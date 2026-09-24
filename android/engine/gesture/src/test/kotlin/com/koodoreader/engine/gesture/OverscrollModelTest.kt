package com.koodoreader.engine.gesture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OverscrollModelTest {

    private val model = OverscrollModel()

    @Test
    fun `disabled returns zero`() {
        model.enabled = false
        assertEquals(0f, model.displacement(100f, 400f), 0f)
    }

    @Test
    fun `zero overscroll returns zero`() {
        assertEquals(0f, model.displacement(0f, 400f), 0f)
    }

    @Test
    fun `rubberband is less than raw overscroll`() {
        val raw = 200f
        val dim = 400f
        val damped = model.displacement(raw, dim)
        assertTrue(damped < raw, "rubberband should damp the displacement")
        assertTrue(damped > 0f, "rubberband should still move something")
    }

    @Test
    fun `rubberband saturates`() {
        val dim = 400f
        val d1 = model.displacement(400f, dim)
        val d10 = model.displacement(4000f, dim)
        val d100 = model.displacement(40000f, dim)
        assertTrue(d100 > d10, "more over-scroll should give more displacement")
        assertTrue(d100 < dim, "displacement should saturate below 1x viewport")
    }

    @Test
    fun `monotonically increasing`() {
        val dim = 400f
        val d1 = model.displacement(10f, dim)
        val d2 = model.displacement(50f, dim)
        val d3 = model.displacement(200f, dim)
        val d4 = model.displacement(500f, dim)
        assertTrue(d1 < d2 && d2 < d3 && d3 < d4, "should be monotonically increasing")
    }

    @Test
    fun `springBackProgress`() {
        val progress = model.springBackProgress(50f, 100f)
        assertEquals(0.5f, progress, 0.01f)
    }

    @Test
    fun `springBackProgress fully sprung`() {
        assertEquals(1f, model.springBackProgress(0f, 100f), 0f)
    }
}
