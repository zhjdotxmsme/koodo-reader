package com.koodoreader.core.designsystem

/**
 * Motion duration scale, in milliseconds.
 *
 * Deliberately short: the shell reads as an e-ink-adjacent reading app, not a
 * game. The chosen mapping for this app:
 *   - exits and small controls → [FAST] (120ms)
 *   - chrome show/hide and crossfades → [NORMAL] (200ms)
 *   - page-turn transitions → [PAGE] (250ms)
 *   - theme gradients, chapter-hint exits → [SLOW] (300ms)
 *   - skeleton-screen shimmer scan loop → [SHIMMER] (900ms)
 *   - hint dwell → [HINT] (2000ms)
 *   - double-back-to-exit acceptance window → [BACK_EXIT] (4000ms)
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

    /** Chrome show/hide and crossfades. */
    const val NORMAL = 200

    /** Page-turn transitions. */
    const val PAGE = 250

    /** Theme gradients, chapter-hint exits. */
    const val SLOW = 300

    /** Skeleton-screen shimmer scan loop. */
    const val SHIMMER = 900

    /** Hint dwell (chapter-tip toast). */
    const val HINT = 2000

    /**
     * Double-back-to-exit acceptance window (EPUB/PDF reader back guard).
     * Deliberately ≥ the visible time of the exit hint's
     * `SnackbarDuration.Short` (~4s): a second back that arrives while
     * "Press back again to exit" is still on screen must exit, not re-queue
     * the hint. Guarded by `MotionTokensTest`.
     */
    const val BACK_EXIT = 4000

    /** Delay between staggered items. */
    const val STAGGER = 30

    /** Pressed-state scale for interactive controls. */
    const val PRESS_SCALE = 0.98f

    /** Duration scale in ascending order; stagger and press scale are not durations. */
    val ALL = listOf(FAST, NORMAL, PAGE, SLOW, SHIMMER, HINT, BACK_EXIT)
}
