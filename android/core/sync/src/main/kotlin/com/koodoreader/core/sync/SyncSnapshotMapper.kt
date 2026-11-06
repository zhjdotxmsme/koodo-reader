package com.koodoreader.core.sync

/**
 * Maps app-layer stats rows into [SyncSnapshot.statistics] without pulling in
 * `feature:stats` types (keeps `:core:sync` JVM-only and acyclic).
 */
object SyncSnapshotMapper {

    data class SessionRow(
        val key: String,
        val bookKey: String,
        val dayIso: String,
        val seconds: Long,
    )

    fun toStatistics(rows: List<SessionRow>): List<ReadingSessionSnapshot> =
        rows.map { row ->
            ReadingSessionSnapshot(
                key = row.key,
                bookKey = row.bookKey,
                day = row.dayIso,
                seconds = row.seconds.coerceAtLeast(0),
            )
        }

    fun mergeStatistics(
        snapshot: SyncSnapshot,
        rows: List<SessionRow>,
    ): SyncSnapshot = snapshot.copy(statistics = toStatistics(rows))
}
