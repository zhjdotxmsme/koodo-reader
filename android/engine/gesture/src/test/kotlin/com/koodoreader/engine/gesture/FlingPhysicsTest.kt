package com.koodoreader.engine.gesture

import kotlin.math.abs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FlingPhysicsTest {

    private val physics = FlingPhysics()

    @Test
    fun `total distance is proportional to velocity`() {
        val d1000 = physics.totalDistance(1000f)
        val d2000 = physics.totalDistance(2000f)
        assertTrue(d2000 > d1000, "2x velocity should give >2x distance")
        assertEquals(d1000 * 2f, d2000, 1f, "distance should be linear in velocity")
    }

    @Test
    fun `total distance for zero velocity is zero`() {
        assertEquals(0f, physics.totalDistance(0f), 0f)
    }

    @Test
    fun `total distance below fling threshold is zero`() {
        assertEquals(0f, physics.totalDistance(10f), 0f)
    }

    @Test
    fun `isFling below threshold returns false`() {
        assertTrue(!physics.isFling(10f))
    }

    @Test
    fun `isFling at and above threshold returns true`() {
        assertTrue(physics.isFling(50f))
        assertTrue(physics.isFling(500f))
    }

    @Test
    fun `duration is positive for fling velocities`() {
        val dur = physics.duration(1000f)
        assertTrue(dur > 0f, "duration should be positive for a fling")
        assertTrue(dur < 10_000f, "duration should be less than 10s")
    }

    @Test
    fun `duration is zero for below-threshold velocity`() {
        assertEquals(0f, physics.duration(10f), 0f)
    }

    @Test
    fun `displacement approaches total distance over time`() {
        val v = 1000f
        val total = physics.totalDistance(v)
        val d100 = physics.displacement(v, 100f)
        val d1000 = physics.displacement(v, 1000f)
        assertTrue(d100 < total, "partial displacement should be less than total")
        assertTrue(d1000 > d100, "more time should give more displacement")
        // At t = 10*τ we should be very close to the total.
        val d10tau = physics.displacement(v, physics.timeConstantMs * 10f)
        assertEquals(total, d10tau, total * 0.001f, "at 10τ should be ~99.9% of total")
    }

    @Test
    fun `predict landing within bounds`() {
        val start = 100f
        val landing = physics.predictLanding(start, 500f, 0f, 1000f)
        assertTrue(landing >= 0f && landing <= 1000f, "landing should be within bounds")
    }

    @Test
    fun `predict landing forward for positive velocity`() {
        val landing = physics.predictLanding(0f, 1000f, 0f, 5000f)
        assertTrue(landing > 0f, "positive velocity should land forward")
    }

    @Test
    fun `predict landing backward for negative velocity`() {
        val landing = physics.predictLanding(1000f, -1000f, 0f, 1000f)
        assertTrue(landing < 1000f, "negative velocity should land backward")
    }

    @Test
    fun `predict landing clamped to bounds`() {
        val landing = physics.predictLanding(0f, 100000f, 0f, 100f)
        assertEquals(100f, landing, 0.1f, "should clamp to max bound")
    }
}
