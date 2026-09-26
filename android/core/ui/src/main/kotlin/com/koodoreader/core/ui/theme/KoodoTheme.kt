package com.koodoreader.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * Shell theme: the koodo brand palette, the shared shape/space scales and the
 * UI type scale.
 *
 * Scope — this themes the CHROME (navigation, library, notes, stats, settings).
 * The reader page keeps its own colours from `ThemeSpec`, which mirrors the
 * desktop `themeList.tsx` values so the Room / backup-zip wire format stays
 * desktop-compatible. See [ThemeSpecBridge] for the reader-page mapping; do not
 * route book text through this theme.
 *
 * Replaces the previous `shell/Theme.kt`, which hardcoded four colours and was
 * explicitly documented as a placeholder.
 */
@Composable
fun KoodoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalKoodoSpacing provides KoodoSpacing(),
        LocalKoodoChartColors provides if (darkTheme) KoodoChartColors.dark() else KoodoChartColors.light(),
    ) {
        MaterialTheme(
            colorScheme = koodoScheme(darkTheme),
            shapes = KoodoShapes,
            typography = KoodoTypography,
            content = content,
        )
    }
}
