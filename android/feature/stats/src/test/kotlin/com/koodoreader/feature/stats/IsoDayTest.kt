package com.koodoreader.feature.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [IsoDay] is the day arithmetic behind the streak / 30-day / heatmap windows,
 * so it is pinned against known epoch days and the desktop `Date` accessors.
 */
class IsoDayTest {

    @Test
    fun `epoch day matches the reference values`() {
        assertEquals(0L, IsoDay(1970, 1, 1).epochDay)
        assertEquals(19358L, IsoDay(2023, 1, 1).epochDay)
        assertEquals(19723L, IsoDay(2024, 1, 1).epochDay)
        assertEquals(-1L, IsoDay(1969, 12, 31).epochDay)
    }

    @Test
    fun `epoch day round trips across a leap year`() {
        var day = IsoDay(2024, 2, 27)
        repeat(5) { index ->
            val expected = IsoDay(2024, 2, 27).plusDays(index.toLong())
            assertEquals(expected, IsoDay.ofEpochDay(day.epochDay))
            assertEquals(index.toLong(), IsoDay(2024, 2, 27).daysUntil(expected))
            day = day.plusDays(1)
        }
        assertEquals(IsoDay(2024, 2, 29), IsoDay(2024, 2, 28).plusDays(1))
        assertEquals(IsoDay(2024, 3, 1), IsoDay(2024, 2, 29).plusDays(1))
    }

    @Test
    fun `dayOfWeek is Sunday-first like the desktop heatmap`() {
        assertEquals(4, IsoDay(1970, 1, 1).dayOfWeek) // Thursday
        assertEquals(0, IsoDay(2023, 1, 8).dayOfWeek) // Sunday
        assertEquals(1, IsoDay(2024, 1, 1).dayOfWeek) // Monday
        assertEquals(5, IsoDay(2024, 3, 15).dayOfWeek) // Friday
        assertEquals(6, IsoDay(2024, 3, 16).dayOfWeek) // Saturday
    }

    @Test
    fun `toString and parse are the desktop dateToKey format`() {
        assertEquals("2024-03-05", IsoDay(2024, 3, 5).toString())
        assertEquals("0999-01-02", IsoDay(999, 1, 2).toString())
        assertEquals(IsoDay(2024, 3, 5), IsoDay.parse("2024-03-05"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parse rejects a malformed key`() {
        IsoDay.parse("2024-3-5")
    }

    @Test
    fun `ofMillis uses the offset that applies at that instant`() {
        val midnightUtc = 1_704_067_200_000L // 2024-01-01T00:00:00Z
        assertEquals(IsoDay(2024, 1, 1), IsoDay.ofMillis(midnightUtc, 0))
        assertEquals(IsoDay(2023, 12, 31), IsoDay.ofMillis(midnightUtc, -60))
        assertEquals(IsoDay(2024, 1, 1), IsoDay.ofMillis(midnightUtc + 30_000L, 0))
        assertTrue(IsoDay.ofMillis(midnightUtc - 1L, 0) < IsoDay(2024, 1, 1))
    }
}
