package com.koodoreader.core.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncMergeTest {

    private fun row(key: String, fraction: Double, at: Long? = null, cfi: String? = null) =
        ProgressSnapshot(bookKey = key, cfi = cfi ?: "cfi-$key-${fraction}", fraction = fraction, updatedAt = at)

    @Test
    fun `remote only rows are applied`() {
        val plan = SyncMerge.planProgress(emptyList(), listOf(row("a", 0.3)))

        assertEquals(listOf(row("a", 0.3)), plan.applyRemote)
        assertTrue(plan.isClean)
    }

    @Test
    fun `same reading position does not churn`() {
        val plan = SyncMerge.planProgress(
            listOf(row("a", 0.300, cfi = "cfi-a-x")),
            listOf(row("a", 0.301, cfi = "cfi-a-y")),
        )

        assertTrue(plan.applyRemote.isEmpty())
        assertTrue(plan.isClean)
    }

    @Test
    fun `newer timestamp wins forwards and backwards`() {
        val forward = SyncMerge.planProgress(listOf(row("a", 0.2, at = 100)), listOf(row("a", 0.4, at = 200)))
        assertEquals(1, forward.applyRemote.size)

        // A deliberate re-read from the start is still the newer write.
        val backward = SyncMerge.planProgress(listOf(row("a", 0.6, at = 100)), listOf(row("a", 0.1, at = 200)))
        assertTrue(backward.isClean)
        assertEquals(1, backward.applyRemote.size)
    }

    @Test
    fun `older timestamp never overwrites a newer local position`() {
        val plan = SyncMerge.planProgress(listOf(row("a", 0.6, at = 500)), listOf(row("a", 0.2, at = 100)))

        assertTrue(plan.applyRemote.isEmpty())
        assertTrue(plan.isClean)
        assertEquals(1, plan.keepLocal.size)
    }

    @Test
    fun `remote behind without timestamps is a conflict not a rollback`() {
        val plan = SyncMerge.planProgress(listOf(row("a", 0.5)), listOf(row("a", 0.03)))

        assertTrue(plan.applyRemote.isEmpty())
        assertFalse(plan.isClean)
        val conflict = plan.conflicts.single()
        assertEquals("a", conflict.bookKey)
        assertEquals(ProgressChoice.KEEP_LOCAL, conflict.suggested)
    }

    @Test
    fun `remote ahead without timestamps is applied`() {
        val plan = SyncMerge.planProgress(listOf(row("a", 0.1)), listOf(row("a", 0.7)))

        assertEquals(1, plan.applyRemote.size)
        assertTrue(plan.isClean)
    }

    @Test
    fun `conflict resolution honours explicit choices`() {
        val plan = SyncMerge.planProgress(listOf(row("a", 0.5), row("b", 0.5)), listOf(row("a", 0.1), row("b", 0.2)))

        val takeRemote = SyncMerge.resolvedRows(plan, mapOf("a" to ProgressChoice.TAKE_REMOTE))
        assertEquals(listOf(row("a", 0.1)), takeRemote)

        val suggested = SyncMerge.resolvedRows(plan)
        assertTrue(suggested.isEmpty(), "default must never discard local progress")
    }

    @Test
    fun `annotation anchors survive the json round trip`() {
        val snapshot = SyncSnapshot(
            annotations = listOf(
                AnnotationSnapshot(
                    key = "n1",
                    bookKey = "k1",
                    chapter = "Ch1",
                    text = "正文",
                    notes = "note body",
                    color = 0xFFFF00L,
                    cfi = "epubcfi(/6/2!/4/1:0)",
                    percentage = "0.12",
                ),
            ),
        )

        assertEquals(snapshot, SyncSnapshotCodec.decode(SyncSnapshotCodec.encode(snapshot)))
    }

    @Test
    fun `updatedAt survives the json round trip`() {
        val snapshot = SyncSnapshot(
            progress = listOf(ProgressSnapshot("k1", "epubcfi(/6/2!/4/1:0)", 0.42, updatedAt = 1_763_000_000_000L)),
        )

        val decoded = SyncSnapshotCodec.decode(SyncSnapshotCodec.encode(snapshot))

        assertEquals(1_763_000_000_000L, decoded.progress.single().updatedAt)
        assertEquals(snapshot, decoded)
    }

    @Test
    fun `snapshots without updatedAt still decode`() {
        val legacy = """{"version":1,"books":[],"progress":[{"bookKey":"k","cfi":"c","fraction":0.25}],"annotations":[],"bookmarks":[],"settings":{},"statistics":[]}"""

        val decoded = SyncSnapshotCodec.decode(legacy)

        assertEquals(null, decoded.progress.single().updatedAt)
        assertEquals(0.25, decoded.progress.single().fraction)
    }
}
