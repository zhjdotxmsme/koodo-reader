package com.koodoreader.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.koodoreader.core.designsystem.ColorTokens

/**
 * Binds [ColorTokens] (plain `Long` ARGB values, pure JVM) to Compose's
 * [ColorScheme]. This is the ONLY place the two vocabularies meet — token
 * placement, contrast validation and the desktop-parity checks all happen in
 * `:core:designsystem`, where they are testable without a screen.
 *
 * Hierarchy note: the tonal `surfaceContainer*` ramp carries elevation in this
 * app. Cards use 0dp shadow height plus a `surfaceContainer*` fill and an
 * `outlineVariant` hairline, rather than drop shadows.
 */
private fun Color(argb: Long): Color = Color(argb.toInt())

fun koodoLightScheme(t: ColorTokens = ColorTokens.LIGHT): ColorScheme = lightColorScheme(
    primary = Color(t.primary),
    onPrimary = Color(t.onPrimary),
    primaryContainer = Color(t.primaryContainer),
    onPrimaryContainer = Color(t.onPrimaryContainer),
    secondary = Color(t.secondary),
    onSecondary = Color(t.onSecondary),
    secondaryContainer = Color(t.secondaryContainer),
    onSecondaryContainer = Color(t.onSecondaryContainer),
    background = Color(t.background),
    onBackground = Color(t.onBackground),
    surface = Color(t.surface),
    onSurface = Color(t.onSurface),
    surfaceVariant = Color(t.surfaceVariant),
    onSurfaceVariant = Color(t.onSurfaceVariant),
    surfaceContainerLowest = Color(t.surfaceContainerLowest),
    surfaceContainerLow = Color(t.surfaceContainerLow),
    surfaceContainer = Color(t.surfaceContainer),
    surfaceContainerHigh = Color(t.surfaceContainerHigh),
    surfaceContainerHighest = Color(t.surfaceContainerHighest),
    surfaceDim = Color(t.surfaceDim),
    surfaceBright = Color(t.surfaceBright),
    outline = Color(t.outline),
    outlineVariant = Color(t.outlineVariant),
    error = Color(t.error),
    onError = Color(t.onError),
    // Elevation stays flat on purpose: hierarchy comes from the tonal ramp
    // above, not from shadows.
    surfaceTint = Color(t.primary),
    scrim = Color(0xFF000000),
    inverseSurface = Color(t.onSurface),
    inverseOnSurface = Color(t.surface),
    inversePrimary = Color(t.primaryContainer),
)

fun koodoDarkScheme(t: ColorTokens = ColorTokens.DARK): ColorScheme = darkColorScheme(
    primary = Color(t.primary),
    onPrimary = Color(t.onPrimary),
    primaryContainer = Color(t.primaryContainer),
    onPrimaryContainer = Color(t.onPrimaryContainer),
    secondary = Color(t.secondary),
    onSecondary = Color(t.onSecondary),
    secondaryContainer = Color(t.secondaryContainer),
    onSecondaryContainer = Color(t.onSecondaryContainer),
    background = Color(t.background),
    onBackground = Color(t.onBackground),
    surface = Color(t.surface),
    onSurface = Color(t.onSurface),
    surfaceVariant = Color(t.surfaceVariant),
    onSurfaceVariant = Color(t.onSurfaceVariant),
    surfaceContainerLowest = Color(t.surfaceContainerLowest),
    surfaceContainerLow = Color(t.surfaceContainerLow),
    surfaceContainer = Color(t.surfaceContainer),
    surfaceContainerHigh = Color(t.surfaceContainerHigh),
    surfaceContainerHighest = Color(t.surfaceContainerHighest),
    surfaceDim = Color(t.surfaceDim),
    surfaceBright = Color(t.surfaceBright),
    outline = Color(t.outline),
    outlineVariant = Color(t.outlineVariant),
    error = Color(t.error),
    onError = Color(t.onError),
    surfaceTint = Color(t.primary),
    scrim = Color(0xFF000000),
    inverseSurface = Color(t.onSurface),
    inverseOnSurface = Color(t.surface),
    inversePrimary = Color(t.primaryContainer),
)

/** Pick the shell scheme for the given mode. */
fun koodoScheme(darkTheme: Boolean): ColorScheme =
    if (darkTheme) koodoDarkScheme() else koodoLightScheme()
