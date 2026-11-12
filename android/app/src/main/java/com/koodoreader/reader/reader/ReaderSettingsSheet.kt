package com.koodoreader.reader.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.koodoreader.core.designsystem.SpaceTokens
import com.koodoreader.core.ui.component.koodoPressScale
import com.koodoreader.reader.shell.FontManager
import com.koodoreader.reader.shell.ReaderChromeUiState
import com.koodoreader.reader.shell.ReaderFeatureCodec
import com.koodoreader.reader.shell.ReaderFeatureState
import com.koodoreader.reader.shell.t

/**
 * Presentation settings sheet (domain plan T3): paragraph spacing, brightness,
 * keep-screen-on, orientation and custom background. Every edit calls
 * [onChange] immediately so the open book updates live; persistence is the
 * caller's job (ReaderFeaturePrefs via commitFeatures).
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ReaderSettingsSheet(
    state: ReaderFeatureState,
    onChange: (ReaderFeatureState) -> Unit,
    chromeState: ReaderChromeUiState = ReaderChromeUiState(),
    onChromeState: (ReaderChromeUiState) -> Unit = {},
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SpaceTokens.READER_HORIZONTAL_DEFAULT.dp)
                .padding(top = SpaceTokens.sm.dp, bottom = SpaceTokens.md.dp),
        ) {
            ReaderSettingSwitch(
                label = t("Full-screen reading"),
                checked = chromeState.fullScreen,
                onChange = { onChromeState(chromeState.copy(fullScreen = it)) },
            )
            ReaderSettingSwitch(
                label = t("First-line indent"),
                checked = chromeState.firstLineIndent,
                onChange = { onChromeState(chromeState.copy(firstLineIndent = it)) },
            )
            Text(t("Page margin"), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(SpaceTokens.sm.dp)) {
                SpaceTokens.READER_HORIZONTAL_OPTIONS.forEach { margin ->
                    FilterChip(
                        selected = chromeState.horizontalMarginDp == margin,
                        onClick = { onChromeState(chromeState.copy(horizontalMarginDp = margin)) },
                        modifier = Modifier.koodoPressScale(),
                        label = { Text("$margin dp") },
                    )
                }
            }
            val context = LocalContext.current
            val fonts = remember(context) { FontManager.systemFonts(context) }
            var fontMenuOpen by remember { mutableStateOf(false) }
            Text(
                t("Font size") + "  ${(state.fontScale * 100).toInt()}%",
                style = MaterialTheme.typography.labelLarge,
            )
            Slider(
                value = state.fontScale.coerceIn(
                    ReaderFeatureCodec.FONT_SCALE_MIN,
                    ReaderFeatureCodec.FONT_SCALE_MAX,
                ),
                onValueChange = { onChange(state.copy(fontScale = it)) },
                valueRange = ReaderFeatureCodec.FONT_SCALE_MIN..ReaderFeatureCodec.FONT_SCALE_MAX,
                steps = 7,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    t("Font"),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    TextButton(
                        onClick = { fontMenuOpen = true },
                        modifier = Modifier.koodoPressScale(),
                    ) {
                        Text(fonts.firstOrNull { it.key == state.fontKey }?.label ?: t("Default"))
                    }
                    DropdownMenu(
                        expanded = fontMenuOpen,
                        onDismissRequest = { fontMenuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(t("Default")) },
                            onClick = {
                                onChange(state.copy(fontKey = ""))
                                fontMenuOpen = false
                            },
                            modifier = Modifier.koodoPressScale(),
                        )
                        fonts.forEach { font ->
                            DropdownMenuItem(
                                text = { Text(font.label) },
                                onClick = {
                                    onChange(state.copy(fontKey = font.key))
                                    fontMenuOpen = false
                                },
                                modifier = Modifier.koodoPressScale(),
                            )
                        }
                    }
                }
            }
            Text(t("Paragraph spacing"), style = MaterialTheme.typography.labelLarge)
            val paragraphGap = ReaderFeatureCodec.effectiveParagraphSpacingPx(state)
            Slider(
                value = paragraphGap,
                onValueChange = { onChange(state.copy(paragraphSpacingPx = it)) },
                valueRange = 0f..maxOf(ReaderFeatureCodec.PARAGRAPH_SPACING_MAX, paragraphGap),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    t("Brightness"),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { onChange(state.copy(brightness = -1f)) },
                    modifier = Modifier.koodoPressScale(),
                ) {
                    Text(t("Follow system"))
                }
            }
            Slider(
                value = state.brightness.coerceIn(0f, 1f),
                onValueChange = { onChange(state.copy(brightness = it)) },
                enabled = state.brightness >= 0f,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    t("Keep screen on"),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = state.keepScreenOn,
                    onCheckedChange = { onChange(state.copy(keepScreenOn = it)) },
                )
            }
            Text(t("Orientation"), style = MaterialTheme.typography.labelLarge)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 8.dp),
            ) {
                listOf(
                    ReaderFeatureCodec.ORIENTATION_SYSTEM to t("Auto"),
                    ReaderFeatureCodec.ORIENTATION_PORTRAIT to t("Portrait"),
                    ReaderFeatureCodec.ORIENTATION_LANDSCAPE to t("Landscape"),
                ).forEach { (value, label) ->
                    FilterChip(
                        selected = state.orientation == value,
                        onClick = { onChange(state.copy(orientation = value)) },
                        modifier = Modifier.koodoPressScale(),
                        label = { Text(label) },
                    )
                }
            }
            Text(t("Custom background"), style = MaterialTheme.typography.labelLarge)
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 12.dp),
            ) {
                listOf(
                    0xFFFFFFFF.toInt() to t("White background"),
                    0xFFF5F0E1.toInt() to t("Paper background"),
                    0xFF1B1B1F.toInt() to t("Dark gray background"),
                    0xFF121212.toInt() to t("Black background"),
                ).forEach { (argb, description) ->
                    // 32dp 色块外包 48dp 触达盒（IconButton 默认最小尺寸）。
                    IconButton(
                        onClick = { onChange(state.copy(customBackgroundArgb = argb)) },
                        modifier = Modifier.koodoPressScale(),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(Color(argb), CircleShape)
                                .semantics { contentDescription = description },
                        )
                    }
                }
                TextButton(
                    onClick = { onChange(state.copy(customBackgroundArgb = null)) },
                    modifier = Modifier.koodoPressScale(),
                ) {
                    Text(t("Default"))
                }
            }
        }
    }
}

@Composable
private fun ReaderSettingSwitch(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
