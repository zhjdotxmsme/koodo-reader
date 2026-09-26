package com.koodoreader.reader.epubhost

import com.koodoreader.engine.layout.TextMeasurers
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * 步骤② 集成测试：EPUB → EpubSpine → HtmlFlattener → LayoutEngine 分页，
 * CFI 经 CfiAddressing 双向（确定性 mono measurer，断言可精确）。
 */
class EpubBookSessionTest {

    @TempDir
    lateinit var dir: File

    /** 三章 EPUB：每章两个 <p>，内容可断言。 */
    private fun makeEpub(name: String): File {
        val zip = File(dir, name)
        ZipOutputStream(zip.outputStream()).use { zos ->
            fun put(entry: String, content: String) {
                zos.putNextEntry(ZipEntry(entry))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            put("mimetype", "application/epub+zip")
            put(
                "META-INF/container.xml",
                """<container><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            )
            put(
                "OEBPS/content.opf",
                """<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <manifest>
                    <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c3" href="ch3.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine>
                    <itemref idref="c1"/><itemref idref="c2"/><itemref idref="c3"/>
                  </spine>
                </package>""",
            )
            for (n in 1..3) {
                put(
                    "OEBPS/ch$n.xhtml",
                    "<html><body>" +
                        "<p>Chapter $n first paragraph with some longer text.</p>" +
                        "<p>Chapter $n second paragraph.</p>" +
                        "</body></html>",
                )
            }
        }
        return zip
    }

    private fun open(file: File): EpubBookSession =
        EpubBookSession.open(
            file,
            viewportWidthPx = 400f,
            viewportHeightPx = 700f,
            measurer = TextMeasurers.mono(),
        )

    // ------------------------------------------------------------- 分页

    @Test
    fun `opens and paginates the whole book`() {
        makeEpub("a.epub").let { file ->
            open(file).use { s ->
                assertEquals(3, s.spine.chapters.size)
                assertTrue(s.pageCount > 0)
                assertTrue(s.pageLines(0).isNotEmpty())
            }
        }
    }

    @Test
    fun `all three chapters land in the document in order`() {
        makeEpub("b.epub").let { file ->
            open(file).use { s ->
                // 每章的内容都可以在分页行里找到
                for (n in 1..3) {
                    assertTrue(
                        s.result.pages.any { page ->
                            page.columns.any { col -> col.lines.any { it.text.contains("Chapter $n first") } }
                        },
                        "chapter $n missing from pagination",
                    )
                }
                // 章序：ch1 的行先于 ch3 的行
                val flat = s.result.pages.flatMap { it.columns }.flatMap { it.lines }
                val first1 = flat.indexOfFirst { it.text.contains("Chapter 1 first") }
                val first3 = flat.indexOfFirst { it.text.contains("Chapter 3 first") }
                assertTrue(first1 in 0 until first3)
            }
        }
    }

    // ------------------------------------------------------------- CFI

    @Test
    fun `cfiForPage emits an epubcfi pointing at the right chapter`() {
        makeEpub("c.epub").let { file ->
            open(file).use { s ->
                val cfi = s.cfiForPage(0)
                assertNotNull(cfi)
                assertTrue(cfi!!.startsWith("epubcfi("), cfi)
                // 第 0 页属于第 1 章 → spine 步为 1（1 基阅读序）
                assertTrue(cfi.contains("/6/1!"), cfi)
            }
        }
    }

    @Test
    fun `cfiForPage and pageForCfi round-trip`() {
        makeEpub("d.epub").let { file ->
            open(file).use { s ->
                for (p in 0 until s.pageCount) {
                    val cfi = s.cfiForPage(p) ?: continue
                    assertEquals(p, s.pageForCfi(cfi), "page $p CFI did not round-trip")
                }
            }
        }
    }

    @Test
    fun `pageForCfi returns null for a foreign cfi`() {
        makeEpub("e.epub").let { file ->
            open(file).use { s ->
                assertNull(s.pageForCfi("epubcfi(/6/99!/2/0)"))
                assertNull(s.pageForCfi("not a cfi"))
            }
        }
    }

    @Test
    fun `pageOfChapter finds each chapter start`() {
        makeEpub("f.epub").let { file ->
            open(file).use { s ->
                val pages = (0..2).map { s.pageOfChapter(it) }
                assertTrue(pages.zipWithNext().all { (a, b) -> a <= b }, "chapter pages must be ordered: $pages")
                assertTrue(s.pageOfChapter(0) <= s.pageOfChapter(2))
            }
        }
    }

    // ------------------------------------------------------- 生命周期

    @Test
    fun `close is idempotent`() {
        makeEpub("g.epub").let { file ->
            val s = open(file)
            s.close()
            s.close()
        }
    }

    @Test
    fun `non-epub payload fails fast`() {
        val junk = File(dir, "junk.epub").apply { writeText("not a zip") }
        assertThrows<IllegalArgumentException> { open(junk) }
    }
}
