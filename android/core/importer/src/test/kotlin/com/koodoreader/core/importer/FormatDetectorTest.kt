package com.koodoreader.core.importer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FormatDetectorTest {

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    @Test
    fun `content wins over misleading name and mime`() {
        val pdf = "%PDF-1.7\n".byteInputStream()
        val detected = BookFormat.detect("attachment.bin", "application/octet-stream") { pdf }
        assertEquals(FormatDetection.Supported(BookFormat.PDF), detected)
    }

    @Test
    fun `detects content without an extension or a specific mime`() {
        val detected = BookFormat.detect(null, "application/octet-stream") {
            "%PDF-1.4\n".byteInputStream()
        }
        assertEquals(FormatDetection.Supported(BookFormat.PDF), detected)
    }

    @Test
    fun `rar is explicitly unsupported before import`() {
        val detected = BookFormat.detect("book.bin", null) {
            byteArrayOf(
                'R'.code.toByte(), 'a'.code.toByte(), 'r'.code.toByte(), '!'.code.toByte(),
                0x1A, 0x07, 0x01, 0x00,
            ).inputStream()
        }
        assertEquals(FormatDetection.Unsupported("CBR"), detected)
    }

    @Test
    fun `cbr metadata is explicitly unsupported`() {
        val detected = BookFormat.detect("book.cbr", "application/x-cbr") {
            byteArrayOf(0, 1, 2).inputStream()
        }
        assertEquals(FormatDetection.Unsupported("CBR"), detected)
    }

    @Test
    fun `detects epub from its mimetype entry with fresh streams`() {
        val epub = zip(
            mapOf(
                "mimetype" to "application/epub+zip".toByteArray(),
                "META-INF/container.xml" to "<container/>".toByteArray(),
            ),
        )
        var opens = 0
        val detected = BookFormat.detect("book.zip", "application/zip") {
            opens++
            epub.inputStream()
        }
        assertEquals(FormatDetection.Supported(BookFormat.EPUB), detected)
        assertEquals(2, opens)
    }

    @Test
    fun `docx structure wins over epub metadata`() {
        val docx = zip(
            mapOf(
                "word/document.xml" to "<document/>".toByteArray(),
                "[Content_Types].xml" to "<Types/>".toByteArray(),
            ),
        )
        val detected = BookFormat.detect("book.epub", "application/epub+zip") { docx.inputStream() }
        assertEquals(FormatDetection.Supported(BookFormat.DOCX), detected)
    }

    @Test
    fun `detects cbz when a zip contains only images`() {
        val cbz = zip(
            mapOf(
                "page-1.jpg" to byteArrayOf(1),
                "page-2.PNG" to byteArrayOf(2),
            ),
        )
        val detected = BookFormat.detect(null, "application/zip") { cbz.inputStream() }
        assertEquals(FormatDetection.Supported(BookFormat.CBZ), detected)
    }

    @Test
    fun `does not classify a zip with non-image entries as cbz`() {
        val archive = zip(mapOf("readme.txt" to "not a comic".toByteArray()))
        val detected = BookFormat.detect(null, "application/zip") { archive.inputStream() }
        assertEquals(FormatDetection.Unknown, detected)
    }

    @Test
    fun `azw3 extension keeps AZW3 when the PalmDB header is BOOKMOBI`() {
        val mobi = ByteArray(68)
        "BOOKMOBI".toByteArray().copyInto(mobi, destinationOffset = 60)
        val detected = BookFormat.detect("book.azw3", null) { mobi.inputStream() }
        assertEquals(FormatDetection.Supported(BookFormat.AZW3), detected)
    }

    @Test
    fun `detects mobi from the PalmDB header`() {
        val mobi = ByteArray(68)
        "BOOKMOBI".toByteArray().copyInto(mobi, destinationOffset = 60)
        val detected = BookFormat.detect("book.bin", null) { mobi.inputStream() }
        assertEquals(FormatDetection.Supported(BookFormat.MOBI), detected)
    }

    @Test
    fun `detects fb2 from its decoded root element`() {
        val fb2 = "<?xml version=\"1.0\" encoding=\"UTF-16\"?>" +
            "<FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\"/>"
        val detected = BookFormat.detect("book.xml", "application/xml") {
            fb2.toByteArray(Charsets.UTF_16).inputStream()
        }
        assertEquals(FormatDetection.Supported(BookFormat.FB2), detected)
    }

    @Test
    fun `detects html from its document structure`() {
        val html = "<!DOCTYPE html>\n<html><body>book</body></html>"
        val detected = BookFormat.detect("book.txt", "text/plain") { html.byteInputStream() }
        assertEquals(FormatDetection.Supported(BookFormat.HTML), detected)
    }

    @Test
    fun `detects mhtml from its mime document structure`() {
        val mhtml = "From: <Saved by Koodo>\nMIME-Version: 1.0\n" +
            "Content-Type: multipart/related; boundary=book\n\n" +
            "--book\nContent-Type: text/html\n\n<html><body>book</body></html>\n"
        val detected = BookFormat.detect("book.bin", null) { mhtml.byteInputStream() }
        assertEquals(FormatDetection.Supported(BookFormat.MHTML), detected)
    }

    @Test
    fun `plain text respects a markdown extension`() {
        val detected = BookFormat.detect("notes.md", null) { "# Title\n".byteInputStream() }
        assertEquals(FormatDetection.Supported(BookFormat.MD), detected)
    }

    @Test
    fun `plain text respects an xml extension`() {
        val xml = "<?xml version=\"1.0\"?><catalog/>"
        val detected = BookFormat.detect("catalog.xml", null) { xml.byteInputStream() }
        assertEquals(FormatDetection.Supported(BookFormat.XML), detected)
    }

    @Test
    fun `plain text respects an html extension for a fragment`() {
        val detected = BookFormat.detect("fragment.html", null) { "<p>fragment</p>".byteInputStream() }
        assertEquals(FormatDetection.Supported(BookFormat.HTML), detected)
    }

    @Test
    fun `detects plain text without metadata`() {
        val detected = BookFormat.detect(null, null) { "A plain text book.\n".byteInputStream() }
        assertEquals(FormatDetection.Supported(BookFormat.TXT), detected)
    }

    @Test
    fun `unknown binary content stays unknown`() {
        val detected = BookFormat.detect(null, null) { byteArrayOf(0, 1, 2, 3).inputStream() }
        assertEquals(FormatDetection.Unknown, detected)
    }

    @Test
    fun `book rules use the format registry and exclude cbr`() {
        assertEquals(BookFormat.entries.map { it.extension }, BookRules.BOOK_EXTENSIONS)
        assertEquals(BookFormat.entries.associate { it.extension to it.mime }, BookRules.MIME_BY_EXT)
        assertFalse(BookRules.isBookName("book.cbr"))
    }
}
