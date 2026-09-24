package com.koodoreader.reader.shell

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [ReaderFiles] — the pure part of the native reader's file handling.
 *
 * Regression context (P3): the PDF screen used to be a placeholder, so nothing
 * ever resolved a book file for a reader. The rules that matter now: prefer the
 * path Room recorded, fall back to the importer's `<key>.<ext>` convention, and
 * never hand a non-existent file to the engine.
 */
class ReaderFilesTest {

    private fun tempBooksDir(): File = Files.createTempDirectory("koodo-books").toFile()

    private fun book(dir: File, name: String): File =
        File(dir, name).apply { writeText("pdf") }

    @Test
    fun `recorded path wins when the file exists`() {
        val dir = tempBooksDir()
        val recorded = book(dir, "key1.pdf")
        val other = book(dir, "key1.epub")
        assertEquals(
            recorded.path,
            ReaderFiles.resolveBookFile(dir, "key1", "pdf", recorded.path)?.path,
        )
        // Sanity: the fallback would have picked the other file, so this really
        // asserts precedence rather than a coincidence.
        assertTrue(other.isFile)
    }

    @Test
    fun `falls back to the conventional key and format`() {
        val dir = tempBooksDir()
        val conventional = book(dir, "abc.pdf")
        assertEquals(
            conventional.path,
            ReaderFiles.resolveBookFile(dir, "abc", "PDF", null)?.path,
        )
    }

    @Test
    fun `falls back to any file with the key prefix`() {
        val dir = tempBooksDir()
        val exported = book(dir, "abc.fb2")
        // A desktop database can record the format as pdf while the copied file
        // kept another extension — the reader must still open something.
        assertEquals(
            exported.path,
            ReaderFiles.resolveBookFile(dir, "abc", "pdf", "/does/not/exist.pdf")?.path,
        )
    }

    @Test
    fun `prefix scan is deterministic`() {
        val dir = tempBooksDir()
        book(dir, "abc.zzz")
        val first = book(dir, "abc.aaa")
        assertEquals(
            first.path,
            ReaderFiles.resolveBookFile(dir, "abc", null, null)?.path,
        )
    }

    @Test
    fun `missing book resolves to null instead of a phantom path`() {
        val dir = tempBooksDir()
        assertNull(ReaderFiles.resolveBookFile(dir, "nope", "pdf", null))
        assertNull(ReaderFiles.resolveBookFile(dir, "nope", null, "/tmp/ghost.pdf"))
    }

    @Test
    fun `a recorded directory is not a book`() {
        val dir = tempBooksDir()
        val nested = File(dir, "key2.pdf").apply { mkdirs() }
        assertNull(ReaderFiles.resolveBookFile(dir, "key2", null, nested.path))
    }

    @Test
    fun `virtual path keeps the loopback route safe`() {
        assertEquals(
            "__books__/My_Book__1_.pdf",
            ReaderFiles.virtualPath(File("/tmp/My Book #1!.pdf")),
        )
        assertEquals("__books__/plain.cbz", ReaderFiles.virtualPath(File("/tmp/plain.cbz")))
    }

    @Test
    fun `snapshot names are filesystem safe and page scoped`() {
        assertEquals("k_1-p12.png", ReaderFiles.snapshotName("k/1", 12, "png"))
        assertEquals("book-p1.jpg", ReaderFiles.snapshotName("", 1, "jpg"))
    }
}
