package com.koodoreader.core.importer.metadata

import com.koodoreader.core.importer.BookFormat
import com.koodoreader.core.importer.BookMetadata
import com.koodoreader.core.importer.BookSource
import com.koodoreader.core.importer.ImportPipeline
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MetadataParsersTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun `epub reads OPF metadata`() {
        val file = zipWith(
            "book.epub",
            "META-INF/container.xml" to """
                <container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>
            """,
            "OEBPS/content.opf" to """
                <package xmlns:dc="http://purl.org/dc/elements/1.1/"><metadata>
                    <dc:title>EPUB &amp; Title</dc:title>
                    <dc:creator>EPUB Author</dc:creator>
                    <dc:description>EPUB Description</dc:description>
                    <dc:publisher>EPUB Publisher</dc:publisher>
                </metadata><manifest/><spine/></package>
            """,
        )

        assertEquals(
            BookMetadata("EPUB & Title", "EPUB Author", "EPUB Description", "EPUB Publisher"),
            MetadataParsers.parse(BookFormat.EPUB, file, "fallback"),
        )
    }

    @Test
    fun `docx reads core properties`() {
        val file = zipWith(
            "book.docx",
            "docProps/core.xml" to """
                <cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>Native title</dc:title><dc:creator>Author</dc:creator>
                    <dc:description>Description</dc:description><dc:publisher>Publisher</dc:publisher>
                </cp:coreProperties>
            """,
        )

        val data = MetadataParsers.parse(BookFormat.DOCX, file, "fallback")
        assertEquals("Native title", data.name)
        assertEquals("Author", data.author)
        assertEquals("Description", data.description)
        assertEquals("Publisher", data.publisher)
    }

    @Test
    fun `mobi family reads EXTH metadata`() {
        val file = File(dir, "book.mobi").apply { writeBytes(mobiWithExth()) }
        val expected = BookMetadata("MOBI Title", "MOBI Author", "MOBI Description", "MOBI Publisher")

        for (format in listOf(BookFormat.MOBI, BookFormat.AZW, BookFormat.AZW3)) {
            assertEquals(expected, MetadataParsers.parse(format, file, "fallback"))
        }
    }

    @Test
    fun `fb2 reads title info and publish info`() {
        val file = File(dir, "book.fb2").apply {
            writeText(
                """<?xml version="1.0" encoding="utf-8"?>
                    <FictionBook><description><title-info>
                        <book-title>FB2 Title</book-title>
                        <author><first-name>First</first-name><middle-name>Middle</middle-name><last-name>Last</last-name></author>
                        <annotation><p>FB2 Description</p></annotation>
                    </title-info><publish-info><publisher>FB2 Publisher</publisher></publish-info></description>
                    <body><section><title>Chapter Title</title><p>Body</p></section></body></FictionBook>
                """,
            )
        }

        assertEquals(
            BookMetadata("FB2 Title", "First Middle Last", "FB2 Description", "FB2 Publisher"),
            MetadataParsers.parse(BookFormat.FB2, file, "fallback"),
        )
    }

    @Test
    fun `html family reads title and meta properties`() {
        val html = """
            <html><head><title>HTML &amp; Title</title>
                <meta name="author" content="HTML Author">
                <meta content="HTML Description" name="description">
                <meta name="publisher" content="HTML Publisher">
            </head><body><p>Body</p></body></html>
        """
        for (format in listOf(BookFormat.HTML, BookFormat.HTM, BookFormat.XHTML, BookFormat.XML)) {
            val file = File(dir, "book.${format.extension}").apply { writeText(html) }
            assertEquals(
                BookMetadata("HTML & Title", "HTML Author", "HTML Description", "HTML Publisher"),
                MetadataParsers.parse(format, file, "fallback"),
            )
        }
    }

    @Test
    fun `mhtml reads metadata from its html part`() {
        val file = File(dir, "book.mhtml").apply {
            writeText(
                "Content-Type: multipart/related; boundary=\"boundary\"\r\n\r\n" +
                    "--boundary\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n" +
                    "<html><head><title>MHTML Title</title>" +
                    "<meta name=\"author\" content=\"MHTML Author\"></head><body>Body</body></html>\r\n" +
                    "--boundary--\r\n",
            )
        }

        assertEquals(
            BookMetadata("MHTML Title", "MHTML Author", null, null),
            MetadataParsers.parse(BookFormat.MHTML, file, "fallback"),
        )
    }

    @Test
    fun `corrupt structured files fall back without throwing`() {
        for (format in listOf(BookFormat.EPUB, BookFormat.MOBI, BookFormat.DOCX, BookFormat.FB2)) {
            val file = File(dir, "broken.${format.extension}").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            assertEquals(
                BookMetadata("fallback", null, null, null),
                MetadataParsers.parse(format, file, "fallback"),
            )
        }
    }

    @Test
    fun `txt and comics use filename without guessing authors`() {
        for (format in listOf(BookFormat.TXT, BookFormat.CBZ)) {
            val file = File(dir, "plain.${format.extension}").apply { writeText("Author: Not Metadata") }
            assertEquals(
                BookMetadata("plain", null, null, null),
                MetadataParsers.parse(format, file, "plain"),
            )
        }
    }

    @Test
    fun `pipeline fills present metadata and retains defaults for missing fields`() = runBlocking {
        val file = zipWith(
            "partial.docx",
            "docProps/core.xml" to """
                <cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>DOCX Pipeline Title</dc:title><dc:creator>DOCX Pipeline Author</dc:creator>
                </cp:coreProperties>
            """,
        )
        val booksOut = File(dir, "books")

        val result = ImportPipeline().process(
            listOf(BookSource("partial.docx", { file.inputStream() })),
            booksOut,
        )

        assertTrue(result.failures.isEmpty(), "failures: ${result.failures}")
        val record = result.records.single()
        assertEquals("DOCX Pipeline Title", record.name)
        assertEquals("DOCX Pipeline Author", record.author)
        assertNull(record.description)
        assertNull(record.publisher)
    }

    @Test
    fun `broken metadata does not block import or add failures`() = runBlocking {
        val file = File(dir, "broken.docx").apply { writeText("not a zip") }
        val result = ImportPipeline().process(
            listOf(BookSource("broken.docx", { file.inputStream() })),
            File(dir, "books"),
        )

        assertTrue(result.failures.isEmpty(), "failures: ${result.failures}")
        val record = result.records.single()
        assertEquals("broken", record.name)
        assertNull(record.author)
        assertNull(record.description)
        assertNull(record.publisher)
    }

    @Test
    fun `pdf metadata comes from the injected host extractor`() = runBlocking {
        val file = File(dir, "document.pdf").apply { writeText("%PDF-1.4\n%%EOF\n") }
        val pipeline = ImportPipeline(
            pdfMetadataExtractor = {
                BookMetadata("PDF Title", "PDF Author", "PDF Description", "PDF Publisher")
            },
        )

        val result = pipeline.process(
            listOf(BookSource("document.pdf", { file.inputStream() })),
            File(dir, "books"),
        )

        assertTrue(result.failures.isEmpty(), "failures: ${result.failures}")
        val record = result.records.single()
        assertEquals("PDF Title", record.name)
        assertEquals("PDF Author", record.author)
        assertEquals("PDF Description", record.description)
        assertEquals("PDF Publisher", record.publisher)
    }

    @Test
    fun `pdf extractor failure retains filename and does not block import`() = runBlocking {
        val file = File(dir, "document.pdf").apply { writeText("%PDF-1.4\n%%EOF\n") }
        val pipeline = ImportPipeline(pdfMetadataExtractor = { error("native metadata unavailable") })

        val result = pipeline.process(
            listOf(BookSource("document.pdf", { file.inputStream() })),
            File(dir, "books"),
        )

        assertTrue(result.failures.isEmpty(), "failures: ${result.failures}")
        assertEquals("document", result.records.single().name)
    }

    private fun zipWith(name: String, vararg entries: Pair<String, String>): File {
        val file = File(dir, name)
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((path, text) in entries) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return file
    }

    private fun mobiWithExth(): ByteArray {
        val exth = exth(
            503 to "MOBI Title",
            100 to "MOBI Author",
            103 to "MOBI Description",
            101 to "MOBI Publisher",
        )
        val record0 = ByteArray(16 + 232 + exth.size)
        putU16(record0, 0, 1)
        putU32(record0, 4, 4)
        putU16(record0, 8, 1)
        putU16(record0, 10, 4096)
        putU16(record0, 12, 0)
        "MOBI".toByteArray(Charsets.US_ASCII).copyInto(record0, 16)
        putU32(record0, 20, 232)
        putU32(record0, 24, 2)
        putU32(record0, 28, 65001)
        putU32(record0, 36, 6)
        putU32(record0, 80, 2)
        putU32(record0, 128, 0x40)
        putU32(record0, 168, -1)
        exth.copyInto(record0, 248)

        val text = "Body".toByteArray(Charsets.UTF_8)
        val record0Offset = 78 + 2 * 8 + 2
        val record1Offset = record0Offset + record0.size + 2
        val pdb = ByteArray(78 + 16)
        "MetadataFixture".toByteArray(Charsets.US_ASCII).copyInto(pdb, 0)
        "BOOK".toByteArray(Charsets.US_ASCII).copyInto(pdb, 60)
        "MOBI".toByteArray(Charsets.US_ASCII).copyInto(pdb, 64)
        putU16(pdb, 76, 2)
        putU32(pdb, 78, record0Offset)
        putU32(pdb, 86, record1Offset)

        return ByteArrayOutputStream().also { out ->
            out.writeBytes(pdb)
            out.writeBytes(ByteArray(2))
            out.writeBytes(record0)
            out.writeBytes(ByteArray(2))
            out.writeBytes(text)
            out.writeBytes(ByteArray(2))
        }.toByteArray()
    }

    private fun exth(vararg values: Pair<Int, String>): ByteArray {
        val body = ByteArrayOutputStream()
        for ((type, value) in values) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            val record = ByteArray(8)
            putU32(record, 0, type)
            putU32(record, 4, bytes.size + 8)
            body.writeBytes(record)
            body.writeBytes(bytes)
        }
        val header = ByteArray(12)
        "EXTH".toByteArray(Charsets.US_ASCII).copyInto(header, 0)
        putU32(header, 4, body.size() + 12)
        putU32(header, 8, values.size)
        return header + body.toByteArray()
    }

    private fun putU16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 8).toByte()
        target[offset + 1] = value.toByte()
    }

    private fun putU32(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }
}
