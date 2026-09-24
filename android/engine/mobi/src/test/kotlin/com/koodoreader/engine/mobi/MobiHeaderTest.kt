package com.koodoreader.engine.mobi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * MOBI header field offsets (record 0) and the EXTH block locator.
 */
class MobiHeaderTest {

    private val B = TestMobiBuilder

    private fun record0Of(mobi: ByteArray): ByteArray = PalmDatabase.read(mobi).records[0]

    @Test
    fun `reads text encoding, compression, file version and record boundaries`() {
        val mobi = B.build(
            textRecords = listOf(B.palmDocRecord(B.bytes(0x41))),
            trailingRecords = listOf(B.JPEG, B.FLIS),
            options = TestMobiBuilder.Options(
                textEncoding = 1252,
                fileVersion = 6,
                firstNonBookIndex = 2,
                firstImageRecord = 2,
            ),
            decompressedLength = 1,
        )
        val header = MobiHeader.read(record0Of(mobi))
        assertEquals(1252, header.textEncoding)
        assertEquals("windows-1252", header.encodingName)
        assertEquals(MobiHeader.CP1252, header.charset)
        assertEquals(MobiCompression.PALMDOC, header.compression)
        assertEquals(6, header.fileVersion)
        assertEquals(2, header.firstNonBookIndex)
        assertEquals(2, header.firstImageRecord)
        assertEquals(1, header.textRecordCount)
        assertEquals(1, header.textLength)
        assertEquals(0, header.encryptionType)
        assertFalse(header.isEncrypted)
        assertFalse(header.isHuffCdic)
        // `headerLength` counts from the "MOBI" magic, so record 0 is 16 larger.
        // The raw field counts from the MOBI magic, so record 0 is 16 larger.
        assertEquals(264, header.headerLength)
        assertEquals(4096, header.textRecordSize)
    }

    @Test
    fun `UTF-8 encoding maps to the UTF-8 charset`() {
        val mobi = B.build(
            textRecords = listOf(B.palmDocRecord(B.bytes(0x41))),
            options = TestMobiBuilder.Options(textEncoding = 65001),
        )
        val header = MobiHeader.read(record0Of(mobi))
        assertEquals(Charsets.UTF_8, header.charset)
        assertEquals("UTF-8", header.encodingName)
    }

    @Test
    fun `EXTH is only located when the 0x40 flag is set`() {
        val withExth = B.build(
            textRecords = listOf(B.palmDocRecord(B.bytes(0x41))),
            options = TestMobiBuilder.Options(
                exthRecords = listOf(B.exthString(ExthType.TITLE, "T")),
            ),
        )
        val h1 = MobiHeader.read(record0Of(withExth))
        assertTrue(h1.hasExth)
        assertEquals(16 + h1.headerLength, h1.exthStart)

        // Flag cleared: the EXTH block is present in the bytes but must be
        // ignored, otherwise a stale/foreign block would leak into the metadata.
        val withoutExth = B.build(
            textRecords = listOf(B.palmDocRecord(B.bytes(0x41))),
            options = TestMobiBuilder.Options(
                exthRecords = listOf(B.exthString(ExthType.TITLE, "T")),
                exthFlagsOverride = 0,
            ),
        )
        val h2 = MobiHeader.read(record0Of(withoutExth))
        assertFalse(h2.hasExth)
        assertNull(h2.exthStart)
    }

    @Test
    fun `DRM fields are surfaced`() {
        val mobi = B.build(
            textRecords = listOf(B.palmDocRecord(B.bytes(0x41))),
            options = TestMobiBuilder.Options(
                encryptionType = MobiEncryption.MOBI,
                drmOffset = 9,
                drmRecordCount = 2,
            ),
        )
        val header = MobiHeader.read(record0Of(mobi))
        assertTrue(header.isEncrypted)
        assertEquals(2, header.encryptionType)
        assertEquals(9, header.drmOffset)
        assertEquals(2, header.drmRecordCount)
    }

    @Test
    fun `KF8 boundary field is read and recognised`() {
        val mobi = B.build(
            textRecords = listOf(B.palmDocRecord(B.bytes(0x41))),
            options = TestMobiBuilder.Options(fileVersion = 8, kf8Boundary = 5),
        )
        val header = MobiHeader.read(record0Of(mobi))
        assertEquals(5, header.kf8BoundaryRecord)
        assertTrue(header.declaresKf8Boundary)
        assertTrue(header.fileVersion >= 8)
    }

    @Test
    fun `rejects a record 0 without the MOBI magic`() {
        val record0 = ByteArray(300)
        "PALM".toByteArray().copyInto(record0, 16)
        val ex = assertThrows(MalformedMobiException::class.java) { MobiHeader.read(record0) }
        assertTrue(ex.message!!.contains("MOBI header"), ex.message)
    }

    @Test
    fun `rejects a header length smaller than the EXTH flag field`() {
        val mobi = B.build(
            textRecords = listOf(B.palmDocRecord(B.bytes(0x41))),
            options = TestMobiBuilder.Options(headerLength = 264),
        )
        val record0 = record0Of(mobi)
        // Rewind the declared header length (record 0, offset 20) to 120: below
        // the 132-byte minimum, so the EXTH flag field would not exist.
        val tooSmall = 120
        record0[20] = (tooSmall ushr 24).toByte()
        record0[21] = (tooSmall ushr 16).toByte()
        record0[22] = (tooSmall ushr 8).toByte()
        record0[23] = tooSmall.toByte()
        val ex = assertThrows(MalformedMobiException::class.java) { MobiHeader.read(record0) }
        assertTrue(ex.message!!.contains("too small"), ex.message)
    }

    @Test
    fun `rejects a header length that overruns record 0`() {
        // Build a valid 264-byte header, then rewind the declared length.
        val mobi = B.build(
            textRecords = listOf(B.palmDocRecord(B.bytes(0x41))),
            options = TestMobiBuilder.Options(headerLength = 264),
        )
        val record0 = record0Of(mobi)
        val oversize = 4096
        record0[20] = (oversize ushr 24).toByte()
        record0[21] = (oversize ushr 16).toByte()
        record0[22] = (oversize ushr 8).toByte()
        record0[23] = oversize.toByte()
        val ex = assertThrows(MalformedMobiException::class.java) { MobiHeader.read(record0) }
        assertTrue(ex.message!!.contains("exceeds record 0"), ex.message)
    }

    @Test
    fun `an implausible text length is reported as unknown instead of trusted`() {
        val mobi = B.build(
            textRecords = listOf(B.palmDocRecord(B.bytes(0x41))),
            options = TestMobiBuilder.Options(textLengthOverride = -1),
        )
        val header = MobiHeader.read(record0Of(mobi))
        assertNull(header.textLength)
    }
}
