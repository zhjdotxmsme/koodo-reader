package com.koodoreader.reader.shell

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.koodoreader.engine.gesture.GestureEngine
import com.koodoreader.engine.gesture.GestureMode
import com.koodoreader.engine.gesture.GestureResult
import com.koodoreader.engine.gesture.TapAction
import kotlin.math.abs

@Composable
fun ReaderGestureModifier(
    engine: GestureEngine,
    onResult: (GestureResult) -> Unit,
): Modifier {
    val density = LocalDensity.current
    val scale = remember(density) { 1f / density.density }

    var liveOffsetX by mutableFloatStateOf(0f)
    var liveOffsetY by mutableFloatStateOf(0f)
    var isDragging by mutableStateOf(false)
    var overscrollOffsetPx by mutableFloatStateOf(0f)
    var pendingOverscrollTarget by mutableFloatStateOf(0f)
    var overscrollTarget by mutableFloatStateOf(0f)
    var overscrollAxis by mutableIntStateOf(0) // 0=horizontal, 1=vertical

    fun handleResult(
        result: GestureResult,
        viewportW: Float,
        viewportH: Float,
    ) {
        when (result) {
            is GestureResult.PageTurn, is GestureResult.ScrollTo,
            is GestureResult.NoOp, is GestureResult.Selection,
            is GestureResult.ViewImage, is GestureResult.OpenLink,
            is GestureResult.Footnote, is GestureResult.Error ->
                onResult(result)

            is GestureResult.Overscroll -> {
                val amount = engine.state.overscrollAmountPx
                val dim = if (result.boundary == "top" || result.boundary == "bottom") viewportH else viewportW
                if (engine.overscroll.enabled && amount > 8f) {
                    overscrollOffsetPx = engine.overscroll.displacement(amount, dim)
                    pendingOverscrollTarget = overscrollOffsetPx
                    overscrollTarget = 0f
                    overscrollAxis = if (result.boundary == "top" || result.boundary == "bottom") 1 else 0
                }
                onResult(result)
            }
        }
    }

    val viewportW = engine.config.viewportWidthPx
    val viewportH = engine.config.viewportHeightPx
    val isHorizontal = engine.config.mode != GestureMode.SCROLL

    return Modifier
        .pointerInput(engine.config) {
            detectTapGestures(
                onTap = { tapOffset ->
                    val x = tapOffset.x * scale
                    val y = tapOffset.y * scale
                    val action = engine.onTap(x, y)
                    if (action != TapAction.NONE && isHorizontal) {
                        val delta = if (action == TapAction.NEXT_PAGE) 1 else -1
                        val target = (engine.state.currentPageIndex + delta).coerceIn(0, engine.state.totalPages - 1)
                        if (target != engine.state.currentPageIndex) {
                            engine.state.currentPageIndex = target
                            onResult(GestureResult.PageTurn(target))
                        }
                    }
                    // For center / NONE, don't consume -- let the content layer handle it.
                },
            )
        }
        .pointerInput(engine.config) {
            detectDragGestures(
                onDragStart = {
                    engine.onTouchDown(0f, 0f, 0L)
                    isDragging = true
                },
                onDrag = { change, dragAmount ->
                    change.consume()
                    if (!isDragging) return@detectDragGestures

                    if (isHorizontal) {
                        engine.onTouchMove(0f, 0f, 0L) // placeholder
                        // Simulate drag tracking: compute drag offset from change position
                        // The actual position is handled by the parent layout via offset modifiers
                        val curX = 0f // In a real implementation, this would track absolute position
                        // For now, apply a relative offset through the state
                    } else {
                        // SCROLL mode: track vertical drag
                        engine.onTouchMove(0f, 0f, 0L)
                    }
                },
                onDragEnd = {
                    isDragging = false
                    if (isHorizontal) {
                        // Simulate a small positive fling for demo purposes
                        val v0x = -1500f
                        val result = engine.onTouchUp(0f, 0f, velocityX = v0x, velocityY = 0f, 100L)
                        handleResult(result, viewportW, viewportH)
                    } else {
                        val v0y = -1500f
                        val result = engine.onTouchUp(0f, 0f, velocityX = 0f, velocityY = v0y, 100L)
                        handleResult(result, viewportW, viewportH)
                    }
                },
                onDragCancel = {
                    isDragging = false
                    liveOffsetX = 0f
                    liveOffsetY = 0f
                },
            )
        }
}

@Composable
fun rememberReaderPageState(
    engine: GestureEngine,
    onResult: (GestureResult) -> Unit,
): Pair<Modifier, Float> {
    // `ReaderGestureModifier` is a @Composable function returning a Modifier, so the
    // first component is typed `Modifier` (there is no type by that name). The offset
    // has to be remembered, otherwise it resets on every recomposition.
    val modifier = ReaderGestureModifier(engine, onResult)
    var offsetPx by remember { mutableFloatStateOf(0f) }
    return Pair(modifier, offsetPx)
}