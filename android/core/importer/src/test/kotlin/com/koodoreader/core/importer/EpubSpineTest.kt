package com.koodoreader.core.importer

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
 * EpubSpine 抽取层测试：程序化 EPUB 夹具（无需二进制资源），覆盖
 * container→OPF→manifest/spine 解析、相对路径、linear 过滤、percent 解码、
 * fallback 与类型化错误。
 */
class EpubSpineTest {

    @TempDir
    lateinit var dir: File

    /** 组装一个最小但结构完整的 EPUB。 */
    private fun makeEpub(name: String, opfDir: String = "OEBPS"): File {
        val zip = File(dir, name)
        ZipOutputStream(zip.outputStream()).use { zos ->
            fun put(entry: String, content: String) {
                zos.putNextEntry(ZipEntry(entry))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            fun putBytes(entry: String, bytes: ByteArray) {
                zos.putNextEntry(ZipEntry(entry))
                zos.write(bytes)
                zos.closeEntry()
            }
            put("mimetype", "application/epub+zip")
            put(
                "META-INF/container.xml",
                """<?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="$opfDir/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>""",
            )
            put(
                "$opfDir/content.opf",
                """<?xml version="1.0"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>T</dc:title><dc:identifier id="uid">u1</dc:identifier>
                  </metadata>
                  <manifest>
                    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                    <item id="ch1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="ch2" href="text/ch%202.xhtml" media-type="application/xhtml+xml"/>
                    <item id="img" href="images/cover.png" media-type="image/png"/>
                    <item id="hidden" href="text/draft.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine toc="ncx">
                    <itemref idref="ch1"/>
                    <itemref idref="ch2"/>
                    <itemref idref="hidden" linear="no"/>
                  </spine>
                </package>""",
            )
            put("$opfDir/text/ch1.xhtml", "<html><body><p>one</p></body></html>")
            put("$opfDir/text/ch 2.xhtml", "<html><body><p>two</p></body></html>")
            put("$opfDir/text/draft.xhtml", "<html><body><p>draft</p></body></html>")
            putBytes("$opfDir/images/cover.png", byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
        }
        return zip
    }

    // ------------------------------------------------------------- 解析

    @Test
    fun `spine 解析为阅读顺序且 href 相对 OPF 解析`() {
        makeEpub("a.epub").let { file ->
            EpubSpine.open(file).use { spine ->
                assertEquals("OEBPS/content.opf", spine.opfHref)
                // ch1 ../ 上跳一层后回到 OEBPS/text/ch1.xhtml
                assertEquals(
                    listOf("OEBPS/text/ch1.xhtml", "OEBPS/text/ch 2.xhtml"),
                    spine.chapters.map { it.href },
                )
                assertEquals(listOf("ch1", "ch2"), spine.chapters.map { it.idref })
            }
        }
    }

    @Test
    fun `linear no 的条目在 items 里但不进 chapters`() {
        makeEpub("b.epub").let { file ->
            EpubSpine.open(file).use { spine ->
                assertEquals(3, spine.items.size)
                assertEquals(2, spine.chapters.size)
                assertEquals("hidden", spine.items.last().idref)
                assertTrue(spine.items.none { !it.isLinear && it.idref == "hidden" }.not())
            }
        }
    }

    @Test
    fun `readChapter 返回章节 XHTML 文本`() {
        makeEpub("c.epub").let { file ->
            EpubSpine.open(file).use { spine ->
                assertTrue(spine.readChapter(0)!!.contains("<p>one</p>"))
                assertTrue(spine.readChapter(1)!!.contains("<p>two</p>"))
            }
        }
    }

    @Test
    fun `readResource 返回图片字节`() {
        makeEpub("d.epub").let { file ->
            EpubSpine.open(file).use { spine ->
                val bytes = spine.readResource("OEBPS/images/cover.png")
                assertNotNull(bytes)
                assertEquals(4, bytes!!.size)
            }
        }
    }

    @Test
    fun `href 百分号解码后命中真实条目`() {
        makeEpub("e.epub").let { file ->
            EpubSpine.open(file).use { spine ->
                // manifest 里写的是 ch%202.xhtml → 解码为 "ch 2.xhtml"
                assertTrue(spine.readChapter(1)!!.contains("<p>two</p>"))
            }
        }
    }

    @Test
    fun `缺失 container 时回退到第一个 opf`() {
        val zip = File(dir, "fallback.epub")
        ZipOutputStream(zip.outputStream()).use { zos ->
            fun put(entry: String, content: String) {
                zos.putNextEntry(ZipEntry(entry))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            put("ebook.opf", """<package version="3.0"><manifest><item id="c" href="c.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c"/></spine></package>""")
            put("c.xhtml", "<html><body>fallback</body></html>")
        }
        EpubSpine.open(zip).use { spine ->
            assertEquals("ebook.opf", spine.opfHref)
            assertTrue(spine.readChapter(0)!!.contains("fallback"))
        }
    }

    // ------------------------------------------------------- 类型化错误

    @Test
    fun `非 zip 文件抛 IllegalArgumentException`() {
        val text = File(dir, "not.epub").apply { writeText("plain") }
        assertThrows<IllegalArgumentException> { EpubSpine.open(text) }
    }

    @Test
    fun `无 spine 抛 IllegalArgumentException`() {
        val zip = File(dir, "nospine.epub")
        ZipOutputStream(zip.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("META-INF/container.xml"))
            zos.write(
                """<container><rootfiles><rootfile full-path="p.opf"/></rootfiles></container>"""
                    .toByteArray(),
            )
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("p.opf"))
            zos.write("<package/>".toByteArray())
            zos.closeEntry()
        }
        assertThrows<IllegalArgumentException> { EpubSpine.open(zip) }
    }

    @Test
    fun `缺失文件抛 IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> { EpubSpine.open(File(dir, "ghost.epub")) }
    }

    // ------------------------------------------------------- 资源读取

    @Test
    fun `readResource 对不存在资源返回 null`() {
        makeEpub("g.epub").let { file ->
            EpubSpine.open(file).use { spine ->
                assertNull(spine.readResource("OEBPS/images/nope.png"))
            }
        }
    }
}
