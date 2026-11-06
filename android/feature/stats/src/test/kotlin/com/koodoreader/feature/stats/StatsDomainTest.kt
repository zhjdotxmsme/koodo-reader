package com.koodoreader.feature.stats

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StatsDomainTest {
    @Test
    fun `daily stats combines sessions and annotation events without double counting`() {
        val day = IsoDay(2026, 11, 2)
        val result = StatsDomain.daily(
            day = day,
            sessions = listOf(
                ReadingSession(key = "s", bookKey = "b", day = day, seconds = 120),
            ),
            events = listOf(
                AnnotationEvent("b", AnnotationEventKind.NOTE, day),
            ),
        )
        assertEquals(120, result.totalDurationSeconds)
        assertEquals(1, result.notesAddedCount)
        assertEquals(0, result.highlightsAddedCount)
    }
}
