package com.koodoreader.core.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SyncSnapshotCodecTest {
    @Test
    fun `snapshot round trip preserves stats and annotation anchors`() {
        val snapshot = SyncSnapshot.fixture()
        assertEquals(snapshot, SyncSnapshotCodec.decode(SyncSnapshotCodec.encode(snapshot)))
    }

    @Test
    fun `newer unsupported snapshot is rejected without dropping data`() {
        assertThrows(SyncVersionException::class.java) {
            SyncSnapshotCodec.decode("""{"version":2,"books":[],"progress":[],"annotations":[],"bookmarks":[],"settings":{},"statistics":[]}""")
        }
    }
}
