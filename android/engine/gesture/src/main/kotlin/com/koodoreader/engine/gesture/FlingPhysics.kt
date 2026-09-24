package com.koodoreader.engine.gesture

import kotlin.math.exp
import kotlin.math.ln

/**
 * Fling deceleration physics, matching Android's `OverScroller` behaviour.
 *
 * ## Model
 *
 * Velocity decays exponentially from an initial velocity [V₀]:
 *
 *     v(t) = V₀ · exp(−t / τ)
 *
 * where τ is the time constant in milliseconds (default 341.4 ms, the
 * Android standard for a natural feel).
 *
 * The cumulative displacement from t = 0 to t:
 *
 *     d(t) = (V₀ / 1000) · τ · (1 − exp(−t / τ))
 *
 * (V₀ is in px/s; the /1000 converts to px·s → px since τ is in ms.)
 *
 * The fling comes to rest when v(t) < [minVelocityPxPerS]:
 *
 *     t_stop = τ · ln(V₀ / minVelocity)
 *
 * ## Total distance
 *
 * The total distance travelled by a fling with initial velocity V₀:
 *
 *     d(∞) = (V₀ / 1000) · τ
 *
 * At V₀ = 1000 px/s: d(∞) = 341.4 px (≈ 85 % of a 400-px-wide viewport).
 *
 * ## Example feel check (400 px viewport, PAGE_TURN mode)
 *
 * | Gesture   | V₀ (px/s) | d(∞) (px) | Lands on?     |
 * |-----------|-----------|-----------|---------------|
 * | slow      | 500       | 171       | same page     |
 * | medium    | 1000      | 341       | next page     |
 * | fast      | 2000      | 683       | next page+1   |
 * | fling     | 4000      | 1366      | next page+3   |
 */
class FlingPhysics(
    /** Time constant of the exponential decay, in ms. */
    val timeConstantMs: Float = DEFAULT_TIME_CONSTANT_MS,
    /** Below this velocity the fling is treated as stopped (px/s). */
    val minVelocityPxPerS: Float = 1f,
    /** Below this initial velocity no fling is issued (px/s). */
    val minFlingVelocityPxPerS: Float = 50f,
) {

    companion object {
        /**
         * Android's standard fling time constant.
         *
         * Derived from: τ = 20 ms × DECELERATION_RATE, where
         *   DECELERATION_RATE = ln(0.85) / ln(0.9) ≈ 11.6
         *   → τ ≈ 20 × 11.6 = 232 ms
         *
         * The empirically better "feel" constant (used by OverScroller on
         * most Android builds) is **341.4 ms** — we use that here.
         */
        const val DEFAULT_TIME_CONSTANT_MS: Float = 341.4f
    }

    init {
        require(timeConstantMs > 0f)
        require(minVelocityPxPerS > 0f)
        require(minFlingVelocityPxPerS > minVelocityPxPerS)
    }

    /** True if [initialVelocityPxPerS] would produce a visible fling. */
    fun isFling(initialVelocityPxPerS: Float): Boolean =
        initialVelocityPxPerS.coerceAtLeast(0f) >= minFlingVelocityPxPerS

    /**
     * Cumulative displacement (px) after [tMs] milliseconds of the fling,
     * starting from initial velocity [v0PxPerS].
     *
     * @return displacement in the positive direction; caller applies sign.
     */
    fun displacement(v0PxPerS: Float, tMs: Float): Float {
        val v = v0PxPerS.coerceAtLeast(0f)
        if (v == 0f) return 0f
        return (v / 1000f) * timeConstantMs * (1f - exp(-tMs.toDouble() / timeConstantMs).toFloat())
    }

    /**
     * Total distance (px) the fling would travel to rest.
     *
     * @param v0PxPerS initial velocity (px/s, magnitude — sign applied by caller)
     */
    fun totalDistance(v0PxPerS: Float): Float {
        val v = v0PxPerS.coerceAtLeast(0f)
        if (v < minFlingVelocityPxPerS) return 0f
        return (v / 1000f) * timeConstantMs
    }

    /**
     * Duration (ms) for the fling to reach [minVelocityPxPerS].
     * Returns 0 immediately if the initial velocity is below the threshold.
     */
    fun duration(v0PxPerS: Float): Float {
        val v = v0PxPerS.coerceAtLeast(0f)
        if (v < minFlingVelocityPxPerS) return 0f
        return timeConstantMs * ln((v / minVelocityPxPerS).toDouble()).toFloat()
    }

    /**
     * Predict the landing position of a fling that starts at [startPosPx]
     * with velocity [v0PxPerS], clamped to [minBoundPx] … [maxBoundPx].
     *
     * @return the clamped landing position in px, in the direction of [v0PxPerS].
     */
    fun predictLanding(
        startPosPx: Float,
        v0PxPerS: Float,
        minBoundPx: Float,
        maxBoundPx: Float,
    ): Float {
        val dist = totalDistance(v0PxPerS)
        val target = startPosPx + v0PxPerS.sign * dist
        return target.coerceIn(minBoundPx, maxBoundPx)
    }
}

/** Sign helper (Float.sign is not a standard property in Kotlin). */
private val Float.sign: Float get() = if (this >= 0f) 1f else -1f
