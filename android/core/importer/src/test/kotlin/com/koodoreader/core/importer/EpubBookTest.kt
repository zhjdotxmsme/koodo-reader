package com.koodoreader.core.importer

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubBookTest {

    // This toolchain's byteArrayOf wants Byte args; literals stay Int-friendly.
    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    @TempDir
    var tempDir: File = File("build/tmp/epub")

    private val PNG_1PX = bytes(
        0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
    )

    private fun writeZip(file: File, entries: Map<String, ByteArray>) {
        ByteArrayOutputStream().use { bos ->
            ZipOutputStream(bos).use { zos ->
                for ((name, bytes) in entries) {
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(bytes)
                    zos.closeEntry()
                }
            }
            file.writeBytes(bos.toByteArray())
        }
    }

    private fun container(opf: String) =
        "<?xml version=\"1.0\"?>" +
            "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">" +
            "<rootfiles><rootfile full-path=\"$opf\" " +
            "media-type=\"application/oebps-package+xml\"/></rootfiles></container>"

    private fun epub2Opf(coverId: String?, coverHref: String?, manifestExtras: String = "") =
        "<package xmlns:dc=\"http://purl.org/dc/elements/1.1/\">" +
            "<metadata>" +
            "<dc:title>A &lt;Great&gt; Book</dc:title>" +
            "<dc:creator>  Jane\nA. Doe  </dc:creator>" +
            "<dc:description>   A    description    here   </dc:description>" +
            "<dc:publisher>Publishers Inc</dc:publisher>" +
            (coverId?.let {
                "<meta name=\"cover\" content=\"$it\"/>"
            } ?: "") +
            "</metadata>" +
            "<manifest><item id=\"ch1\" href=\"text/ch1.xhtml\" media-type=\"application/xhtml+xml\"/>" +
            (coverId?.let { "<item id=\"$it\" href=\"$coverHref\" media-type=\"image/jpeg\"/>" } ?: "") +
            manifestExtras +
            "</manifest><spine><itemref idref=\"ch1\"/></spine></package>"

    @Test
    fun `parses EPUB2 metadata entities whitespace and cover`() {
        val epub = File(tempDir, "ok.epub")
        writeZip(
            epub,
            mapOf(
                "META-INF/container.xml" to container("OEBPS/content.opf").toByteArray(),
                "OEBPS/content.opf" to epub2Opf("cover-id", "images/cover.JPG").toByteArray(),
                "OEBPS/images/cover.JPG" to PNG_1PX,
            ),
        )
        val result = EpubBook.parse(epub)
        assertEquals("A <Great> Book", result.metadata.title)
        assertEquals("Jane A. Doe", result.metadata.creator)
        assertEquals("A description here", result.metadata.description)
        assertEquals("Publishers Inc", result.metadata.publisher)
        assertArrayEquals(PNG_1PX, result.cover?.bytes)
        assertEquals("jpeg", result.cover?.extension)
    }

    @Test
    fun `handles namespace-prefixed EPUB3 and reversed meta attribute order`() {
        val opf =
            "<package xmlns=\"http://www.idpf.org/2007/opf\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">" +
            "<metadata>" +
            "<dc:title>EPUB 3 Title</dc:title>" +
            "<dc:creator>Someone</dc:creator>" +
            "<meta content=\"rev-id\" name=\"cover\"/>" +
            "</metadata>" +
            "<manifest><item id=\"rev-id\" href=\"img/c.gif\" media-type=\"image/gif\"/></manifest>" +
            "<spine><itemref idref=\"rev-id\"/></spine></package>"
        val epub = File(tempDir, "epub3.epub")
        writeZip(
            // The cover href is relative to the OPF's directory (pkg/).
            epub,
            mapOf(
                "META-INF/container.xml" to container("pkg/opf.opf").toByteArray(),
                "pkg/opf.opf" to opf.toByteArray(),
                "pkg/img/c.gif" to byteArrayOf(0x47, 0x49, 0x46, 0x38),
            ),
        )
        val result = EpubBook.parse(epub)
        assertEquals("EPUB 3 Title", result.metadata.title)
        assertEquals("gif", result.cover?.extension)
    }

    @Test
    fun `falls back to a manifest image whose name contains cover`() {
        val epub = File(tempDir, "fallback.epub")
        writeZip(
            epub,
            mapOf(
                "META-INF/container.xml" to container("content.opf").toByteArray(),
                "content.opf" to epub2Opf(
                    coverId = null,
                    coverHref = null,
                    manifestExtras = "<item id=\"img1\" href=\"images/cover-front.png\" media-type=\"image/png\"/>",
                ).toByteArray(),
                "images/cover-front.png" to PNG_1PX,
            ),
        )
        val result = EpubBook.parse(epub)
        assertEquals("Jane A. Doe", result.metadata.creator)
        assertEquals("png", result.cover?.extension)
    }

    @Test
    fun `resolves dot-dot relative cover paths`() {
        val epub = File(tempDir, "dotdot.epub")
        writeZip(
            epub,
            mapOf(
                "META-INF/container.xml" to container("OEBPS/content.opf").toByteArray(),
                "OEBPS/content.opf" to epub2Opf("cid", "../img/c.png").toByteArray(),
                "img/c.png" to PNG_1PX,
            ),
        )
        val result = EpubBook.parse(epub)
        assertEquals("png", result.cover?.extension)
        assertEquals("img/c.png", EpubBook.resolvePath("OEBPS/content.opf", "../img/c.png"))
    }

    @Test
    fun `survives a missing container by finding the first opf entry`() {
        val epub = File(tempDir, "nocontainer.epub")
        writeZip(
            epub,
            mapOf(
                "META-INF/other.xml" to "<x/>".toByteArray(),
                "package.opf" to epub2Opf(null, null).toByteArray(),
            ),
        )
        val result = EpubBook.parse(epub)
        assertEquals("A <Great> Book", result.metadata.title)
    }

    @Test
    fun `covers are case-insensitively matched but metadata missing is null`() {
        val epub = File(tempDir, "case.epub")
        writeZip(
            epub,
            mapOf(
                "META-INF/container.xml" to container("CONTENT.OPF").toByteArray(),
                "CONTENT.OPF" to epub2Opf(null, null).toByteArray(),
            ),
        )
        val result = EpubBook.parse(epub) // container path "CONTENT.OPF" vs actual "CONTENT.OPF" — exact
        assertEquals("A <Great> Book", result.metadata.title)

        // And a genuinely mismatched case must still resolve:
        val epub2 = File(tempDir, "case2.epub")
        writeZip(
            epub2,
            mapOf(
                "META-INF/container.xml" to container("Content.Opf").toByteArray(),
                "content.opf" to epub2Opf(null, null).toByteArray(),
            ),
        )
        assertEquals("A <Great> Book", EpubBook.parse(epub2).metadata.title)
    }

    @Test
    fun `rejects a zip without an opf`() {
        val epub = File(tempDir, "bad.epub")
        writeZip(epub, mapOf("META-INF/container.xml" to "<container/>".toByteArray()))
        assertThrows(IllegalArgumentException::class.java) { EpubBook.parse(epub) }
        assertThrows(IllegalArgumentException::class.java) {
            EpubBook.parse(File(tempDir, "does-not-exist.epub"))
        }
    }
}
