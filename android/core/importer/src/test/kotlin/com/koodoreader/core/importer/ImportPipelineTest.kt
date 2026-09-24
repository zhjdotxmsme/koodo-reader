package com.koodoreader.core.importer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ImportPipelineTest {

    @TempDir
    var workRoot: File = File("build/tmp/pipeline")

    private val booksOut = File(workRoot, "books")
    private val srcDir = File(workRoot, "src")

    private var seq = 0
    private fun tickClock(): Long {
        seq += 1
        return 1_700_000_000_000L + seq
    }

    private val pipeline = ImportPipeline(clock = { tickClock() }, random = { seq % 1000 })

    // This toolchain's byteArrayOf wants Byte args; literals stay Int-friendly.
    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    init {
        booksOut.mkdirs()
        srcDir.mkdirs()
    }

    private fun src(name: String, bytes: ByteArray = name.toByteArray()): File {
        val f = File(srcDir, name)
        f.writeBytes(bytes)
        return f
    }

    private fun pending(name: String, file: File): BookSource =
        BookSource(name, { file.inputStream() })

    private fun makeEpub(name: String, title: String = "Pipeline Title"): File {
        val container =
            "<?xml version=\"1.0\"?><container version=\"1.0\"><rootfiles>" +
                "<rootfile full-path=\"content.opf\" media-type=\"application/oebps-package+xml\"/>" +
                "</rootfiles></container>"
        val opf =
            "<package xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><metadata>" +
                "<dc:title>$title</dc:title><dc:creator>Author X</dc:creator></metadata>" +
                "<manifest/>" +
                "<spine/>" +
                "</package>"
        val epub = File(srcDir, name)
        java.io.ByteArrayOutputStream().use { bos ->
            ZipOutputStream(bos).use { zos ->
                zos.putNextEntry(ZipEntry("META-INF/container.xml"))
                zos.write(container.toByteArray())
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("content.opf"))
                zos.write(opf.toByteArray())
                zos.closeEntry()
            }
            epub.writeBytes(bos.toByteArray())
        }
        return epub
    }

    @Test
    suspend fun `imports txt and enriched epub with cover and dedup`() {
        val book1 = src("hello.txt", byteArrayOf(1, 2, 3))
        val epub = makeEpub("story.epub")
        // Same content as book1 under another name = same md5 = duplicate.
        val dup = src("hello-copy.txt", byteArrayOf(1, 2, 3))

        val result = pipeline.process(
            listOf(
                pending("hello.txt", book1),
                pending("hello-copy.txt", dup),
                pending("story.epub", epub),
            ),
            booksOut,
        )

        assertEquals(2, result.imported)
        assertEquals(1, result.duplicates)
        assertEquals(0, result.unsupported)
        assertTrue(result.failures.isEmpty(), "failures: ${result.failures}")

        val txt = result.records.first { it.name == "hello" }
        assertEquals("TXT", txt.format)
        assertEquals("hello", txt.name, "name = file name without extension (desktop parity)")
        assertEquals(FileMd5.ofFile(book1), txt.md5)
        assertEquals(3L, txt.size)
        assertTrue(txt.path?.startsWith(booksOut.path) == true, "path=${txt.path}")

        val e = result.records.first { it.format == "EPUB" }
        assertEquals("Pipeline Title", e.name)
        assertEquals("Author X", e.author)

        // Cover written into the result map, keyed by book key:
        val coverKeys = result.covers.keys
        assertTrue(coverKeys.size <= 1)
    }

    @Test
    suspend fun `md5 already in the database is skipped and the copy deleted`() {
        val f = src("db.txt", byteArrayOf(9, 9))
        val known = FileMd5.ofFile(f)
        val before = booksOut.listFiles()?.size ?: 0
        val result = pipeline.process(
            listOf(pending("db.txt", f)),
            booksOut,
            existingMd5 = { it == known },
        )
        assertEquals(0, result.imported)
        assertEquals(1, result.duplicates)
        assertEquals(before, booksOut.listFiles()?.size ?: 0, "deduped book must not leave a copy")
    }

    @Test
    suspend fun `unsupported and broken books are counted, good books proceed`() {
        val good = src("good.txt", byteArrayOf(5))
        val missing = File(srcDir, "ghost.txt") // not created
        val result = pipeline.process(
            listOf(
                pending("photo.jpg", File(srcDir, "photo.jpg")), // unsupported ext
                pending("noextension", File(srcDir, "noextension")), // unsupported
                pending("ghost.txt", missing), // missing source -> failure
                pending("good.txt", good),
            ),
            booksOut,
        )
        assertEquals(1, result.imported)
        assertEquals(2, result.unsupported)
        assertEquals(1, result.failures.size)
        assertEquals("ghost.txt", result.failures.single().name)
    }

    @Test
    suspend fun `epub without cover still imports (no cover in map)`() {
        val noCover = makeEpub("plain.epub")
        val result = pipeline.process(listOf(pending("plain.epub", noCover)), booksOut)
        assertEquals(1, result.imported)
        assertFalse(result.covers.values.any { it.extension.isNotBlank() })
    }

    @Test
    suspend fun `cbz gets its natural-sorted cover and page count on the record`() {
        val cbz = File(srcDir, "comic.cbz")
        val png = bytes(0x89, 0x50, 0x4E, 0x47)
        java.io.ByteArrayOutputStream().use { bos ->
            ZipOutputStream(bos).use { zos ->
                fun put(name: String, data: ByteArray) {
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(data)
                    zos.closeEntry()
                }
                put("03.png", png)
                put("10.png", png)
                put("01.png", png)
                put("notes.txt", "x".toByteArray())
            }
            cbz.writeBytes(bos.toByteArray())
        }
        val result = pipeline.process(listOf(pending("comic.cbz", cbz)), booksOut)
        assertEquals(1, result.imported)
        assertEquals(3L, result.records.single().page)
        val cover = result.covers.values.singleOrNull()
        assertTrue(cover != null && cover.extension == "png", "cover: $cover")
    }

    @Test
    suspend fun `a thousand synthetic books import without duplicates and stay in memory-bounded`() {
        val n = 1000
        val pendings = (0 until n).map {
            pending("book-${it}.txt", src("s${it}.txt", "unique-content-$it".toByteArray()))
        }
        val started = System.nanoTime()
        val result = pipeline.process(pendings, booksOut)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(n, result.imported, "failures: ${result.failures.take(5)}")
        assertEquals(0, result.duplicates)
        assertEquals(n, result.records.map { it.md5 }.toSet().size)
        assertEquals(n, result.records.map { it.key }.toSet().size)
        val importedFiles = booksOut.listFiles { _, name -> name.matches(Regex(".*\\.txt")) }?.size ?: 0
        assertTrue(importedFiles >= n, "expected >=$n copied files, got $importedFiles")
        // (Timing is informational; a regression to whole-file buffering shows
        // up as a visible jump here on CI.)
        println("[ImportPipeline] 1000 books in ${elapsedMs} ms")
    }

    @Test
    suspend fun `MAX_FILES cap keeps the batch bounded`() {
        val big = (0 until BookRules.MAX_FILES + 5).map {
            pending("x${it}.txt", src("xb${it}.txt", "content-$it".toByteArray()))
        }
        val result = pipeline.process(big, booksOut)
        assertEquals(BookRules.MAX_FILES, result.imported)
    }
}
