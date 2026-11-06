package com.koodoreader.reader.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import com.koodoreader.engine.layout.LayoutLine
import com.koodoreader.engine.layout.reader.ReaderView

/**
 * Continuous scroll over the same [ReaderView.layout] lines as [TextPageView].
 */
@Composable
fun TextScrollView(
    view: ReaderView,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onBackground,
    backgroundColor: Color = MaterialTheme.colorScheme.background,
    highlightBlockColors: Map<Int, Long> = emptyMap(),
    pageImages: Map<String, Bitmap?> = emptyMap(),
    onLongPressLine: ((LayoutLine) -> Unit)? = null,
) {
    val lines = view.layout.lines
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    LaunchedEffect(view.location.chapterId, view.scrollTopPx) {
        if (lines.isEmpty()) return@LaunchedEffect
        val index = lines.indexOfFirst { it.y >= view.scrollTopPx - 1f }.coerceAtLeast(0)
        listState.scrollToItem(index)
    }
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor),
    ) {
        itemsIndexed(lines, key = { index, line -> "$index:${line.blockIndex}:${line.start}" }) { _, line ->
            androidx.compose.foundation.Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(
                        with(density) {
                            line.height.toDp().coerceAtLeast(1.dp)
                        },
                    )
                    .then(
                        if (onLongPressLine != null) {
                            Modifier.pointerInput(line.blockIndex, line.start) {
                                detectTapGestures(
                                    onLongPress = {
                                        if (line.imageSrc == null) onLongPressLine.invoke(line)
                                    },
                                )
                            }
                        } else {
                            Modifier
                        },
                    ),
            ) {
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
}
