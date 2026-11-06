package com.koodoreader.feature.stats

enum class AnnotationEventKind { NOTE, HIGHLIGHT }

data class AnnotationEvent(
    val bookKey: String,
    val kind: AnnotationEventKind,
    val day: IsoDay,
)

data class DailyStatsView(
    val date: IsoDay,
    val totalDurationSeconds: Long,
    val booksReadCount: Int,
    val notesAddedCount: Int,
    val highlightsAddedCount: Int,
)

enum class StatsRange {
    SEVEN_DAYS,
    THIRTY_DAYS,
    ALL,
}

object StatsDomain {
    fun daily(
        day: IsoDay,
        sessions: List<ReadingSession>,
        events: List<AnnotationEvent>,
    ): DailyStatsView {
        val daySessions = sessions.filter { it.day == day }
        val dayEvents = events.filter { it.day == day }
        return DailyStatsView(
            date = day,
            totalDurationSeconds = daySessions.sumOf { it.seconds.coerceAtLeast(0) },
            booksReadCount = daySessions.map { it.bookKey }.toSet().size,
            notesAddedCount = dayEvents.count { it.kind == AnnotationEventKind.NOTE },
            highlightsAddedCount = dayEvents.count { it.kind == AnnotationEventKind.HIGHLIGHT },
        )
    }
}
