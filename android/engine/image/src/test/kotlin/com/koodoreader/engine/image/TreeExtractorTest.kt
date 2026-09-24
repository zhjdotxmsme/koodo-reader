package com.koodoreader.engine.image

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** 散图目录（SAF tree / 文件夹版漫画）。 */
class TreeExtractorTest {

    @TempDir
    var tempDir: File = File("build/tmp/image-test")

    private fun file(relative: String, bytes: ByteArray): File {
        val f = File(tempDir, relative)
        f.parentFile.mkdirs()
        f.writeBytes(bytes)
        return f
    }

    @Test
    fun `directory pages are relative path natural sorted`() {
        val root = File(tempDir, "comic")
        root.mkdirs()
        file("comic/ch2/p10.jpg", Pics.jpeg(10, 10))
        file("comic/ch1/p1.png", Pics.png(1, 1))
        file("comic/ch1/p2.png", Pics.png(2, 2))
        file("comic/notes.txt", "skip".toByteArray())
        file("comic/.hidden/p0.jpg", Pics.jpeg(1, 1))
        file("comic/ch1/._p3.png", Pics.png(3, 3))

        TreeExtractor.open(root).use { extractor ->
            assertEquals(ArchiveKind.DIRECTORY, extractor.kind)
            assertEquals(
                listOf("ch1/p1.png", "ch1/p2.png", "ch2/p10.jpg"),
                extractor.entries.map { it.name },
                "隐藏目录 / macOS 资源分支 / 非图片都不算页",
            )
            assertArrayEquals(Pics.png(1, 1), extractor.readPage(0))
            assertArrayEquals(Pics.jpeg(10, 10), extractor.readPage(2))
            assertEquals(
                Pics.png(1, 1).size + Pics.png(2, 2).size + Pics.jpeg(10, 10).size.toLong(),
                extractor.fileSizeBytes,
            )
        }
    }

    @Test
    fun `a folder without images lists zero pages`() {
        val root = File(tempDir, "empty-comic")
        root.mkdirs()
        file("empty-comic/readme.md", "hi".toByteArray())

        TreeExtractor.open(root).use { extractor ->
            assertEquals(0, extractor.pageCount)
        }
    }

    @Test
    fun `openDirectory routes to the tree extractor`() {
        val root = File(tempDir, "routed")
        root.mkdirs()
        file("routed/1.jpg", Pics.jpeg(2, 2))

        ArchiveExtractors.openDirectory(root).use { extractor ->
            assertEquals(ArchiveKind.DIRECTORY, extractor.kind)
            assertEquals(1, extractor.pageCount)
            assertTrue(ArchiveExtractors.isNativelyReadable(root))
        }
    }

    @Test
    fun `page extensions rule matches the importer whitelist`() {
        // 与 P1 的 IMAGE_EXTS 同源：这里只锁定几个容易写错的边界
        assertTrue(ImageEntries.isImage("a/001.JPG"))
        assertTrue(ImageEntries.isImage("001.jfif"))
        assertEquals("jpeg", ImageEntries.extOf("001.jfif"))
        assertEquals("jpeg", ImageEntries.extOf("a/001.pjpeg"))
        assertFalse(ImageEntries.isImage("a/001.txt"))
        assertFalse(ImageEntries.isImage("a/001.jpg/"))
        assertFalse(ImageEntries.isImage("a/"))
        assertEquals(ImageEntries.EXTS, com.koodoreader.core.importer.ComicCover.IMAGE_EXTS)
    }
}
