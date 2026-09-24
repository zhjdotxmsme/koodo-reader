// feature/stats — the single source of truth for every number on /stats (P6).
package com.koodoreader.feature.stats

/**
 * Aggregates persisted [ReadingSession]s into a [StatsSnapshot].
 *
 * Desktop parity notes (bug-for-bug, verified by reading
 * `src/pages/stats/component.tsx`):
 *  1. `longestStreak` is the LONGEST run of consecutive reading days inside the
 *     trailing 365-day window — the desktop scans `i = 0..364` backwards from
 *     today while keeping the maximum, so a run that ended months ago still
 *     wins over a live run of the same length. The label says "streak", the
 *     metric is "best streak in the last year".
 *  2. `avgDailySeconds` divides by ACTIVE days (days with > 0 seconds), not by
 *     the days since the first session.
 *  3. The heatmap legend uses [HEATMAP_LEGEND_SECONDS] = 0/200/600/1200/2400
 *     while the cells are coloured with [HEATMAP_COLOR_THRESHOLDS] =
 *     300/900/1800. That desktop inconsistency is preserved here (and called
 *     out in docs/p6-stats-ocr-design.md); changing it would break visual
 *     parity with the web build.
 *  4. `totalBooks` is the number of books with a non-empty `recordLocation`,
 *     not the number of books in the library; it is supplied by the caller.
 */
class StatsAggregator {

    /**
     * @param sessions       day-attributed sessions (already split at midnight)
     * @param booksRead      books with a non-empty `recordLocation` (desktop §1)
     * @param progressByBook desktop `recordLocation.percentage`, a 0..1 fraction
     * @param today          injected "today" — keeps the aggregation deterministic
     */
    fun aggregate(
        sessions: List<ReadingSession>,
        booksRead: Int,
        progressByBook: Map<String, Double> = emptyMap(),
        today: IsoDay,
    ): StatsSnapshot {
        val secondsByDay = LinkedHashMap<IsoDay, Long>()
        var totalSeconds = 0L
        var totalWords = 0L
        for (session in sessions) {
            val seconds = session.seconds.coerceAtLeast(0L)
            secondsByDay[session.day] = (secondsByDay[session.day] ?: 0L) + seconds
            totalSeconds += seconds
            totalWords += session.words.coerceAtLeast(0L)
        }

        val activeDays = secondsByDay.count { it.value > 0L }
        val avgDailySeconds =
            if (activeDays > 0) Math.round(totalSeconds / activeDays.toDouble()) else 0L

        return StatsSnapshot(
            totalBooks = booksRead,
            totalSeconds = totalSeconds,
            longestStreak = longestStreak(secondsByDay, today),
            avgDailySeconds = avgDailySeconds,
            activeDays = activeDays,
            totalWords = totalWords,
            averageProgress = averageProgress(progressByBook),
            last30Days = last30Days(secondsByDay, today),
            heatmap = heatmap(secondsByDay, today),
            secondsByDay = secondsByDay,
        )
    }

    /**
     * Longest run of days with reading inside
     * `[today - (STREAK_WINDOW_DAYS - 1), today]` — mirrors the desktop
     * backwards scan exactly.
     */
    fun longestStreak(secondsByDay: Map<IsoDay, Long>, today: IsoDay): Int {
        var longest = 0
        var current = 0
        for (i in 0 until STREAK_WINDOW_DAYS) {
            val day = today.plusDays(-i.toLong())
            if ((secondsByDay[day] ?: 0L) > 0L) {
                current++
                if (current > longest) longest = current
            } else {
                current = 0
            }
        }
        return longest
    }

    /** Desktop §5: fixed 30 entries, oldest first, `M/D` labels. */
    fun last30Days(secondsByDay: Map<IsoDay, Long>, today: IsoDay): List<DayPoint> =
        (CHART_DAYS - 1 downTo 0).map { back ->
            val day = today.plusDays(-back.toLong())
            DayPoint(day = day, label = "${day.month}/${day.day}", seconds = secondsByDay[day] ?: 0L)
        }

    /**
     * Desktop §6: start at `today - 51 weeks`, snap back to that week's Sunday,
     * then one cell per day up to and including today.
     */
    fun heatmap(secondsByDay: Map<IsoDay, Long>, today: IsoDay): List<HeatmapCell> {
        val start = today
            .plusDays(-(HEATMAP_WEEKS - 1).toLong() * 7L)
            .plusDays(-today.dayOfWeek.toLong())
        val cells = ArrayList<HeatmapCell>(HEATMAP_WEEKS * 7 + 6)
        var day = start
        while (day <= today) {
            val seconds = secondsByDay[day] ?: 0L
            cells.add(HeatmapCell(day = day, seconds = seconds, level = heatmapLevel(seconds)))
            day = day.plusDays(1L)
        }
        return cells
    }

    /** `recordLocation.percentage` is a 0..1 fraction; the UI shows 0..100 with 2 decimals. */
    fun averageProgress(progressByBook: Map<String, Double>): Double {
        val values = progressByBook.values.filter { it.isFinite() && it > 0.0 }
        if (values.isEmpty()) return 0.0
        return round2(values.sum() / values.size * 100.0)
    }

    /**
     * Desktop `renderHeatmap` pads the trailing week with invisible cells and
     * splits the list into 7-cell columns (`null` = invisible cell).
     */
    fun heatmapWeeks(cells: List<HeatmapCell>): List<List<HeatmapCell?>> {
        if (cells.isEmpty()) return emptyList()
        val pad = (7 - cells.size % 7) % 7
        val padded: List<HeatmapCell?> = cells + List(pad) { null }
        return padded.chunked(7)
    }

    /**
     * The month label of every heatmap column, `null` when the column gets no
     * label. Desktop: the first column is always labelled (with its first
     * visible day) and every later column is labelled when it contains the 1st
     * of a month; the UI formats the returned day with the device locale.
     */
    fun heatmapMonthAnchors(weeks: List<List<HeatmapCell?>>): List<IsoDay?> =
        weeks.mapIndexed { index, week ->
            val firstVisible = week.firstOrNull { it != null }
            if (index == 0) {
                firstVisible?.day
            } else {
                week.firstOrNull { it != null && it.day.day == 1 }?.day
            }
        }

    companion object {
        /** Desktop loops `for (i = 0; i < 365; i++)`. */
        const val STREAK_WINDOW_DAYS = 365

        /** Desktop "Last 30 Days" chart. */
        const val CHART_DAYS = 30

        /** Desktop heatmap: `today - 51 * 7` days, snapped to Sunday. */
        const val HEATMAP_WEEKS = 52

        /** Desktop legend levels (`.heatmap-legend` maps these through getHeatmapColor). */
        val HEATMAP_LEGEND_SECONDS = listOf(0L, 200L, 600L, 1200L, 2400L)

        /** Desktop cell thresholds: <300 / <900 / <1800 / else. */
        val HEATMAP_COLOR_THRESHOLDS = listOf(300L, 900L, 1800L)

        /** Desktop `getHeatmapColor`: 0 = empty, 1..4 = increasing intensity. */
        fun heatmapLevel(seconds: Long): Int = when {
            seconds <= 0L -> 0
            seconds < HEATMAP_COLOR_THRESHOLDS[0] -> 1
            seconds < HEATMAP_COLOR_THRESHOLDS[1] -> 2
            seconds < HEATMAP_COLOR_THRESHOLDS[2] -> 3
            else -> 4
        }

        /** Desktop `formatTime`: `59s`, `5m`, `1h 5m` (seconds are dropped above 60). */
        fun formatTime(seconds: Long): String {
            if (seconds < 60L) return "${seconds}s"
            val hours = seconds / 3600L
            val minutes = (seconds % 3600L) / 60L
            return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
        }

        /** Desktop `formatTime` for a fractional total (React state is numeric). */
        fun formatTime(seconds: Double): String = formatTime(Math.round(seconds))

        /**
         * Word count used by the P6 word metric: CJK ideographs / kana / hangul
         * count one per character, everything else is counted as
         * whitespace-separated words.
         */
        fun wordCount(text: String): Long {
            var words = 0L
            var inLatinWord = false
            for (ch in text) {
                when {
                    isCjk(ch) -> {
                        words++
                        inLatinWord = false
                    }
                    isWordChar(ch) -> {
                        // Count a latin word once, at its first character.
                        if (!inLatinWord) words++
                        inLatinWord = true
                    }
                    else -> inLatinWord = false
                }
            }
            return words
        }

        /** A latin word character: letters, digits and the two apostrophes. */
        fun isWordChar(ch: Char): Boolean =
            ch.isLetterOrDigit() || ch == '\'' || ch == '\u2019'

        /**
         * CJK ideographs (BMP), kana and hangul syllables. Characters outside
         * the BMP (CJK ext B+, i.e. surrogate pairs) are not counted as words —
         * they are rare enough in books that the desktop reader counts them the
         * same way.
         */
        fun isCjk(ch: Char): Boolean {
            val code = ch.code
            return code in 0x3040..0x30FF || // kana
                code in 0x3400..0x4DBF || // CJK ext A
                code in 0x4E00..0x9FFF || // CJK unified
                code in 0xAC00..0xD7AF || // hangul syllables
                code in 0xF900..0xFAFF // CJK compatibility
        }

        private fun round2(value: Double): Double = Math.round(value * 100.0) / 100.0
    }
}
