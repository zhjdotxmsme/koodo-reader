package com.koodoreader.core.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.lerp
import com.koodoreader.core.designsystem.MotionTokens

/**
 * Crossfades the shell palette whenever the target scheme flips (light ↔ dark,
 * live from the settings page — no activity restart, no hard cut).
 *
 * [lerpSchemes] is an instantaneous function called every frame; the returned
 * scheme is NOT remembered, only the two endpoints and the fraction are.
 */
@Composable
fun rememberAnimatedScheme(target: ColorScheme): ColorScheme {
    val fraction = remember { Animatable(1f) }
    var from by remember { mutableStateOf(target) }

    LaunchedEffect(target) {
        if (target != from) {
            // Freeze whatever is currently on screen as the new start point, so
            // an interrupted transition continues from where it stopped.
            from = lerpSchemes(from, target, fraction.value)
            fraction.snapTo(0f)
            fraction.animateTo(1f, tween(MotionTokens.SLOW, easing = KoodoMotion.Standard))
        }
    }

    return lerpSchemes(from, target, fraction.value)
}

/**
 * Per-role colour interpolation. The role list mirrors `KoodoColors` exactly —
 * a new ColorTokens slot must be added here too or it will hard-cut mid-fade.
 */
private fun lerpSchemes(from: ColorScheme, to: ColorScheme, fraction: Float): ColorScheme =
    from.copy(
        primary = lerp(from.primary, to.primary, fraction),
        onPrimary = lerp(from.onPrimary, to.onPrimary, fraction),
        primaryContainer = lerp(from.primaryContainer, to.primaryContainer, fraction),
        onPrimaryContainer = lerp(from.onPrimaryContainer, to.onPrimaryContainer, fraction),
        secondary = lerp(from.secondary, to.secondary, fraction),
        onSecondary = lerp(from.onSecondary, to.onSecondary, fraction),
        secondaryContainer = lerp(from.secondaryContainer, to.secondaryContainer, fraction),
        onSecondaryContainer = lerp(from.onSecondaryContainer, to.onSecondaryContainer, fraction),
        background = lerp(from.background, to.background, fraction),
        onBackground = lerp(from.onBackground, to.onBackground, fraction),
        surface = lerp(from.surface, to.surface, fraction),
        onSurface = lerp(from.onSurface, to.onSurface, fraction),
        surfaceVariant = lerp(from.surfaceVariant, to.surfaceVariant, fraction),
        onSurfaceVariant = lerp(from.onSurfaceVariant, to.onSurfaceVariant, fraction),
        surfaceContainerLowest = lerp(from.surfaceContainerLowest, to.surfaceContainerLowest, fraction),
        surfaceContainerLow = lerp(from.surfaceContainerLow, to.surfaceContainerLow, fraction),
        surfaceContainer = lerp(from.surfaceContainer, to.surfaceContainer, fraction),
        surfaceContainerHigh = lerp(from.surfaceContainerHigh, to.surfaceContainerHigh, fraction),
        surfaceContainerHighest = lerp(from.surfaceContainerHighest, to.surfaceContainerHighest, fraction),
        surfaceDim = lerp(from.surfaceDim, to.surfaceDim, fraction),
        surfaceBright = lerp(from.surfaceBright, to.surfaceBright, fraction),
        outline = lerp(from.outline, to.outline, fraction),
        outlineVariant = lerp(from.outlineVariant, to.outlineVariant, fraction),
        error = lerp(from.error, to.error, fraction),
        onError = lerp(from.onError, to.onError, fraction),
        surfaceTint = lerp(from.surfaceTint, to.surfaceTint, fraction),
        scrim = lerp(from.scrim, to.scrim, fraction),
        inverseSurface = lerp(from.inverseSurface, to.inverseSurface, fraction),
        inverseOnSurface = lerp(from.inverseOnSurface, to.inverseOnSurface, fraction),
        inversePrimary = lerp(from.inversePrimary, to.inversePrimary, fraction),
    )
