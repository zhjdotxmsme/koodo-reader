package com.koodoreader.core.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.koodoreader.core.designsystem.MotionTokens
import com.koodoreader.core.designsystem.SpaceTokens
import com.koodoreader.core.ui.theme.KoodoMotion
import kotlinx.coroutines.delay

@Composable
fun Modifier.koodoPressScale(): Modifier = composed {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) MotionTokens.PRESS_SCALE else 1f,
        animationSpec = tween(MotionTokens.FAST, easing = KoodoMotion.Standard),
        label = "koodoPressScale",
    )
    this.graphicsLayer { scaleX = scale; scaleY = scale }.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            pressed = true
            waitForUpOrCancellation()
            pressed = false
        }
    }
}

@Composable
fun Modifier.staggeredEntrance(index: Int, play: Boolean): Modifier = composed {
    if (!play) return@composed this
    val progress = remember(play) { Animatable(0f) }
    LaunchedEffect(play) {
        delay(index.coerceAtMost(12) * MotionTokens.STAGGER.toLong())
        progress.animateTo(1f, tween(MotionTokens.PAGE, easing = KoodoMotion.Decelerate))
    }
    this.graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * SpaceTokens.md.dp.toPx()
    }
}
