package com.koodoreader.feature.stats

/**
 * Derives annotation stats from Room note rows (date payload is not stored natively;
 * callers supply event day from import time or `IsoDay.today()`).
 */
object AnnotationEvents {

    data class NoteRow(
        val bookKey: String,
        val hasNoteBody: Boolean,
        val day: IsoDay,
    )

    fun fromRows(rows: List<NoteRow>): List<AnnotationEvent> = rows.map { row ->
        AnnotationEvent(
            bookKey = row.bookKey,
            kind = if (row.hasNoteBody) AnnotationEventKind.NOTE else AnnotationEventKind.HIGHLIGHT,
            day = row.day,
        )
    }
}
