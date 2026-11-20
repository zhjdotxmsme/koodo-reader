package com.koodoreader.core.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.LinearEasing

/**
 * Shared easing curves for the shell. Durations live in
 * `com.koodoreader.core.designsystem.MotionTokens` (pure JVM); only the easing
 * types need Compose, so they stay here.
 *
 * ### The curves and when to use which
 *
 * - [Emphasized] — the Material 3 "emphasized" curve. For elements that
 *   arrive: they should decelerate into place, so the motion starts fast and
 *   settles gently. Used for the reader chrome appearing.
 * - [Standard] — the Material 3 "standard" curve. The general-purpose
 *   default; symmetric, for changes that neither arrive nor depart.
 * - [Decelerate] — decelerate only. For content that is *entering* a
 *   container (a page turn, a sheet sliding up): it should arrive, not
 *   bounce.
 * - [Accelerate] — accelerate only. For content that is *leaving* (a sheet
 *   sliding away): it should get out of the way.
 * - [Linear] — no easing. Only for continuously repeating motion (the
 *   shimmer sweep), where easing would read as a stutter.
 */
object KoodoMotion {
    /** Decelerate-heavy curve for emphasis transitions (theme change, chrome). */
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** The Material standard curve; default for most shell motion. */
    val Standard = FastOutSlowInEasing

    val Decelerate = LinearOutSlowInEasing

    /** Accelerate-only curve for departing content. */
    val Accelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** No easing; for looping motion only. */
    val Linear = LinearEasing
}
