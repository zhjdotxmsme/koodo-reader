package com.koodoreader.core.importer

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

class AtomicImportTest {

    @TempDir
    lateinit var workRoot: File

    private val booksDir get() = File(workRoot, "books")

    @Test
    fun `failed copy removes partial file`() {
        val dir = booksDir.apply { mkdirs() }
        val broken = object : InputStream() {
            private var calls = 0
            override fun read(): Int = if (calls++ == 0) 1 else throw IOException("disk full")
        }

        assertThrows(IOException::class.java) { AtomicBookStore(dir).copy(broken, "1.txt") }
        assertEquals(emptyList<String>(), dir.listFiles()?.map { it.name }.orEmpty())
    }

    @Test
    fun `copy commits once and calculates md5 while streaming`() {
        val content = ByteArray(200_000) { (it % 251).toByte() }
        var largestRead = 0
        val input = object : ByteArrayInputStream(content) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                largestRead = maxOf(largestRead, length)
                return super.read(buffer, offset, length)
            }
        }

        val stored = AtomicBookStore(booksDir).copy(input, "book.txt")

        assertEquals(content.toList(), stored.file.readBytes().toList())
        assertEquals(md5(content), stored.md5)
        assertTrue(largestRead <= 64 * 1024)
        assertFalse(File(booksDir, "book.txt.part").exists())
    }

    @Test
    fun `duplicate returns existing edited record without replacing it`() = runBlocking {
        val original = "same bytes".toByteArray()
        val existingFile = File(booksDir, "existing.txt").apply {
            parentFile.mkdirs()
            writeBytes(original)
        }
        val existing = BookRecord(
            key = "existing-key",
            name = "Edited title",
            author = "Edited author",
            description = "Edited description",
            md5 = md5(original),
            format = "TXT",
            publisher = null,
            size = original.size.toLong(),
            page = null,
            path = existingFile.path,
        )
        var queries = 0

        val outcome = ImportPipeline(clock = { 1_700_000_000_000L }, random = { 1 }).importBook(
            BookSource("duplicate.txt", { original.inputStream() }, original.size.toLong()),
            booksDir,
            existingBook = { md5 ->
                queries++
                existing.takeIf { it.md5 == md5 }
            },
        )

        val success = assertInstanceOf(ImportOutcome.Success::class.java, outcome)
        assertTrue(success.duplicate)
        assertEquals(existing, success.record)
        assertEquals("Edited title", success.record.name)
        assertEquals(original.toList(), existingFile.readBytes().toList())
        assertEquals(1, queries)
        assertEquals(listOf("existing.txt"), booksDir.listFiles()?.map { it.name }?.sorted())
    }

    @Test
    fun `unsupported and unknown formats never copy or query room`() = runBlocking {
        var streamsOpened = 0
        var queries = 0
        val pipeline = ImportPipeline()

        val unsupported = pipeline.importBook(
            BookSource("comic.cbr", {
                streamsOpened++
                "rar".byteInputStream()
            }),
            booksDir,
            existingBook = { queries++; null },
        )
        val unknown = pipeline.importBook(
            BookSource("blob.bin", {
                streamsOpened++
                ByteArrayInputStream(ByteArray(256) { 1 })
            }),
            booksDir,
            existingBook = { queries++; null },
        )

        assertInstanceOf(ImportOutcome.Failure::class.java, unsupported)
        assertInstanceOf(ImportOutcome.Failure::class.java, unknown)
        assertEquals(0, queries)
        assertEquals(emptyList<String>(), booksDir.listFiles()?.map { it.name }.orEmpty())
    }

    @Test
    fun `known size larger than free space fails before copying`() = runBlocking {
        var streamsOpened = 0
        val source = BookSource(
            name = "huge.txt",
            openStream = {
                streamsOpened++
                "not copied".byteInputStream()
            },
            size = Long.MAX_VALUE,
        )

        val outcome = ImportPipeline().importBook(source, booksDir)

        val failure = assertInstanceOf(ImportOutcome.Failure::class.java, outcome)
        assertInstanceOf(ImportFailure.InsufficientSpace::class.java, failure.reason)
        assertEquals(0, streamsOpened)
        assertEquals(emptyList<String>(), booksDir.listFiles()?.map { it.name }.orEmpty())
    }

    @Test
    fun `startup reconciliation removes parts and orphans but keeps owned books`() {
        booksDir.mkdirs()
        val owned = File(booksDir, "owned.epub").apply { writeText("owned") }
        File(booksDir, "interrupted.txt.part").writeText("partial")
        File(booksDir, "orphan.pdf").writeText("orphan")
        File(booksDir, "nested").mkdirs()

        AtomicBookStore(booksDir).reconcile(listOf(owned.path))

        assertTrue(owned.isFile)
        assertEquals(listOf("nested", "owned.epub"), booksDir.listFiles()?.map { it.name }?.sorted())
    }

    private fun md5(content: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(content).joinToString("") { "%02x".format(it) }
}
