package com.koodoreader.engine.mobi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Real calibre-generated fixtures (`ebook-convert in.html out.mobi --cover
 * cover.png`, same file with a `.azw3` extension for KF8). Hand-crafted bytes
 * prove the algorithms; these prove the offsets and record conventions of the
 * files users actually have.
 */
class RealFixtureTest {

    @TempDir
    lateinit var dir: File

    private fun fixture(name: String): File {
        val stream = javaClass.getResourceAsStream("/mobi/$name")
            ?: error("missing test resource /mobi/$name")
        val f = File(dir, name)
        stream.use { input -> f.outputStream().use { input.copyTo(it) } }
        return f
    }

    @Test
    fun `parses a real calibre MOBI6 with text metadata and cover`() {
        val book = MobiParser.parse(fixture("sample.mobi"))
        assertNotNull(book, "real calibre MOBI must parse")
        book!!

        assertEquals("Koodo Mobi Fixture", book.metadata.title)
        assertEquals("zh", book.metadata.language)
        assertEquals("Unknown", book.metadata.author)
        assertEquals(MobiCompression.PALMDOC, book.compression)
        assertEquals("UTF-8", book.encoding)
        assertEquals("Koodo_Mobi_Fixture", book.pdbName)
        assertTrue(book.text.contains("KOODO_PARA_MARKER"), "text lost: ${book.text.take(120)}")
        assertTrue(book.text.contains("KOODO_SECOND_MARKER"))
        assertTrue(book.text.contains("<html") || book.text.contains("<?xml"), book.text.take(60))
        assertTrue(book.resources.isNotEmpty(), "expected image resources")
        assertTrue(book.resources.all { it.isImage }, "non-image record leaked into resources")
        assertNotNull(book.cover)
        assertTrue(book.cover!!.size > 200, "cover too small: ${book.cover!!.size}")
        assertTrue(
            ImageSniffer.isJpeg(book.cover!!) || ImageSniffer.isPng(book.cover!!),
            "cover is not a sniffable image",
        )
    }

    @Test
    fun `parses a real calibre AZW3 as KF8`() {
        val file = fixture("sample.azw3")
        val book = MobiParser.parse(file)
        assertNotNull(book, "real calibre AZW3 must parse")
        book!!

        assertEquals("Koodo Mobi Fixture", book.metadata.title)
        assertTrue(book.kf8, "AZW3 should be detected as KF8")
        // The KF8 file version is the structural signal behind that verdict.
        val header = MobiHeader.read(PalmDatabase.read(file.readBytes()).records[0])
        assertEquals(8, header.fileVersion)
        assertTrue(book.text.contains("KOODO_PARA_MARKER"), "text lost: ${book.text.take(120)}")
        assertTrue(book.text.contains("KOODO_SECOND_MARKER"))
        assertTrue(book.resources.isNotEmpty())
        assertNotNull(book.cover)
        assertTrue(book.cover!!.size > 200)
        // The AZW3 fixture embeds the PNG cover.
        assertTrue(ImageSniffer.isPng(book.cover!!), "expected the PNG cover")
    }

    @Test
    fun `parsing is deterministic across repeated reads`() {
        val a = MobiParser.parse(fixture("sample.mobi"))
        val b = MobiParser.parse(fixture("sample.mobi"))
        assertNotNull(a)
        assertNotNull(b)
        assertEquals(a!!.text, b!!.text)
        assertEquals(a.resources.size, b.resources.size)
        assertEquals(a.cover?.size, b.cover?.size)
        assertEquals(a.metadata, b.metadata)
    }

    @Test
    fun `the extracted text is the whole book not a truncated prefix`() {
        val book = MobiParser.parse(fixture("sample.mobi"))!!
        // calibre wrote 1141 bytes of text; a wrong textRecordRange or a bad
        // VLQ handling shows up as a much shorter (or garbled) string.
        assertTrue(book.text.length > 1000, "text suspiciously short: ${book.text.length}")
        assertTrue(book.text.trimEnd().endsWith("</html>"), "text does not end cleanly: ${book.text.takeLast(40)}")
    }
}
