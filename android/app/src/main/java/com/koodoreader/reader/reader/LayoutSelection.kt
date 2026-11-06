package com.koodoreader.reader.reader

import android.graphics.Paint
import com.koodoreader.engine.layout.LayoutLine
import com.koodoreader.engine.layout.LayoutResult
import kotlin.math.abs

/** Character-offset selection helpers for canvas layout lines. */
object LayoutSelection {

    fun charOffsetInLine(
        line: LayoutLine,
        x: Float,
        prefixWidth: (text: String, endIndex: Int) -> Float,
    ): Int {
        if (line.text.isEmpty()) return 0
        val localX = (x - line.x).coerceIn(0f, line.width + line.justifyStretchPx)
        var lo = 0
        var hi = line.text.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (prefixWidth(line.text, mid) <= localX) lo = mid else hi = mid - 1
        }
        return lo.coerceIn(0, line.text.length)
    }

    fun charOffsetInLine(line: LayoutLine, x: Float, paint: Paint): Int {
        paint.textSize = line.fontSizePx
        return charOffsetInLine(line, x) { text, end -> paint.measureText(text, 0, end) }
    }

    fun globalOffsetAt(
        layout: LayoutResult,
        page: Int,
        x: Float,
        y: Float,
        paint: Paint,
    ): Int? {
        val pos = layout.positionAt(x, y) ?: return null
        val line = layout.lineAt(pos) ?: return null
        if (line.page != page || line.imageSrc != null) return null
        return line.start + charOffsetInLine(line, x, paint)
    }

    fun excerptBetween(
        layout: LayoutResult,
        page: Int,
        start: Int,
        end: Int,
    ): String {
        val a = minOf(start, end)
        val b = maxOf(start, end)
        if (a >= b) return ""
        return layout.lines
            .asSequence()
            .filter { it.page == page && it.imageSrc == null }
            .filter { it.end > a && it.start < b }
            .joinToString("") { line ->
                val from = (a - line.start).coerceAtLeast(0)
                val to = (b - line.start).coerceAtMost(line.text.length)
                if (from >= to) "" else line.text.substring(from, to)
            }
    }

    fun primaryLine(
        layout: LayoutResult,
        page: Int,
        offset: Int,
    ): LayoutLine? = layout.lines
        .filter { it.page == page && it.imageSrc == null }
        .firstOrNull { offset in it.start until it.end }
        ?: layout.lines
            .filter { it.page == page && it.imageSrc == null }
            .minByOrNull { abs(it.start - offset) }
}
