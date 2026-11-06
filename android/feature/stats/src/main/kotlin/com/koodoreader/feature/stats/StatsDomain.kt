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

/** Today / last-7-days / last-30-days reading seconds. */
data class StatsSummary(
    val todaySeconds: Long,
    val weekSeconds: Long,
    val monthSeconds: Long,
)

/** One slice of the per-format reading-time ring chart. */
data class FormatShare(
    val format: String,
    val seconds: Long,
    val fraction: Double,
)

/** Book display info needed by ranking / format stats (title + format). */
data class StatsBookInfo(
    val title: String,
    val format: String,
)

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

    fun summary(sessions: List<ReadingSession>, today: IsoDay): StatsSummary = StatsSummary(
        todaySeconds = sessions.filter { it.day == today }.sumOf { it.seconds.coerceAtLeast(0) },
        weekSeconds = inRange(sessions, StatsRange.SEVEN_DAYS, today).sumOf { it.seconds.coerceAtLeast(0) },
        monthSeconds = inRange(sessions, StatsRange.THIRTY_DAYS, today).sumOf { it.seconds.coerceAtLeast(0) },
    )

    /** Inclusive window ending at [today] (7/30 days) or everything. */
    fun inRange(sessions: List<ReadingSession>, range: StatsRange, today: IsoDay): List<ReadingSession> {
        val from = when (range) {
            StatsRange.SEVEN_DAYS -> today.plusDays(-6)
            StatsRange.THIRTY_DAYS -> today.plusDays(-29)
            StatsRange.ALL -> null
        }
        return if (from == null) sessions
        else sessions.filter { it.day >= from && it.day <= today }
    }

    /** Reading seconds per format; fractions sum to 1 when any time exists. */
    fun formatDistribution(
        sessions: List<ReadingSession>,
        books: Map<String, StatsBookInfo>,
    ): List<FormatShare> {
        val byFormat = sessions.groupBy { session ->
            books[session.bookKey]?.format?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: "OTHER"
        }.mapValues { (_, rows) -> rows.sumOf { it.seconds.coerceAtLeast(0) } }
        val total = byFormat.values.sum().coerceAtLeast(1L)
        return byFormat.map { (format, seconds) ->
            FormatShare(format, seconds, seconds.toDouble() / total)
        }.sortedWith(compareByDescending<FormatShare> { it.seconds }.thenBy { it.format })
    }

    /** 24 hourly buckets of reading seconds, attributed to the session's local
     *  start hour (session spans are already day-split by the repository). */
    fun hourDistribution(sessions: List<ReadingSession>, zoneOffsetMinutes: Int): List<Long> {
        val buckets = MutableList(24) { 0L }
        sessions.forEach { session ->
            val localMillis = session.startMillis + zoneOffsetMinutes * 60_000L
            val hour = (Math.floorDiv(localMillis, 3_600_000L) % 24).toInt()
            buckets[hour] += session.seconds.coerceAtLeast(0)
        }
        return buckets
    }
}
