package com.koodoreader.core.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import com.koodoreader.core.designsystem.MotionTokens

/**
 * Skeleton-placeholder shimmer: a diagonal light band sweeping across the
 * surface. Scan speed comes from [MotionTokens.SHIMMER]; colours come from the
 * scheme so it reads correctly in both light and dark.
 */
fun Modifier.shimmer(): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(MotionTokens.SHIMMER, easing = LinearEasing),
        ),
        label = "shimmerProgress",
    )
    background(
        brush = Brush.linearGradient(
            0f to MaterialTheme.colorScheme.surfaceVariant,
            0.5f to MaterialTheme.colorScheme.surfaceContainerHigh,
            1f to MaterialTheme.colorScheme.surfaceVariant,
            start = Offset(x * 480f, 0f),
            end = Offset((x + 1) * 480f, 480f),
        ),
    )
}
