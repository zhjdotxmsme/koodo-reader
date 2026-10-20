package com.koodoreader.reader.epubhost

import com.koodoreader.core.designsystem.ThemeKind

/**
 * In-reader theme / typography payload for the foliate host.
 *
 * Night mode must paint chapter DOM (not only html/body) and the reader
 * chrome, matching desktop ThemeKind presets. [invertImages] stays off:
 * colour override already darkens the page; inverting photos turns them
 * into negatives.
 */
object FoliateAppearance {

    data class Opts(
        val bg: String,
        val fg: String,
        val fontSizePx: Int,
        val fontFamily: String,
        val lineHeight: Double,
        val marginPx: Int,
        val invertImages: Boolean,
        val colorScheme: String,
    )

    fun colorScheme(dark: Boolean): String = if (dark) "dark" else "light"

    @Suppress("UNUSED_PARAMETER")
    fun invertImages(dark: Boolean): Boolean = false

    fun themeLabelKey(kind: ThemeKind): String = when (kind) {
        ThemeKind.DEFAULT -> "Default"
        ThemeKind.PROTECT_EYE -> "Eye protection"
        ThemeKind.NIGHT -> "Night"
        ThemeKind.CUSTOM -> "Custom"
    }

    /** Theme chips live in the reader More sheet for both engines. */
    @Suppress("UNUSED_PARAMETER")
    fun showInReaderSheet(useFoliate: Boolean): Boolean = true

    fun opts(
        bg: String,
        fg: String,
        fontSizePx: Int,
        dark: Boolean,
        fontFamily: String = "",
        lineHeight: Float = 1.5f,
        marginPx: Float = 0f,
    ): Opts = Opts(
        bg = bg,
        fg = fg,
        fontSizePx = fontSizePx,
        fontFamily = fontFamily,
        lineHeight = lineHeight.toDouble(),
        marginPx = marginPx.toInt(),
        invertImages = invertImages(dark),
        colorScheme = colorScheme(dark),
    )
}
