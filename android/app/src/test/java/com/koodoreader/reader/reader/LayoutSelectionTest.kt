package com.koodoreader.reader.reader

import com.koodoreader.engine.layout.LayoutLine
import com.koodoreader.engine.layout.LayoutPosition
import com.koodoreader.engine.layout.TextAlign
import org.junit.Assert.assertEquals
import org.junit.Test

class LayoutSelectionTest {

    private fun line(text: String, start: Int, x: Float, width: Float) = LayoutLine(
        page = 0,
        column = 0,
        position = LayoutPosition(0, 0, start),
        blockIndex = 0,
        text = text,
        start = start,
        end = start + text.length,
        fontSizePx = 20f,
        indentPx = 0f,
        x = x,
        y = 0f,
        width = width,
        height = 24f,
        baselineY = 20f,
        align = TextAlign.LEFT,
        justifyStretchPx = 0f,
        wordGaps = 0,
        forced = false,
    )

    @Test
    fun `charOffsetInLine picks nearest character boundary`() {
        val l = line("abcdef", 0, 10f, 120f)
        val mono: (String, Int) -> Float = { _, end -> end * 10f }
        assertEquals(0, LayoutSelection.charOffsetInLine(l, 10f, mono))
        assertEquals(3, LayoutSelection.charOffsetInLine(l, 40f, mono))
    }
}
