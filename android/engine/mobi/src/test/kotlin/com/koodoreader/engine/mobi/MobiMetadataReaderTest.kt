package com.koodoreader.engine.mobi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile

class MobiMetadataReaderTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun `readable EXTH survives corrupt body records`() {
        val bytes = TestMobiBuilder.build(
            textRecords = listOf(TestMobiBuilder.palmDocRecord(TestMobiBuilder.bytes(0))),
            options = TestMobiBuilder.Options(
                exthRecords = listOf(TestMobiBuilder.exthString(ExthType.TITLE, "Metadata Only")),
                textLengthOverride = 4096,
            ),
        )
        assertNull(MobiParser.parse(bytes), "fixture must have an unreadable body")

        val metadata = MobiMetadataReader.read(file(bytes))

        assertEquals("Metadata Only", metadata?.title)
    }

    @Test
    fun `readable EXTH survives DRM text encryption`() {
        val bytes = TestMobiBuilder.build(
            textRecords = listOf(TestMobiBuilder.uncompressedRecord(TestMobiBuilder.ascii("Body"))),
            options = TestMobiBuilder.Options(
                compression = MobiCompression.NONE,
                encryptionType = MobiEncryption.MOBI,
                exthRecords = listOf(
                    TestMobiBuilder.exthString(ExthType.TITLE, "DRM Title"),
                    TestMobiBuilder.exthString(ExthType.AUTHOR, "DRM Author"),
                ),
            ),
            decompressedLength = 4,
        )
        assertNull(MobiParser.parse(bytes), "full parser must reject DRM")

        val metadata = MobiMetadataReader.read(file(bytes))

        assertEquals("DRM Title", metadata?.title)
        assertEquals("DRM Author", metadata?.author)
    }

    @Test
    fun `record zero larger than the metadata bound is rejected before reading it`() {
        val bytes = TestMobiBuilder.build(
            textRecords = listOf(TestMobiBuilder.uncompressedRecord(TestMobiBuilder.ascii("Body"))),
            options = TestMobiBuilder.Options(
                compression = MobiCompression.NONE,
                exthRecords = listOf(
                    TestMobiBuilder.exthRecord(
                        ExthType.DESCRIPTION,
                        ByteArray(MobiMetadataReader.MAX_RECORD0_BYTES),
                    ),
                ),
            ),
            decompressedLength = 4,
        )

        assertNull(MobiMetadataReader.read(file(bytes)))
    }

    @Test
    fun `large trailing resources are not needed for metadata`() {
        val bytes = TestMobiBuilder.build(
            textRecords = listOf(TestMobiBuilder.uncompressedRecord(TestMobiBuilder.ascii("Body"))),
            options = TestMobiBuilder.Options(
                compression = MobiCompression.NONE,
                exthRecords = listOf(TestMobiBuilder.exthString(ExthType.TITLE, "Large Resource")),
            ),
            trailingRecords = listOf(TestMobiBuilder.FLIS),
            decompressedLength = 4,
        )
        val file = file(bytes)
        RandomAccessFile(file, "rw").use { it.setLength(it.length() + 32L * 1024 * 1024) }
        assertTrue(file.length() > 32L * 1024 * 1024)
        var bytesRead = 0
        val counting = object : RandomAccessFile(file, "r") {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val count = super.read(buffer, offset, length)
                if (count > 0) bytesRead += count
                return count
            }
        }

        counting.use { assertEquals("Large Resource", MobiMetadataReader.read(it)?.title) }
        assertTrue(bytesRead < 64 * 1024, "metadata read $bytesRead bytes from a ${file.length()}-byte file")
    }

    private fun file(bytes: ByteArray): File = File(dir, "fixture.mobi").apply { writeBytes(bytes) }
}
