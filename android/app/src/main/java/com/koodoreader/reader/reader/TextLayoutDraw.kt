package com.koodoreader.reader.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import android.graphics.Bitmap
import android.graphics.Rect
import com.koodoreader.engine.layout.LayoutLine
import com.koodoreader.engine.annotate.SidemarkGeometry
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.min

internal object TextLayoutDraw {

    /**
     * @param highlightColor the 30%-alpha band behind the line, or null.
     * @param sidemarkColor the page-margin tick for that same highlight, or
     *   null. P1-10: the band shows *where* the note is, the tick shows that
     *   there is one without needing to look at the glyphs.
     * @param rtl mirror the tick to the right margin.
     * @param pageWidthPx the page width, so the tick never leaves the page.
     */
    fun drawLine(
        scope: DrawScope,
        line: LayoutLine,
        textColor: Color,
        highlightColor: Long? = null,
        pageImages: Map<String, Bitmap?> = emptyMap(),
        sidemarkColor: Long? = null,
        rtl: Boolean = false,
        pageWidthPx: Float = scope.size.width,
    ) {
        val canvas = scope.drawContext.canvas.nativeCanvas
        if (line.imageSrc != null && line.text.isEmpty()) {
            val bmp = pageImages[imageLineKey(line)]
            if (bmp != null) {
                val maxW = scope.size.width
                val scale = min(1f, maxW / bmp.width)
                val drawW = (bmp.width * scale).toInt()
                val drawH = (bmp.height * scale).toInt()
                val left = ((scope.size.width - drawW) / 2f).toInt()
                val top = line.y.toInt()
                canvas.drawBitmap(
                    bmp,
                    Rect(0, 0, bmp.width, bmp.height),
                    Rect(left, top, left + drawW, top + drawH),
                    null,
                )
            }
            return
        }
        if (highlightColor != null) {
            val bg = android.graphics.Paint().apply {
                color = highlightColor.toInt()
                style = android.graphics.Paint.Style.FILL
            }
            canvas.drawRect(
                line.x - 2f,
                line.y,
                line.rightPx + 2f,
                line.y + line.height,
                bg,
            )
            if (sidemarkColor != null) {
                SidemarkGeometry.tickLeftPxOrNull(
                    lineLeftPx = line.x,
                    lineRightPx = line.rightPx,
                    indentPx = line.indentPx,
                    rtl = rtl,
                    pageWidthPx = pageWidthPx,
                )?.let { left ->
                    val tick = android.graphics.Paint().apply {
                        color = sidemarkColor.toInt()
                        style = android.graphics.Paint.Style.FILL
                    }
                    canvas.drawRect(
                        left,
                        line.y,
                        left + SidemarkGeometry.WIDTH_PX,
                        line.y + line.height,
                        tick,
                    )
                }
            }
        }
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor.toArgb()
            textSize = line.fontSizePx
        }
        // Drawn through the shared renderer so the glyphs land exactly where
        // the engine measured them: the same letter spacing, and the same
        // justification slack. `Canvas.drawText` alone would apply neither.
        TextLineRenderer.drawLine(
            canvas = canvas,
            text = line.text,
            x = line.x,
            baselineY = line.baselineY,
            paint = paint,
            letterSpacingPx = line.letterSpacingPx,
            stretchPx = line.justifyStretchPx,
            wordGaps = line.wordGaps,
            ideographGaps = line.ideographGaps,
        )
    }
}
