package com.koodoreader.core.designsystem

/**
 * Data-visualisation colours (reading-stats charts and the activity heatmap).
 *
 * Deliberately NOT part of [ColorTokens]'s semantic slots. Charts are neither
 * surfaces, text nor borders, and forcing them into the `ColorTokens` contrast
 * gates would be wrong: a grid line is *supposed* to sit just above the
 * background, and a heatmap ramp is a scale, not a text/background pair.
 *
 * Two properties here are real invariants and are asserted by `selfCheck`:
 *   - the heatmap has exactly [HEATMAP_LEVELS] steps, fully opaque, and its
 *     luminance is monotonic along the ramp;
 *   - the line colour is perceptibly distinct from the page behind it.
 *
 * Alpha IS used for [grid] and [heatmapEmpty] on purpose: those two must read as
 * a faint overlay on whatever surface they land on, so they are the one place in
 * the token layer where translucency is intended rather than a bug. They are
 * therefore never used as contrast-pair endpoints.
 *
 * Colours carried over from the previous `StatsPalette` so the visual result is
 * unchanged — this is a re-homing, not a restyle.
 */
data class ChartTokens(
    /** Series line / primary chart accent. */
    val line: Long,
    /** Chart grid lines. Translucent by design (see class doc). */
    val grid: Long,
    /** Heatmap cell for "no activity". Translucent by design. */
    val heatmapEmpty: Long,
    /**
     * Heatmap ramp, least → most activity. Ramp direction differs per mode:
     * on light backgrounds more activity is DARKER, on dark backgrounds it is
     * BRIGHTER. `ChartTokensTest` pins that.
     */
    val heatmap: List<Long>,
) {
    companion object {

        const val HEATMAP_LEVELS = 4

        val LIGHT = ChartTokens(
            line = 0xFFFF6B1A,
            grid = 0x14000000,
            heatmapEmpty = 0x12000000,
            heatmap = listOf(
                0xFF9BE9A8,
                0xFF40C463,
                0xFF30A14E,
                0xFF216E39,
            ),
        )

        val DARK = ChartTokens(
            line = 0xFFFFB066,
            grid = 0x14FFFFFF,
            heatmapEmpty = 0x12FFFFFF,
            heatmap = listOf(
                0xFF0E4429,
                0xFF006D32,
                0xFF26A641,
                0xFF39D353,
            ),
        )

        val ALL = listOf(LIGHT, DARK)

        /** The heatmap colour for a 0..[HEATMAP_LEVELS] activity level. */
        fun heatmapColor(tokens: ChartTokens, level: Int): Long =
            if (level <= 0) tokens.heatmapEmpty
            else tokens.heatmap[(level - 1).coerceIn(0, tokens.heatmap.size - 1)]
    }
}
