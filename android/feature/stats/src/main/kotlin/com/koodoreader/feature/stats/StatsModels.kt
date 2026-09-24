// feature/stats — pure-Kotlin statistics models (P6).
//
// Desktop /stats page (src/pages/stats/component.tsx) is the metric baseline:
// books read, total reading time, "reading streak (days)" and daily average,
// plus the last-30-days chart and the 52-week heatmap. Every number that the
// Compose screen shows is produced by [StatsAggregator], so the aggregation can
// be unit-tested on the JVM without the Android SDK (same approach as
// :engine:feature).
//
// Deliberately dependency-free:
//   * NO java.time — minSdk 24 without core-library desugaring would crash on
//     API 24/25; [IsoDay] below is a tiny proleptic-Gregorian day value using
//     the standard civil-days algorithm (Howard Hinnant, public domain).
//   * NO Android / Compose / Room types — Android-only code lives in the
//     `ui/` and `platform/` packages.
package com.koodoreader.feature.stats

/**
 * A calendar day in the reader's local time zone (desktop uses `new Date()` +
 * `getFullYear()/getMonth()/getDate()`, i.e. the local calendar date).
 *
 * `epochDay` (days since 1970-01-01) is the canonical form used for streak /
 * window arithmetic; [toString] is the desktop storage-key format
 * (`YYYY-MM-DD`, cf. `Stats.dateToKey`).
 */
data class IsoDay(val year: Int, val month: Int, val day: Int) : Comparable<IsoDay> {

    /** Days since 1970-01-01 (may be negative). */
    val epochDay: Long
        get() {
            val y = if (month <= 2) year - 1 else year
            val era = (if (y >= 0) y else y - 399) / 400
            val yoe = y - era * 400 // [0, 399]
            val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1 // [0, 365]
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy // [0, 146096]
            return era * 146097L + doe - 719468L
        }

    /** 0 = Sunday … 6 = Saturday — the desktop heatmap is Sunday-aligned. */
    val dayOfWeek: Int
        get() = (((epochDay + 4L) % 7L + 7L) % 7L).toInt()

    fun plusDays(days: Long): IsoDay = ofEpochDay(epochDay + days)

    fun daysUntil(other: IsoDay): Long = other.epochDay - epochDay

    override fun compareTo(other: IsoDay): Int = epochDay.compareTo(other.epochDay)

    /** `YYYY-MM-DD` — identical to the desktop `dateToKey` output. */
    override fun toString(): String =
        buildString(10) {
            append(year.toString().padStart(4, '0'))
            append('-')
            append(month.toString().padStart(2, '0'))
            append('-')
            append(day.toString().padStart(2, '0'))
        }

    companion object {
        fun ofEpochDay(epochDay: Long): IsoDay {
            val z = epochDay + 719468L
            val era = (if (z >= 0) z else z - 146096L) / 146097L
            val doe = z - era * 146097L // [0, 146096]
            val yoe = (doe - doe / 1460L + doe / 36524L - doe / 146096L) / 365L // [0, 399]
            val y = yoe + era * 400L
            val doy = doe - (365L * yoe + yoe / 4L - yoe / 100L) // [0, 365]
            val mp = (5L * doy + 2L) / 153L // [0, 11]
            val d = doy - (153L * mp + 2L) / 5L + 1L // [1, 31]
            val m = mp + if (mp < 10L) 3L else -9L // [1, 12]
            return IsoDay(
                year = (y + if (m <= 2L) 1L else 0L).toInt(),
                month = m.toInt(),
                day = d.toInt(),
            )
        }

        /** Parses `YYYY-MM-DD`; throws [IllegalArgumentException] on anything else. */
        fun parse(iso: String): IsoDay {
            require(iso.length == 10 && iso[4] == '-' && iso[7] == '-') {
                "not an ISO day: $iso"
            }
            val y = iso.substring(0, 4).toIntOrNull()
            val m = iso.substring(5, 7).toIntOrNull()
            val d = iso.substring(8, 10).toIntOrNull()
            require(y != null && m != null && d != null && m in 1..12 && d in 1..31) {
                "not an ISO day: $iso"
            }
            return IsoDay(y, m, d)
        }

        /**
         * Local calendar day of [epochMillis], using the zone offset (minutes)
         * that applies at that instant — DST-correct, unlike a fixed offset.
         */
        fun ofMillis(epochMillis: Long, zoneOffsetMinutes: Int): IsoDay {
            val localMillis = epochMillis + zoneOffsetMinutes * 60_000L
            return ofEpochDay(Math.floorDiv(localMillis, 86_400_000L))
        }
    }
}

/**
 * One reading session as persisted by [ReadingSessionRepository]: a contiguous
 * span of reading time already attributed to a single calendar [day] (spans
 * crossing midnight are split by the repository, mirroring how the desktop
 * `ReadingTimeUtil` books seconds into the day key it saw).
 */
data class ReadingSession(
    val key: String,
    val bookKey: String,
    val day: IsoDay,
    val seconds: Long,
    val words: Long = 0L,
    val startMillis: Long = 0L,
    val endMillis: Long = startMillis,
)

/** Chart point of the "Last 30 Days" section (desktop: `{date: M/D, seconds}`). */
data class DayPoint(
    val day: IsoDay,
    val label: String,
    val seconds: Long,
) {
    /** Desktop rounds to whole minutes for both the axis and the tooltip. */
    val minutes: Long get() = Math.round(seconds / 60.0)
}

/** Heatmap cell of the "Reading Activity" section. */
data class HeatmapCell(
    val day: IsoDay,
    val seconds: Long,
    /** 0..4, see [StatsAggregator.heatmapLevel] (desktop `getHeatmapColor`). */
    val level: Int,
)

/**
 * Everything the /stats screen renders. Field-for-field the desktop
 * `StatsState` (src/pages/stats/interface.tsx) plus the two P6 additions that
 * the desktop has no card for yet: [totalWords] and [averageProgress].
 */
data class StatsSnapshot(
    val totalBooks: Int,
    val totalSeconds: Long,
    val longestStreak: Int,
    val avgDailySeconds: Long,
    val activeDays: Int,
    val totalWords: Long,
    val averageProgress: Double,
    val last30Days: List<DayPoint>,
    val heatmap: List<HeatmapCell>,
    val secondsByDay: Map<IsoDay, Long>,
) {
    companion object {
        val EMPTY = StatsSnapshot(
            totalBooks = 0,
            totalSeconds = 0L,
            longestStreak = 0,
            avgDailySeconds = 0L,
            activeDays = 0,
            totalWords = 0L,
            averageProgress = 0.0,
            last30Days = emptyList(),
            heatmap = emptyList(),
            secondsByDay = emptyMap(),
        )
    }
}
