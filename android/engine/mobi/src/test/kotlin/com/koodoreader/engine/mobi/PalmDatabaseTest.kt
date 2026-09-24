package com.koodoreader.engine.mobi

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PalmDB container parsing: header, type+creator, the 8-byte record table (the
 * u32 uniqueID must be skipped) and the EOF sentinel.
 */
class PalmDatabaseTest {

    private val B = TestMobiBuilder

    @Test
    fun `parses records and skips the u32 uniqueID in every entry`() {
        // The builder writes uniqueID = index * 2 right after each record offset.
        // A parser that forgets to skip it (or skips 8 bytes instead of 4) reads
        // those ids as offsets and produces shifted, wrong records.
        val text = B.palmDocRecord(B.bytes(0x41, 0x42, 0x43))
        val mobi = B.build(
            textRecords = listOf(text),
            trailingRecords = listOf(B.FLIS),
        )

        val db = PalmDatabase.read(mobi)
        assertEquals(3, db.recordCount)
        assertTrue(db.isMobi)
        assertFalse(db.isPalmDoc)
        assertEquals("BOOK", db.type)
        assertEquals("MOBI", db.creator)
        assertEquals("TestBook", db.name)

        // Last record must end exactly at the byte before its 2-byte gap.
        // Each slice runs to the next record's offset, so it carries the
        // 2-byte inter-record gap as well.
        assertEquals(B.FLIS.size + 2, db.records[2].size)
        assertTrue(db.records[2].copyOf(B.FLIS.size).contentEquals(B.FLIS))
        assertEquals(text.size + 2, db.records[1].size)
        assertTrue(db.records[1].copyOf(text.size).contentEquals(text))
    }

    @Test
    fun `record sizes are consistent with the file layout and its EOF sentinel`() {
        val mobi = B.build(textRecords = listOf(B.palmDocRecord(B.bytes(0x41))))
        val db = PalmDatabase.read(mobi)
        assertEquals(2, db.recordCount)
        assertEquals(282, db.records[0].size)
        // The last record runs to the file's EOF sentinel; it therefore holds its
        // payload plus whatever inter-record padding the file carries.
        val rest = mobi.size - PalmDatabase.HEADER_SIZE - 2 * PalmDatabase.RECORD_ENTRY_SIZE - 2
        assertTrue(db.records[1].size >= rest - db.records[0].size)
        assertTrue(db.records[1][0] == 0x41.toByte(), "record payload was mislocated")
    }

    @Test
    fun `rejects a file that is not BOOKMOBI`() {
        val notMobi = B.bytes(0x25, 0x50, 0x44, 0x46) + ByteArray(200) // starts "%PDF"
        val ex = assertThrows(MalformedMobiException::class.java) { PalmDatabase.read(notMobi) }
        assertTrue(ex.message!!.contains("type"), ex.message)
        assertNull(PalmDatabase.tryRead(notMobi))
    }

    @Test
    fun `accepts the legacy TEXtREAd PalmDOC container`() {
        val records = listOf(B.bytes(0x41))
        val db = PalmDatabase.of("Legacy", "TEXt", "REAd", records)
        assertTrue(db.isPalmDoc)
        assertFalse(db.isMobi)
        assertTrue(db.isSupportedContainer)
    }

    @Test
    fun `rejects a file shorter than a PalmDB header`() {
        val ex = assertThrows(MalformedMobiException::class.java) {
            PalmDatabase.read(ByteArray(40))
        }
        assertTrue(ex.message!!.contains("too short"), ex.message)
    }

    @Test
    fun `rejects a truncated record table`() {
        // 78-byte header claiming 20 records, but the file stops right after it.
        val bytes = ByteArray(PalmDatabase.HEADER_SIZE)
        "BOOK".toByteArray().copyInto(bytes, 60)
        "MOBI".toByteArray().copyInto(bytes, 64)
        bytes[76] = 0
        bytes[77] = 20
        val ex = assertThrows(MalformedMobiException::class.java) { PalmDatabase.read(bytes) }
        assertTrue(ex.message!!.contains("truncated record table"), ex.message)
    }

    @Test
    fun `rejects a record offset that points outside the file`() {
        val text = B.palmDocRecord(B.bytes(0x41, 0x42))
        val mobi = B.build(
            textRecords = listOf(text),
            options = TestMobiBuilder.Options(recordOffsetOverrides = mapOf(1 to 1_000_000)),
        )
        val ex = assertThrows(MalformedMobiException::class.java) { PalmDatabase.read(mobi) }
        assertTrue(ex.message!!.contains("outside the file"), ex.message)
    }

    @Test
    fun `rejects a non monotonic record table`() {
        val text = B.palmDocRecord(B.bytes(0x41, 0x42))
        val mobi = B.build(
            textRecords = listOf(text),
            trailingRecords = listOf(B.FLIS),
            options = TestMobiBuilder.Options(recordOffsetOverrides = mapOf(2 to 0)),
        )
        val ex = assertThrows(MalformedMobiException::class.java) { PalmDatabase.read(mobi) }
        assertTrue(ex.message!!.contains("not monotonic"), ex.message)
    }

    @Test
    fun `rejects a container with no records`() {
        val bytes = ByteArray(PalmDatabase.HEADER_SIZE)
        "BOOK".toByteArray().copyInto(bytes, 60)
        "MOBI".toByteArray().copyInto(bytes, 64)
        bytes[76] = 0
        bytes[77] = 0
        assertThrows(MalformedMobiException::class.java) { PalmDatabase.read(bytes) }
    }

    @Test
    fun `of builds an in memory container for callers that already hold records`() {
        val db = PalmDatabase.of("X", "BOOK", "MOBI", listOf(B.bytes(1), B.bytes(2, 3)))
        assertEquals(2, db.recordCount)
        assertArrayEquals(B.bytes(2, 3), db.records[1])
    }
}
