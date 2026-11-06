package com.koodoreader.feature.stats

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StatsDistributionTest {

    private val today = IsoDay(2026, 11, 3)

    private fun session(
        bookKey: String,
        daysAgo: Long,
        seconds: Long,
        startMillis: Long = 0L,
    ) = ReadingSession(
        key = "$bookKey-$daysAgo-$seconds",
        bookKey = bookKey,
        day = today.plusDays(-daysAgo),
        seconds = seconds,
        startMillis = startMillis,
    )

    @Test
    fun `summary splits today week and month`() {
        val sessions = listOf(
            session("a", 0, 60),
            session("a", 3, 120),
            session("b", 10, 240),
            session("b", 40, 480),
        )
        val summary = StatsDomain.summary(sessions, today)
        assertEquals(60L, summary.todaySeconds)
        assertEquals(180L, summary.weekSeconds)
        assertEquals(420L, summary.monthSeconds)
    }

    @Test
    fun `summary includes calendar week and month buckets`() {
        val sessions = listOf(session("a", 0, 90))
        val summary = StatsDomain.summary(sessions, today)
        assertEquals(90L, summary.calendarWeekSeconds)
        assertEquals(90L, summary.calendarMonthSeconds)
    }

    @Test
    fun `range windows are inclusive and exclude future days`() {
        val sessions = listOf(
            session("a", 6, 10),
            session("a", 7, 20),
            session("a", 29, 30),
            session("a", 30, 40),
            session("a", -1, 50),
        )
        assertEquals(10L, StatsDomain.inRange(sessions, StatsRange.SEVEN_DAYS, today).sumOf { it.seconds })
        assertEquals(60L, StatsDomain.inRange(sessions, StatsRange.THIRTY_DAYS, today).sumOf { it.seconds })
        assertEquals(150L, StatsDomain.inRange(sessions, StatsRange.ALL, today).sumOf { it.seconds })
    }

    @Test
    fun `format shares sum to one and unknown books are OTHER`() {
        val sessions = listOf(
            session("a", 0, 100),
            session("b", 0, 200),
            session("c", 0, 100),
        )
        val shares = StatsDomain.formatDistribution(
            sessions,
            mapOf("a" to StatsBookInfo("Alpha", "epub"), "b" to StatsBookInfo("Beta", "pdf")),
        )
        assertEquals(listOf("PDF", "EPUB", "OTHER"), shares.map { it.format })
        assertEquals(1.0, shares.sumOf { it.fraction }, 0.0001)
        assertEquals(0.5, shares[0].fraction, 0.0001)
    }

    @Test
    fun `empty sessions produce no shares and zero buckets`() {
        assertEquals(emptyList<FormatShare>(), StatsDomain.formatDistribution(emptyList(), emptyMap()))
        assertEquals(List(24) { 0L }, StatsDomain.hourDistribution(emptyList(), 0))
    }

    @Test
    fun `hour buckets use the local zone offset`() {
        // 19_800_000 ms = 05:30 UTC → 13:30 local at +08:00.
        val sessions = listOf(session("a", 0, 90, startMillis = 19_800_000L))
        val buckets = StatsDomain.hourDistribution(sessions, zoneOffsetMinutes = 480)
        assertEquals(24, buckets.size)
        assertEquals(90L, buckets[13])
        assertEquals(90L, buckets.sum())
    }
}
