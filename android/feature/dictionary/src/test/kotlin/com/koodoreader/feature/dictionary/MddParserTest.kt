package com.koodoreader.feature.dictionary

import com.koodoreader.feature.dictionary.mdx.MdictFixtureWriter
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MDD (resource container) reading — the companion file a definition's images / CSS
 * / audio live in.
 *
 * Reference: `node_modules/js-mdict/dist/esm/mdd.js:11-30` (`locate` returns base64).
 */
class MddParserTest {

    private val css = "body{margin:0}".toByteArray()
    private val png = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4,
    )
    private val resources = listOf(
        "\\style.css" to css,
        "\\img\\logo.png" to png,
    )

    private fun mddBytes() = MdictFixtureWriter.writeMdd(resources)

    @Test
    fun `locates resources and reports a mime type`() {
        MddParser.open(mddBytes(), "p6.mdd").use { mdd ->
            assertEquals(2, mdd.keywordCount)

            val style = mdd.locate("\\style.css")
            assertNotNull(style)
            assertEquals("text/css", style!!.mimeType)
            assertArrayEquals(css, style.bytes)
            // `mdd.js:2-4` — the reference hands back base64.
            assertEquals(Base64.getEncoder().encodeToString(css), style.base64())

            val logo = mdd.locate("\\img\\logo.png")
            assertNotNull(logo)
            assertEquals("image/png", logo!!.mimeType)
            assertArrayEquals(png, logo.bytes)
            assertEquals("iVBORw0KGgoBAgME", logo.base64())
        }
    }

    @Test
    fun `unknown resource returns null`() {
        MddParser.open(mddBytes(), "p6.mdd").use { mdd ->
            assertNull(mdd.locate("\\img\\missing.png"))
            assertNull(mdd.locateBytes("nope.css"))
        }
    }

    @Test
    fun `resource keys are always utf16 in an mdd`() {
        MddParser.open(mddBytes(), "p6.mdd").use { mdd ->
            // mdict-base.js:418-421 — the extension forces UTF-16LE even if the header lies.
            assertEquals(com.koodoreader.feature.dictionary.mdx.MdictEncoding.UTF16LE, mdd.meta.encoding)
            assertTrue(mdd.keywords.contains("\\style.css"))
        }
    }

    /**
     * Definitions link with whatever spelling the compiler emitted; the container may
     * use forward slashes, a missing leading backslash or different case.
     */
    @Test
    fun `flexible lookup tolerates slash and case differences`() {
        MddParser.open(mddBytes(), "p6.mdd").use { mdd ->
            assertNotNull(mdd.locateFlexible("img/logo.png"))
            assertNotNull(mdd.locateFlexible("/img/logo.PNG"))
            assertNotNull(mdd.locateFlexible("\\IMG\\LOGO.png"))
            assertNotNull(mdd.locateFlexible("style.css"))
            assertNull(mdd.locateFlexible("nothing/here.png"))
        }
    }

    @Test
    fun `mime types fall back to magic bytes without an extension`() {
        assertEquals("image/png", MimeTypes.sniff(png))
        assertEquals("image/jpeg", MimeTypes.sniff(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0)))
        assertEquals("image/gif", MimeTypes.sniff("GIF89a....".toByteArray()))
        assertEquals("audio/ogg", MimeTypes.sniff("OggSxxxx".toByteArray()))
        assertEquals("application/octet-stream", MimeTypes.sniff(byteArrayOf(1, 2, 3)))
        // Extension wins, magic bytes are the fallback (compilers emit extension-less keys).
        assertEquals("image/png", MimeTypes.of("\\img\\1", png))
        assertEquals("image/png", MimeTypes.of("\\img\\logo.png"))
    }
}
