package com.koodoreader.core.sync

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test

class SyncCoordinatorTest {
    @Test
    fun `sync coordinator writes exact snapshot and never invents remote data`() {
        val target = MemorySyncTarget()
        SyncCoordinator.export(target, byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), SyncCoordinator.import(target))
    }
}

class MemorySyncTarget : SyncTarget {
    private var bytes: ByteArray? = null

    override fun read() = bytes?.inputStream()

    override fun write(snapshot: ByteArray) {
        bytes = snapshot.copyOf()
    }
}
