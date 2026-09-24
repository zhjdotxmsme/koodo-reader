package com.koodoreader.feature.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Desktop-parity tests for the /stats metrics. Every expectation below is
 * derived from `src/pages/stats/component.tsx` (line references in the
 * comments), so a regression here means the native screen would show a
 * different number than the web build for the same data.
 */
class StatsAggregatorTest {

    private val aggregator = StatsAggregator()
    private val today = IsoDay(2024, 3, 15) // Friday

    private fun sessions(vararg days: Pair<IsoDay, Long>): List<ReadingSession> =
        days.mapIndexed { index, (day, seconds) ->
            ReadingSession(
                key = "book$index|$day|$index",
                bookKey = "book$index",
                day = day,
                seconds = seconds,
            )
        }

    // ── cards ────────────────────────────────────────────────────────────────

    @Test
    fun `total seconds and active days are summed per day`() {
        val snapshot = aggregator.aggregate(
            sessions = sessions(
                today to 600L,
                today.plusDays(-1) to 300L,
                today.plusDays(-1) to 300L, // second session, same day
                today.plusDays(-40) to 0L, // zero-second day is NOT active
            ),
            booksRead = 7,
            today = today,
        )
        assertEquals(1200L, snapshot.totalSeconds)
        assertEquals(2, snapshot.activeDays)
        assertEquals(7, snapshot.totalBooks)
        assertEquals(600L, snapshot.avgDailySeconds) // desktop §4: total / activeDays
        assertEquals(600L, snapshot.secondsByDay[today])
        assertEquals(600L, snapshot.secondsByDay[today.plusDays(-1)])
    }

    @Test
    fun `daily average is zero without any active day`() {
        val snapshot = aggregator.aggregate(emptyList(), booksRead = 0, today = today)
        assertEquals(0L, snapshot.totalSeconds)
        assertEquals(0L, snapshot.totalWords)
        assertEquals(0, snapshot.activeDays)
        assertEquals(0, snapshot.longestStreak)
        assertEquals(0L, snapshot.avgDailySeconds)
        assertEquals(30, snapshot.last30Days.size)
    }

    // ── streak (desktop §3) ──────────────────────────────────────────────────

    @Test
    fun `longest streak counts consecutive days backwards from today`() {
        val snapshot = aggregator.aggregate(
            sessions = sessions(
                today to 60L,
                today.plusDays(-1) to 60L,
                today.plusDays(-2) to 60L,
                today.plusDays(-4) to 60L,
            ),
            booksRead = 1,
            today = today,
        )
        assertEquals(3, snapshot.longestStreak)
    }

    @Test
    fun `an old run wins when it is longer than the live run`() {
        // Desktop takes max() over the whole 365-day window, so a 5-day run that
        // ended 100 days ago beats the 2-day run that ends today.
        val snapshot = aggregator.aggregate(
            sessions = sessions(
                today to 60L,
                today.plusDays(-1) to 60L,
                today.plusDays(-100) to 60L,
                today.plusDays(-101) to 60L,
                today.plusDays(-102) to 60L,
                today.plusDays(-103) to 60L,
                today.plusDays(-104) to 60L,
            ),
            booksRead = 1,
            today = today,
        )
        assertEquals(5, snapshot.longestStreak)
    }

    @Test
    fun `days outside the 365-day window are ignored`() {
        val snapshot = aggregator.aggregate(
            sessions = sessions(
                today.plusDays(-364) to 60L, // last day inside the window
                today.plusDays(-365) to 60L, // first day outside
                today.plusDays(-366) to 60L,
            ),
            booksRead = 1,
            today = today,
        )
        assertEquals(1, snapshot.longestStreak)
    }

    // ── last 30 days (desktop §5) ────────────────────────────────────────────

    @Test
    fun `last 30 days is a fixed oldest-first window with M D labels`() {
        val snapshot = aggregator.aggregate(
            sessions = sessions(today to 3600L, today.plusDays(-29) to 60L, today.plusDays(-30) to 60L),
            booksRead = 1,
            today = today,
        )
        val chart = snapshot.last30Days
        assertEquals(30, chart.size)
        assertEquals(today.plusDays(-29), chart.first().day)
        assertEquals(today, chart.last().day)
        assertEquals("3/15", chart.last().label)
        assertEquals("2/15", chart.first().label)
        assertEquals(60L, chart.first().seconds)
        assertEquals(60L, chart.last().minutes) // 3600 s → 60 min (desktop rounds)
        assertEquals(0L, chart[1].seconds)
        assertTrue(chart.none { it.day == today.plusDays(-30) })
    }

    // ── heatmap (desktop §6) ─────────────────────────────────────────────────

    @Test
    fun `heatmap starts on the Sunday 51 weeks back and ends today`() {
        val snapshot = aggregator.aggregate(
            sessions = sessions(IsoDay(2024, 1, 1) to 120L),
            booksRead = 1,
            today = IsoDay(2024, 1, 1), // Monday
        )
        val cells = snapshot.heatmap
        assertEquals(IsoDay(2023, 1, 8), cells.first().day)
        assertEquals(0, cells.first().day.dayOfWeek)
        assertEquals(IsoDay(2024, 1, 1), cells.last().day)
        assertEquals(359, cells.size)
        assertEquals(0, cells.first().level)
        assertEquals(120L, cells.last().seconds)
        assertEquals(1, cells.last().level)
    }

    @Test
    fun `heatmap levels follow the desktop thresholds`() {
        assertEquals(0, StatsAggregator.heatmapLevel(0L))
        assertEquals(1, StatsAggregator.heatmapLevel(1L))
        assertEquals(1, StatsAggregator.heatmapLevel(299L))
        assertEquals(2, StatsAggregator.heatmapLevel(300L))
        assertEquals(2, StatsAggregator.heatmapLevel(899L))
        assertEquals(3, StatsAggregator.heatmapLevel(900L))
        assertEquals(3, StatsAggregator.heatmapLevel(1799L))
        assertEquals(4, StatsAggregator.heatmapLevel(1800L))
        assertEquals(4, StatsAggregator.heatmapLevel(86_400L))
    }

    @Test
    fun `legend keeps the desktop levels that disagree with the cell thresholds`() {
        // Desktop bug-for-bug: the legend uses 0/200/600/1200/2400 while the
        // cells are coloured with 300/900/1800 (see the design doc).
        assertEquals(listOf(0L, 200L, 600L, 1200L, 2400L), StatsAggregator.HEATMAP_LEGEND_SECONDS)
        assertEquals(listOf(300L, 900L, 1800L), StatsAggregator.HEATMAP_COLOR_THRESHOLDS)
    }

    @Test
    fun `heatmap columns are padded to full weeks like the desktop grid`() {
        val snapshot = aggregator.aggregate(emptyList(), booksRead = 0, today = IsoDay(2024, 1, 1))
        val weeks = aggregator.heatmapWeeks(snapshot.heatmap)
        assertEquals(IsoDay(2024, 1, 1).epochDay - IsoDay(2023, 1, 8).epochDay + 1, snapshot.heatmap.size.toLong())
        assertEquals(52, weeks.size)
        assertTrue(weeks.all { it.size == 7 })
        assertTrue(weeks.first().all { it != null })
        // 359 cells → the last column is padded with 5 invisible cells.
        assertEquals(2, weeks.last().count { it != null })
        assertEquals(5, weeks.last().count { it == null })
    }

    @Test
    fun `month anchors label the first column and every column containing the 1st`() {
        // 2024-01-01 is the first day of a month and sits in the last column.
        val snapshot = aggregator.aggregate(emptyList(), booksRead = 0, today = IsoDay(2024, 1, 1))
        val anchorDays = aggregator.heatmapMonthAnchors(aggregator.heatmapWeeks(snapshot.heatmap))
        val anchors = anchorDays.filterNotNull()
        assertEquals(IsoDay(2023, 1, 8), anchors.first()) // first column is always labelled
        assertEquals(IsoDay(2024, 1, 1), anchors.last())
        assertEquals(13, anchors.size) // 12 month starts + the labelled first column
        assertEquals(12, anchors.count { it.day == 1 })
    }

    // ── time formatting (desktop formatTime) ─────────────────────────────────

    @Test
    fun `formatTime matches the desktop rendering`() {
        assertEquals("0s", StatsAggregator.formatTime(0L))
        assertEquals("59s", StatsAggregator.formatTime(59L))
        assertEquals("1m", StatsAggregator.formatTime(60L))
        assertEquals("1m", StatsAggregator.formatTime(119L))
        assertEquals("59m", StatsAggregator.formatTime(3599L))
        assertEquals("1h 0m", StatsAggregator.formatTime(3600L))
        assertEquals("1h 1m", StatsAggregator.formatTime(3661L))
        assertEquals("23h 59m", StatsAggregator.formatTime(86_399L))
        assertEquals("2h 30m", StatsAggregator.formatTime(9000.4))
    }

    // ── P6 additions: words + progress ───────────────────────────────────────

    @Test
    fun `word count treats CJK per character and latin per word`() {
        assertEquals(0L, StatsAggregator.wordCount(""))
        assertEquals(0L, StatsAggregator.wordCount("   \n\t"))
        assertEquals(2L, StatsAggregator.wordCount("hello world"))
        assertEquals(2L, StatsAggregator.wordCount("don't stop"))
        assertEquals(4L, StatsAggregator.wordCount("你好世界"))
        assertEquals(3L, StatsAggregator.wordCount("你好 world"))
        assertEquals(2L, StatsAggregator.wordCount("café naïve"))
        assertEquals(2L, StatsAggregator.wordCount("2024-01"))
        assertEquals(4L, StatsAggregator.wordCount("《三体》 is great"))
    }

    @Test
    fun `word totals come from the sessions`() {
        val snapshot = aggregator.aggregate(
            sessions = listOf(
                ReadingSession("a", "book1", today, seconds = 60L, words = 120L),
                ReadingSession("b", "book1", today, seconds = 60L, words = 30L),
            ),
            booksRead = 1,
            today = today,
        )
        assertEquals(150L, snapshot.totalWords)
    }

    @Test
    fun `average progress converts the desktop 0-1 fraction to percent`() {
        assertEquals(37.5, aggregator.averageProgress(mapOf("a" to 0.5, "b" to 0.25)), 0.0001)
        assertEquals(33.33, aggregator.averageProgress(mapOf("a" to 1.0 / 3.0)), 0.0001)
        assertEquals(0.0, aggregator.averageProgress(mapOf("a" to 0.0)), 0.0001)
        assertEquals(0.0, aggregator.averageProgress(emptyMap()), 0.0001)
        // Only STARTED books count: an unopened book (0 %) must not drag the
        // average of a finished one down.
        assertEquals(100.0, aggregator.averageProgress(mapOf("a" to 1.0, "b" to 0.0)), 0.0001)
    }
}
