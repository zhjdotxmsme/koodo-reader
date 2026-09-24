package com.koodoreader.engine.mobi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * EXTH metadata block parsing (types 503/100/101/103/104/105/106/108/524/201/202/121).
 */
class ExthTest {

    private val B = TestMobiBuilder

    /** Builds a record-0-like buffer holding just an EXTH block at [start]. */
    private fun exthBuffer(
        records: List<ByteArray>,
        charset: java.nio.charset.Charset = Charsets.UTF_8,
        declaredLengthOverride: Int? = null,
        declaredCountOverride: Int? = null,
        start: Int = 16,
    ): Pair<ByteArray, Exth?> {
        val body = ByteArrayOutputStream().also { out -> records.forEach { out.write(it) } }.toByteArray()
        val block = ByteArrayOutputStream().also { out ->
            out.write(B.ascii("EXTH"))
            out.write(B.u32(declaredLengthOverride ?: (body.size + 12)))
            out.write(B.u32(declaredCountOverride ?: records.size))
            out.write(body)
        }.toByteArray()
        val buffer = ByteArray(start + block.size + 8)
        block.copyInto(buffer, start)
        return buffer to Exth.read(buffer, start, start + block.size, charset)
    }

    @Test
    fun `reads the core metadata record types`() {
        val (_, exth) = exthBuffer(
            listOf(
                B.exthString(ExthType.TITLE, "Native MOBI"),
                B.exthString(ExthType.AUTHOR, "Ada Lovelace"),
                B.exthString(ExthType.AUTHOR, "Alan Turing"),
                B.exthString(ExthType.PUBLISHER, "Koodo Press"),
                B.exthString(ExthType.DESCRIPTION, "A test book about parsing."),
                B.exthString(ExthType.ISBN, "978-0-00-000000-0"),
                B.exthString(ExthType.SUBJECT, "Computing"),
                B.exthString(ExthType.SUBJECT, "History"),
                B.exthString(ExthType.PUBLISHING_DATE, "2024-01-31T00:00:00+00:00"),
                B.exthString(ExthType.CONTRIBUTOR, "Grace Hopper"),
                B.exthString(ExthType.LANGUAGE, "en"),
                B.exthInt(ExthType.COVER_OFFSET, 1),
                B.exthInt(ExthType.THUMBNAIL_OFFSET, 0),
                B.exthInt(ExthType.KF8_BOUNDARY, 7),
            )
        )
        assertNotNull(exth)
        assertEquals("Native MOBI", exth!!.text(ExthType.TITLE))
        assertEquals("Ada Lovelace", exth.text(ExthType.AUTHOR))
        assertEquals(listOf("Ada Lovelace", "Alan Turing"), exth.texts(ExthType.AUTHOR))
        assertEquals("Koodo Press", exth.text(ExthType.PUBLISHER))
        assertEquals("A test book about parsing.", exth.text(ExthType.DESCRIPTION))
        assertEquals("978-0-00-000000-0", exth.text(ExthType.ISBN))
        assertEquals(listOf("Computing", "History"), exth.texts(ExthType.SUBJECT))
        assertEquals("2024-01-31T00:00:00+00:00", exth.text(ExthType.PUBLISHING_DATE))
        assertEquals("Grace Hopper", exth.text(ExthType.CONTRIBUTOR))
        assertEquals("en", exth.text(ExthType.LANGUAGE))
        assertEquals(1, exth.int(ExthType.COVER_OFFSET))
        assertEquals(0, exth.int(ExthType.THUMBNAIL_OFFSET))
        assertEquals(7, exth.int(ExthType.KF8_BOUNDARY))
    }

    @Test
    fun `decodes values with the windows-1252 charset from the MOBI header`() {
        val latin = "caf\u00e9 \u2013 na\u00efve"
        val (_, exth) = exthBuffer(
            records = listOf(B.exthRecord(ExthType.TITLE, latin.toByteArray(MobiHeader.CP1252))),
            charset = MobiHeader.CP1252,
        )
        assertEquals(latin, exth!!.text(ExthType.TITLE))
    }

    @Test
    fun `unknown record types are preserved but ignored by the typed accessors`() {
        val (_, exth) = exthBuffer(
            listOf(
                B.exthString(113, "uuid-abc"),
                B.exthString(503, "Title"),
                B.exthString(528, "true"),
            )
        )
        assertEquals(3, exth!!.records.size)
        assertEquals("Title", exth.text(ExthType.TITLE))
        assertNull(exth.text(ExthType.PUBLISHER))
        assertNull(exth.int(ExthType.COVER_OFFSET))
    }

    @Test
    fun `a record with a bogus length stops parsing but keeps earlier records`() {
        val good = B.exthString(ExthType.TITLE, "Kept")
        // Declared length 3: smaller than its own 8-byte header.
        val bogus = B.u32(ExthType.AUTHOR) + B.u32(3) + B.bytes(0x41)
        val (_, exth) = exthBuffer(listOf(good, bogus), declaredCountOverride = 2)
        assertNotNull(exth)
        assertEquals("Kept", exth!!.text(ExthType.TITLE))
        assertEquals(1, exth.records.size, "bogus record must not be collected")
    }

    @Test
    fun `a record that overruns the block is dropped`() {
        val good = B.exthString(ExthType.TITLE, "Kept")
        // Declared length 16384 while the block only holds a few bytes.
        val overrun = B.u32(ExthType.AUTHOR) + B.u32(16384) + B.bytes(0x41, 0x42)
        val (_, exth) = exthBuffer(listOf(good, overrun), declaredCountOverride = 2)
        assertEquals("Kept", exth!!.text(ExthType.TITLE))
        assertEquals(1, exth.records.size)
    }

    @Test
    fun `a declared EXTH length that exceeds the record falls back to the record end`() {
        val (_, exth) = exthBuffer(
            listOf(B.exthString(ExthType.TITLE, "Bounded")),
            declaredLengthOverride = 100_000,
        )
        assertNotNull(exth)
        assertEquals("Bounded", exth!!.text(ExthType.TITLE))
    }

    @Test
    fun `a missing EXTH block yields null and empty metadata accessors`() {
        assertNull(Exth.read(ByteArray(64), null, null, Charsets.UTF_8))
        // Wrong magic at the declared offset.
        val buffer = ByteArray(64)
        "XXXX".toByteArray().copyInto(buffer, 16)
        assertNull(Exth.read(buffer, 16, 64, Charsets.UTF_8))
    }

    @Test
    fun `string values are trimmed of trailing NULs`() {
        val (_, exth) = exthBuffer(
            listOf(B.exthRecord(ExthType.TITLE, "Padded".toByteArray() + B.bytes(0x00, 0x00)))
        )
        assertEquals("Padded", exth!!.text(ExthType.TITLE))
    }

    @Test
    fun `non-ASCII multi byte UTF-8 values round trip`() {
        val (_, exth) = exthBuffer(listOf(B.exthString(ExthType.TITLE, "\u4e2d\u6587\u6807\u9898")))
        assertEquals("\u4e2d\u6587\u6807\u9898", exth!!.text(ExthType.TITLE))
    }

    @Test
    fun `int accessors need at least four bytes`() {
        val record = Exth.Record(ExthType.COVER_OFFSET, B.bytes(0x01))
        assertNull(record.intValue())
        assertEquals(0x01020304, Exth.Record(ExthType.COVER_OFFSET, B.u32(0x01020304)).intValue())
        // A block start outside the record is reported as "no EXTH", not a crash.
        assertNull(Exth.read(ByteArray(8), 4, 8, Charsets.UTF_8))
    }
}
