package com.koodoreader.reader.shell

/**
 * Librera-style reader gesture options (stored in [ReaderGesturePrefs]).
 */
data class ReaderGestureConfig(
    /** Fraction of screen width used for prev/next tap bands (center = chrome). */
    val tapEdgeFraction: Float = 1f / 3f,
    val swipePageTurn: Boolean = true,
    val volumePageTurn: Boolean = true,
    /** When true, volume-up → next page (default: up = previous). */
    val volumeInverted: Boolean = false,
)

object ReaderGestureCodec {

    const val KEY_TAP_EDGE = "tapEdgeFraction"
    const val KEY_SWIPE = "swipePageTurn"
    const val KEY_VOLUME = "volumePageTurn"
    const val KEY_VOLUME_INVERT = "volumeInverted"

    const val TAP_EDGE_MIN = 0.15f
    const val TAP_EDGE_MAX = 0.45f

    fun fromMap(map: Map<String, String>): ReaderGestureConfig = ReaderGestureConfig(
        tapEdgeFraction = floatOf(map[KEY_TAP_EDGE], 1f / 3f),
        swipePageTurn = boolOf(map[KEY_SWIPE], default = true),
        volumePageTurn = boolOf(map[KEY_VOLUME], default = true),
        volumeInverted = boolOf(map[KEY_VOLUME_INVERT], default = false),
    )

    fun toMap(config: ReaderGestureConfig): Map<String, String> = mapOf(
        KEY_TAP_EDGE to clampedTapEdge(config).toString(),
        KEY_SWIPE to config.swipePageTurn.toString(),
        KEY_VOLUME to config.volumePageTurn.toString(),
        KEY_VOLUME_INVERT to config.volumeInverted.toString(),
    )

    fun clampedTapEdge(config: ReaderGestureConfig): Float =
        config.tapEdgeFraction.coerceIn(TAP_EDGE_MIN, TAP_EDGE_MAX)

    private fun floatOf(raw: String?, default: Float): Float =
        raw?.toFloatOrNull() ?: default

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
