// feature/stats — Compose UI: the native /stats screen (desktop parity).
//
// Desktop reference: src/pages/stats/{component.tsx,stats.css}. Layout, colour
// tokens, chart behaviour and heatmap geometry are ported 1:1; the numbers come
// from StatsAggregator (pure, unit-tested), the strings from the shell's
// desktop-parity translator.
//
// Composition contract (keeps the module dependency-free — :app depends on
// features, never the other way round):
//   StatsScreen(state, t = { key -> LocalI18n.t(key) }, onClose = { nav.popBackStack() })
//
// Strings use the DESKTOP keys ("Reading Stats", "Books read", "Total reading
// time", "Reading streak (days)", "Daily average", "Last 30 Days", "Bar Chart",
// "Line Chart", "Reading Activity", "Reading progress", "Word count") so the
// Android catalogs (verbatim copies of src/assets/locales/*.json, ADR-004)
// translate them without a new key namespace.
package com.koodoreader.feature.stats.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.koodoreader.core.ui.theme.KoodoCardShape
import com.koodoreader.core.ui.theme.KoodoShapes
import com.koodoreader.core.ui.theme.LocalKoodoChartColors
import com.koodoreader.feature.stats.DayPoint
import com.koodoreader.feature.stats.HeatmapCell
import com.koodoreader.feature.stats.StatsAggregator
import com.koodoreader.feature.stats.StatsSnapshot
import java.text.DateFormatSymbols

/** Desktop palette (`stats.css` + inline colours in component.tsx). */
data class StatsPalette(
    val background: Color,
    val card: Color,
    val text: Color,
    val line: Color,
    val grid: Color,
    val tabActiveBackground: Color,
    val tabActiveText: Color,
    val tabInactiveBackground: Color,
    val tabInactiveText: Color,
    val heatmapEmpty: Color,
    val heatmap: List<Color>,
)

/**
 * Theme-derived colours for the stats screen.
 *
 * This struct used to carry 22 hard-coded literals in two hand-written light and
 * dark sets (`StatsPalette.of(dark)`). It is now a BINDING: every value comes
 * from the shell theme (`MaterialTheme.colorScheme`) or from the design system's
 * chart tokens (`LocalKoodoChartColors`). Nothing here decides a colour any
 * more — which is exactly why `darkTheme` no longer needs threading through the
 * API: the theme already knows.
 *
 * Kept as a struct (instead of inlining `MaterialTheme.colorScheme` at ~40 call
 * sites) so this stays a re-homing rather than a rewrite of a 400-line screen.
 *
 * Chart colours deliberately do NOT come from `ColorScheme`: M3 has no role for
 * "series line" or "heatmap step 3", and overloading e.g. `tertiary` would make
 * the chart change colour for unrelated reasons later.
 */
@Composable
fun statsPalette(): StatsPalette {
    val cs = MaterialTheme.colorScheme
    val chart = LocalKoodoChartColors.current
    return StatsPalette(
        background = cs.background,
        card = cs.surfaceContainerLow,
        text = cs.onSurface,
        line = chart.line,
        grid = chart.grid,
        tabActiveBackground = cs.secondaryContainer,
        tabActiveText = cs.onSecondaryContainer,
        tabInactiveBackground = Color.Transparent,
        tabInactiveText = cs.onSurfaceVariant,
        heatmapEmpty = chart.heatmapEmpty,
        heatmap = chart.heatmap,
    )
}

/** Desktop `getHeatmapColor` + `legendLevels`. */
fun heatmapColor(level: Int, palette: StatsPalette): Color =
    if (level <= 0) palette.heatmapEmpty else palette.heatmap[(level - 1).coerceIn(0, 3)]

@Composable
fun StatsScreen(
    state: StatsUiState,
    modifier: Modifier = Modifier,
    t: (String) -> String = { it },
    onClose: () -> Unit = {},
    onChartTabSelected: (ChartTab) -> Unit = {},
    /**
     * False when this screen is mounted as a TOP-LEVEL tab (the shell's bottom
     * bar already provides navigation). A close affordance there would be a
     * second, conflicting way out of a screen that is not a drill-down.
     */
    showClose: Boolean = true,
) {
    val palette = statsPalette()
    Surface(modifier = modifier.fillMaxSize(), color = palette.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .horizontalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = t("Reading Stats"),
                    color = palette.text,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (showClose) {
                    TextButton(onClick = onClose) {
                        Text(text = "✕", color = palette.text, fontSize = 18.sp)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))

            if (state.isLoading) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            StatCards(state.snapshot, palette, t)
            Spacer(Modifier.height(24.dp))
            ChartSection(state, palette, t, onChartTabSelected)
            Spacer(Modifier.height(24.dp))
            HeatmapSection(state.snapshot, palette, t)
        }
    }
}

@Composable
private fun StatCards(snapshot: StatsSnapshot, palette: StatsPalette, t: (String) -> String) {
    val cards = listOf(
        snapshot.totalBooks.toString() to t("Books read"),
        StatsAggregator.formatTime(snapshot.totalSeconds) to t("Total reading time"),
        snapshot.longestStreak.toString() to t("Reading streak (days)"),
        StatsAggregator.formatTime(snapshot.avgDailySeconds) to t("Daily average"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        cards.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (value, label) ->
                    StatCard(value, label, palette, Modifier.weight(1f))
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatCard("${snapshot.totalWords}", t("Word count"), palette, Modifier.weight(1f))
        StatCard("${snapshot.averageProgress}%", t("Reading progress"), palette, Modifier.weight(1f))
    }
}

@Composable
private fun StatCard(
    value: String,
    label: String,
    palette: StatsPalette,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(palette.card, KoodoCardShape)
            .padding(horizontal = 16.dp, vertical = 20.dp),
    ) {
        Text(value, color = palette.text, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(label, color = palette.text.copy(alpha = 0.6f), fontSize = 13.sp)
    }
}

@Composable
private fun ChartSection(
    state: StatsUiState,
    palette: StatsPalette,
    t: (String) -> String,
    onChartTabSelected: (ChartTab) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.card, KoodoCardShape)
            .padding(16.dp),
    ) {
        Text(t("Last 30 Days"), color = palette.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChartTab.entries.forEach { tab ->
                val selected = tab == state.chartTab
                TextButton(
                    onClick = { onChartTabSelected(tab) },
                    modifier = Modifier.background(
                        if (selected) palette.tabActiveBackground else palette.tabInactiveBackground,
                        // Snapped from the previous ad-hoc 20.dp to the scale's
                        // `large`: closest on-scale step (-4dp) and the same one
                        // cards and controls use. An off-scale radius is exactly
                        // what the token layer exists to remove.
                        KoodoShapes.large,
                    ),
                ) {
                    Text(
                        text = t(if (tab == ChartTab.BAR) "Bar Chart" else "Line Chart"),
                        color = if (selected) palette.tabActiveText else palette.tabInactiveText,
                        fontSize = 13.sp,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        ThirtyDayChart(state.snapshot.last30Days, state.chartTab, palette, Modifier.fillMaxWidth().height(220.dp))
    }
}

/** Desktop `recharts` bar / area-line chart, hand-drawn on a Canvas. */
@Composable
private fun ThirtyDayChart(
    points: List<DayPoint>,
    tab: ChartTab,
    palette: StatsPalette,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = remember(palette) {
        TextStyle(fontSize = 10.sp, color = palette.text.copy(alpha = 0.5f))
    }
    Canvas(modifier) {
        if (points.isEmpty()) return@Canvas
        val axisHeight = 18.dp.toPx()
        val axisWidth = 26.dp.toPx()
        val plotWidth = size.width - axisWidth
        val plotHeight = size.height - axisHeight
        val maxMinutes = points.maxOf { it.minutes }.coerceAtLeast(1L).toFloat()
        val slot = plotWidth / points.size
        val barWidth = slot * 0.55f

        // Horizontal grid lines (desktop CartesianGrid: 3 dashed lines, no verticals).
        for (step in 0..3) {
            val y = plotHeight * step / 3f
            drawLine(
                color = palette.grid,
                start = Offset(axisWidth, y),
                end = Offset(size.width, y),
                strokeWidth = 1f,
            )
        }
        // Y axis: minutes, same as the desktop `tickFormatter` (`${v}m`).
        listOf(0f, 0.5f, 1f).forEach { fraction ->
            val value = kotlin.math.round(maxMinutes * (1f - fraction)).toInt()
            drawText(
                textMeasurer = measurer,
                text = "${value}m",
                style = labelStyle,
                topLeft = Offset(0f, plotHeight * fraction - 6.dp.toPx()),
            )
        }

        when (tab) {
            ChartTab.BAR -> points.forEachIndexed { index, point ->
                val height = plotHeight * (point.minutes / maxMinutes)
                drawRoundRect(
                    color = palette.text,
                    topLeft = Offset(axisWidth + index * slot + (slot - barWidth) / 2f, plotHeight - height),
                    size = Size(barWidth, height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f),
                )
            }

            ChartTab.LINE -> {
                val path = Path()
                points.forEachIndexed { index, point ->
                    val x = axisWidth + index * slot + slot / 2f
                    val y = plotHeight - plotHeight * (point.minutes / maxMinutes)
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color = palette.line, style = Stroke(width = 2.5.dp.toPx()))
                // Desktop draws the area under the line with a gradient; a flat
                // translucent fill is the Compose equivalent.
                val area = Path().apply {
                    addPath(path)
                    lineTo(axisWidth + (points.size - 0.5f) * slot, plotHeight)
                    lineTo(axisWidth + slot / 2f, plotHeight)
                    close()
                }
                drawPath(area, color = palette.line.copy(alpha = 0.18f))
            }
        }
        // X labels every 5th point (desktop `interval={4}`).
        points.forEachIndexed { index, point ->
            if (index % 5 == 0 || index == points.lastIndex) {
                drawText(
                    textMeasurer = measurer,
                    text = point.label,
                    style = labelStyle,
                    topLeft = Offset(axisWidth + index * slot, plotHeight + 4.dp.toPx()),
                )
            }
        }
    }
}

@Composable
private fun HeatmapSection(snapshot: StatsSnapshot, palette: StatsPalette, t: (String) -> String) {
    val aggregator = remember { StatsAggregator() }
    val weeks = remember(snapshot) { aggregator.heatmapWeeks(snapshot.heatmap) }
    val anchors = remember(weeks) { aggregator.heatmapMonthAnchors(weeks) }
    val monthNames = remember { DateFormatSymbols.getInstance().shortMonths }
    val cell = 13.dp
    val gap = 3.dp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.card, KoodoCardShape)
            .padding(16.dp),
    ) {
        Text(t("Reading Activity"), color = palette.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        Row {
            // Weekday labels (desktop shows Mon/Wed/Fri only).
            Column(
                verticalArrangement = Arrangement.spacedBy(gap),
                modifier = Modifier.padding(end = 6.dp),
            ) {
                Spacer(Modifier.height(14.dp)) // month-label row height
                listOf("", "Mon", "", "Wed", "", "Fri", "").forEach { label ->
                    Box(Modifier.size(cell), contentAlignment = Alignment.Center) {
                        Text(label, color = palette.text.copy(alpha = 0.55f), fontSize = 9.sp)
                    }
                }
            }
            // 52 week columns, horizontally scrollable exactly like the
            // desktop `.stats-heatmap-wrapper { overflow-x: auto }`.
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                weeks.forEachIndexed { index, week ->
                    Column(
                        verticalArrangement = Arrangement.spacedBy(gap),
                        modifier = Modifier.padding(end = gap),
                    ) {
                        Text(
                            text = anchors[index]?.let { monthNames[it.month - 1] } ?: "",
                            color = palette.text.copy(alpha = 0.55f),
                            fontSize = 9.sp,
                            maxLines = 1,
                        )
                        week.forEach { day ->
                            Box(
                                Modifier
                                    .size(cell)
                                    .background(
                                        if (day == null) Color.Transparent
                                        else heatmapColor(day.level, palette),
                                        // Heatmap cells: snapped 3dp -> extraSmall (4dp), the
                                        // smallest on-scale step.
                                        KoodoShapes.extraSmall,
                                    ),
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.align(Alignment.End),
        ) {
            StatsAggregator.HEATMAP_LEGEND_SECONDS.forEach { seconds ->
                Box(
                    Modifier
                        .size(cell)
                        .background(
                            heatmapColor(StatsAggregator.heatmapLevel(seconds), palette),
                            KoodoShapes.extraSmall,
                        ),
                )
            }
        }
    }
}

/** Desktop `formatTime` tooltip text of one heatmap cell. */
fun heatmapCellTooltip(cell: HeatmapCell): String =
    "${cell.day}: ${StatsAggregator.formatTime(cell.seconds)}"
