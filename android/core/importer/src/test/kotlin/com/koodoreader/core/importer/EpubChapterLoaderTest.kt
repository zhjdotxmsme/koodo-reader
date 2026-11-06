package com.koodoreader.core.importer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubChapterLoaderTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `open reads metadata but not spine bodies`() {
        val epub = multiChapterEpub(chapters = 5)
        EpubChapterLoader.open(epub).use { loader ->
            assertEquals(5, loader.descriptors().size)
            assertEquals(0, loader.chapterReadCount())
        }
    }

    @Test
    fun `load reads exactly one chapter body`() {
        val epub = multiChapterEpub(chapters = 3)
        EpubChapterLoader.open(epub).use { loader ->
            val id = loader.descriptors().first().id
            loader.load(id)
            assertEquals(1, loader.chapterReadCount())
        }
    }

    @Test
    fun `resource path cannot escape package`() {
        assertNull(EpubResourceLoader.resolve("OEBPS/ch1.xhtml", "../../outside.png"))
        assertEquals(
            "OEBPS/images/cover.png",
            EpubResourceLoader.resolve("OEBPS/ch1.xhtml", "images/cover.png"),
        )
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
                """<?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>""",
            )
            val manifest = buildString {
                append("""<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf"><metadata/><manifest>""")
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
                    """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><body><p>Chapter $i</p></body></html>""",
                )
            }
        }
        return zip
    }
}
