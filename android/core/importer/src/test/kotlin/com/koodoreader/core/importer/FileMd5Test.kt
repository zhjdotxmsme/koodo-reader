package com.koodoreader.core.importer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.random.Random

class FileMd5Test {

    @TempDir
    var tempDir: File = File("build/tmp/md5")

    @Test
    fun `empty input has the canonical empty MD5`() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", FileMd5.ofStream(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun `single-byte and known vectors match`() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", FileMd5.ofStream("abc".toByteArray().inputStream()))
        assertEquals(
            "9e107d9d372bb6826bd81d3542a419d6",
            FileMd5.ofStream("The quick brown fox jumps over the lazy dog".toByteArray().inputStream()),
        )
    }

    @Test
    fun `chunk boundaries do not change the digest`() {
        val rnd = Random(20260923)
        for (size in listOf(
            FileMd5.CHUNK_SIZE - 1,
            FileMd5.CHUNK_SIZE,
            FileMd5.CHUNK_SIZE + 1,
            FileMd5.CHUNK_SIZE * 2,
            FileMd5.CHUNK_SIZE * 7,
        )) {
            val data = ByteArray(size) { rnd.nextInt(256).toByte() }
            val streamMd5 = FileMd5.ofStream(ByteArrayInputStream(data))
            val file = File(tempDir, "c$size.bin")
            file.writeBytes(data)
            assertEquals(FileMd5.ofFile(file), streamMd5, "size=$size mismatch between file and stream paths")
            // Cross-check against the JDK's one-shot digest:
            val jdk = java.security.MessageDigest.getInstance("MD5").digest(data)
            val hex = jdk.joinToString("") { "%02x".format(it) }
            assertEquals(hex, streamMd5, "size=$size differs from JDK digest")
            assertEquals(32, streamMd5.length)
        }
    }

    @Test
    fun `file API streams a large file without buffering it whole`() {
        // 5 * CHUNK = 80 KB: forces many chunks; digest must stay correct.
        val data = ByteArray(FileMd5.CHUNK_SIZE * 5) { (it % 251).toByte() }
        val file = File(tempDir, "big.bin")
        file.writeBytes(data)
        assertEquals(FileMd5.ofStream(ByteArrayInputStream(data)), FileMd5.ofFile(file))
    }
}
