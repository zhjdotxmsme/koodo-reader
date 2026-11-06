package com.koodoreader.reader.reader

import androidx.compose.foundation.Canvas
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
