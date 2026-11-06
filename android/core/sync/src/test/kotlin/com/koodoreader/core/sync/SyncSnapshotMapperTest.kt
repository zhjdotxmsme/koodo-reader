package com.koodoreader.core.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SyncSnapshotMapperTest {
    @Test
    fun `statistics rows map into snapshot dto`() {
        val stats = SyncSnapshotMapper.toStatistics(
            listOf(
                SyncSnapshotMapper.SessionRow("s1", "k1", "2026-11-02", 120),
            ),
        )
        assertEquals(1, stats.size)
        assertEquals("k1", stats[0].bookKey)
        assertEquals(120, stats[0].seconds)
    }

    @Test
    fun `merge replaces statistics on snapshot`() {
        val base = SyncSnapshot.fixture().copy(statistics = emptyList())
        val merged = SyncSnapshotMapper.mergeStatistics(
            base,
            listOf(SyncSnapshotMapper.SessionRow("s2", "k1", "2026-11-03", 30)),
        )
        assertEquals(1, merged.statistics.size)
        assertEquals("2026-11-03", merged.statistics[0].day)
    }
}
