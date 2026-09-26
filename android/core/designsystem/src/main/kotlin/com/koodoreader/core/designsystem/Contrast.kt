package com.koodoreader.core.designsystem

import kotlin.math.pow

/**
 * WCAG 2.1 relative luminance and contrast ratio over `0xAARRGGBB` values.
 *
 * Pure Kotlin on purpose: this is the one part of "does the design system look
 * right" that a machine can actually verify, so it must run in `selfCheck` and
 * in plain JVM unit tests without Compose, Android or a screen.
 *
 * Alpha is IGNORED (treated as opaque). Compositing makes the real ratio depend
 * on what is underneath, which these functions cannot know — so the token layer
 * asserts opacity separately via [ColorTokens.requireOpaque] instead of letting
 * a translucent colour pass a contrast check by accident.
 */
object Contrast {

    /** WCAG AA for normal-size body text. */
    const val TEXT_MIN_RATIO = 4.5

    /** WCAG AA for large text (>=18pt / 14pt bold). */
    const val LARGE_TEXT_MIN_RATIO = 3.0

    /** WCAG AA for non-text UI components, borders and graphical objects. */
    const val ACCENT_MIN_RATIO = 3.0

    /** Contrast of a colour against itself — the theoretical minimum. */
    const val IDENTITY_RATIO = 1.0

    /** Black on white, the theoretical maximum. */
    const val MAX_RATIO = 21.0

    fun alphaOf(argb: Long): Int = ((argb shr 24) and 0xFF).toInt()

    fun redOf(argb: Long): Int = ((argb shr 16) and 0xFF).toInt()

    fun greenOf(argb: Long): Int = ((argb shr 8) and 0xFF).toInt()

    fun blueOf(argb: Long): Int = (argb and 0xFF).toInt()

    /** sRGB channel value (0..255) → linearised 0..1. */
    private fun linearise(channel: Int): Double {
        require(channel in 0..255) { "channel out of range: $channel" }
        val s = channel / 255.0
        return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }

    /**
     * WCAG relative luminance, 0.0 (black) .. 1.0 (white).
     *
     * Reference points used by the tests: black = 0.0, white = 1.0.
     */
    fun relativeLuminance(argb: Long): Double {
        val r = linearise(redOf(argb))
        val g = linearise(greenOf(argb))
        val b = linearise(blueOf(argb))
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /**
     * Contrast ratio in 1.0..21.0. Symmetric, so argument order does not matter.
     */
    fun ratio(a: Long, b: Long): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /**
     * Render an `0xAARRGGBB` value as `#RRGGBB`, for readable failure messages.
     */
    fun hex(argb: Long): String =
        "#%02X%02X%02X".format(redOf(argb), greenOf(argb), blueOf(argb))
}
