package com.koodoreader.core.importer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * EPUB 真目录解析（EpubToc）：EPUB3 NAV 优先、EPUB2 NCX 回退、无 TOC 降级。
 * 夹具与 `ReaderSessionFactoryTest.epubFile()` 同构（手写最小合法 EPUB）。
 */
class EpubTocTest {

    @TempDir
    lateinit var dir: File

    private fun epubFile(entries: Map<String, String>): File =
        File(dir, "book-${entries.size}-${entries.keys.hashCode()}.epub").apply {
            ZipOutputStream(outputStream()).use { zos ->
                entries.forEach { (name, content) ->
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(content.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
            }
        }

    // ---- EPUB3 NAV -------------------------------------------------------

    @Test
    fun `epub3 nav produces real chapter labels`() {
        val file = epubFile(
            linkedMapOf(
                "mimetype" to "application/epub+zip",
                "META-INF/container.xml" to
                    """<container><rootfiles><rootfile full-path="content.opf"/></rootfiles></container>""",
                "content.opf" to
                    """<package xmlns:epub="http://www.idpf.org/2007/ops" version="3.0"><manifest>
                       <item id="nav" href="toc.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                       <item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
                       <item id="c2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
                       </manifest><spine>
                       <itemref idref="c1"/><itemref idref="c2"/>
                       </spine></package>""",
                "toc.xhtml" to
                    """<html><body><nav epub:type="toc">
                       <ol>
                         <li><a href="text/ch1.xhtml">Chapter One &amp; Intro</a></li>
                         <li><a href="text/ch2.xhtml"><span>Chapter Two</span></a></li>
                       </ol></nav></body></html>""",
                "text/ch1.xhtml" to "<html><body><p>One.</p></body></html>",
                "text/ch2.xhtml" to "<html><body><p>Two.</p></body></html>",
            ),
        )
        EpubSpine.open(file).use { spine ->
            val labels = EpubToc.chapterLabels(spine)
            assertEquals(2, labels.size)
            // 实体解码（&amp;）+ 内嵌 <span> 剥离
            assertEquals("Chapter One & Intro", labels["text/ch1.xhtml"])
            assertEquals("Chapter Two", labels["text/ch2.xhtml"])
            // 键空间与 spine 章节一致（EpubBookSession 查表用）
            assertTrue(spine.chapters.all { labels.containsKey(it.href.lowercase()) })
        }
    }

    @Test
    fun `epub3 nav falls back to first nav when none is toc-typed`() {
        val file = epubFile(
            linkedMapOf(
                "mimetype" to "application/epub+zip",
                "META-INF/container.xml" to
                    """<container><rootfiles><rootfile full-path="content.opf"/></rootfiles></container>""",
                "content.opf" to
                    """<package version="3.0"><manifest>
                       <item id="nav" href="toc.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                       <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                       </manifest><spine><itemref idref="c1"/></spine></package>""",
                "toc.xhtml" to
                    """<html><body><nav><ol><li><a href="ch1.xhtml">Only One</a></li></ol></nav></body></html>""",
                "ch1.xhtml" to "<html><body><p>Body.</p></body></html>",
            ),
        )
        EpubSpine.open(file).use { spine ->
            assertEquals("Only One", EpubToc.chapterLabels(spine)["ch1.xhtml"])
        }
    }

    // ---- EPUB2 NCX -------------------------------------------------------

    @Test
    fun `epub2 ncx is used when there is no nav item`() {
        val file = epubFile(
            linkedMapOf(
                "mimetype" to "application/epub+zip",
                "META-INF/container.xml" to
                    """<container><rootfiles><rootfile full-path="package.opf"/></rootfiles></container>""",
                "package.opf" to
                    """<package version="2.0"><manifest>
                       <item id="ncx" href="OEBPS/nav.ncx" media-type="application/x-dtbncx+xml"/>
                       <item id="c1" href="OEBPS/ch1.xhtml" media-type="application/xhtml+xml"/>
                       <item id="c2" href="OEBPS/ch2.xhtml" media-type="application/xhtml+xml"/>
                       </manifest><spine>
                       <itemref idref="c1"/><itemref idref="c2"/>
                       </spine></package>""",
                "OEBPS/nav.ncx" to
                    """<ncx version="2005-1"><navMap>
                       <navPoint id="n1" playOrder="1">
                         <navLabel><text>Part One</text></navLabel>
                         <content src="ch1.xhtml"/>
                       </navPoint>
                       <navPoint id="n2" playOrder="2">
                         <navLabel><text>Part Two</text></navLabel>
                         <content src="ch2.xhtml"/>
                       </navPoint>
                       </navMap></ncx>""",
                "OEBPS/ch1.xhtml" to "<html><body><p>One.</p></body></html>",
                "OEBPS/ch2.xhtml" to "<html><body><p>Two.</p></body></html>",
            ),
        )
        EpubSpine.open(file).use { spine ->
            val labels = EpubToc.chapterLabels(spine)
            // NCX 的 src 是相对 OPF 目录的，归一化后与 spine href 同键空间
            assertEquals("Part One", labels["oebps/ch1.xhtml"])
            assertEquals("Part Two", labels["oebps/ch2.xhtml"])
        }
    }

    @Test
    fun `ncx fragment identifiers are stripped so labels key to spine hrefs`() {
        // Calibre / mobiunpack NCX: src="Text/part0000.xhtml#chapter".
        // Spine hrefs have no fragment. If the fragment stays in the map key,
        // chapterLabels lookup misses and the reader falls back to filenames.
        val file = epubFile(
            linkedMapOf(
                "mimetype" to "application/epub+zip",
                "META-INF/container.xml" to
                    """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""",
                "OEBPS/content.opf" to
                    """<package version="2.0"><manifest>
                       <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                       <item id="c0" href="Text/part0000.xhtml" media-type="application/xhtml+xml"/>
                       <item id="c1" href="Text/part0001.xhtml" media-type="application/xhtml+xml"/>
                       </manifest><spine>
                       <itemref idref="c0"/><itemref idref="c1"/>
                       </spine></package>""",
                "OEBPS/toc.ncx" to
                    """<ncx version="2005-1"><navMap>
                       <navPoint id="np_1" playOrder="1">
                         <navLabel><text>封面页</text></navLabel>
                         <content src="Text/part0000.xhtml#chapter"/>
                       </navPoint>
                       <navPoint id="np_2" playOrder="2">
                         <navLabel><text>版权页</text></navLabel>
                         <content src="Text/part0001.xhtml#calibre_pb_0"/>
                       </navPoint>
                       </navMap></ncx>""",
                "OEBPS/Text/part0000.xhtml" to "<html><body><p>Cover.</p></body></html>",
                "OEBPS/Text/part0001.xhtml" to "<html><body><p>Copyright.</p></body></html>",
            ),
        )
        EpubSpine.open(file).use { spine ->
            val labels = EpubToc.chapterLabels(spine)
            assertTrue(spine.chapters.all { labels.containsKey(it.href.lowercase()) })
            assertEquals("封面页", labels["oebps/text/part0000.xhtml"])
            assertEquals("版权页", labels["oebps/text/part0001.xhtml"])
        }
    }

    @Test
    fun `nested ncx navPoints each keep their own label`() {
        // Real Calibre books nest chapter navPoints under a part. A non-greedy
        // </navPoint> match swallows the first child of each parent.
        val file = epubFile(
            linkedMapOf(
                "mimetype" to "application/epub+zip",
                "META-INF/container.xml" to
                    """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""",
                "OEBPS/content.opf" to
                    """<package version="2.0"><manifest>
                       <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                       <item id="p" href="Text/part0004.xhtml" media-type="application/xhtml+xml"/>
                       <item id="c" href="Text/part0005.xhtml" media-type="application/xhtml+xml"/>
                       <item id="d" href="Text/part0006.xhtml" media-type="application/xhtml+xml"/>
                       </manifest><spine>
                       <itemref idref="p"/><itemref idref="c"/><itemref idref="d"/>
                       </spine></package>""",
                "OEBPS/toc.ncx" to
                    """<ncx version="2005-1"><navMap>
                       <navPoint id="np_5" playOrder="5">
                         <navLabel><text>一　文明的兴衰</text></navLabel>
                         <content src="Text/part0004.xhtml#3Q281-frag"/>
                         <navPoint id="np_6" playOrder="6">
                           <navLabel><text>夏：二里头人</text></navLabel>
                           <content src="Text/part0005.xhtml#4OIQ1-frag"/>
                         </navPoint>
                         <navPoint id="np_7" playOrder="7">
                           <navLabel><text>商：洹上宅殷</text></navLabel>
                           <content src="Text/part0006.xhtml#5N3C1-frag"/>
                         </navPoint>
                       </navPoint>
                       </navMap></ncx>""",
                "OEBPS/Text/part0004.xhtml" to "<html><body><p>Part.</p></body></html>",
                "OEBPS/Text/part0005.xhtml" to "<html><body><p>Xia.</p></body></html>",
                "OEBPS/Text/part0006.xhtml" to "<html><body><p>Shang.</p></body></html>",
            ),
        )
        EpubSpine.open(file).use { spine ->
            val labels = EpubToc.chapterLabels(spine)
            assertEquals(3, labels.size)
            assertEquals("一　文明的兴衰", labels["oebps/text/part0004.xhtml"])
            assertEquals("夏：二里头人", labels["oebps/text/part0005.xhtml"])
            assertEquals("商：洹上宅殷", labels["oebps/text/part0006.xhtml"])
        }
    }

    @Test
    fun `epub3 nav fragments are stripped the same way`() {
        val file = epubFile(
            linkedMapOf(
                "mimetype" to "application/epub+zip",
                "META-INF/container.xml" to
                    """<container><rootfiles><rootfile full-path="content.opf"/></rootfiles></container>""",
                "content.opf" to
                    """<package version="3.0"><manifest>
                       <item id="nav" href="toc.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                       <item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
                       </manifest><spine><itemref idref="c1"/></spine></package>""",
                "toc.xhtml" to
                    """<html><body><nav epub:type="toc"><ol>
                       <li><a href="text/ch1.xhtml#start">Chapter One</a></li>
                       </ol></nav></body></html>""",
                "text/ch1.xhtml" to "<html><body><p>One.</p></body></html>",
            ),
        )
        EpubSpine.open(file).use { spine ->
            assertEquals("Chapter One", EpubToc.chapterLabels(spine)["text/ch1.xhtml"])
        }
    }

    // ---- 降级 ------------------------------------------------------------

    @Test
    fun `book without any toc parses to an empty list`() {
        val file = epubFile(
            linkedMapOf(
                "mimetype" to "application/epub+zip",
                "META-INF/container.xml" to
                    """<container><rootfiles><rootfile full-path="content.opf"/></rootfiles></container>""",
                "content.opf" to
                    """<package version="3.0"><manifest>
                       <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                       </manifest><spine><itemref idref="c1"/></spine></package>""",
                "ch1.xhtml" to "<html><body><p>Body.</p></body></html>",
            ),
        )
        EpubSpine.open(file).use { spine ->
            assertTrue(EpubToc.parse(spine).isEmpty())
            assertTrue(EpubToc.chapterLabels(spine).isEmpty())
        }
    }

    @Test
    fun `unreadable nav target degrades to empty instead of throwing`() {
        val file = epubFile(
            linkedMapOf(
                "mimetype" to "application/epub+zip",
                "META-INF/container.xml" to
                    """<container><rootfiles><rootfile full-path="content.opf"/></rootfiles></container>""",
                "content.opf" to
                    """<package version="3.0"><manifest>
                       <item id="nav" href="missing/nav.xhtml" properties="nav"/>
                       <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                       </manifest><spine><itemref idref="c1"/></spine></package>""",
                "ch1.xhtml" to "<html><body><p>Body.</p></body></html>",
            ),
        )
        EpubSpine.open(file).use { spine ->
            assertTrue(EpubToc.parse(spine).isEmpty())
        }
    }
}
