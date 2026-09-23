package com.koodoreader.reader.shell

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Minimal shell theme. The real reader theming (fonts, sepia/dark reader
 * palettes, per-book overrides) is a P2 concern; this only needs to look
 * reasonable for the bookshelf preview.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF3A6EA5),
    secondary = Color(0xFF6B8E23),
    background = Color(0xFFF8F6F2),
    surface = Color(0xFFFFFFFF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9EC3E8),
    secondary = Color(0xFFB5C98A),
    background = Color(0xFF16181D),
    surface = Color(0xFF1F2229),
)

@Composable
fun KoodoShellTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
