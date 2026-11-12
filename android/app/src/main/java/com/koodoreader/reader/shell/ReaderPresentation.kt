package com.koodoreader.reader.shell

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.koodoreader.core.designsystem.ThemeKind
import com.koodoreader.core.ui.theme.ThemeSpecBridge

@Composable
fun rememberReaderColorScheme(kind: ThemeKind, customBackgroundArgb: Int?): ColorScheme {
    val base = remember(kind) { ThemeSpecBridge.readerScheme(kind.builtInPreset()) }
    return remember(kind, customBackgroundArgb) {
        if (customBackgroundArgb == null) return@remember base
        val background = Color(customBackgroundArgb)
        val dark = background.luminance() < 0.5f
        val content = if (dark) Color(0xFFE3E2E6) else Color(0xFF1A1C1E)
        base.copy(
            background = background,
            onBackground = content,
            surface = background,
            onSurface = content,
            primary = content,
            onPrimary = background,
        )
    }
}
