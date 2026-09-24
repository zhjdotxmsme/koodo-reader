package com.koodoreader.engine.gesture

/**
 * Edge-bounce (rubber-band) model matching Android's OverScroller behaviour.
 *
 * Visual displacement for an over-scroll distance x:
 *   rubberband(x, dim) = dim * (1 - 1 / (x * C / dim + 1))
 *
 * C = 0.55f (Android rubber-band constant).
 */
class OverscrollModel(
    val rubberBandConstant: Float = 0.55f,
    var enabled: Boolean = true,
) {

    init {
        require(rubberBandConstant > 0f)
    }

    /**
     * Compute the rubber-band displacement for an over-scroll distance.
     *
     * @param rawOverscrollPx How far past the boundary the user dragged (px, positive).
     * @param dimensionPx The viewport dimension in the over-scroll direction (px).
     * @return The visual displacement (px) to apply. 0 if disabled or no overscroll.
     */
    fun displacement(
        rawOverscrollPx: Float,
        dimensionPx: Float,
    ): Float {
        if (!enabled) return 0f
        val x = rawOverscrollPx.coerceAtLeast(0f)
        if (x <= 0f || dimensionPx <= 0f) return 0f
        // rubberband(x, dim) = dim * (1 - 1 / (x * C / dim + 1))
        val ratio = x * rubberBandConstant / dimensionPx
        return dimensionPx * (1f - 1f / (ratio + 1f))
    }

    /**
     * Spring-back progress factor for a given rubber-band value.
     * Used by the Compose animation layer to know how far to animate back.
     * Returns a value in [0.0, 1.0] where 1.0 means "fully sprung back".
     *
     * @param currentRubberbandPx The current rubber-band displacement (px).
     * @param maxRubberbandPx     The maximum expected rubber-band displacement (px).
     */
    fun springBackProgress(
        currentRubberbandPx: Float,
        maxRubberbandPx: Float,
    ): Float {
        if (maxRubberbandPx <= 0f) return 1f
        return (1f - (currentRubberbandPx / maxRubberbandPx)).coerceIn(0f, 1f)
    }
}
