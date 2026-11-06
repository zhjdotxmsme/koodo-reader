package com.koodoreader.reader.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import com.koodoreader.reader.shell.ReaderGestureCodec
import com.koodoreader.reader.shell.ReaderGestureRuntime
import com.koodoreader.engine.layout.LayoutLine
import com.koodoreader.engine.layout.reader.ReaderView
import com.koodoreader.reader.shell.ReaderPageGesture
import com.koodoreader.reader.shell.ReaderPageGestures

/**
 * Paginated native text viewport: one [ReaderView.page] of shared [ReaderView.layout].
 */
@Composable
fun TextPageView(
    view: ReaderView,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onBackground,
    backgroundColor: Color = MaterialTheme.colorScheme.background,
    canGoNext: Boolean = view.hasNextPage,
    canGoPrevious: Boolean = view.hasPrevPage,
    onPageTurn: (forward: Boolean) -> Unit,
    onToggleChrome: () -> Unit = {},
    highlightBlockColors: Map<Int, Long> = emptyMap(),
    pageImages: Map<String, Bitmap?> = emptyMap(),
    onLongPressLine: ((LayoutLine) -> Unit)? = null,
) {
    val lines = remember(view.layout, view.page) {
        view.layout.lines.filter { it.page == view.page }
    }
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(view.page, canGoNext, canGoPrevious, onLongPressLine) {
                detectTapGestures(
                    onLongPress = { offset ->
                        val line = lines.firstOrNull {
                            it.containsY(offset.y) && offset.x in it.x..it.rightPx
                        }
                        if (line != null && line.imageSrc == null) onLongPressLine?.invoke(line)
                    },
                ) { offset ->
                    val edge = ReaderGestureCodec.clampedTapEdge(ReaderGestureRuntime.config)
                    when (
                        ReaderPageGestures.onTap(offset.x, size.width.toFloat(), edge)
                    ) {
                        ReaderPageGesture.PREV_PAGE -> if (canGoPrevious) onPageTurn(false)
                        ReaderPageGesture.NEXT_PAGE -> if (canGoNext) onPageTurn(true)
                        ReaderPageGesture.TOGGLE_CHROME -> onToggleChrome()
                        ReaderPageGesture.SELECT -> Unit
                    }
                }
            }
            // 横滑翻页：与 EPUB Canvas / PDF 轨共用 ReaderPageGestures 的判定
            // （累计位移 48f 阈值，垂直分量大则让位滚动/揭示）。
            .pointerInput(view.page, canGoNext, canGoPrevious) {
                var dx = 0f
                var dy = 0f
                detectDragGestures(
                    onDragStart = { dx = 0f; dy = 0f },
                    onDragEnd = {
                        when (
                            ReaderPageGestures.onHorizontalSwipe(
                                dx,
                                dy,
                                enabled = ReaderGestureRuntime.config.swipePageTurn,
                            )
                        ) {
                            ReaderPageGesture.PREV_PAGE -> if (canGoPrevious) onPageTurn(false)
                            ReaderPageGesture.NEXT_PAGE -> if (canGoNext) onPageTurn(true)
                            else -> Unit
                        }
                    },
                    onDrag = { _, amount ->
                        dx += amount.x
                        dy += amount.y
                    },
                )
            },
    ) {
        drawRect(backgroundColor)
        for (line in lines) {
            TextLayoutDraw.drawLine(
                this,
                line,
                textColor,
                highlightColor = highlightBlockColors[line.blockIndex],
                pageImages = pageImages,
            )
        }
    }
}
