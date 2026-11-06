package com.koodoreader.feature.stats

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AnnotationEventsTest {

    @Test
    fun `note body counts as note otherwise highlight`() {
        val day = IsoDay.parse("2026-11-03")
        val events = AnnotationEvents.fromRows(
            listOf(
                AnnotationEvents.NoteRow("a", hasNoteBody = false, day = day),
                AnnotationEvents.NoteRow("a", hasNoteBody = true, day = day),
            ),
        )
        assertEquals(1, events.count { it.kind == AnnotationEventKind.HIGHLIGHT })
        assertEquals(1, events.count { it.kind == AnnotationEventKind.NOTE })
    }
}
