package com.koodoreader.engine.annotate

/**
 * Maps persisted highlight values to a paintable ARGB and a CSS hex.
 *
 * Room stores either a [HighlightColor] code (1..8) or a legacy packed color.
 * The old yellow was `0x99FFFF` (alpha 0), which Canvas draws as invisible.
 */
object HighlightPalette {
    const val LEGACY_INVISIBLE_YELLOW = 0x99FFFFL

    fun argb(color: HighlightColor, alpha: Int = 0x99): Long {
        val rgb = color.hex.removePrefix("#").toLong(16)
        return ((alpha.toLong() and 0xFF) shl 24) or rgb
    }

    fun paintArgb(stored: Long?): Long {
        if (stored == null || stored == LEGACY_INVISIBLE_YELLOW) return argb(HighlightColor.YELLOW)
        if (stored in 1L..8L) return argb(HighlightColor.fromCode(stored))
        val alpha = (stored ushr 24) and 0xFF
        return if (alpha != 0L) stored else argb(HighlightColor.YELLOW)
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
