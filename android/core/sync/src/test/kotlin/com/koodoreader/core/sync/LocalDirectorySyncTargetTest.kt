package com.koodoreader.core.sync

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class LocalDirectorySyncTargetTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `missing file reads as null and write round-trips`() {
        val file = File(dir, "snap/reader.bin")
        val target = LocalDirectorySyncTarget(file)
        assertNull(target.read())
        val bytes = byteArrayOf(1, 2, 3, 4)
        target.write(bytes)
        assertArrayEquals(bytes, target.read()!!.use { it.readBytes() })
        target.write(byteArrayOf(9))
        assertArrayEquals(byteArrayOf(9), file.readBytes())
    }
}
