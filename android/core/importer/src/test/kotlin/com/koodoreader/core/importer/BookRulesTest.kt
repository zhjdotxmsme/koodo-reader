package com.koodoreader.core.importer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Kotlin mirror of `src/utils/android/folderBridge.test.js` (17 Jest cases)
 * plus the desktop key/name/format generation rules. Keep both suites in
 * lock-step — `scripts/check-import-rules.js` fails CI on constant drift.
 */
class BookRulesTest {

    // ---- constants -----------------------------------------------------

    @Test
    fun `exposes protocol constants`() {
        assertEquals("folder-picked", BookRules.FOLDER_EVENT)
        assertEquals(1000, BookRules.MAX_FILES)
        assertEquals(2, BookRules.FOLDER_DEPTH)
    }

    @Test
    fun `keeps the book-extension whitelist and MIME map in sync`() {
        assertTrue(BookRules.BOOK_EXTENSIONS.size > 10)
        for (ext in BookRules.BOOK_EXTENSIONS) {
            assertTrue(ext.isNotEmpty())
            assertTrue(
                BookRules.MIME_BY_EXT[ext].isNullOrEmpty().not(),
                "missing MIME for .$ext",
            )
        }
        assertEquals(BookRules.BOOK_EXTENSIONS.size, BookRules.MIME_BY_EXT.size)
    }

    // ---- getExt --------------------------------------------------------

    @Test
    fun `getExt returns the lower-cased last extension`() {
        assertEquals("epub", BookRules.getExt("notes.EPUB"))
        assertEquals("epub", BookRules.getExt("archive.tar.epub"))
        assertEquals("txt", BookRules.getExt("a.b.c.txt"))
    }

    @Test
    fun `getExt returns empty when there is no extension`() {
        assertEquals("", BookRules.getExt("readme"))
        assertEquals("", BookRules.getExt(".hidden"))
        assertEquals("", BookRules.getExt("trailing."))
        assertEquals("", BookRules.getExt(null))
    }

    // ---- isBookName ----------------------------------------------------

    @Test
    fun `isBookName accepts supported book files case-insensitively`() {
        val names = listOf(
            "book.epub", "book.pdf", "book.MOBI", "book.AZW3", "story.azw",
            "notes.txt", "book.fb2", "comic.cbz", "comic.cbr", "comic.CBT",
            "comic.cb7", "doc.md", "doc.docx", "page.html", "page.htm",
            "page.xhtml", "page.mhtml",
        )
        for (name in names) {
            assertTrue(BookRules.isBookName(name), "should accept $name")
        }
    }

    @Test
    fun `xml is part of the desktop import list`() {
        assertTrue(BookRules.isBookName("page.xml"))
        assertEquals("application/xml", BookRules.mimeForExt("x.XML"))
        assertTrue("xhtml" in BookRules.BOOK_EXTENSIONS)
        // folderBridge (WebView track) is the 17-format subset without xml.
        assertEquals(18, BookRules.BOOK_EXTENSIONS.size)
    }

    @Test
    fun `isBookName rejects non-book names`() {
        val names = listOf(
            "IMG_0001.jpg", "readme", ".hidden", "trailing.",
            "data.json", "app.exe", null,
        )
        for (name in names) {
            assertFalse(BookRules.isBookName(name), "should reject $name")
        }
    }

    // ---- mimeForExt ----------------------------------------------------

    @Test
    fun `mimeForExt maps known extensions to MIME types`() {
        assertEquals("application/epub+zip", BookRules.mimeForExt("x.epub"))
        assertEquals("application/x-cb7", BookRules.mimeForExt("x.cb7"))
        assertEquals("text/plain", BookRules.mimeForExt("x.TXT"))
    }

    @Test
    fun `mimeForExt returns empty for unknown names`() {
        assertEquals("", BookRules.mimeForExt("x.jpg"))
        assertEquals("", BookRules.mimeForExt("noext"))
        assertEquals("", BookRules.mimeForExt(null))
    }

    // ---- filterBooks (normalizeEntry + filtering + cap) ----------------

    @Test
    fun `filterBooks normalizes a raw entry`() {
        val out = filterBooks(
            listOf(RawFolderEntry("book.EPUB", "content://doc/1", 1234, "application/octet-stream")),
        )
        assertEquals(
            listOf(FolderBookEntry("book.EPUB", "content://doc/1", 1234, "application/epub+zip")),
            out,
        )
    }

    @Test
    fun `filterBooks defaults missing size-uri and falls back to given mime`() {
        val out = filterBooks(listOf(RawFolderEntry("notes.md", size = null, mime = "text/x-md")))
        // Canonical MIME by extension wins over the raw entry's mime (JS
        // normalizeEntry rule: MIME_BY_EXT[ext] || raw.mime || "").
        assertEquals(listOf(FolderBookEntry("notes.md", "", null, "text/markdown")), out)

        // Entries without size/uri keep defaults and still pass the whitelist.
        val out2 = filterBooks(listOf(RawFolderEntry("page.xhtml", mime = "application/x-xyz")))
        assertEquals(listOf(FolderBookEntry("page.xhtml", "", null, "application/xhtml+xml")), out2)
    }

    @Test
    fun `filterBooks drops invalid entries`() {
        val out = filterBooks(listOf(null, RawFolderEntry(null), RawFolderEntry("")))
        assertTrue(out.isEmpty())
    }

    @Test
    fun `filterBooks keeps only supported books in order`() {
        val mixed = listOf(
            RawFolderEntry("cover.jpg", "content://a/1", 10),
            RawFolderEntry("book.AZw3", "content://a/2", 20),
            RawFolderEntry("a.pdf", "content://a/3"),
            null,
            RawFolderEntry("", "content://a/4"),
            RawFolderEntry("comic.cbz", "content://a/5", 30, "application/octet-stream"),
        )
        val out = filterBooks(mixed)
        assertEquals(listOf("book.AZw3", "a.pdf", "comic.cbz"), out.map { it.name })
        assertTrue(out.all { it.mime.isNotEmpty() })
    }

    @Test
    fun `filterBooks truncates at MAX_FILES`() {
        val big = (0 until BookRules.MAX_FILES + 10).map {
            RawFolderEntry("b$it.txt", "content://x/$it")
        }
        assertEquals(BookRules.MAX_FILES, filterBooks(big).size)
    }

    @Test
    fun `filterBooks throws on a null list`() {
        assertThrows(IllegalArgumentException::class.java) { filterBooks(null) }
    }

    // ---- desktop row generation (importLocal/component.tsx) ------------

    @Test
    fun `buildBookKey concatenates timestamp and random suffix`() {
        assertEquals("171234567890142", BookRules.buildBookKey(1712345678901, 42))
        assertEquals("10007", BookRules.buildBookKey(1000, 7))
        // Pure string concatenation, same as `getTime() + "" + Math.floor(random)`.
        assertEquals("00", BookRules.buildBookKey(0, 0))
        // Generated keys are all digits, like `getTime() + "" + random`.
        repeat(50) {
            assertTrue(BookRules.generateBookKey().matches(Regex("\\d{13,16}")))
        }
    }

    @Test
    fun `bookNameFromFile strips the last extension like desktop`() {
        assertEquals("My Book", BookRules.bookNameFromFile("My Book.epub"))
        assertEquals("archive.tar", BookRules.bookNameFromFile("archive.tar.epub"))
        assertEquals("readme", BookRules.bookNameFromFile("readme"))
        // A dotfile has no extension (getExt treats a leading dot as no ext),
        // so the name is returned unchanged — desktop never reaches these.
        assertEquals(".hidden", BookRules.bookNameFromFile(".hidden"))
    }

    @Test
    fun `formatFromFile upper-cases the extension like desktop`() {
        assertEquals("EPUB", BookRules.formatFromFile("book.epub"))
        assertEquals("PDF", BookRules.formatFromFile("book.PDF"))
        assertEquals("", BookRules.formatFromFile("readme"))
    }
}
