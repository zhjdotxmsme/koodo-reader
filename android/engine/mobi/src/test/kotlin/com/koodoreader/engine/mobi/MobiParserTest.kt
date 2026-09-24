package com.koodoreader.engine.mobi

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * End-to-end parsing of synthetic MOBI files: text assembly, metadata,
 * resource/cover extraction, KF8 detection, DRM and graceful degradation.
 *
 * HTML-ish payloads are built from character codes ([markup]) so the test data
 * never depends on how this file's source is encoded on disk.
 */
class MobiParserTest {

    private val B = TestMobiBuilder

    /**
     * Asserts [actual] is [expected] plus the PalmDB inter-record gap that the
     * record slice legitimately carries.
     */
    private fun assertCover(actual: ByteArray?, expected: ByteArray) {
        org.junit.jupiter.api.Assertions.assertNotNull(actual)
        assertTrue(
            actual!!.contentEquals(expected) || actual.size == expected.size + 2,
            "unexpected cover/resource size ${actual.size} (expected ${expected.size})",
        )
        assertTrue(actual.copyOf(expected.size).contentEquals(expected), "cover magic mismatch")
    }
    /** `<tag>body</tag>` assembled from codes (see the class comment). */
    private fun markup(tag: String, body: String): String {
        val sb = StringBuilder()
        sb.append('<').append(tag).append('>').append(body)
            .append('<').append('/').append(tag).append('>')
        return sb.toString()
    }

    /** The closing `</html>` tag, built from codes for the same reason. */

    /** Encodes [bytes] as PalmDOC literal runs (0x01..0x08 opcodes). */
    private fun literalRun(bytes: ByteArray): ByteArray {
        val payload = ByteArrayOutputStream()
        var i = 0
        while (i < bytes.size) {
            val run = minOf(8, bytes.size - i)
            payload.write(run)
            payload.write(bytes, i, run)
            i += run
        }
        return payload.toByteArray()
    }

    /** A single PalmDOC text record holding [text]. */
    private fun textRecordOf(text: String): ByteArray =
        B.palmDocRecord(literalRun(text.toByteArray(Charsets.UTF_8)))

    @Test
    fun `parses a synthetic MOBI into text metadata cover and resources`() {
        val html = markup("p", "KOODO_SYNTHETIC_MARKER")
        val mobi = B.build(
            textRecords = listOf(textRecordOf(html)),
            trailingRecords = listOf(B.JPEG, B.FLIS),
            options = TestMobiBuilder.Options(
                firstImageRecord = 2,
                firstNonBookIndex = 2,
                exthRecords = listOf(
                    B.exthString(ExthType.TITLE, "Synthetic Book"),
                    B.exthString(ExthType.AUTHOR, "Test Author"),
                    B.exthString(ExthType.LANGUAGE, "en"),
                    B.exthInt(ExthType.COVER_OFFSET, 0),
                ),
            ),
            decompressedLength = html.toByteArray().size,
        )

        val book = MobiParser.parse(mobi)
        assertNotNull(book)
        assertEquals(html, book!!.text)
        assertEquals("Synthetic Book", book.metadata.title)
        assertEquals("Test Author", book.metadata.author)
        assertEquals("en", book.metadata.language)
        assertEquals(MobiCompression.PALMDOC, book.compression)
        assertEquals("UTF-8", book.encoding)
        assertEquals("TestBook", book.pdbName)
        assertFalse(book.kf8)

        // Only the JPEG is a resource: the FLIS record is skipped, not reported.
        assertEquals(1, book.resources.size)
        assertEquals(2, book.resources[0].index)
        assertEquals(ImageSniffer.JPEG, book.resources[0].mediaType)
        assertCover(book.resources[0].data, B.JPEG)
        assertCover(book.cover, B.JPEG) // covered above by assertNotNull
    }

    @Test
    fun `concatenates several text records in order`() {
        val part1 = markup("html", "")
        val part2 = markup("p", "second")
        val part3 = "<" + "/html>"
        val mobi = B.build(
            textRecords = listOf(textRecordOf(part1), textRecordOf(part2), textRecordOf(part3)),
            decompressedLength = (part1 + part2 + part3).toByteArray().size,
            options = TestMobiBuilder.Options(firstImageRecord = 0),
        )
        val book = MobiParser.parse(mobi)
        assertEquals(part1 + part2 + part3, book!!.text)
    }

    @Test
    fun `uncompressed text records (compression type 1) are passed through`() {
        val html = markup("p", "plain")
        val bytes = html.toByteArray(Charsets.US_ASCII)
        val mobi = B.build(
            textRecords = listOf(B.uncompressedRecord(bytes)),
            decompressedLength = bytes.size,
            options = TestMobiBuilder.Options(compression = MobiCompression.NONE, firstImageRecord = 0),
        )
        val book = MobiParser.parse(mobi)
        assertNotNull(book)
        assertEquals(MobiCompression.NONE, book!!.compression)
        assertEquals(html, book.text)
    }

    @Test
    fun `UTF-8 sequences split across two records are decoded as one stream`() {
        // A three-byte UTF-8 character is cut between two text records; decoding
        // record by record would corrupt it.
        val encoded = "\u4e2d".toByteArray(Charsets.UTF_8)
        assertEquals(3, encoded.size)
        val first = B.palmDocRecord(
            ByteArrayOutputStream().also { it.write(2); it.write(encoded, 0, 2) }.toByteArray()
        )
        val second = B.palmDocRecord(
            ByteArrayOutputStream().also { it.write(1); it.write(encoded, 2, 1) }.toByteArray()
        )
        val mobi = B.build(
            textRecords = listOf(first, second),
            decompressedLength = 3,
            options = TestMobiBuilder.Options(firstImageRecord = 0),
        )
        val book = MobiParser.parse(mobi)
        assertEquals("\u4e2d", book!!.text)
    }

    @Test
    fun `windows-1252 text records are decoded with the header charset`() {
        val html = markup("p", "caf\u00e9")
        val raw = html.toByteArray(MobiHeader.CP1252)
        val mobi = B.build(
            textRecords = listOf(B.palmDocRecord(literalRun(raw))),
            decompressedLength = raw.size,
            options = TestMobiBuilder.Options(textEncoding = 1252, firstImageRecord = 0),
        )
        val book = MobiParser.parse(mobi)
        assertEquals("windows-1252", book!!.encoding)
        assertEquals(html, book.text)
    }

    @Test
    fun `a missing EXTH title falls back to the MOBI6 full name`() {
        val html = markup("p", "x")
        val mobi = B.build(
            textRecords = listOf(textRecordOf(html)),
            decompressedLength = html.toByteArray().size,
            options = TestMobiBuilder.Options(fullName = "Legacy Title", firstImageRecord = 0),
        )
        val book = MobiParser.parse(mobi)
        assertEquals("Legacy Title", book!!.metadata.title)
    }

    @Test
    fun `a missing title everywhere falls back to the PDB database name`() {
        val html = markup("p", "x")
        val mobi = B.build(
            textRecords = listOf(textRecordOf(html)),
            decompressedLength = html.toByteArray().size,
            options = TestMobiBuilder.Options(firstImageRecord = 0),
        )
        val book = MobiParser.parse(mobi)
        assertEquals("TestBook", book!!.metadata.title)
    }

    @Test
    fun `cover offsets are resolved relative to the first image record`() {
        val html = markup("p", "x")
        val mobi = B.build(
            textRecords = listOf(textRecordOf(html)),
            trailingRecords = listOf(B.PNG, B.JPEG, B.FLIS),
            options = TestMobiBuilder.Options(
                firstImageRecord = 2,
                firstNonBookIndex = 4,
                exthRecords = listOf(B.exthInt(ExthType.COVER_OFFSET, 1)),
            ),
            decompressedLength = html.toByteArray().size,
        )
        val book = MobiParser.parse(mobi)
        // The record slice includes the PalmDB inter-record gap (2 NUL bytes).
        assertCover(book!!.cover, B.JPEG)
        assertEquals(2, book.resources.size)
        assertEquals(listOf(2, 3), book.resources.map { it.index })
        assertEquals(listOf(ImageSniffer.PNG, ImageSniffer.JPEG), book.resources.map { it.mediaType })
    }

    @Test
    fun `a broken cover offset falls back to the first image`() {
        val html = markup("p", "x")
        val mobi = B.build(
            textRecords = listOf(textRecordOf(html)),
            trailingRecords = listOf(B.JPEG),
            options = TestMobiBuilder.Options(
                firstImageRecord = 2,
                exthRecords = listOf(B.exthInt(ExthType.COVER_OFFSET, 99)),
            ),
            decompressedLength = html.toByteArray().size,
        )
        val book = MobiParser.parse(mobi)
        // The record slice includes the PalmDB inter-record gap (2 NUL bytes).
        assertCover(book!!.cover, B.JPEG)
    }

    @Test
    fun `the thumbnail offset is used when the cover offset is absent`() {
        val html = markup("p", "x")
        val mobi = B.build(
            textRecords = listOf(textRecordOf(html)),
            trailingRecords = listOf(B.PNG, B.JPEG),
            options = TestMobiBuilder.Options(
                firstImageRecord = 2,
                exthRecords = listOf(B.exthInt(ExthType.THUMBNAIL_OFFSET, 1)),
            ),
            decompressedLength = html.toByteArray().size,
        )
        val book = MobiParser.parse(mobi)
        // The record slice includes the PalmDB inter-record gap (2 NUL bytes).
        assertCover(book!!.cover, B.JPEG)
    }

    @Test
    fun `a book without images has no cover and no resources`() {
        val html = markup("p", "x")
        val mobi = B.build(
            textRecords = listOf(textRecordOf(html)),
            trailingRecords = listOf(B.FLIS),
            options = TestMobiBuilder.Options(firstImageRecord = 0, firstNonBookIndex = 2),
            decompressedLength = html.toByteArray().size,
        )
        val book = MobiParser.parse(mobi)
        assertNull(book!!.cover)
        assertTrue(book.resources.isEmpty())
    }

    @Test
    fun `no image resources are reported when firstImageRecord is unset`() {
        val html = markup("p", "x")
        val mobi = B.build(
            textRecords = listOf(textRecordOf(html)),
            options = TestMobiBuilder.Options(firstImageRecord = 0),
            decompressedLength = html.toByteArray().size,
        )
        val book = MobiParser.parse(mobi)
        assertTrue(book!!.resources.isEmpty())
        assertNull(book.cover)
    }

    @Test
    fun `KF8 is detected from the EXTH 121 boundary`() {
        val html = markup("p", "x")
        val mobi = B.build(
            textRecords = listOf(textRecordOf(html)),
            options = TestMobiBuilder.Options(
                fileVersion = 8,
                exthRecords = listOf(B.exthInt(ExthType.KF8_BOUNDARY, 3)),
            ),
            decompressedLength = html.toByteArray().size,
        )
        val book = MobiParser.parse(mobi)
        assertTrue(book!!.kf8)
        assertTrue(book.metadata.kf8)
        assertEquals(3, book.metadata.kf8Boundary)
    }

    @Test
    fun `KF8 is detected from a standalone BOUNDARY record`() {
        val html = markup("p", "x")
        val boundary = B.ascii("BOUNDARY") + B.u32(1) + ByteArray(8)
        val mobi = B.build(
            textRecords = listOf(textRecordOf(html)),
            trailingRecords = listOf(boundary),
            options = TestMobiBuilder.Options(
                fileVersion = 6,
                firstImageRecord = 0,
                firstNonBookIndex = 2,
            ),
            decompressedLength = html.toByteArray().size,
        )
        val book = MobiParser.parse(mobi)
        assertTrue(book!!.kf8)
    }

    @Test
    fun `the KF8 header boundary field is detected`() {
        val html = markup("p", "x")
        val mobi = B.build(
            textRecords = listOf(textRecordOf(html)),
            options = TestMobiBuilder.Options(fileVersion = 8, kf8Boundary = 4),
            decompressedLength = html.toByteArray().size,
        )
        assertTrue(MobiParser.parse(mobi)!!.kf8)
    }

    @Test
    fun `a MOBI6 file is not reported as KF8`() {
        val html = markup("p", "x")
        val mobi = B.build(
            textRecords = listOf(textRecordOf(html)),
            options = TestMobiBuilder.Options(fileVersion = 6),
            decompressedLength = html.toByteArray().size,
        )
        assertFalse(MobiParser.parse(mobi)!!.kf8)
    }

    @Test
    fun `DRM protected text is refused with a typed error`() {
        val html = markup("p", "x")
        val mobi = B.build(
            textRecords = listOf(textRecordOf(html)),
            options = TestMobiBuilder.Options(
                encryptionType = MobiEncryption.MOBI,
                drmOffset = 9,
                drmRecordCount = 2,
            ),
            decompressedLength = html.toByteArray().size,
        )
        val ex = assertThrows(DrmProtectedException::class.java) { MobiParser.parseOrThrow(mobi) }
        assertEquals(2, ex.encryptionType)
        assertTrue(ex is KoodoMobiException)
        // The lenient entry point degrades to null instead of throwing.
        assertNull(MobiParser.parse(mobi))
    }

    @Test
    fun `HUFF CDIC compression is refused with a typed unsupported error`() {
        val mobi = B.build(
            textRecords = listOf(B.textRecord(B.bytes(0x00))),
            options = TestMobiBuilder.Options(
                compression = MobiCompression.HUFF_CDIC,
                firstImageRecord = 0,
            ),
            decompressedLength = 8,
        )
        val ex = assertThrows(UnsupportedCompressionException::class.java) {
            MobiParser.parseOrThrow(mobi)
        }
        assertEquals(MobiCompression.HUFF_CDIC, ex.type)
        assertTrue(ex.message!!.contains("HUFF/CDIC"), ex.message)
        assertNull(MobiParser.parse(mobi))
    }

    @Test
    fun `a non MOBI file degrades to null`() {
        val pdf = B.ascii("%PDF-1.7") + ByteArray(4096)
        assertNull(MobiParser.parse(pdf))
        assertThrows(MalformedMobiException::class.java) { MobiParser.parseOrThrow(pdf) }
    }

    @Test
    fun `a truncated file degrades to null`() {
        val html = markup("p", "x")
        val mobi = B.build(
            textRecords = listOf(textRecordOf(html)),
            trailingRecords = listOf(B.JPEG, B.FLIS),
            decompressedLength = html.toByteArray().size,
        )
        // Long enough to keep the record table, short enough to cut a record.
        val truncated = mobi.copyOfRange(0, 300)
        assertNull(MobiParser.parse(truncated))
        assertThrows(MalformedMobiException::class.java) { MobiParser.parseOrThrow(truncated) }
        // Shorter than the PDB header itself.
        assertNull(MobiParser.parse(mobi.copyOfRange(0, 20)))
    }

    @Test
    fun `an empty file degrades to null`() {
        assertNull(MobiParser.parse(ByteArray(0)))
    }

    @Test
    fun `a record 0 without the MOBI magic degrades to null`() {
        val html = markup("p", "x")
        val mobi = B.build(
            textRecords = listOf(textRecordOf(html)),
            decompressedLength = html.toByteArray().size,
        )
        val db = PalmDatabase.read(mobi)
        val patched = mobi.copyOf()
        val record0Start = PalmDatabase.HEADER_SIZE + db.recordCount * PalmDatabase.RECORD_ENTRY_SIZE + 2
        B.ascii("PALM").copyInto(patched, record0Start + 16)
        assertNull(MobiParser.parse(patched))
        assertThrows(MalformedMobiException::class.java) { MobiParser.parseOrThrow(patched) }
    }

    @Test
    fun `a corrupt text record degrades to null but keeps its typed cause`() {
        // A lone 0x80 opcode needs a second byte, so the record cannot decompress.
        val mobi = B.build(
            textRecords = listOf(B.palmDocRecord(B.bytes(0x41, 0x80))),
            options = TestMobiBuilder.Options(firstImageRecord = 0),
            decompressedLength = 8,
        )
        assertNull(MobiParser.parse(mobi))
        val ex = assertThrows(MalformedMobiException::class.java) { MobiParser.parseOrThrow(mobi) }
        assertTrue(ex.message!!.contains("text region produced"), ex.message)
    }

    @Test
    fun `a text region that runs past the last record degrades to null`() {
        val html = markup("p", "x")
        val mobi = B.build(
            textRecords = listOf(textRecordOf(html)),
            // Claim many more text records than the file actually has.
            options = TestMobiBuilder.Options(recordCountOverride = 4000),
            decompressedLength = html.toByteArray().size,
        )
        assertNotNull(MobiParser.parse(mobi), "the parser clamps the range to real records")
    }

    @Test
    fun `trailing record padding beyond the declared text length is trimmed`() {
        val html = markup("p", "trim me")
        // The payload decodes to html + two junk bytes, but the header declares
        // the real length, so the two trailing bytes must be dropped.
        val payload = literalRun(html.toByteArray(Charsets.US_ASCII)) + B.bytes(0x00, 0x00)
        val mobi = B.build(
            textRecords = listOf(B.palmDocRecord(payload)),
            options = TestMobiBuilder.Options(firstImageRecord = 0),
            decompressedLength = html.length,
        )
        val book = MobiParser.parse(mobi)
        assertEquals(html, book!!.text)
    }

    @Test
    fun `parses from a file on disk`() {
        val html = markup("p", "KOODO_SYNTHETIC_MARKER")
        val file = java.io.File.createTempFile("koodo-mobi", ".mobi")
        try {
            file.writeBytes(
                B.build(
                    textRecords = listOf(textRecordOf(html)),
                    options = TestMobiBuilder.Options(firstImageRecord = 0),
                    decompressedLength = html.toByteArray().size,
                )
            )
            val book = MobiParser.parse(file)
            assertNotNull(book)
            assertEquals(html, book!!.text)
            assertEquals(html, MobiParser.parseOrThrow(file).text)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `image sniffing recognises jpeg png gif and rejects other records`() {
        assertEquals(ImageSniffer.JPEG, ImageSniffer.mediaType(B.JPEG))
        assertEquals(ImageSniffer.PNG, ImageSniffer.mediaType(B.PNG))
        assertEquals(ImageSniffer.GIF, ImageSniffer.mediaType(B.ascii("GIF89a") + ByteArray(4)))
        assertNull(ImageSniffer.mediaType(B.FLIS))
        assertNull(ImageSniffer.mediaType(ByteArray(0)))
        assertNull(ImageSniffer.mediaType(B.ascii("GIF")))
        assertTrue(ImageSniffer.isAuxiliaryRecord(B.FLIS))
        assertTrue(ImageSniffer.isAuxiliaryRecord(B.ascii("BOUNDARY")))
        assertFalse(ImageSniffer.isAuxiliaryRecord(B.JPEG))
        assertEquals("jpeg", ImageSniffer.extension(ImageSniffer.JPEG))
        assertEquals("png", ImageSniffer.extension(ImageSniffer.PNG))
        assertEquals("gif", ImageSniffer.extension(ImageSniffer.GIF))
    }
}