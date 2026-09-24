package com.koodoreader.core.importer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ComicCoverTest {

    // This toolchain's byteArrayOf wants Byte args; literals stay Int-friendly.
    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    @TempDir
    var tempDir: File = File("build/tmp/comic")

    private fun writeZip(file: File, entries: List<Pair<String, ByteArray>>) {
        ByteArrayOutputStream().use { bos ->
            ZipOutputStream(bos).use { zos ->
                for ((name, bytes) in entries) {
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(bytes)
                    zos.closeEntry()
                }
            }
            file.writeBytes(bos.toByteArray())
        }
    }

    @Test
    fun `cover is the FIRST image under natural (numeric) sort`() {
        val cbz = File(tempDir, "c.cbz")
        val jpg = bytes(0xFF, 0xD8, 0xFF, 0xE0)
        val png = bytes(0x89, 0x50, 0x4E, 0x47)
        writeZip(
            cbz,
            listOf(
                "notes.txt" to "not a page".toByteArray(),
                "chapters/" to ByteArray(0),
                "2.jpg" to jpg,
                "10.png" to png,
                "01.jpg" to jpg,
            ),
        )
        val result = ComicCover.extract(cbz)
        assertNotNull(result)
        assertEquals(3, result?.pageCount, "txt/dir filtered, 3 images kept")
        assertEquals("jpeg", result?.cover?.extension, "first natural-sorted image is 01.jpg -> jpeg")
    }

    @Test
    fun `jpegy extensions normalize to jpeg`() {
        val cbz = File(tempDir, "e.cbz")
        val gif = byteArrayOf(0x47, 0x49, 0x46, 0x38)
        writeZip(cbz, listOf("001.jfif" to gif, "002.png" to gif))
        assertEquals("jpeg", ComicCover.extract(cbz)?.cover?.extension)
    }

    @Test
    fun `archive without images yields null cover and zero pages`() {
        val cbz = File(tempDir, "empty.cbz")
        writeZip(cbz, listOf("readme.txt" to "hi".toByteArray()))
        val result = ComicCover.extract(cbz)
        assertNull(result?.cover)
        assertEquals(0, result?.pageCount)
    }

    @Test
    fun `a non-zip file degrades to null instead of throwing to the caller`() {
        val bad = File(tempDir, "bad.cbz")
        bad.writeText("not a zip")
        assertNull(ComicCover.extract(bad))
    }

    @Test
    fun `naturalComparator orders numeric runs like JS numeric localeCompare`() {
        val c = ComicCover.naturalComparator()
        assertOrder(c, "a2", "a10")
        assertOrder(c, "01.jpg", "2.jpg", "10.png")
        assertOrder(c, "a1b", "ab", "acb")
        assertOrder(c, "b2", "b10")
        // Leading-zero runs: ICU numeric collation places "07" before "7"
        // (numeric tie, then '0' < '7') — we match that order.
        assertOrder(c, "07", "7")
    }

    private fun assertOrder(c: Comparator<String>, vararg names: String) {
        val sorted = names.sortedWith(c)
        assertEquals(names.toList(), sorted, "$names should already be sorted")
    }
}
