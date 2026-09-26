package com.koodoreader.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.koodoreader.core.designsystem.ChartTokens

/**
 * Compose binding for [ChartTokens].
 *
 * Charts are data visualisation, not UI chrome, so they get their own local
 * rather than being squeezed into [androidx.compose.material3.ColorScheme] —
 * M3 has no role for "series line" or "heatmap step 3", and inventing one by
 * overloading e.g. `tertiary` would make the chart colour change for unrelated
 * reasons the next time someone adjusts the scheme.
 *
 * Provided by [KoodoTheme], so a `:feature:*` screen reads it without depending
 * on anything but `:core:ui`.
 */
@Immutable
data class KoodoChartColors(
    val line: Color,
    val grid: Color,
    val heatmapEmpty: Color,
    /** Least → most activity; ramp direction differs per mode (see ChartTokens). */
    val heatmap: List<Color>,
) {
    companion object {
        fun of(tokens: ChartTokens): KoodoChartColors = KoodoChartColors(
            line = Color(tokens.line.toInt()),
            grid = Color(tokens.grid.toInt()),
            heatmapEmpty = Color(tokens.heatmapEmpty.toInt()),
            heatmap = tokens.heatmap.map { Color(it.toInt()) },
        )

        fun light(): KoodoChartColors = of(ChartTokens.LIGHT)

        fun dark(): KoodoChartColors = of(ChartTokens.DARK)
    }
}

/** Static: the chart palette only changes with the light/dark mode. */
val LocalKoodoChartColors = staticCompositionLocalOf { KoodoChartColors.light() }
