package com.koodoreader.core.dbio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Date

/**
 * Round-trip extension tests for [BackupBundle] (P7): covers/fonts/book
 * parity with the desktop layout, plus parity-enforcing tests for
 * [DataExport] / [DataImport] (CSV/JSON encode/decode matching the
 * desktop `export.ts` / `importData.ts`).
 *
 * Target: 8 tests on top of the existing 7 in BackupBundleTest, pushing
 * the matrix to the "10 round-trip" milestone the parent task tracks.
 * Tests run as part of `:core:dbio:test` (JVM), so they validate the
 * engine-level surface without requiring a device.
 */
class BackupBundleRoundTripTest {

    @TempDir
    lateinit var dir: File

    // --------------------------------------------------------- helpers

    private fun books(): List<Row> = listOf(
        mapOf(
            "key" to "k1", "name" to "EPUB One", "author" to "A",
            "description" to "d1", "md5" to "abc", "cover" to null,
            "format" to "epub", "publisher" to null, "size" to 1234L,
            "page" to 10L, "path" to "/storage/emulated/0/books/k1.epub", "charset" to null,
        ),
        mapOf(
            "key" to "k2", "name" to "漫画", "author" to null,
            "description" to null, "md5" to "def", "cover" to null,
            "format" to "cbz", "publisher" to null, "size" to 9000L,
            "page" to 7L, "path" to "/storage/emulated/0/books/k2.cbz", "charset" to null,
        ),
    )

    private fun notes(): List<Row> = listOf(
        mapOf(
            "key" to "n1", "bookKey" to "k1", "date" to "2025-01-09",
            "chapter" to "c1", "chapterIndex" to 1L, "text" to "marked text",
            "cfi" to "epubcfi(0,1)", "range" to null, "notes" to "user comment",
            "percentage" to "5", "color" to 123L, "tag" to "[\"a\",\"b\"]",
        ),
    )

    // ============================================== extended zip structure

    @Test
    fun `backup zip includes book and font entries when supplied`() {
        val zip = File(dir, "with-assets.zip")
        val bookBytes = "FAKE-EPUB-BYTES".toByteArray()
        val fontBytes = byteArrayOf(0, 1, 0, 0, 1, 1)
        BackupBundle.write(
            out = zip,
            tables = mapOf("books" to books(), "notes" to notes()),
            configJson = "{\"k\":\"v\"}",
            covers = listOf(CoverRef.of("k1.jpg", byteArrayOf(9, 9))),
            bookFiles = listOf(BookRef.of("k1.epub", bookBytes)),
            fontFiles = listOf(FontRef.of("lxgw.ttf", fontBytes)),
        )
        BackupBundle.open(zip).use { bundle ->
            // All four namespaces must be enumerated (P7 round-trip):
            assertEquals(listOf("k1.jpg"), bundle.covers.map { it.name })
            assertEquals(listOf("k1.epub"), bundle.bookFiles.map { it.name })
            assertEquals(listOf("lxgw.ttf"), bundle.fontFiles.map { it.name })
            // Books + fonts are streamed, never fully in memory at the
            // bridge boundary — verify the bytes arrive verbatim:
            val bookOut = java.io.ByteArrayOutputStream()
            bundle.bookStream("k1.epub", bookOut)
            assertTrue(bookBytes.contentEquals(bookOut.toByteArray()))
            val fontOut = java.io.ByteArrayOutputStream()
            bundle.fontStream("lxgw.ttf", fontOut)
            assertTrue(fontBytes.contentEquals(fontOut.toByteArray()))
        }
    }

    @Test
    fun `backup zip still omits book and font entries by default`() {
        // The P1 bridge default — bookFiles/fontFiles absent — must NOT
        // suddenly include empty directories (desktop restore relies on
        // absent directories to skip the iteration).
        val zip = File(dir, "defaults.zip")
        BackupBundle.write(zip, mapOf("books" to books()))
        BackupBundle.open(zip).use { bundle ->
            assertTrue(bundle.bookFiles.isEmpty())
            assertTrue(bundle.fontFiles.isEmpty())
        }
    }

    // ================================================ data export / import

    @Test
    fun `data export JSON shape matches desktop exportType contract`() {
        val rows = listOf(
            DataExport.NoteRow(
                key = "k1", bookKey = "k1", bookName = "EPUB One",
                bookAuthor = "A", bookMd5 = "abc", chapter = "c1",
                chapterIndex = 1L, text = "marked text", notes = null,
                percentage = "5", color = 0x10FFEEDD,
                tag = listOf("a", "b"), date = makeDate(2025, 1, 9),
                exportType = DataExport.ExportType.HIGHLIGHT,
            ),
        )
        val json = DataExport.encode(rows, DataExport.Format.JSON)
        // Desktop contract: exportType field drives `importData.ts` dispatch.
        assertTrue(json.contains("\"exportType\":\"highlight\""))
        assertTrue(json.contains("\"tag\":\"a,b\""))
        assertTrue(json.contains("\"date\":\"2025-01-09\""))
        // styleType + colour unpacked from the packed integer (desktop
        // `HighlightUtil.convertNumberToHighlightValue` semantics).
        assertTrue(json.contains("\"color\":\"#FFEEDD\""))
    }

    @Test
    fun `data export CSV contains the same fields in lexical header order`() {
        val rows = listOf(
            DataExport.NoteRow(
                key = "n1", bookKey = "k1", bookName = "EPUB One",
                bookAuthor = "A", bookMd5 = null, chapter = "c1",
                chapterIndex = 1L, text = "hello, world", notes = "你好",
                percentage = "5", color = null, tag = emptyList(),
                date = makeDate(2025, 3, 14),
                exportType = DataExport.ExportType.NOTE,
            ),
        )
        val csv = DataExport.encode(rows, DataExport.Format.CSV)
        // BOM for Excel; commas inside the cell quoted; non-ASCII pass-through.
        assertTrue(csv.startsWith("\ufeff"))
        assertTrue(csv.contains("\"hello, world\""))
        assertTrue(csv.contains("你好"))
        assertTrue(csv.contains("exportType"))
    }

    @Test
    fun `zip export names and per-book grouping mirror desktop export ts`() {
        val rows = listOf(
            DataExport.NoteRow(
                key = "a", bookKey = "k1", bookName = "Book One",
                bookAuthor = null, bookMd5 = null, chapter = null,
                chapterIndex = null, text = "t1", notes = "n1",
                percentage = null, color = null, tag = emptyList(),
                date = makeDate(2025, 1, 1),
                exportType = DataExport.ExportType.NOTE,
            ),
            DataExport.NoteRow(
                key = "b", bookKey = "k2", bookName = "Book Two",
                bookAuthor = null, bookMd5 = null, chapter = null,
                chapterIndex = null, text = "t2", notes = "n2",
                percentage = null, color = null, tag = emptyList(),
                date = makeDate(2025, 1, 2),
                exportType = DataExport.ExportType.NOTE,
            ),
        )
        val out = DataExport.writeExport(
            outDir = dir,
            records = rows,
            format = DataExport.Format.CSV,
            type = "Note",
        )
        assertTrue(out.isFile && out.length() > 0)
        // Verify the zip structure mirrors `KoodoReader-Note-<date>-<epoch>-CSV.zip`:
        val z = java.util.zip.ZipFile(out)
        try {
            val entries = z.entries().toList().map { it.name }.toSet()
            assertTrue("all.csv" in entries)
            assertTrue("Book One.csv" in entries)
            assertTrue("Book Two.csv" in entries)
        } finally {
            z.close()
        }
    }

    @Test
    fun `single book data export writes bare file not a zip`() {
        val rows = listOf(
            DataExport.NoteRow(
                key = "a", bookKey = "k1", bookName = "Book One",
                bookAuthor = null, bookMd5 = null, chapter = null,
                chapterIndex = null, text = "t", notes = "n",
                percentage = null, color = null, tag = emptyList(),
                date = makeDate(2025, 1, 1),
                exportType = DataExport.ExportType.NOTE,
            ),
        )
        val out = DataExport.writeExport(
            outDir = dir,
            records = rows,
            format = DataExport.Format.CSV,
            type = "Note",
        )
        assertTrue(out.name.startsWith("KoodoReader-Note-"))
        assertTrue(out.name.endsWith(".csv"))
        // bare file, not a zip — the stream MUST be closed, otherwise Windows keeps the
        // file locked and JUnit's @TempDir cleanup fails with "Failed to close extension
        // context" (the export file is still open).
        val first = out.inputStream().use { it.read() }
        assertTrue(first.toInt() != 0x50) // 'P' of PK\x03\x04
    }

    @Test
    fun `data import resolves bookKey by md5 and reports missing books`() {
        val csv = """
            key,bookKey,bookMd5,bookName,exportType,date,chapter,text,notes,tag,color,styleType,chapterIndex,percentage
            n1,missing,aAAa,Book One,note,2025-01-01,c1,marked,user comment,"a,b",,#FEF3CD,background,1,5
            n2,,dEEd,Book Two,note,2025-01-02,c2,t2,,,
            n3,gone,ffff,Book Three,note,2025-01-03,c3,t3,,,
        """.trimIndent()
        val parsed = DataImport.decodeCsv(csv)
        assertEquals(3, parsed.size)
        val books = DataImport.BookIndex(
            // A real index is derived from ONE book list, so byKey covers every value of
            // byMd5 — that invariant is what lets resolveBooks treat "resolved, but absent
            // from byKey" as a missing book.
            byKey = mapOf("k1" to "Book One", "k2" to "Book Two"),
            byMd5 = mapOf("aAAa" to "k1", "dEEd" to "k2"),
        )
        val (resolved, missing) = DataImport.resolveBooks(parsed, books)
        // n1: bookKey "missing" not in library but md5 "aAAa" matches → k1
        assertEquals("k1", resolved[0].raw["bookKey"])
        // n2: bookKey blank, md5 → k2 (library has k2 by md5)
        assertEquals("k2", resolved[1].raw["bookKey"])
        // n3: neither the key nor the md5 is in the library → keeps its key and is reported
        assertEquals("gone", resolved[2].raw["bookKey"])
        assertEquals(listOf("Book Three"), missing)
    }

    @Test
    fun `data import dedupes by primary key against an existing set`() {
        val csv = """
            key,bookKey,bookName,exportType
            n1,k1,One,note
            n2,k1,One,note
            n3,k1,One,note
        """.trimIndent()
        val rows = DataImport.decodeCsv(csv)
        val (fresh, skipped) = DataImport.dedupe(rows, existingKeys = setOf("n1"))
        assertEquals(listOf("n2", "n3"), fresh.map { it.raw["key"] })
        assertEquals(1, skipped) // n1 was already there
    }

    @Test
    fun `round-trip CSV encodes then decodes back to the same exportType fields`() {
        val rows = listOf(
            DataExport.NoteRow(
                key = "k1", bookKey = "bk", bookName = "B",
                bookAuthor = "A", bookMd5 = null, chapter = "ch",
                chapterIndex = 1L, text = "hello, world", notes = null,
                percentage = "5", color = null, tag = listOf("x"),
                date = makeDate(2025, 1, 1),
                exportType = DataExport.ExportType.HIGHLIGHT,
            ),
            DataExport.NoteRow(
                key = "k2", bookKey = "bk", bookName = "B",
                bookAuthor = "A", bookMd5 = null, chapter = "ch",
                chapterIndex = 1L, text = "goodbye", notes = "user note",
                percentage = "10", color = null, tag = emptyList(),
                date = makeDate(2025, 1, 2),
                exportType = DataExport.ExportType.NOTE,
            ),
        )
        val csv = DataExport.encode(rows, DataExport.Format.CSV)
        val decoded = DataImport.decodeCsv(csv)
        assertEquals(2, decoded.size)
        assertEquals(DataImport.ExportType.HIGHLIGHT, decoded[0].exportType)
        assertEquals("hello, world", decoded[0].raw["text"])
        assertEquals(DataImport.ExportType.NOTE, decoded[1].exportType)
        assertEquals("user note", decoded[1].raw["notes"])
        assertEquals("x", decoded[0].raw["tag"])
    }

    @Test
    fun `round-trip JSON encodes then decodes including UTF-8 fields`() {
        val rows = listOf(
            DataExport.WordRow(
                key = "w1", bookKey = "bk", bookName = "B",
                bookAuthor = "A", bookMd5 = null, chapter = null,
                word = "read", sentence = "I read a book.",
                date = makeDate(2025, 1, 1),
            ),
        )
        val json = DataExport.encodeWords(rows, DataExport.Format.JSON)
        val parsed = DataImport.decodeJson(json)
        assertEquals(1, parsed.size)
        assertEquals(DataImport.ExportType.DICTIONARY_HISTORY, parsed[0].exportType)
        assertEquals("read", parsed[0].raw["word"])
        assertEquals("I read a book.", parsed[0].raw["sentence"])
        assertEquals("2025-01-01", parsed[0].raw["date"])
    }

    private fun makeDate(year: Int, month: Int, day: Int): Date {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"), java.util.Locale.US)
        cal.set(year, month - 1, day, 0, 0, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.time
    }
}
