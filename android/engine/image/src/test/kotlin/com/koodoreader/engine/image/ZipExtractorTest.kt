package com.koodoreader.engine.image

import com.koodoreader.core.importer.ComicCover
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * CBZ 路径 + **与 P1 `core:importer ComicCover` 的一致性**（衔接依据）。
 *
 * 关键断言：同一个 CBZ 上，`ComicCover.extract()` 抽出的封面字节必须等于
 * 本模块 `pages[0]` 的字节、页数必须相等 —— 这样「书架上那张封面」与
 * 「阅读器里翻到的第 1 页」不可能不是同一张图。
 */
class ZipExtractorTest {

    @TempDir
    var tempDir: File = File("build/tmp/image-test")

    private fun cbz(name: String, entries: List<Pair<String, ByteArray>>): File {
        val file = File(tempDir, name)
        writeZip(file, entries)
        return file
    }

    @Test
    fun `pages are the natural sorted image entries`() {
        val svg = "<svg width=\"100\" height=\"200\"/>".toByteArray()
        val file = cbz(
            "natural.cbz",
            listOf(
                "notes.txt" to "not a page".toByteArray(),
                "chapters/" to ByteArray(0),
                "page10.jpg" to Pics.jpeg(10, 10),
                "page2.jpg" to Pics.jpeg(2, 2),
                "page01.png" to Pics.png(1, 1),
                "bonus.svg" to svg,
            ),
        )

        ZipExtractor.open(file).use { extractor ->
            assertEquals(ArchiveKind.ZIP, extractor.kind)
            assertEquals("natural.cbz", extractor.name)
            assertEquals(4, extractor.pageCount, "txt 与目录条目不是页，svg 在清单内")
            assertEquals(
                listOf("bonus.svg", "page01.png", "page2.jpg", "page10.jpg"),
                extractor.entries.map { it.name },
            )
            assertEquals(listOf(0, 1, 2, 3), extractor.entries.map { it.index })
            assertEquals(listOf("svg", "png", "jpeg", "jpeg"), extractor.entries.map { it.ext })
            assertArrayEquals(Pics.png(1, 1), extractor.readPage(1))
            assertEquals(svg.size.toLong(), extractor.entry(0).sizeBytes)
        }
    }

    @Test
    fun `page zero is exactly the cover the P1 importer extracts`() {
        val file = cbz(
            "parity.cbz",
            listOf(
                "notes.txt" to "x".toByteArray(),
                "10.jpg" to Pics.jpeg(10, 10),
                "2.jpg" to Pics.jpeg(2, 2),
                "01.jpg" to Pics.jpeg(1, 1),
            ),
        )

        val cover = ComicCover.extract(file)
        assertNotNull(cover, "P1 的封面抽取必须能读同一个 CBZ")

        ZipExtractor.open(file).use { extractor ->
            assertEquals(cover!!.pageCount, extractor.pageCount, "页数必须与 books.page 一致")
            assertEquals("jpeg", cover.cover?.extension)
            assertEquals("01.jpg", extractor.entries[0].name, "natural 序首图")
            assertArrayEquals(cover.cover!!.bytes, extractor.readPage(0), "封面字节 == 第 0 页字节")
        }
    }

    @Test
    fun `openPage streams the same bytes as readPage`() {
        val file = cbz("stream.cbz", listOf("1.jpg" to Pics.jpeg(4, 3), "2.jpg" to Pics.jpeg(5, 6)))

        ZipExtractor.open(file).use { extractor ->
            extractor.openPage(1).use { stream ->
                assertArrayEquals(extractor.readPage(1), stream.readBytes())
            }
        }
    }

    @Test
    fun `page indexes outside the archive are rejected`() {
        val file = cbz("small.cbz", listOf("1.jpg" to Pics.jpeg(1, 1)))

        ZipExtractor.open(file).use { extractor ->
            assertThrows(IndexOutOfBoundsException::class.java) { extractor.readPage(5) }
        }
    }

    @Test
    fun `an archive without images lists zero pages`() {
        val file = cbz("text-only.cbz", listOf("readme.txt" to "hi".toByteArray()))

        ZipExtractor.open(file).use { extractor ->
            assertEquals(0, extractor.pageCount)
            assertThrows(IndexOutOfBoundsException::class.java) { extractor.readPage(0) }
        }
    }
}
