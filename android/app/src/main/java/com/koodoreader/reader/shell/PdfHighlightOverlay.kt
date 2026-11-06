package com.koodoreader.reader.shell

import androidx.compose.ui.geometry.Rect
import com.koodoreader.engine.pdf.CfiPdfMapper

/** Maps desktop PDF bbox (points, bottom-left origin) into Compose overlay space. */
object PdfHighlightOverlay {

    fun toOverlayRect(
        range: CfiPdfMapper.PdfRange,
        pageWidthPt: Float,
        pageHeightPt: Float,
        dstWidthPx: Float,
        dstHeightPx: Float,
    ): Rect? {
        if (range.isPageLevel || range.width <= 0f || range.height <= 0f) return null
        if (pageWidthPt <= 0f || pageHeightPt <= 0f || dstWidthPx <= 0f || dstHeightPx <= 0f) return null
        val scaleX = dstWidthPx / pageWidthPt
        val scaleY = dstHeightPx / pageHeightPt
        val left = range.x * scaleX
        val top = (pageHeightPt - range.y - range.height) * scaleY
        val w = range.width * scaleX
        val h = range.height * scaleY
        if (w <= 0f || h <= 0f) return null
        return Rect(left, top, left + w, top + h)
    }
}
