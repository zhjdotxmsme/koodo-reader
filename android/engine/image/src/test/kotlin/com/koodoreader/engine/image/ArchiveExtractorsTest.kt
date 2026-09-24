package com.koodoreader.engine.image

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * 容器探测与路由：魔数优先（`.cbz` 里可能其实是 rar），扩展名兜底；
 * 未接线的 CB7/CBR 必须给出**可执行**的错误信息而不是静默失败。
 */
class ArchiveExtractorsTest {

    @TempDir
    var tempDir: File = File("build/tmp/image-test")

    private fun file(name: String, header: ByteArray): File {
        val f = File(tempDir, name)
        f.writeBytes(header)
        return f
    }

    @Test
    fun `kinds are detected by magic bytes`() {
        assertEquals(ArchiveKind.ZIP, ArchiveExtractors.kindOf(file("a.bin", bytesOf(0x50, 0x4B, 0x03, 0x04))))
        assertEquals(ArchiveKind.RAR, ArchiveExtractors.kindOf(file("b.bin", bytesOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01))))
        assertEquals(
            ArchiveKind.SEVEN_ZIP,
            ArchiveExtractors.kindOf(file("c.bin", bytesOf(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C))),
        )
        assertEquals(ArchiveKind.TAR_GZIP, ArchiveExtractors.kindOf(file("d.bin", bytesOf(0x1F, 0x8B, 0x08, 0x00))))

        // ustar magic 在 257 字节处
        val tar = ByteArray(512)
        "ustar".forEachIndexed { i, c -> tar[257 + i] = c.code.toByte() }
        assertEquals(ArchiveKind.TAR, ArchiveExtractors.kindOf(file("e.bin", tar)))
    }

    @Test
    fun `magic wins over a lying extension`() {
        // 名字叫 .cbz，内容是 rar —— 桌面同样按魔数派发
        val liar = file("liar.cbz", bytesOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00))
        assertEquals(ArchiveKind.RAR, ArchiveExtractors.kindOf(liar))
        assertFalse(ArchiveExtractors.isNativelyReadable(liar))
    }

    @Test
    fun `extensions are the fallback for headerless containers`() {
        val empty = file("mystery.cbt", ByteArray(0))
        assertEquals(ArchiveKind.TAR, ArchiveExtractors.kindOf(empty))
        assertEquals(ArchiveKind.SEVEN_ZIP, ArchiveExtractors.kindOf(file("x.cb7", ByteArray(0))))
        assertEquals(ArchiveKind.TAR_GZIP, ArchiveExtractors.kindOf(file("y.tar.gz", ByteArray(0))))
        assertEquals(ArchiveKind.UNKNOWN, ArchiveExtractors.kindOf(file("z.bin", ByteArray(0))))
    }

    @Test
    fun `directories are a first class comic source`() {
        val dir = File(tempDir, "comic-dir")
        dir.mkdirs()
        assertEquals(ArchiveKind.DIRECTORY, ArchiveExtractors.kindOf(dir))
        assertTrue(ArchiveExtractors.isNativelyReadable(dir))
    }

    @Test
    fun `rar is deferred with a route back to the fallback island`() {
        val rar = file("book.cbr", bytesOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00))

        val error = assertThrows(UnsupportedArchiveException::class.java) { ArchiveExtractors.open(rar) }

        assertTrue(error.message!!.contains("兜底岛"), "要告诉宿主回退哪条路: ${error.message}")
        assertFalse(RarExtractor.NATIVE_SUPPORTED)
        assertTrue(RarExtractor.FALLBACK_ROUTE.contains("webisland"))
    }

    @Test
    fun `seven zip is wired through the pure java dependency`() {
        // CBR 仍是骨架（UnRAR 许可证 + native 库），CB7 已实装（P5-CB7 补强）：
        // 这里用「魔数正确但内容不是 7z」的文件，验证失败路径给出的是**可执行**的错误，
        // 而不是像接线前那样无条件抛「尚未接线」。
        val seven = file("book.cb7", bytesOf(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C, 0x00, 0x04))

        val error = assertThrows(UnsupportedArchiveException::class.java) { ArchiveExtractors.open(seven) }

        assertTrue(error.message!!.contains("打开失败"), "损坏的 7z 要报可诊断的理由: ${error.message}")
        assertTrue(SevenZExtractor.NATIVE_SUPPORTED)
        assertTrue(SevenZExtractor.DEPENDENCY.contains("commons-compress"))
        // 依赖必须是纯 Java（无 .so）：这是 CB7 能原生、CBR 不能的分界线。
        assertFalse(SevenZExtractor.DEPENDENCY.contains(".so"))
    }

    @Test
    fun `unknown containers are rejected explicitly`() {
        val junk = file("junk.bin", "not an archive".toByteArray())

        assertThrows(UnsupportedArchiveException::class.java) { ArchiveExtractors.open(junk) }
    }

    @Test
    fun `native readable kinds are exactly the implemented ones`() {
        assertEquals(
            setOf(
                ArchiveKind.ZIP,
                ArchiveKind.TAR,
                ArchiveKind.TAR_GZIP,
                ArchiveKind.SEVEN_ZIP,
                ArchiveKind.DIRECTORY,
            ),
            ArchiveExtractors.nativeReadableKinds(),
        )
        assertEquals(ArchiveKind.Support.DEFERRED, ArchiveKind.RAR.support)
        assertEquals(ArchiveKind.Support.READY, ArchiveKind.SEVEN_ZIP.support)
        assertEquals(ArchiveKind.Support.READY, ArchiveKind.ZIP.support)
        assertEquals(ArchiveKind.Support.UNSUPPORTED, ArchiveKind.UNKNOWN.support)
    }

    @Test
    fun `zip extension aliases resolve to the zip reader`() {
        assertEquals(ArchiveKind.ZIP, ArchiveKind.ofExtension("book.CBZ"), "大小写不敏感")
        assertEquals(ArchiveKind.ZIP, ArchiveKind.ofExtension("book.zip"))
        assertEquals(ArchiveKind.RAR, ArchiveKind.ofExtension("book.cbr"))
        assertEquals(ArchiveKind.TAR, ArchiveKind.ofExtension("book.cbt"))
        assertEquals(ArchiveKind.UNKNOWN, ArchiveKind.ofExtension("book.epub"))
    }
}
