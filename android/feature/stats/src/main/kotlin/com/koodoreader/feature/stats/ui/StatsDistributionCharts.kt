// feature/stats — distribution charts (format ring / 24h bars) and ranking rows.
package com.koodoreader.feature.stats.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.koodoreader.core.ui.theme.LocalKoodoChartColors
import com.koodoreader.feature.stats.BookReadingRank
import com.koodoreader.feature.stats.FormatShare
import com.koodoreader.feature.stats.StatsAggregator

/** Reading-time share per format: Canvas ring + legend. */
@Composable
fun FormatRingChart(shares: List<FormatShare>, modifier: Modifier = Modifier) {
    if (shares.isEmpty()) return
    val palette = LocalKoodoChartColors.current
    val sliceColors = palette.heatmap.ifEmpty { listOf(palette.line) }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(120.dp)) {
            var start = -90f
            shares.forEachIndexed { index, share ->
                val sweep = (share.fraction * 360f).toFloat()
                if (sweep > 0f) {
                    drawArc(
                        color = sliceColors[index % sliceColors.size],
                        startAngle = start,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = Offset(8f, 8f),
                        size = Size(size.width - 16f, size.height - 16f),
                        style = Stroke(width = 18f),
                    )
                }
                start += sweep
            }
        }
        Column(modifier = Modifier.padding(start = 16.dp)) {
            shares.forEachIndexed { index, share ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .background(sliceColors[index % sliceColors.size], CircleShape),
                    )
                    Text(
                        text = "%s %.0f%%".format(share.format, share.fraction * 100),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

/** 24 hourly buckets as a Canvas bar chart. */
@Composable
fun HourBarChart(buckets: List<Long>, modifier: Modifier = Modifier) {
    if (buckets.isEmpty()) return
    val palette = LocalKoodoChartColors.current
    val bar = palette.line
    val grid = palette.grid
    Canvas(modifier = modifier.fillMaxWidth().height(140.dp)) {
        val max = buckets.max().coerceAtLeast(1L)
        val slot = size.width / buckets.size
        val barWidth = slot * 0.6f
        drawLine(grid, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f))
        buckets.forEachIndexed { hour, seconds ->
            val height = (seconds.toFloat() / max) * (size.height - 8f)
            if (height > 0f) {
                drawRect(
                    color = bar,
                    topLeft = Offset(hour * slot + (slot - barWidth) / 2f, size.height - height),
                    size = Size(barWidth, height),
                )
            }
        }
    }
}

/** Top-10 books by reading time (already sorted by the aggregator). */
@Composable
fun BookRankingList(ranking: List<BookReadingRank>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        ranking.forEachIndexed { index, rank ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 4.dp),
            ) {
                Text(
                    text = "${index + 1}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = rank.title.ifEmpty { rank.bookKey },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text(
                    text = StatsAggregator.formatTime(rank.seconds),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
