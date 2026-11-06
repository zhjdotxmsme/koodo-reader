package com.koodoreader.reader.epubhost

import com.koodoreader.engine.layout.TextMeasurers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubBookSessionProgressiveTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `expand remaining loads all chapters and preserves first page cfi`() {
        val file = threeChapterEpub()
        EpubBookSession.open(
            file,
            viewportWidthPx = 400f,
            viewportHeightPx = 700f,
            measurer = TextMeasurers.mono(),
            chapterLimit = 1,
        ).use { session ->
            assertEquals(1, session.loadedChapterCount)
            assertEquals(3, session.totalChapterCount)
            assertFalse(session.isFullyLoaded)
            assertTrue(session.pageLines(0).any { it.text.contains("Chapter 1") })
            assertFalse(session.expandRemaining(), "single expand with default batch loads all remaining chapters")
            assertEquals(3, session.loadedChapterCount)
            assertTrue(session.isFullyLoaded)
            assertFalse(session.expandRemaining())
            assertTrue(session.pageLines(0).any { it.text.contains("Chapter 1") })
            assertTrue(session.chapterText(2).contains("Chapter 3"))
        }
    }

    private fun threeChapterEpub(): File {
        val zip = File(dir, "prog.epub")
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
                """<package xmlns="http://www.idpf.org/2007/opf">
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
                    "<html><body><p>Chapter $n paragraph with enough text to layout.</p></body></html>",
                )
            }
        }
        return zip
    }
}
