package com.koodoreader.engine.annotate

/**
 * Maps persisted highlight values to reader paint and legacy CSS colors.
 *
 * Room stores either a [HighlightColor] code (1..8) or a legacy packed color.
 * The old yellow was `0x99FFFF` (alpha 0), which Canvas draws as invisible.
 */
object HighlightPalette {
    const val LEGACY_INVISIBLE_YELLOW = 0x99FFFFL
    const val READER_ALPHA = 0.30f
    private const val READER_ALPHA_INT = 0x4D

    fun argb(color: HighlightColor, alpha: Int = READER_ALPHA_INT): Long {
        val rgb = color.hex.removePrefix("#").toLong(16)
        return ((alpha.toLong() and 0xFF) shl 24) or rgb
    }

    fun paintArgb(stored: Long?): Long {
        val resolved = when {
            stored == null || stored == LEGACY_INVISIBLE_YELLOW -> argb(HighlightColor.YELLOW)
            stored in 1L..8L -> argb(HighlightColor.fromCode(stored))
            (stored ushr 24) and 0xFF != 0L -> stored
            else -> argb(HighlightColor.YELLOW)
        }
        return (READER_ALPHA_INT.toLong() shl 24) or (resolved and 0xFFFFFF)
    }

    fun readerCss(stored: Long?): String {
        val paint = paintArgb(stored)
        val red = (paint ushr 16) and 0xFF
        val green = (paint ushr 8) and 0xFF
        val blue = paint and 0xFF
        return "rgba($red,$green,$blue,$READER_ALPHA)"
    }

    fun css(stored: Long?): String {
        if (stored == null || stored == LEGACY_INVISIBLE_YELLOW) return HighlightColor.YELLOW.hex
        if (stored in 1L..8L) return HighlightColor.fromCode(stored).hex
        val alpha = (stored ushr 24) and 0xFF
        if (alpha == 0L) return HighlightColor.YELLOW.hex
        val rgb = (stored and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')
        return "#$rgb"
    }
}
