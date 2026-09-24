package com.koodoreader.core.importer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class MobiCoverTest {

    @TempDir
    lateinit var dir: File

    /**
     * Real-world fixture: a calibre-generated MOBI6 with an embedded cover
     * (PNG). Checked in as a test resource — hand-crafted PDB/MOBI/EXTH bytes
     * proved too easy to get subtly wrong (8-byte record entries, field
     * offsets), and a real file exercises the exact offsets the desktop
     * engine produces.
     */

    private fun sampleMobi(): File {
        val stream = javaClass.getResourceAsStream("/mobi/cover-sample.mobi")
            ?: error("missing test resource /mobi/cover-sample.mobi")
        val f = File(dir, "cover-sample.mobi")
        stream.use { input -> f.outputStream().use { input.copyTo(it) } }
        return f
    }

    @Test
    fun `extracts the embedded cover from a real calibre mobi`() {
        val cover = MobiCover.extract(sampleMobi())
        assertNotNull(cover, "real calibre MOBI should yield its cover")
        val ext = cover!!.extension
        assertTrue(ext == "jpeg" || ext == "png", "unexpected cover ext: $ext")
        assertTrue(cover.bytes.size > 100, "cover suspiciously small: ${cover.bytes.size}")
    }

    @Test
    fun `deterministic across repeated reads`() {
        val a = MobiCover.extract(sampleMobi())
        val b = MobiCover.extract(sampleMobi())
        assertEquals(a?.bytes?.size, b?.bytes?.size)
        assertEquals(a?.extension, b?.extension)
    }

    @Test
    fun `non mobi file degrades to null`() {
        val f = File(dir, "not.mobi")
        f.writeText("plain text")
        assertNull(MobiCover.extract(f))
    }

    @Test
    fun `truncated file degrades to null`() {
        val f = File(dir, "trunc.mobi")
        f.writeBytes(ByteArray(50))
        assertNull(MobiCover.extract(f))
    }
}
