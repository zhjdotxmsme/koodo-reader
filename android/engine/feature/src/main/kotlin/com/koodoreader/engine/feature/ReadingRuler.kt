package com.koodoreader.engine.feature

import kotlin.math.floor

/**
 * Reading-ruler configuration.
 *
 * Desktop config keys (verified by read-only search of `src/`):
 *  - `readingRulerLineHeight`       — slider 0…20, default 3, in **line units**
 *    (`src/components/readerSettings/settingSwitch/component.tsx`).
 *  - `readingRulerBackgroundOpacity` — slider 0…1, default 0.5.
 *
 * @param lineHeight   ruler height in lines (desktop `readingRulerLineHeight`).
 * @param lineHeightPx pixel height of one text line, supplied by the renderer.
 * @param offset       vertical offset in px applied to the ruler centre.
 * @param opacity      background opacity (desktop `readingRulerBackgroundOpacity`).
 * @param followFinger when true the ruler tracks the finger/pointer; otherwise it
 *   stays centred in the viewport (plus [offset]).
 * @param minHeightPx  floor so `lineHeight = 0` still yields something touchable.
 */
data class ReadingRulerConfig(
    val lineHeight: Float = DEFAULT_LINE_HEIGHT,
    val lineHeightPx: Float = DEFAULT_LINE_HEIGHT_PX,
    val offset: Float = 0f,
    val opacity: Float = DEFAULT_OPACITY,
    val followFinger: Boolean = false,
    val minHeightPx: Float = MIN_HEIGHT_PX,
) {
    /** Ruler height in px. */
    val heightPx: Float get() = maxOf(minHeightPx, lineHeight * lineHeightPx)

    /** [opacity] clamped into the desktop 0…1 range. */
    val safeOpacity: Float get() = opacity.coerceIn(0f, 1f)

    companion object {
        const val DEFAULT_LINE_HEIGHT = 3f
        const val DEFAULT_LINE_HEIGHT_PX = 24f
        const val DEFAULT_OPACITY = 0.5f
        const val MIN_HEIGHT_PX = 8f
    }
}

/**
 * The hit region the ruler occupies, in viewport coordinates (y grows downward).
 *
 * @param top    top edge in px.
 * @param bottom bottom edge in px.
 * @param height rendered height in px.
 */
data class RulerArea(val top: Float, val bottom: Float, val height: Float) {
    val centerY: Float get() = (top + bottom) / 2f

    /** True when [y] falls inside the ruler band (edges inclusive). */
    fun contains(y: Float): Boolean = height > 0f && y >= top && y <= bottom

    companion object {
        val EMPTY = RulerArea(0f, 0f, 0f)
    }
}

/**
 * Reading ruler geometry ("阅读尺"): where the highlight band sits for a given
 * finger position, what it hits, and which reading position it implies.
 *
 * Pure functions only — the Compose layer owns the overlay and the touch
 * handling; this object owns the arithmetic, which is what the tests pin down.
 *
 * Reference: kookit `readingRulerUtil` (docs/android-native-migration.md, P6).
 */
object ReadingRuler {

    /**
     * Resolve the band for a pointer at [fingerY].
     *
     * When [ReadingRulerConfig.followFinger] is false the ruler ignores
     * [fingerY] and stays centred in the viewport. Either way the band is
     * clamped so it never leaves `[0, viewportHeight]`.
     */
    fun resolve(
        fingerY: Float,
        viewportHeight: Float,
        config: ReadingRulerConfig = ReadingRulerConfig(),
    ): RulerArea {
        if (viewportHeight <= 0f) return RulerArea.EMPTY

        val height = config.heightPx.coerceAtMost(viewportHeight)
        val rawCenter =
            if (config.followFinger) fingerY + config.offset
            else viewportHeight / 2f + config.offset

        val maxTop = (viewportHeight - height).coerceAtLeast(0f)
        val top = (rawCenter - height / 2f).coerceIn(0f, maxTop)
        return RulerArea(top, top + height, height)
    }

    /** Convenience hit test for a pointer at [y] against [area]. */
    fun hits(area: RulerArea, y: Float): Boolean = area.contains(y)

    /**
     * Reading position implied by the ruler centre, as a 0…1 fraction of the
     * viewport (used to keep the scroll position in sync in follow mode).
     */
    fun progressOf(area: RulerArea, viewportHeight: Float): Float {
        if (viewportHeight <= 0f || area.height <= 0f) return 0f
        return (area.centerY / viewportHeight).coerceIn(0f, 1f)
    }

    /**
     * 0-based index of the first text line covered by the ruler.
     *
     * @param lineHeightPx height of one line; `<= 0` yields 0.
     */
    fun lineIndexOf(area: RulerArea, lineHeightPx: Float): Int {
        if (lineHeightPx <= 0f || area.height <= 0f) return 0
        return floor(area.top / lineHeightPx).toInt().coerceAtLeast(0)
    }

    /** How many whole lines the band covers (at least 1 when it is visible). */
    fun lineSpanOf(area: RulerArea, lineHeightPx: Float): Int {
        if (lineHeightPx <= 0f || area.height <= 0f) return 0
        return floor(area.height / lineHeightPx).toInt().coerceAtLeast(1)
    }
}
