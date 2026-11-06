package com.koodoreader.core.designsystem

/**
 * Motion duration scale, in milliseconds.
 *
 * Deliberately short: the shell reads as an e-ink-adjacent reading app, not a
 * game. The chosen mapping for this app:
 *   - exits and small controls → [FAST] (120ms)
 *   - chrome show/hide, crossfades, page-turn transitions → [NORMAL] (200ms)
 *   - theme gradients, chapter-hint exits → [SLOW] (300ms)
 *   - skeleton-screen shimmer scan loop → [SHIMMER] (900ms)
 *   - hint dwell / double-back-to-exit window → [HINT] (2000ms)
 *
 * Easing curves live in `:core:ui` (`KoodoMotion`) because they are Compose
 * types; this module stays pure JVM like [ShapeTokens].
 *
 * These are plain Ints (ms) so `:core:designsystem` stays free of Compose /
 * Android dependencies, and so `selfCheck` and tests can iterate the whole
 * scale without a screen.
 */
object MotionTokens {

    /** Exits and small controls. */
    const val FAST = 120

    /** Chrome show/hide, crossfades, page-turn transitions. */
    const val NORMAL = 200

    /** Theme gradients, chapter-hint exits. */
    const val SLOW = 300

    /** Skeleton-screen shimmer scan loop. */
    const val SHIMMER = 900

    /** Hint dwell / double-back-to-exit window. */
    const val HINT = 2000

    /** The scale in ascending order, for validation and iteration. */
    val ALL = listOf(FAST, NORMAL, SLOW, SHIMMER, HINT)
}
