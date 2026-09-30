package com.koodoreader.reader.epubhost

import com.koodoreader.engine.layout.TextMeasurers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 端到端：EPUB 会话的章节标签（目录对话框 / 书签 / TTS 共用）来自
 * 书自带目录（EpubToc NAV），不再是 zip 文件名；无 TOC 的降级回文件名。
 */
class EpubTocLabelsTest {

    @TempDir
    lateinit var dir: File

    private fun epubFile(): File {
        val f = File(dir, "toc-books.epub")
        ZipOutputStream(f.outputStream()).use { zos ->
            fun put(entry: String, content: String) {
                zos.putNextEntry(ZipEntry(entry))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            put("mimetype", "application/epub+zip")
            put(
                "META-INF/container.xml",
                """<container><rootfiles><rootfile full-path="content.opf"/></rootfiles></container>""",
            )
            put(
                "content.opf",
                """<package version="3.0"><manifest>
                   <item id="nav" href="toc.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                   <item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
                   <item id="c2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
                   </manifest><spine>
                   <itemref idref="c1"/><itemref idref="c2"/>
                   </spine></package>""",
            )
            put(
                "toc.xhtml",
                """<html><body><nav epub:type="toc"><ol>
                   <li><a href="text/ch1.xhtml">第一章 启程</a></li>
                   <li><a href="text/ch2.xhtml">第二章 远方</a></li>
                   </ol></nav></body></html>""",
            )
            put("text/ch1.xhtml", "<html><body><p>第一章正文。</p></body></html>")
            put("text/ch2.xhtml", "<html><body><p>第二章正文，足够长以分页。</p></body></html>")
        }
        return f
    }

    @Test
    fun `session chapter labels come from the books own nav toc`() {
        val session = EpubBookSession.open(
            epubFile(), 400f, 700f, TextMeasurers.mono(),
        )
        session.use { s ->
            assertEquals(2, s.chapterCount)
            assertEquals("第一章 启程", s.chapterLabel(0))
            assertEquals("第二章 远方", s.chapterLabel(1))
            // 分页 / CFI 主线不受标签来源影响
            val cfi = s.cfiForPage(0)
            assertEquals(0, s.pageForCfi(cfi))
        }
    }

    @Test
    fun `books without toc keep the filename labels`() {
        val f = File(dir, "no-toc.epub")
        ZipOutputStream(f.outputStream()).use { zos ->
            fun put(entry: String, content: String) {
                zos.putNextEntry(ZipEntry(entry))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            put("mimetype", "application/epub+zip")
            put(
                "META-INF/container.xml",
                """<container><rootfiles><rootfile full-path="content.opf"/></rootfiles></container>""",
            )
            put(
                "content.opf",
                """<package version="3.0"><manifest>
                   <item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
                   </manifest><spine><itemref idref="c1"/></spine></package>""",
            )
            put("text/ch1.xhtml", "<html><body><p>正文。</p></body></html>")
        }
        val session = EpubBookSession.open(f, 400f, 700f, TextMeasurers.mono())
        session.use { s ->
            assertEquals("ch1.xhtml", s.chapterLabel(0))
        }
    }
}
