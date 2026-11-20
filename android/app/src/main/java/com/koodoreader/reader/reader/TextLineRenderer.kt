package com.koodoreader.reader.reader

import android.graphics.Canvas
import android.graphics.Paint

/**
 * Draws a laid-out line with the SAME metrics the pagination engine measured
 * it with.
 *
 * ## Why this exists
 *
 * The engine measures a line character by character and adds the letter
 * spacing *between* characters (see `TextMeasurer`'s contract). But
 * `Canvas.drawText(text, x, y, paint)` draws the whole run with the font's
 * own advances and applies no tracking at all.
 *
 * The consequence was invisible but real: the engine thought a line was
 * 3px wider than the canvas actually drew it, so the justified column never
 * reached the right edge, and the measured line width did not match the drawn
 * one — which in turn made tap-to-select land on the wrong character near the
 * end of a line.
 *
 * Drawing character by character closes that gap: what is measured is what
 * is drawn.
 *
 * ## Justification
 *
 * When the engine marked a line as justified it computed a
 * [com.koodoreader.engine.layout.LayoutLine.justifyStretchPx] to distribute
 * over the line's gaps. This class applies exactly that stretch, over the
 * same gaps the engine counted:
 *  - the word gaps (spaces) when the line has any, otherwise
 *  - the gaps between ideographs (the inter-ideograph rule that makes a
 *    Chinese column flush on the right).
 */
object TextLineRenderer {

    /**
     * Draws [text] at [x] with the engine's metrics.
     *
     * @param letterSpacingPx the tracking added between two characters.
     * @param stretchPx the justification slack to distribute over the gaps.
     * @param wordGaps the number of inter-word spaces in the line.
     * @param ideographGaps the number of gaps between two ideographs.
     * @param paint the paint to draw with; its `textSize` is set by the caller.
     */
    fun drawLine(
        canvas: Canvas,
        text: String,
        x: Float,
        baselineY: Float,
        paint: Paint,
        letterSpacingPx: Float = 0f,
        stretchPx: Float = 0f,
        wordGaps: Int = 0,
        ideographGaps: Int = 0,
    ) {
        if (text.isEmpty()) return
        if (letterSpacingPx == 0f && stretchPx == 0f) {
            canvas.drawText(text, x, baselineY, paint)
            return
        }

        // How much extra space each gap receives.
        val gapCount = when {
            wordGaps > 0 -> wordGaps
            ideographGaps > 0 -> ideographGaps
            else -> 0
        }
        val perGapStretch = if (gapCount > 0) stretchPx / gapCount else 0f

        var cursor = x
        var wordGapsSeen = 0
        var ideographGapsSeen = 0
        for (i in text.indices) {
            if (i > 0) {
                // The tracking between two characters is always applied.
                cursor += letterSpacingPx
                // The justification slack goes to the word gaps when the line
                // has any, and to the ideograph gaps otherwise — the same
                // choice the engine made when it computed the stretch.
                val ch = text[i - 1]
                val isGap = when {
                    wordGaps > 0 -> {
                        if (isCollapsibleWhitespace(ch)) {
                            wordGapsSeen++
                            true
                        } else {
                            false
                        }
                    }
                    ideographGaps > 0 -> {
                        if (isIdeograph(ch) && isIdeograph(text[i])) {
                            ideographGapsSeen++
                            true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
                if (isGap) cursor += perGapStretch
            }
            val ch = text[i].toString()
            canvas.drawText(ch, cursor, baselineY, paint)
            cursor += paint.measureText(ch)
        }
    }

    /** True for a collapsible whitespace character (CSS `white-space: normal`). */
    private fun isCollapsibleWhitespace(ch: Char): Boolean = when (ch) {
        ' ', '\t', '\n', '\r', '\u000C', '\u000B' -> true
        else -> false
    }

    /**
     * True for a character the browser treats as breakable between neighbours:
     * CJK ideographs, kana, Hangul and fullwidth forms.
     */
    private fun isIdeograph(ch: Char): Boolean = when {
        ch in '\u3400'..'\u4DBF' -> true // CJK Extension A
        ch in '\u4E00'..'\u9FFF' -> true // CJK Unified Ideographs
        ch in '\uF900'..'\uFAFF' -> true // CJK Compatibility Ideographs
        ch in '\u3040'..'\u30FF' -> true // Hiragana + Katakana
        ch in '\u3005'..'\u3007' -> true
        ch in '\uAC00'..'\uD7A3' -> true // Hangul
        ch in '\uFF00'..'\uFF60' -> true // fullwidth forms
        else -> false
    }
}
