package com.koodoreader.reader.pdfhost

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.koodoreader.engine.pdf.PdfSnapshotExporter
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * PdfRenderer-backed implementation of [PdfSnapshotExporter] (P3).
 *
 * The PDF reader's main rendering path is pdf.js inside the engine
 * WebView (see docs/android-pdf-poc.md). Framework `PdfRenderer` exists
 * as a zero-dependency fallback for:
 *   - **"Share current page"** — rasterise the visible page to PNG/JPEG;
 *   - **Print** — feed a bitmap into the system print framework.
 *
 * Rationale: `PdfRenderer` ships with the platform, so it adds no .so
 * footprint (and is therefore 16KB-page-safe by definition). Its render
 * path is significantly faster than pdf.js for a one-shot raster of a
 * single page — exactly the "snapshot" shape we want.
 *
 * Limitations: `PdfRenderer` doesn't expose the document's text layer, so
 * it cannot be used for the reader's main view. It is a print/export
 * sidekick only.
 *
 * Usage:
 *   val exporter = PdfRendererSnapshot(bookFile)   // one snapshot = one file
 *   exporter.export(bookFile.path, pageNumber = 12, Spec(targetWidthPx = 1024))
 */
class PdfRendererSnapshot(private val pdf: File) : PdfSnapshotExporter {

    override fun export(pdfPath: String, pageNumber: Int, spec: PdfSnapshotExporter.Spec): PdfSnapshotExporter.Result {
        // The snapshot is bound to a single file (PdfRenderer instances
        // are file-scoped); we still accept the path argument so the
        // interface contract doesn't leak this constraint.
        if (pdfPath != pdf.path) {
            // Allow a trailing-slash or case difference; fall back to
            // the bound file when caller mismatches, but log it.
            android.util.Log.w(TAG, "snapshot path mismatch: requested=$pdfPath bound=${pdf.path}")
        }
        return ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                require(pageNumber in 1..renderer.pageCount) {
                    "pageNumber out of range (was $pageNumber, count=${renderer.pageCount})"
                }
                renderer.openPage(pageNumber - 1).use { page ->
                    val (w, h) = PdfSnapshotExporter.computeSize(
                        page.width.toFloat(), page.height.toFloat(), spec,
                    )
                    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    try {
                        if (!spec.keepBackground) bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        val bytes = encode(bitmap, spec)
                        PdfSnapshotExporter.Result(
                            data = bytes,
                            meta = PdfSnapshotExporter.Meta(
                                pageWidthPx = w,
                                pageHeightPx = h,
                                format = spec.format,
                                bytes = bytes.size,
                            ),
                        )
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }
    }

    private fun encode(bitmap: Bitmap, spec: PdfSnapshotExporter.Spec): ByteArray {
        val out = ByteArrayOutputStream()
        val format = when (spec.format) {
            PdfSnapshotExporter.Spec.Format.PNG -> Bitmap.CompressFormat.PNG
            PdfSnapshotExporter.Spec.Format.JPEG -> Bitmap.CompressFormat.JPEG
        }
        bitmap.compress(format, spec.jpegQuality, out)
        return out.toByteArray()
    }

    private companion object {
        const val TAG = "PdfRendererSnapshot"
    }
}