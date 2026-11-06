package com.koodoreader.reader.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import com.koodoreader.reader.shell.FontManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.koodoreader.reader.shell.ReaderFeatureCodec
import com.koodoreader.reader.shell.ReaderFeatureState
import com.koodoreader.reader.shell.t

/**
 * Presentation settings sheet (domain plan T3): paragraph spacing, brightness,
 * keep-screen-on, orientation and custom background. Every edit calls
 * [onChange] immediately so the open book updates live; persistence is the
 * caller's job (ReaderFeaturePrefs via commitFeatures).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsSheet(
    state: ReaderFeatureState,
    onChange: (ReaderFeatureState) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
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
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    t("Font"),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    TextButton(onClick = { fontMenuOpen = true }) {
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
                        )
                        fonts.forEach { font ->
                            DropdownMenuItem(
                                text = { Text(font.label) },
                                onClick = {
                                    onChange(state.copy(fontKey = font.key))
                                    fontMenuOpen = false
                                },
                            )
                        }
                    }
                }
            }
            Text(t("Paragraph spacing"), style = MaterialTheme.typography.labelLarge)
            Slider(
                value = ReaderFeatureCodec.clampedParagraphSpacing(state),
                onValueChange = { onChange(state.copy(paragraphSpacingPx = it)) },
                valueRange = ReaderFeatureCodec.PARAGRAPH_SPACING_MIN..ReaderFeatureCodec.PARAGRAPH_SPACING_MAX,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    t("Brightness"),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onChange(state.copy(brightness = -1f)) }) {
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
                listOf(0xFFFFFFFF.toInt(), 0xFFF5F0E1.toInt(), 0xFF1B1B1F.toInt(), 0xFF121212.toInt())
                    .forEach { argb ->
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(Color(argb), CircleShape)
                                .clickable { onChange(state.copy(customBackgroundArgb = argb)) },
                        )
                    }
                TextButton(onClick = { onChange(state.copy(customBackgroundArgb = null)) }) {
                    Text(t("Default"))
                }
            }
        }
    }
}
