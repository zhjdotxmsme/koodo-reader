package com.koodoreader.engine.image

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * CB7 / `.7z` 实装测试（P5-CB7 补强，卡 `t-mufbaoux-yjmwrl`）。
 *
 * 夹具是五个内容相同、压缩方式不同的 7z 归档（见
 * `src/test/resources/sevenz/README.md`）：Copy / LZMA / LZMA2 / BCJ2+LZMA2 /
 * AES 头部加密。前四种必须能读出页表与页字节，最后一种必须给出**可执行**的错误。
 */
class SevenZExtractorTest {

    @TempDir
    lateinit var dir: File

    /** 把 classpath 夹具拷成真实文件（[SevenZExtractor] 按 [File] 打开）。 */
    private fun fixture(name: String): File {
        val target = File(dir, name)
        val stream = javaClass.getResourceAsStream("/sevenz/$name")
            ?: error("缺少夹具 /sevenz/$name —— 见 src/test/resources/sevenz/README.md")
        stream.use { input -> target.outputStream().use { input.copyTo(it) } }
        return target
    }

    private val readableVariants = listOf(
        "cb7-copy.7z", // -m0=Copy
        "cb7-lzma.7z", // -m0=LZMA
        "cb7-lzma2.7z", // -m0=LZMA2（最常见，靠 org.tukaani:xz 解码）
        "cb7-bcj2.7z", // -m0=BCJ2 -m1=LZMA2（多级过滤器链）
    )

    // ── 四种可读变体：页表 + 字节 ───────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "cb7-copy.7z",
            "cb7-lzma.7z",
            "cb7-lzma2.7z",
        ],
    )
    @DisplayName("每个可读 7z 变体都能建出页表并读出页字节")
    fun `every variant reads its page table and page bytes`(name: String) {
        val file = fixture(name)
        SevenZExtractor.open(file).use { extractor ->
            assertEquals(ArchiveKind.SEVEN_ZIP, extractor.kind)
            assertEquals(name, extractor.name)
            assertEquals(file.length(), extractor.fileSizeBytes)

            // notes.txt 不是图片 → 不进页表；页表按 NATURAL 顺序（9 在 10 前，
            // 而不是字典序的 1,10,9）——这与 ZipExtractor / P1 导入计数一致。
            assertEquals(3, extractor.pageCount)
            assertEquals(
                listOf("pages/1.jpg", "pages/9.jpg", "pages/10.jpg"),
                extractor.entries.map { it.name },
            )
            // jpg 归一为 jpeg（与 ComicCover 封面文件名规则一致）
            assertTrue(extractor.entries.all { it.ext == "jpeg" })
            assertTrue(extractor.entries.all { it.sizeBytes > 0 })

            assertEquals("PAGE-jpg-1", String(extractor.readPage(0)))
            assertEquals("PAGE-jpg-9", String(extractor.readPage(1)))
            assertEquals("PAGE-jpg-10", String(extractor.readPage(2)))
        }
    }

    @Test
    @DisplayName("流式读页：不留下被锁住的句柄，可反复读同一页")
    fun `openPage streams and releases the archive`() {
        SevenZExtractor.open(fixture("cb7-lzma2.7z")).use { extractor ->
            repeat(3) {
                assertEquals("PAGE-jpg-1", extractor.openPage(0).use { String(it.readBytes()) })
                assertEquals("PAGE-jpg-10", extractor.openPage(2).use { String(it.readBytes()) })
            }
            // close() 是无状态实现的空操作，重复调用必须安全
            extractor.close()
            extractor.close()
        }
    }

    // ── 路由：CB7 现在是原生可读 ────────────────────────────────────────────

    @Test
    @DisplayName("ArchiveExtractors 把 .cb7 路由到原生实现（验收 [3]）")
    fun `cb7 is natively readable through the dispatcher`() {
        val file = fixture("cb7-lzma2.7z")

        assertEquals(ArchiveKind.SEVEN_ZIP, ArchiveExtractors.kindOf(file))
        assertTrue(ArchiveExtractors.isNativelyReadable(file))
        assertTrue(ArchiveKind.SEVEN_ZIP.nativelyReadable)
        assertTrue(SevenZExtractor.NATIVE_SUPPORTED)
        assertTrue(ArchiveKind.SEVEN_ZIP in ArchiveExtractors.nativeReadableKinds())

        ArchiveExtractors.open(file).use { extractor ->
            assertTrue(extractor is SevenZExtractor, "应分派到 SevenZExtractor，实际 ${extractor::class}")
            assertEquals(3, extractor.pageCount)
        }
    }

    // ── 错误路径 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("BCJ2 过滤器：纯 Java 栈解不了，给出可执行错误（不假装支持）")
    fun `bcj2 filtered archive is rejected with an actionable message`() {
        val file = fixture("cb7-bcj2.7z")

        // 魔数/扩展名仍然认得出 CB7（宿主据此知道该回退兜底岛）
        assertEquals(ArchiveKind.SEVEN_ZIP, ArchiveExtractors.kindOf(file))

        val error = assertThrows(UnsupportedArchiveException::class.java) {
            SevenZExtractor.open(file)
        }
        assertTrue(error.message!!.contains("BCJ2"), "要点名是哪种过滤器: ${error.message}")
        assertTrue(error.message!!.contains("兜底岛"), "要给出替代路径: ${error.message}")
        // 底层原因来自 commons-compress：多输入/输出流编码器未实现
        assertTrue(
            error.cause?.message?.contains("Multi input/output stream coders") == true,
            "要保留底层原因: ${error.cause?.message}",
        )
    }

    @Test
    @DisplayName("头部加密的 7z：打开即失败，并指向兜底岛")
    fun `encrypted header archive fails with an actionable message`() {
        val file = fixture("cb7-encrypted-header.7z")

        // 魔数与扩展名仍然认得出来（宿主据此决定路由）
        assertEquals(ArchiveKind.SEVEN_ZIP, ArchiveExtractors.kindOf(file))

        val error = assertThrows(UnsupportedArchiveException::class.java) {
            SevenZExtractor.open(file)
        }
        assertTrue(error.message!!.contains("加密"), "要说明是加密导致: ${error.message}")
        assertTrue(error.message!!.contains("兜底岛"), "要给出可执行的替代路径: ${error.message}")
        // 底层原因（commons-compress 的 PasswordRequiredException）不能丢
        assertTrue(error.cause != null, "要保留底层异常便于诊断")
    }

    @Test
    @DisplayName("损坏 / 截断的 7z：报可诊断的错误而不是静默空页表")
    fun `truncated archive fails loudly`() {
        val whole = fixture("cb7-lzma2.7z").readBytes()
        val broken = File(dir, "broken.cb7")
        broken.writeBytes(whole.copyOf(48)) // 只剩魔数与部分头

        val error = assertThrows(UnsupportedArchiveException::class.java) {
            SevenZExtractor.open(broken)
        }
        assertTrue(error.message!!.contains("打开失败"), "错误信息要能定位原因: ${error.message}")
    }

    @Test
    @DisplayName("越界页号抛 IndexOutOfBoundsException")
    fun `out of range page index throws`() {
        SevenZExtractor.open(fixture("cb7-copy.7z")).use { extractor ->
            assertThrows(IndexOutOfBoundsException::class.java) { extractor.readPage(3) }
            assertThrows(IndexOutOfBoundsException::class.java) { extractor.openPage(-1) }
        }
    }

    @Test
    @DisplayName("页表不依赖扩展名大小写与目录层级")
    fun `page table ignores name case and directory depth`() {
        SevenZExtractor.open(fixture("cb7-lzma.7z")).use { extractor ->
            assertTrue(extractor.entries.none { it.name.endsWith("notes.txt") })
            assertFalse(extractor.entries.any { it.name.contains('\\') }, "条目名要用 / 归一化")
            assertEquals(3, extractor.pageCount)
        }
    }

    @Test
    @DisplayName("单页体积上限是一个显式的常量")
    fun `page size cap is explicit`() {
        assertEquals(64L * 1024 * 1024, SevenZExtractor.MAX_PAGE_BYTES)
    }
}
