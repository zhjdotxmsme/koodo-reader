package com.koodoreader.engine.pdf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PdfSnapshotExporterTest {

    @Test
    fun letterAt1024() {
        val (w, h) = PdfSnapshotExporter.computeSize(612f, 792f, PdfSnapshotExporter.Spec(targetWidthPx = 1024))
        // longest side = 792; 612 * (1024/792) = 791.6 → 791
        assertEquals(791, w)
        assertEquals(1024, h)
    }

    @Test
    fun a4At1024() {
        val (w, h) = PdfSnapshotExporter.computeSize(595f, 842f, PdfSnapshotExporter.Spec(targetWidthPx = 1024))
        // longest = 842; 595 * (1024/842) = 723.6 → 723
        assertEquals(723, w)
        assertEquals(1024, h)
    }

    @Test
    fun noEnlargeForVeryLargePage() {
        val (w, h) = PdfSnapshotExporter.computeSize(8000f, 12000f, PdfSnapshotExporter.Spec(targetWidthPx = 1024))
        assertEquals(1024, w)
        assertEquals(1536, h)
    }

    @Test
    fun mimeAndExtension() {
        assertEquals("image/png", PdfSnapshotExporter.mimeType(PdfSnapshotExporter.Spec.Format.PNG))
        assertEquals("image/jpeg", PdfSnapshotExporter.mimeType(PdfSnapshotExporter.Spec.Format.JPEG))
        assertEquals("png", PdfSnapshotExporter.extension(PdfSnapshotExporter.Spec.Format.PNG))
        assertEquals("jpg", PdfSnapshotExporter.extension(PdfSnapshotExporter.Spec.Format.JPEG))
    }

    @Test
    fun specValidation() {
        // Width / quality must be in spec bounds.
        val thrown = runCatching {
            PdfSnapshotExporter.Spec(targetWidthPx = 0)
        }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException, "got $thrown")

        val thrown2 = runCatching {
            PdfSnapshotExporter.Spec(jpegQuality = 0)
        }.exceptionOrNull()
        assertTrue(thrown2 is IllegalArgumentException, "got $thrown2")
    }
}