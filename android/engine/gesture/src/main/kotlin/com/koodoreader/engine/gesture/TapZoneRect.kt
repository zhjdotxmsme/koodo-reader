package com.koodoreader.engine.gesture

/**
 * A page-turn hot area expressed in **normalized viewport coordinates**
 * (`0..1`), the geometry model KOReader uses in
 * `frontend/apps/reader/modules/readerview.lua#getTapZones`.
 *
 * Why normalized and not pixels:
 *
 *  - a ratio survives rotation, split-screen and any density without rescaling;
 *  - one mirror transform (`x' = 1 - x`) is enough for right-to-left books, so
 *    manga / Arabic / Hebrew do not need a second copy of the hit-testing logic.
 *
 * Unlike [TapZone] (the fixed 3×3 desktop-parity grid), rectangles let the two
 * page-turn bands have **independent widths**, and can span the full height or
 * only the bottom half — which is where a thumb actually rests.
 */
data class TapZoneRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)

    /** Inclusive on both edges: a tap exactly on the seam still counts. */
    fun contains(nx: Float, ny: Float): Boolean =
        nx >= left && nx <= right && ny >= top && ny <= bottom

    /**
     * Mirror across the vertical axis.
     *
     * KOReader applies this to *every* zone, not only the side ones; for a
     * full-width band (`left = 0, right = 1`) the transform is the identity,
     * so the same code covers both cases.
     */
    fun mirroredHorizontally(): TapZoneRect =
        TapZoneRect(1f - right, top, 1f - left, bottom)
}

/**
 * How the page-turn hot areas are arranged.
 *
 * The names follow KOReader's `page_turns_tap_zones` setting, minus the
 * "corner" variant it inherits from its 9-cell default — that behaviour is
 * available as [DESKTOP], which reuses this module's [TapControlRule].
 */
enum class TapZoneLayout {
    /**
     * Left and right vertical bands, full height (the Android default before
     * this model existed: a 1/3 split with the middle band toggling chrome).
     */
    EDGES,

    /**
     * Desktop `touchControlRule` parity: zones 1,2,4,7 turn back, 3,6,8,9 turn
     * forward, and only the exact centre cell toggles chrome. Ratios are
     * ignored — the grid is fixed at thirds.
     */
    DESKTOP,

    /** Top band = previous, bottom band = next. Ratios are band *heights*. */
    TOP_BOTTOM,

    /** Top band = next, bottom band = previous. Ratios are band *heights*. */
    BOTTOM_TOP,
    ;

    companion object {
        fun fromPersisted(raw: String?): TapZoneLayout =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: EDGES
    }
}

/** The resolved pair for a viewport, before hit-testing. */
data class TapZonePair(
    val previous: TapZoneRect,
    val next: TapZoneRect,
)

/**
 * Builds and hit-tests ratio-based tap zones.
 *
 * All ratios are clamped to [MIN_RATIO]..[MAX_RATIO] so that a corrupted
 * setting can never (a) swallow the chrome band entirely, or (b) overlap the
 * two page-turn bands and make the direction depend on check order.
 */
object TapZoneGeometry {

    const val MIN_RATIO = 0.08f
    const val MAX_RATIO = 0.45f

    /** Matches the historical Android reader behaviour and the settings slider. */
    const val DEFAULT_RATIO = 1f / 3f

    fun clampRatio(ratio: Float): Float =
        if (ratio.isNaN()) DEFAULT_RATIO else ratio.coerceIn(MIN_RATIO, MAX_RATIO)

    /**
     * @param forwardRatio width (or height) of the "next page" band.
     * @param backwardRatio width (or height) of the "previous page" band.
     * @param mirrored `true` for right-to-left reading order.
     */
    fun zones(
        layout: TapZoneLayout,
        forwardRatio: Float = DEFAULT_RATIO,
        backwardRatio: Float = DEFAULT_RATIO,
        mirrored: Boolean = false,
    ): TapZonePair {
        val fw = clampRatio(forwardRatio)
        val bw = clampRatio(backwardRatio)

        val pair = when (layout) {
            // Fixed thirds: the rects below describe the same bands the grid
            // produces, so callers can still hit-test without a special case.
            TapZoneLayout.EDGES,
            TapZoneLayout.DESKTOP,
            -> TapZonePair(
                previous = TapZoneRect(0f, 0f, bw, 1f),
                next = TapZoneRect(1f - fw, 0f, 1f, 1f),
            )

            TapZoneLayout.TOP_BOTTOM -> TapZonePair(
                previous = TapZoneRect(0f, 0f, 1f, bw),
                next = TapZoneRect(0f, 1f - fw, 1f, 1f),
            )

            TapZoneLayout.BOTTOM_TOP -> TapZonePair(
                previous = TapZoneRect(0f, 1f - bw, 1f, 1f),
                next = TapZoneRect(0f, 0f, 1f, fw),
            )
        }

        return if (!mirrored) {
            pair
        } else {
            TapZonePair(
                previous = pair.previous.mirroredHorizontally(),
                next = pair.next.mirroredHorizontally(),
            )
        }
    }

    /**
     * Resolve a tap to a page-turn direction.
     *
     * Returns [TapAction.NONE] for the chrome band — the caller decides whether
     * that toggles the toolbar, opens a menu, or is inert.
     */
    fun resolve(
        x: Float,
        y: Float,
        viewportWidth: Float,
        viewportHeight: Float,
        layout: TapZoneLayout = TapZoneLayout.EDGES,
        forwardRatio: Float = DEFAULT_RATIO,
        backwardRatio: Float = DEFAULT_RATIO,
        mirrored: Boolean = false,
    ): TapAction {
        if (viewportWidth <= 0f || viewportHeight <= 0f) return TapAction.NONE

        if (layout == TapZoneLayout.DESKTOP) {
            return resolveTapAction(x, y, viewportWidth, viewportHeight)
        }

        val zones = zones(layout, forwardRatio, backwardRatio, mirrored)
        val nx = (x / viewportWidth).coerceIn(0f, 1f)
        val ny = (y / viewportHeight).coerceIn(0f, 1f)

        return when {
            zones.previous.contains(nx, ny) -> TapAction.PREV_PAGE
            zones.next.contains(nx, ny) -> TapAction.NEXT_PAGE
            else -> TapAction.NONE
        }
    }
}
