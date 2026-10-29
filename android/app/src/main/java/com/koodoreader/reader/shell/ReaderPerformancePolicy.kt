package com.koodoreader.reader.shell

/**
 * Maps [ReaderFeatureState.perfTier] to engine limits (M2 low-end profile).
 */
object ReaderPerformancePolicy {

    const val TIER_NORMAL = "normal"
    const val TIER_LITE = "lite"

    fun isLite(state: ReaderFeatureState): Boolean =
        state.perfTier.equals(TIER_LITE, ignoreCase = true)

    /** PDF raster width multiplier (viewport × zoom × this). */
    fun pdfRenderScale(state: ReaderFeatureState): Float =
        if (isLite(state)) 0.72f else 1f

    fun foliateMaxInlineSizePx(state: ReaderFeatureState): Int =
        if (isLite(state)) 560 else 720

    fun effectiveMaxColumns(state: ReaderFeatureState): Int =
        if (isLite(state)) 1 else ReaderFeatureCodec.clampedColumns(state)
}
