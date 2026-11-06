package com.koodoreader.reader.shell

import com.koodoreader.core.data.entity.BookEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class BookFileMoverTest {

    @TempDir
    lateinit var temp: File

    private fun book(key: String, file: File) = BookEntity(
        key = key,
        name = key,
        format = "epub",
        path = file.path,
    )

    @Test
    fun `move updates only private path and preserves book identity`() {
        val old = File(temp, "k.epub").apply { writeText("book-bytes") }
        val targetDir = File(temp, "moved")
        val entity = book("k", old)

        val moved = BookFileMover.move(entity, targetDir)

        assertEquals("k", moved.key)
        assertEquals(targetDir.resolve("k.epub").path, moved.path)
        assertEquals("book-bytes", targetDir.resolve("k.epub").readText())
        assertFalse(old.exists())
    }

    @Test
    fun `move refuses to overwrite an existing target`() {
        val old = File(temp, "k.epub").apply { writeText("book-bytes") }
        val targetDir = File(temp, "moved").apply { mkdirs() }
        targetDir.resolve("k.epub").writeText("other")

        assertThrows(IllegalArgumentException::class.java) {
            BookFileMover.move(book("k", old), targetDir)
        }
        assertTrue(old.exists())
        assertEquals("other", targetDir.resolve("k.epub").readText())
    }

    @Test
    fun `failed room update rolls the file back`() {
        val old = File(temp, "k.epub").apply { writeText("book-bytes") }
        val targetDir = File(temp, "moved")

        assertThrows(IllegalStateException::class.java) {
            BookFileMover.moveAndUpdate(book("k", old), targetDir) {
                throw IllegalStateException("room write failed")
            }
        }
        assertTrue(old.exists())
        assertEquals("book-bytes", old.readText())
        assertFalse(targetDir.resolve("k.epub").exists())
    }

    @Test
    fun `renameFile updates path when title changes`() {
        val booksDir = File(temp, "books").apply { mkdirs() }
        val old = File(booksDir, "k.epub").apply { writeText("book-bytes") }
        val entity = book("k", old)
        val renamed = BookFileMover.renameFile(entity, "New Title", booksDir)
        assertEquals(File(booksDir, "New Title.epub").path, renamed.path)
        assertFalse(old.exists())
        assertEquals("book-bytes", File(renamed.path!!).readText())
    }

    @Test
    fun `saf content uris are rejected without touching files`() {
        val entity = BookEntity(key = "k", format = "epub", path = "content://books/1")
        assertThrows(IllegalArgumentException::class.java) {
            BookFileMover.move(entity, File(temp, "moved"))
        }
    }

    @Test
    fun `books outside the private dir are rejected when a root is given`() {
        val booksDir = File(temp, "books").apply { mkdirs() }
        val outside = File(temp, "outside.epub").apply { writeText("x") }

        assertThrows(IllegalArgumentException::class.java) {
            BookFileMover.move(book("k", outside), File(temp, "moved"), booksDir)
        }
        assertTrue(outside.exists())
    }
}
