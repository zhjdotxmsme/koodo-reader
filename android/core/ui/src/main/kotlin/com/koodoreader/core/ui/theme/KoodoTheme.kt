package com.koodoreader.core.ui.theme

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.toArgb

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
    val scheme = rememberAnimatedScheme(koodoScheme(darkTheme))
    val view = LocalView.current
    if (!view.isInEditMode) {
        // Keep the window background and system-bar icon colour in lockstep
        // with the animated scheme, so the fade does not leave a stale
        // platform-coloured flash behind the Compose surface.
        SideEffect {
            (view.context as? Activity)?.window?.apply {
                setBackgroundDrawable(ColorDrawable(scheme.background.toArgb()))
                WindowCompat.getInsetsController(this, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
        }
    }
    CompositionLocalProvider(
        LocalKoodoChartColors provides if (darkTheme) KoodoChartColors.dark() else KoodoChartColors.light(),
    ) {
        MaterialTheme(
            colorScheme = scheme,
            shapes = KoodoShapes,
            typography = KoodoTypography,
            content = content,
        )
    }
}
