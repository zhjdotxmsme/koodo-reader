package com.koodoreader.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.koodoreader.core.importer.BookCover
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.min

/**
 * PDF cover extraction for the native import (P1 收口): render page 1 via the
 * framework [PdfRenderer] and encode as JPEG — no extra dependency. Any
 * failure degrades to null (placeholder cover), mirroring the other
 * extractors.
 */
object PdfCoverExtractor {

    private const val MAX_DIMENSION = 1024f
    private const val JPEG_QUALITY = 85

    fun extract(file: File): BookCover? = runCatching {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            val renderer = PdfRenderer(pfd)
            try {
                if (renderer.pageCount <= 0) return null
                val page = renderer.openPage(0)
                try {
                    val scale = min(
                        1f,
                        min(MAX_DIMENSION / page.width, MAX_DIMENSION / page.height),
                    )
                    val w = (page.width * scale).toInt().coerceAtLeast(1)
                    val h = (page.height * scale).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE) // PDFs are transparent by default
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val out = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                        BookCover(out.toByteArray(), "jpeg")
                    } finally {
                        bitmap.recycle()
                    }
                } finally {
                    page.close() // Page is not AutoCloseable below API 31
                }
            } finally {
                renderer.close()
            }
        }
    }.getOrNull()
}
