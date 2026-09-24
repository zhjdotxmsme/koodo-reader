package com.koodoreader.engine.image

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** CBT 路径：ustar 随机访问、`.tar.gz` 内存解压与体积护栏、GNU long name。 */
class TarExtractorTest {

    @TempDir
    var tempDir: File = File("build/tmp/image-test")

    private val pages = listOf(
        "ch2/p10.jpg" to Pics.jpeg(10, 10),
        "ch1/p1.png" to Pics.png(1, 1),
        "ch1/p2.png" to Pics.png(2, 2),
        "readme.txt" to "skip me".toByteArray(),
    )

    @Test
    fun `tar pages are natural sorted and readable`() {
        val file = File(tempDir, "book.cbt")
        TarWriter.writeTar(file, pages)

        TarExtractor.open(file).use { extractor ->
            assertEquals(ArchiveKind.TAR, extractor.kind)
            assertFalse(extractor.materialisedInMemory, "未压缩 tar 不整体读进内存")
            assertEquals(3, extractor.pageCount)
            assertEquals(
                listOf("ch1/p1.png", "ch1/p2.png", "ch2/p10.jpg"),
                extractor.entries.map { it.name },
            )
            assertArrayEquals(Pics.png(2, 2), extractor.readPage(1))
            assertArrayEquals(Pics.jpeg(10, 10), extractor.readPage(2))
            assertEquals(pages[0].second.size.toLong(), extractor.entry(2).sizeBytes)
        }
    }

    @Test
    fun `tar gz is materialised in memory and stays readable`() {
        val file = File(tempDir, "book.tgz")
        TarWriter.writeTarGz(file, pages)

        TarExtractor.open(file).use { extractor ->
            assertEquals(ArchiveKind.TAR_GZIP, extractor.kind)
            assertTrue(extractor.materialisedInMemory, "gzip 无法随机访问 → 打开时整体解压")
            assertEquals(3, extractor.pageCount)
            assertArrayEquals(Pics.png(1, 1), extractor.readPage(0))
        }
    }

    @Test
    fun `tar gz over the materialise limit is refused with guidance`() {
        val file = File(tempDir, "big.tgz")
        TarWriter.writeTarGz(file, listOf("1.jpg" to ByteArray(4096)))

        val error = assertThrows(UnsupportedArchiveException::class.java) {
            TarExtractor.open(file, maxGzipMaterialiseBytes = 1024)
        }
        assertTrue(error.message!!.contains("CBZ"), "错误信息要给出可执行的替代方案: ${error.message}")
    }

    @Test
    fun `gnu long names are honoured`() {
        val longName = "a/very/long/chapter/directory/name/that/clearly/exceeds/one/hundred/characters/" +
            "of/the/ustar/name/field/page-001.jpg"
        assertTrue(longName.length > 100, "夹具必须超过 ustar 的 100 字节 name 字段")
        val file = File(tempDir, "long.cbt")
        TarWriter.writeTar(file, listOf(longName to Pics.jpeg(3, 3)))

        TarExtractor.open(file).use { extractor ->
            assertEquals(1, extractor.pageCount)
            assertEquals(longName, extractor.entries[0].name)
        }
    }

    @Test
    fun `tar without images lists zero pages`() {
        val file = File(tempDir, "docs.cbt")
        TarWriter.writeTar(file, listOf("readme.txt" to "hi".toByteArray()))

        TarExtractor.open(file).use { extractor ->
            assertEquals(0, extractor.pageCount)
        }
    }

    @Test
    fun `octal and base-256 size fields are parsed`() {
        val octal = ByteArray(12)
        "00000001234\u0000".forEachIndexed { i, c -> octal[i] = c.code.toByte() }
        assertEquals(668L, TarExtractor.parseNumeric(octal, 0, 12), "0o1234 = 668")

        val base256 = ByteArray(12)
        base256[0] = 0x80.toByte() // 高位标记
        base256[10] = 0x01
        base256[11] = 0x00
        assertEquals(256L, TarExtractor.parseNumeric(base256, 0, 12))
    }

    @Test
    fun `empty tar yields no pages`() {
        val file = File(tempDir, "empty.cbt")
        file.writeBytes(TarWriter.tar(emptyList()))

        TarExtractor.open(file).use { extractor ->
            assertEquals(0, extractor.pageCount)
        }
    }
}
