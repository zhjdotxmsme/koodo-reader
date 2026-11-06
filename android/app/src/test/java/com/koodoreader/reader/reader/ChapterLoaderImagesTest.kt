package com.koodoreader.reader.reader

import com.koodoreader.core.importer.EpubChapterLoader
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ChapterLoaderImagesTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `readInlineImage loads png bytes from epub zip`() {
        val epub = epubWithImage()
        val pngMagic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        EpubChapterLoader.open(epub).use { loader ->
            val item = loader.load(loader.descriptors().first().id)
            val block = item.blocks.first { it.imageSrc != null }
            val bytes = loader.readInlineImage(item.href, block.imageSrc!!)
            assertNotNull(bytes)
            assertArrayEquals(pngMagic, bytes!!.copyOfRange(0, 4))
        }
    }

    private fun epubWithImage(): File {
        val zip = File(dir, "img.epub")
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4.toByte(),
            0x89.toByte(), 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41, 0x54,
            0x78, 0x9C.toByte(), 0x63, 0x00, 0x01, 0x00, 0x00, 0x05, 0x00, 0x01,
            0x0D, 0x0A, 0x2D, 0xB4.toByte(), 0x00, 0x00, 0x00, 0x00,
            0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
        )
        ZipOutputStream(zip.outputStream()).use { zos ->
            fun put(path: String, bytes: ByteArray) {
                zos.putNextEntry(ZipEntry(path))
                zos.write(bytes)
                zos.closeEntry()
            }
            put(
                "mimetype",
                "application/epub+zip".toByteArray(),
            )
            put(
                "META-INF/container.xml",
                """
                <?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
                """.trimIndent().toByteArray(),
            )
            put("OEBPS/content.opf", opf().toByteArray())
            put("OEBPS/ch1.xhtml", """<html><body><p>Hi</p><img src="images/p.png"/></body></html>""".toByteArray())
            put("OEBPS/images/p.png", png)
        }
        return zip
    }

    private fun opf(): String = """
        <?xml version="1.0"?>
        <package version="2.0" xmlns="http://www.idpf.org/2007/opf">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
            <dc:title>T</dc:title>
          </metadata>
          <manifest>
            <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
            <item id="img" href="images/p.png" media-type="image/png"/>
          </manifest>
          <spine>
            <itemref idref="c1"/>
          </spine>
        </package>
    """.trimIndent()
}
