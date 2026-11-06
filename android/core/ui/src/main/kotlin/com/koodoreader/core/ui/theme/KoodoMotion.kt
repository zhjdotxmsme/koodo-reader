package com.koodoreader.core.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing

/**
 * Shared easing curves for the shell. Durations live in
 * `com.koodoreader.core.designsystem.MotionTokens` (pure JVM); only the easing
 * types need Compose, so they stay here.
 */
object KoodoMotion {
    /** Decelerate-heavy curve for emphasis transitions (theme change, chrome). */
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** The Material standard curve; default for most shell motion. */
    val Standard = FastOutSlowInEasing
}
