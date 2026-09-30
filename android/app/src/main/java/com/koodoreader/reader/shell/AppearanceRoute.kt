package com.koodoreader.reader.shell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.koodoreader.core.designsystem.CssColor
import com.koodoreader.core.designsystem.ThemeKind
import com.koodoreader.core.designsystem.ThemeSpec

/**
 * Settings → Appearance (settings page 2026-09-29 spec §3.2).
 *
 * Two independent switches, both live: the app chrome theme (recolored by
 * NativeShellActivity / ComicViewerActivity from the same [ShellAppearancePrefs]
 * file) and the reader page theme (read by NativeEpubScreen through
 * ThemeSpecBridge). This screen only reads/writes the prefs; it owns no colour
 * logic of its own (parsing stays in `:core:designsystem`, Compose adaptation
 * stays in `:core:ui`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { ShellAppearancePrefs(context) }
    DisposableEffect(prefs) { onDispose { prefs.close() } }
    val appMode by prefs.appThemeModeFlow.collectAsStateWithLifecycle()
    val readerTheme by prefs.readerThemeKindFlow.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t("Appearance")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("Back"))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            Text(text = t("App theme"), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            // The swatch on each row previews the palette that mode produces.
            ThemeModeRow(
                label = t("Follow OS"),
                selected = appMode == AppThemeMode.SYSTEM,
                background = Color(0xFF525252),
                text = Color(0xFFE6E6E6),
                onClick = { prefs.appThemeMode = AppThemeMode.SYSTEM },
            )
            ThemeModeRow(
                label = t("Light mode"),
                selected = appMode == AppThemeMode.LIGHT,
                background = Color(0xFFFFFFFF),
                text = Color(0xFF333333),
                onClick = { prefs.appThemeMode = AppThemeMode.LIGHT },
            )
            ThemeModeRow(
                label = t("Night mode"),
                selected = appMode == AppThemeMode.DARK,
                background = Color(0xFF141414),
                text = Color(0xFFE6E6E6),
                onClick = { prefs.appThemeMode = AppThemeMode.DARK },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text(text = t("Reader page theme"), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "独立于应用主题 · 与桌面版预设同源",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            READER_THEME_OPTIONS.forEach { kind ->
                ReaderThemeRow(
                    label = readerThemeLabel(kind),
                    selected = readerTheme == kind,
                    preset = kind.builtInPreset(),
                    onClick = { prefs.readerThemeKind = kind },
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Display name for a reader page preset; rendered through the catalogs. */
private fun readerThemeLabel(kind: ThemeKind): String = when (kind) {
    ThemeKind.DEFAULT -> "Default"
    ThemeKind.PROTECT_EYE -> "Eye protection"
    ThemeKind.NIGHT -> "Night"
    ThemeKind.CUSTOM -> "Custom"
}

@Composable
private fun ThemeModeRow(
    label: String,
    selected: Boolean,
    background: Color,
    text: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThemeSwatch(background = background, text = text)
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = if (selected) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ReaderThemeRow(
    label: String,
    selected: Boolean,
    preset: ThemeSpec,
    onClick: () -> Unit,
) {
    ReaderThemeRow(
        label = label,
        selected = selected,
        background = Color(CssColor.parse(preset.backgroundColor).toInt()),
        text = Color(CssColor.parse(preset.foregroundColor).toInt()),
        onClick = onClick,
    )
}

@Composable
private fun ReaderThemeRow(
    label: String,
    selected: Boolean,
    background: Color,
    text: Color,
    onClick: () -> Unit,
) {
    ThemeModeRow(label = label, selected = selected, background = background, text = text, onClick = onClick)
}

/** A little "book page" preview: background colour + two text lines. */
@Composable
private fun ThemeSwatch(background: Color, text: Color) {
    Canvas(modifier = Modifier.size(44.dp, 30.dp)) {
        drawRoundRect(color = background, size = size, cornerRadius = CornerRadius(8f, 8f))
        drawRoundRect(
            color = text.copy(alpha = 0.75f),
            topLeft = Offset(8f, 9f),
            size = Size(28f, 3f),
            cornerRadius = CornerRadius(1.5f, 1.5f),
        )
        drawRoundRect(
            color = text.copy(alpha = 0.45f),
            topLeft = Offset(8f, 17f),
            size = Size(18f, 3f),
            cornerRadius = CornerRadius(1.5f, 1.5f),
        )
    }
}
