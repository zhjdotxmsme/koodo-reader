package com.koodoreader.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.koodoreader.core.designsystem.CssColor
import com.koodoreader.core.designsystem.ThemeSpec

/**
 * The single point where the reader's own theme meets Compose.
 *
 * The reader page and its panels must follow the BOOK's theme, not the shell
 * theme — that is the whole point of `ThemeSpec` existing. So this bridge
 * derives a [ColorScheme] from a [ThemeSpec] instead of from [ColorTokens].
 *
 * All parsing is delegated to [CssColor] in `:core:designsystem`, which owns the
 * desktop wire format and is covered by pure-JVM tests. This object is
 * deliberately logic-free: a Compose module is the wrong place to unit-test
 * string parsing.
 *
 * The reader palette is intentionally small. M3's full role set assumes a
 * designed palette; a book theme only supplies a background and a foreground,
 * so the remaining roles are derived by tonal offset from those two. Anything
 * richer would be inventing contrast the book did not ask for.
 */
object ThemeSpecBridge {

    /** The book's page background as a Compose colour. */
    fun background(theme: ThemeSpec): Color = Color(CssColor.parse(theme.backgroundColor).toInt())

    /** The book's text colour as a Compose colour. */
    fun foreground(theme: ThemeSpec): Color = Color(CssColor.parse(theme.foregroundColor).toInt())

    /**
     * True when the book theme is dark, judged by the actual luminance of its
     * background rather than by `ThemeKind`. A user can point the CUSTOM slot at
     * a dark colour, and the panels must follow the colour, not the label.
     */
    fun isDark(theme: ThemeSpec): Boolean =
        com.koodoreader.core.designsystem.Contrast.relativeLuminance(
            CssColor.parse(theme.backgroundColor),
        ) < 0.5

    /**
     * Colour scheme for reader chrome (toolbars, panels, popups) derived from
     * the book theme, so the controls match the page they float over.
     */
    fun readerScheme(theme: ThemeSpec): ColorScheme {
        val bg = CssColor.parse(theme.backgroundColor)
        val fg = CssColor.parse(theme.foregroundColor)
        val dark = isDark(theme)
        // (surface, lowest, low, high) derived by small tonal offsets from the
        // page so the chrome never introduces a second visual language.
        val surfaces = if (dark) readerDarkSurfaces(bg) else readerLightSurfaces(bg)
        val surface = surfaces[0]
        val lowest = surfaces[1]
        val low = surfaces[2]
        val high = surfaces[3]

        // Explicit branches rather than a `::lightColorScheme` reference: both
        // builders are overloaded, so a function reference is ambiguous.
        return if (dark) {
            darkColorScheme(
                background = Color(bg.toInt()),
                onBackground = Color(fg.toInt()),
                surface = Color(surface.toInt()),
                surfaceContainerLowest = Color(lowest.toInt()),
                surfaceContainerLow = Color(low.toInt()),
                surfaceContainerHigh = Color(high.toInt()),
                onSurface = Color(fg.toInt()),
                primary = Color(fg.toInt()),
                onPrimary = Color(bg.toInt()),
                outlineVariant = Color(mix(bg, fg, 0.16).toInt()),
                outline = Color(mix(bg, fg, 0.38).toInt()),
            )
        } else {
            lightColorScheme(
                background = Color(bg.toInt()),
                onBackground = Color(fg.toInt()),
                surface = Color(surface.toInt()),
                surfaceContainerLowest = Color(lowest.toInt()),
                surfaceContainerLow = Color(low.toInt()),
                surfaceContainerHigh = Color(high.toInt()),
                onSurface = Color(fg.toInt()),
                primary = Color(fg.toInt()),
                onPrimary = Color(bg.toInt()),
                outlineVariant = Color(mix(bg, fg, 0.16).toInt()),
                outline = Color(mix(bg, fg, 0.38).toInt()),
            )
        }
    }

    /** (surface, lowest, low, high) derived by small tonal offsets from the page. */
    private fun readerLightSurfaces(bg: Long): List<Long> =
        listOf(mix(bg, 0xFF000000, 0.03), 0xFFFFFFFF, mix(bg, 0xFF000000, 0.05), mix(bg, 0xFF000000, 0.09))

    private fun readerDarkSurfaces(bg: Long): List<Long> =
        listOf(mix(bg, 0xFFFFFFFF, 0.05), mix(bg, 0xFF000000, 0.20), mix(bg, 0xFFFFFFFF, 0.06), mix(bg, 0xFFFFFFFF, 0.12))

    /**
     * Linear blend of two opaque ARGB colours; [amount] 0 returns [a], 1 returns
     * [b]. Alpha of the result is always opaque — the token layer forbids
     * translucency because contrast then depends on what is composited beneath.
     */
    private fun mix(a: Long, b: Long, amount: Double): Long {
        val t = amount.coerceIn(0.0, 1.0)
        fun ch(shift: Int): Long {
            val av = (a shr shift) and 0xFF
            val bv = (b shr shift) and 0xFF
            return ((av + (bv - av) * t) + 0.5).toLong().coerceIn(0, 255)
        }
        return (0xFFL shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
