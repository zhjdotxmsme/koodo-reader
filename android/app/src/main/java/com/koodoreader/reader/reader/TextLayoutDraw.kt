package com.koodoreader.reader.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import android.graphics.Bitmap
import android.graphics.Rect
import com.koodoreader.engine.layout.LayoutLine
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.min

internal object TextLayoutDraw {

    fun drawLine(
        scope: DrawScope,
        line: LayoutLine,
        textColor: Color,
        highlightColor: Long? = null,
        pageImages: Map<String, Bitmap?> = emptyMap(),
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
        }
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor.toArgb()
            textSize = line.fontSizePx
        }
        canvas.drawText(line.text, line.x, line.baselineY, paint)
    }
}
