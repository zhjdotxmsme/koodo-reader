package com.koodoreader.reader.epubhost

import com.koodoreader.core.importer.EpubChapterLoader
import com.koodoreader.engine.layout.TextMeasurers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubBookSessionLoaderTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `open with chapter limit paginates first chapter only`() {
        val file = multiChapterEpub(5)
        EpubChapterLoader.open(file).use { loader ->
            assertEquals(0, loader.chapterReadCount())
        }
        EpubBookSession.open(
            file,
            viewportWidthPx = 400f,
            viewportHeightPx = 700f,
            measurer = TextMeasurers.mono(),
            chapterLimit = 1,
        ).use { session ->
            assertEquals(1, session.chapterCount)
            assertTrue(session.pageCount > 0)
        }
        EpubChapterLoader.open(file).use { loader ->
            assertEquals(0, loader.chapterReadCount())
        }
    }

    private fun multiChapterEpub(chapters: Int): File {
        val zip = File(dir, "many.epub")
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
            val manifest = buildString {
                append("""<package xmlns="http://www.idpf.org/2007/opf"><manifest>""")
                repeat(chapters) { i ->
                    append("""<item id="c$i" href="ch$i.xhtml" media-type="application/xhtml+xml"/>""")
                }
                append("</manifest><spine>")
                repeat(chapters) { i -> append("""<itemref idref="c$i"/>""") }
                append("</spine></package>")
            }
            put("OEBPS/content.opf", manifest)
            repeat(chapters) { i ->
                put(
                    "OEBPS/ch$i.xhtml",
                    "<html><body><p>Chapter $i body text long enough to paginate.</p></body></html>",
                )
            }
        }
        return zip
    }
}
