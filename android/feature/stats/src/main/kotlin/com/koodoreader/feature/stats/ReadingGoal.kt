package com.koodoreader.feature.stats

/**
 * A daily reading target, expressed in minutes.
 *
 * The desktop has no equivalent: it shows totals, which tell a reader how much
 * they *have* read but never whether that is enough. A goal turns the stats
 * page into something worth opening daily, and it costs one subtraction.
 *
 * The goal is deliberately a **small closed set** rather than a free slider:
 * the useful answers are "none", "a quarter hour", "half an hour", "an hour" —
 * asking a reader to pick 37 minutes is a decision they do not want to make.
 */
object ReadingGoal {

    /** 0 means "no goal" — the card then invites the reader to set one. */
    const val OFF: Int = 0

    /** Selectable goals, in minutes, in menu order. */
    val PRESETS: List<Int> = listOf(0, 15, 30, 45, 60, 90)

    const val MAX_MINUTES: Int = 240

    /**
     * Today's standing against the goal.
     *
     * @param todaySeconds time read today; negative or garbage input reads as 0.
     * @param goalMinutes the configured goal; 0 (or out of range) means off.
     */
    fun progress(todaySeconds: Long, goalMinutes: Int): GoalProgress {
        val goal = goalMinutes.coerceIn(OFF, MAX_MINUTES)
        val read = todaySeconds.coerceAtLeast(0L)
        if (goal == OFF) {
            return GoalProgress(
                goalMinutes = OFF,
                todaySeconds = read,
                fraction = 0f,
                remainingSeconds = 0L,
                met = false,
                enabled = false,
            )
        }
        val target = goal * 60L
        return GoalProgress(
            goalMinutes = goal,
            todaySeconds = read,
            fraction = (read.toFloat() / target).coerceIn(0f, 1f),
            remainingSeconds = (target - read).coerceAtLeast(0L),
            met = read >= target,
            enabled = true,
        )
    }
}

/** The result of [ReadingGoal.progress]. */
data class GoalProgress(
    val goalMinutes: Int,
    val todaySeconds: Long,
    /** 0f..1f, already clamped — safe to feed straight to a progress bar. */
    val fraction: Float,
    val remainingSeconds: Long,
    val met: Boolean,
    val enabled: Boolean,
)
