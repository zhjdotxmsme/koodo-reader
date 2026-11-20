package com.koodoreader.core.sync

/** What the user (or the default rule) decided for one conflicting book. */
enum class ProgressChoice {
    KEEP_LOCAL,
    TAKE_REMOTE,
}

/**
 * One book whose local and remote reading position disagree in a way that
 * cannot be resolved without guessing.
 *
 * A conflict is only ever raised when taking the remote row would move the
 * reader **backwards** and there is no proof it is the newer row. Moving
 * forwards is always safe, so it is applied without asking.
 */
data class ProgressConflict(
    val bookKey: String,
    val local: ProgressSnapshot,
    val remote: ProgressSnapshot,
) {
    val localFraction: Double get() = (local.fraction ?: 0.0).coerceIn(0.0, 1.0)
    val remoteFraction: Double get() = (remote.fraction ?: 0.0).coerceIn(0.0, 1.0)

    /** True when the remote row is further into the book. */
    val remoteIsAhead: Boolean
        get() = remoteFraction > localFraction + SyncMerge.FRACTION_EPSILON

    /** Default selection: never propose losing a reading position. */
    val suggested: ProgressChoice
        get() = if (remoteIsAhead) ProgressChoice.TAKE_REMOTE else ProgressChoice.KEEP_LOCAL
}

/**
 * The outcome of comparing the local reading positions with the remote ones.
 *
 * @property applyRemote remote rows that are safe to write locally.
 * @property keepLocal local rows that win outright (nothing written).
 * @property conflicts rows the user has to arbitrate.
 */
data class ProgressMergePlan(
    val applyRemote: List<ProgressSnapshot> = emptyList(),
    val keepLocal: List<ProgressSnapshot> = emptyList(),
    val conflicts: List<ProgressConflict> = emptyList(),
) {
    val isClean: Boolean get() = conflicts.isEmpty()
}

/**
 * Merge policy for synced reading positions.
 *
 * ## Why this exists
 *
 * The first implementation of `Pull` upserted every remote row, so a device
 * that had only read the first pages overwrote a device that was at 50%.
 * Progress is the one piece of synced state that cannot be re-derived, so the
 * merge has to be conservative:
 *
 *  1. **Forward is safe.** A remote position further into the book is applied
 *     without asking — no information is lost.
 *  2. **Backwards needs proof.** A remote position behind the local one is only
 *     applied when both sides carry a write timestamp and the remote one is
 *     strictly newer (or equal-newer with a higher fraction, which happens when
 *     two devices write in the same millisecond).
 *  3. **Everything else is a conflict.** The UI asks; the default answer never
 *     discards progress.
 *
 * Rows from peers that predate [ProgressSnapshot.updatedAt] simply arrive with
 * `updatedAt == null` and fall into rules 1–3, which is why the field is
 * optional in the wire format.
 */
object SyncMerge {

    /** Two fractions closer than this are the same reading position. */
    const val FRACTION_EPSILON = 0.005

    private enum class Decision { TAKE_REMOTE, KEEP_LOCAL, CONFLICT }

    fun planProgress(
        local: List<ProgressSnapshot>,
        remote: List<ProgressSnapshot>,
    ): ProgressMergePlan {
        val localByKey = LinkedHashMap<String, ProgressSnapshot>(local.size)
        local.forEach { row ->
            if (row.bookKey.isNotBlank()) localByKey[row.bookKey] = row
        }

        val apply = ArrayList<ProgressSnapshot>()
        val keep = ArrayList<ProgressSnapshot>()
        val conflicts = ArrayList<ProgressConflict>()

        remote.forEach { row ->
            if (row.bookKey.isBlank()) return@forEach
            val mine = localByKey[row.bookKey]
            if (mine == null) {
                apply += row
                return@forEach
            }
            when (decide(mine, row)) {
                Decision.TAKE_REMOTE -> apply += row
                Decision.KEEP_LOCAL -> keep += mine
                Decision.CONFLICT -> conflicts += ProgressConflict(row.bookKey, mine, row)
            }
        }
        return ProgressMergePlan(apply, keep, conflicts)
    }

    /**
     * Remote rows to write for the given per-book decisions.
     *
     * A book with no explicit choice falls back to [ProgressConflict.suggested],
     * so a caller that ignores the conflicts still cannot move backwards.
     */
    fun resolvedRows(
        plan: ProgressMergePlan,
        choices: Map<String, ProgressChoice> = emptyMap(),
    ): List<ProgressSnapshot> {
        val out = ArrayList<ProgressSnapshot>(plan.applyRemote.size + plan.conflicts.size)
        out += plan.applyRemote
        plan.conflicts.forEach { conflict ->
            val choice = choices[conflict.bookKey] ?: conflict.suggested
            if (choice == ProgressChoice.TAKE_REMOTE) out += conflict.remote
        }
        return out
    }

    private fun decide(local: ProgressSnapshot, remote: ProgressSnapshot): Decision {
        val localFraction = (local.fraction ?: 0.0).coerceIn(0.0, 1.0)
        val remoteFraction = (remote.fraction ?: 0.0).coerceIn(0.0, 1.0)

        // Same place: nothing to write, and no churn when only the CFI encoding
        // differs between peers.
        if (kotlin.math.abs(localFraction - remoteFraction) <= FRACTION_EPSILON) {
            return Decision.KEEP_LOCAL
        }

        val localAt = local.updatedAt
        val remoteAt = remote.updatedAt
        if (localAt != null && remoteAt != null) {
            return when {
                remoteAt > localAt -> Decision.TAKE_REMOTE
                localAt > remoteAt -> Decision.KEEP_LOCAL
                remoteFraction > localFraction -> Decision.TAKE_REMOTE
                else -> Decision.KEEP_LOCAL
            }
        }

        // At most one side has a timestamp: ordering cannot be proven, so the
        // only automatic move is the one that cannot lose progress.
        return when {
            remoteFraction > localFraction + FRACTION_EPSILON -> Decision.TAKE_REMOTE
            remoteFraction < localFraction - FRACTION_EPSILON -> Decision.CONFLICT
            else -> Decision.KEEP_LOCAL
        }
    }
}
