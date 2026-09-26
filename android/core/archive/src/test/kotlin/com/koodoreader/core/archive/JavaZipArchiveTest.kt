package com.koodoreader.core.archive

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

class JavaZipArchiveTest {

    @TempDir
    lateinit var dir: File

    // ------------------------------------------------------------ open

    @Test
    fun `opens a valid zip and lists all entries in order`() {
        val archive = ZipArchives.open(TestZips.sample(File(dir, "book.epub")))
        archive.use {
            assertEquals(
                listOf(
                    "META-INF/container.xml",
                    "OEBPS/content.opf",
                    "OEBPS/images/cover.png",
                    "Images/Page1.PNG",
                    "chapter1.txt",
                ),
                it.entries.map { e -> e.name },
            )
            assertFalse(it.entries.any { e -> e.isDirectory })
        }
    }

    @Test
    fun `reports entry sizes`() {
        ZipArchives.open(TestZips.sample(File(dir, "a.zip"))).use {
            assertEquals("hello world".length.toLong(), it.entry("chapter1.txt")?.sizeBytes)
        }
    }

    @Test
    fun `missing or non-zip file raises typed open exception`() {
        assertThrows<ArchiveOpenException> { ZipArchives.open(File(dir, "absent.zip")) }
        val text = File(dir, "text.zip").apply { writeText("definitely not a zip") }
        val e = assertThrows<ArchiveOpenException> { ZipArchives.open(text) }
        assertTrue(e.message!!.contains(text.path))
    }

    @Test
    fun `truncated zip raises typed open exception`() {
        val good = TestZips.sample(File(dir, "good.zip"))
        val bytes = good.readBytes()
        val truncated = File(dir, "truncated.zip")
        truncated.writeBytes(bytes.copyOf(bytes.size / 2))
        assertThrows<ArchiveOpenException> { ZipArchives.open(truncated) }
    }

    @Test
    fun `openOrNull returns null instead of throwing`() {
        val text = File(dir, "text2.zip").apply { writeText("nope") }
        assertNull(ZipArchives.openOrNull(text))
    }

    // --------------------------------------------------------- lookup

    @Test
    fun `entry resolves exact name first`() {
        ZipArchives.open(TestZips.sample(File(dir, "b.zip"))).use {
            assertEquals("Images/Page1.PNG", it.entry("Images/Page1.PNG")?.name)
        }
    }

    @Test
    fun `entry falls back to case-insensitive match`() {
        ZipArchives.open(TestZips.sample(File(dir, "c.zip"))).use {
            // The archive only contains "Images/Page1.PNG" (capital I).
            assertEquals("Images/Page1.PNG", it.entry("images/page1.png")?.name)
            assertEquals("Images/Page1.PNG", it.entry("IMAGES/PAGE1.png")?.name)
        }
    }

    @Test
    fun `entry returns null for absent names`() {
        ZipArchives.open(TestZips.sample(File(dir, "d.zip"))).use {
            assertNull(it.entry("nope.txt"))
            assertNull(it.entry("OEBPS/content.opfx"))
        }
    }

    // --------------------------------------------------------- stream

    @Test
    fun `readBytes streams entry content`() {
        ZipArchives.open(TestZips.sample(File(dir, "e.zip"))).use {
            assertEquals("hello world", it.readBytes("chapter1.txt").decodeToString())
        }
    }

    @Test
    fun `readBytes honours case-insensitive fallback`() {
        ZipArchives.open(TestZips.sample(File(dir, "f.zip"))).use {
            assertEquals("page-one", it.readBytes("images/page1.png").decodeToString())
        }
    }

    @Test
    fun `openStream of absent entry throws typed error`() {
        ZipArchives.open(TestZips.sample(File(dir, "g.zip"))).use {
            assertThrows<ArchiveEntryNotFoundException> { it.openStream("nope.txt") }
        }
    }

    @Test
    fun `copyTo streams without materialising the entry`() {
        ZipArchives.open(TestZips.sample(File(dir, "h.zip"))).use {
            val out = ByteArrayOutputStream()
            it.copyTo("chapter1.txt", out)
            assertEquals("hello world", out.toString("UTF-8"))
        }
    }

    // ----------------------------------------------------- zip magic

    @Test
    fun `isZip detects magic only`() {
        val good = TestZips.sample(File(dir, "magic.zip"))
        val bad = File(dir, "plain.txt").apply { writeText("plain text") }
        val empty = File(dir, "empty.zip").apply { createNewFile() }
        assertTrue(ZipArchives.isZip(good))
        assertFalse(ZipArchives.isZip(bad))
        assertFalse(ZipArchives.isZip(empty))
        assertFalse(ZipArchives.isZip(File(dir, "ghost.zip")))
    }

    @Test
    fun `moderately large entry streams end to end`() {
        // ~2 MB of zeros: proves the stream path (no accidental full-entry
        // buffering contract) without paying for a real decompression bomb.
        val payload = ByteArray(2 * 1024 * 1024)
        val zip = TestZips.write(File(dir, "big.zip"), mapOf("big.bin" to payload))
        ZipArchives.open(zip).use {
            var total = 0L
            it.openStream("big.bin").use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                }
            }
            assertEquals(payload.size.toLong(), total)
        }
    }
}

