package com.koodoreader.feature.stats

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the daily-goal arithmetic: the progress bar must never exceed 100%, the
 * remaining time must never go negative, and "off" must be a distinct state
 * rather than "goal of zero minutes" (which would read as always met).
 */
class ReadingGoalTest {

    @Test
    fun `off is a distinct state, not a met goal`() {
        val p = ReadingGoal.progress(todaySeconds = 600, goalMinutes = 0)

        assertFalse(p.enabled)
        assertFalse(p.met)
        assertEquals(0f, p.fraction)
        assertEquals(600L, p.todaySeconds)
    }

    @Test
    fun `progress is a fraction of the goal`() {
        val p = ReadingGoal.progress(todaySeconds = 15 * 60, goalMinutes = 30)

        assertEquals(0.5f, p.fraction)
        assertEquals(15L * 60, p.remainingSeconds)
        assertFalse(p.met)
    }

    @Test
    fun `reaching the goal is met with nothing remaining`() {
        val p = ReadingGoal.progress(todaySeconds = 30 * 60, goalMinutes = 30)

        assertEquals(1f, p.fraction)
        assertEquals(0L, p.remainingSeconds)
        assertTrue(p.met)
    }

    @Test
    fun `overshooting clamps the bar at one`() {
        val p = ReadingGoal.progress(todaySeconds = 5 * 3600, goalMinutes = 30)

        assertEquals(1f, p.fraction)
        assertEquals(0L, p.remainingSeconds)
        assertTrue(p.met)
    }

    @Test
    fun `negative time reads as zero rather than producing a negative bar`() {
        val p = ReadingGoal.progress(todaySeconds = -100, goalMinutes = 30)

        assertEquals(0L, p.todaySeconds)
        assertEquals(0f, p.fraction)
        assertEquals(30L * 60, p.remainingSeconds)
    }

    @Test
    fun `goals outside the supported range are clamped`() {
        assertEquals(
            ReadingGoal.OFF,
            ReadingGoal.progress(todaySeconds = 0, goalMinutes = -5).goalMinutes,
        )
        assertEquals(
            ReadingGoal.MAX_MINUTES,
            ReadingGoal.progress(todaySeconds = 0, goalMinutes = 10_000).goalMinutes,
        )
    }

    @Test
    fun `presets start with off so the menu can turn the feature off`() {
        assertEquals(ReadingGoal.OFF, ReadingGoal.PRESETS.first())
        assertTrue(ReadingGoal.PRESETS.all { it in 0..ReadingGoal.MAX_MINUTES })
        assertTrue(ReadingGoal.PRESETS.containsAll(listOf(15, 30, 60)))
    }
}
