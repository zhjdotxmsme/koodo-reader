package com.koodoreader.reader.shell

import com.koodoreader.engine.gesture.TapZoneLayout

/**
 * Librera-style reader gesture options (stored in [ReaderGesturePrefs]).
 */
data class ReaderGestureConfig(
    /**
     * Fraction of the viewport used by the prev/next tap bands (the middle is
     * chrome). For [TapZoneLayout.TOP_BOTTOM] / [BOTTOM_TOP] this is the band
     * *height* instead of the width.
     */
    val tapEdgeFraction: Float = 1f / 3f,
    /** Separate previous-page band size. 0 means "same as [tapEdgeFraction]". */
    val tapBackFraction: Float = 0f,
    /** Where the page-turn hot areas sit (see [TapZoneLayout]). */
    val tapZoneLayout: TapZoneLayout = TapZoneLayout.EDGES,
    val swipePageTurn: Boolean = true,
    val volumePageTurn: Boolean = true,
    /** When true, volume-up → next page (default: up = previous). */
    val volumeInverted: Boolean = false,
) {
    /** Previous-band ratio, falling back to the shared width when unset. */
    val resolvedBackFraction: Float
        get() = if (tapBackFraction > 0f) tapBackFraction else tapEdgeFraction
}

object ReaderGestureCodec {

    const val KEY_TAP_EDGE = "tapEdgeFraction"
    const val KEY_TAP_BACK = "tapBackFraction"
    const val KEY_TAP_LAYOUT = "tapZoneLayout"
    const val KEY_SWIPE = "swipePageTurn"
    const val KEY_VOLUME = "volumePageTurn"
    const val KEY_VOLUME_INVERT = "volumeInverted"

    const val TAP_EDGE_MIN = 0.15f
    const val TAP_EDGE_MAX = 0.45f

    fun fromMap(map: Map<String, String>): ReaderGestureConfig = ReaderGestureConfig(
        tapEdgeFraction = floatOf(map[KEY_TAP_EDGE], 1f / 3f),
        tapBackFraction = floatOf(map[KEY_TAP_BACK], 0f),
        tapZoneLayout = TapZoneLayout.fromPersisted(map[KEY_TAP_LAYOUT]),
        swipePageTurn = boolOf(map[KEY_SWIPE], default = true),
        volumePageTurn = boolOf(map[KEY_VOLUME], default = true),
        volumeInverted = boolOf(map[KEY_VOLUME_INVERT], default = false),
    )

    fun toMap(config: ReaderGestureConfig): Map<String, String> = mapOf(
        KEY_TAP_EDGE to clampedTapEdge(config).toString(),
        KEY_TAP_BACK to clampedTapBack(config).toString(),
        KEY_TAP_LAYOUT to config.tapZoneLayout.name,
        KEY_SWIPE to config.swipePageTurn.toString(),
        KEY_VOLUME to config.volumePageTurn.toString(),
        KEY_VOLUME_INVERT to config.volumeInverted.toString(),
    )

    fun clampedTapEdge(config: ReaderGestureConfig): Float =
        config.tapEdgeFraction.coerceIn(TAP_EDGE_MIN, TAP_EDGE_MAX)

    /** 0 is a valid "follow [clampedTapEdge]" sentinel; anything else is clamped. */
    fun clampedTapBack(config: ReaderGestureConfig): Float =
        if (config.tapBackFraction <= 0f) 0f
        else config.tapBackFraction.coerceIn(TAP_EDGE_MIN, TAP_EDGE_MAX)

    private fun floatOf(raw: String?, default: Float): Float =
        raw?.toFloatOrNull()?.takeIf { it.isFinite() } ?: default

    private fun boolOf(raw: String?, default: Boolean): Boolean =
        when {
            raw == null -> default
            raw.equals("true", ignoreCase = true) || raw == "1" -> true
            raw.equals("false", ignoreCase = true) || raw == "0" -> false
            else -> default
        }
}

/** Live config for [NativeShellActivity] volume keys and reader touch handlers. */
object ReaderGestureRuntime {
    @Volatile
    var config: ReaderGestureConfig = ReaderGestureConfig()
}
