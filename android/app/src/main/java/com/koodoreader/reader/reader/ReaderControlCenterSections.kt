package com.koodoreader.reader.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.koodoreader.core.designsystem.SpaceTokens
import com.koodoreader.core.designsystem.ThemeKind
import com.koodoreader.core.ui.component.koodoPressScale
import com.koodoreader.core.ui.theme.ThemeSpecBridge
import com.koodoreader.reader.epubhost.FoliateAppearance
import com.koodoreader.reader.shell.FontManager
import com.koodoreader.reader.shell.READER_THEME_OPTIONS
import com.koodoreader.reader.shell.ReaderChromeUiState
import com.koodoreader.reader.shell.ReaderFeatureCodec
import com.koodoreader.reader.shell.ReaderFeatureState
import com.koodoreader.reader.shell.builtInPreset
import com.koodoreader.reader.shell.t

@Composable
fun AppearanceSection(
    format: ReaderFormatKind,
    useFoliate: Boolean,
    readerThemeKind: ThemeKind,
    onTheme: (ThemeKind) -> Unit,
    fontScale: Float,
    onFontScale: (Float) -> Unit,
    lineHeight: Float,
    onLineHeight: (Float) -> Unit,
    marginDp: Int,
    onMarginDp: (Int) -> Unit,
    pdfReflow: Boolean,
) {
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        if (format != ReaderFormatKind.PDF && FoliateAppearance.showInReaderSheet(useFoliate)) {
            Text(t("Theme"), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(SpaceTokens.sm.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                READER_THEME_OPTIONS.forEach { kind ->
                    val preset = kind.builtInPreset()
                    val bg = ThemeSpecBridge.background(preset)
                    val fg = ThemeSpecBridge.foreground(preset)
                    val active = readerThemeKind == kind
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clickable { onTheme(kind) }
                            .padding(SpaceTokens.sm.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(bg)
                                .then(
                                    if (active) Modifier.border(2.dp, fg, CircleShape) else Modifier,
                                ),
                        )
                        Spacer(Modifier.height(SpaceTokens.xs.dp))
                        Text(
                            t(FoliateAppearance.themeLabelKey(kind)),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (active) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(SpaceTokens.md.dp))
        }
        val showTypography = format != ReaderFormatKind.PDF || pdfReflow
        if (showTypography) {
            Text(
                "${t("Font size")} · ${(fontScale * 100).toInt()}%",
                style = MaterialTheme.typography.titleMedium,
            )
            Slider(
                value = fontScale,
                onValueChange = onFontScale,
                valueRange = ReaderFeatureCodec.FONT_SCALE_MIN..ReaderFeatureCodec.FONT_SCALE_MAX,
                steps = 17,
            )
            if (format != ReaderFormatKind.PDF) {
                Text(
                    "${t("Line height")} · ${"%.2f".format(lineHeight)}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Slider(
                    value = lineHeight,
                    onValueChange = onLineHeight,
                    valueRange = ReaderFeatureCodec.LINE_HEIGHT_MIN..ReaderFeatureCodec.LINE_HEIGHT_MAX,
                    steps = 9,
                )
                Text(t("Margin"), style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(SpaceTokens.sm.dp)) {
                    SpaceTokens.READER_HORIZONTAL_OPTIONS.forEach { margin ->
                        FilterChip(
                            selected = marginDp == margin,
                            onClick = { onMarginDp(margin) },
                            modifier = Modifier.koodoPressScale(),
                            label = { Text("$margin dp") },
                        )
                    }
                }
            }
        } else if (format == ReaderFormatKind.PDF) {
            Text(
                text = t("Switch to PDF reflow view to adjust text size"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun LayoutSection(
    format: ReaderFormatKind,
    useFoliate: Boolean,
    state: ReaderFeatureState,
    onChange: (ReaderFeatureState) -> Unit,
) {
    Column {
        if (format != ReaderFormatKind.PDF && useFoliate) {
            Text(t("Layout"), style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                TextButton(onClick = { onChange(state.copy(scrolled = true)) }) {
                    Text(t("Scrolled") + if (state.scrolled) " ✓" else "")
                }
                TextButton(onClick = { onChange(state.copy(scrolled = false)) }) {
                    Text(t("Paginated") + if (!state.scrolled) " ✓" else "")
                }
            }
            Spacer(Modifier.height(SpaceTokens.sm.dp))
        }
        ReaderSettingSwitch(
            label = t("Double-page view"),
            checked = state.doublePage,
            onChange = { onChange(state.copy(doublePage = it)) },
        )
        if (format != ReaderFormatKind.PDF && useFoliate) {
            ReaderSettingSwitch(
                label = t("Two-column layout"),
                checked = ReaderFeatureCodec.clampedColumns(state) >= 2,
                onChange = { on ->
                    onChange(state.copy(columnsPerPage = if (on) 2 else 1))
                },
            )
        }
        if (format == ReaderFormatKind.REFLOW && !useFoliate) {
            ReaderSettingSwitch(
                label = t("Fast reading mode (make the first half of the word bold)"),
                checked = state.bionic,
                onChange = { onChange(state.copy(bionic = it)) },
            )
            ReaderSettingSwitch(
                label = t("Enable reading ruler"),
                checked = state.ruler,
                onChange = { onChange(state.copy(ruler = it)) },
            )
            ReaderSettingSwitch(
                label = t("Enable paragraph mode"),
                checked = state.paragraphMode,
                onChange = { onChange(state.copy(paragraphMode = it)) },
            )
        }
    }
}

@Composable
fun PdfDisplaySection(
    state: ReaderFeatureState,
    onChange: (ReaderFeatureState) -> Unit,
) {
    Column {
        ReaderSettingSwitch(
            label = t("PDF reflow view"),
            checked = state.pdfReflow,
            onChange = { onChange(state.copy(pdfReflow = it)) },
        )
        Text(
            "${t("PDF margin crop")} · ${ReaderFeatureCodec.clampedPdfCrop(state).toInt()} px",
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = state.pdfCropMarginPx,
            onValueChange = { onChange(state.copy(pdfCropMarginPx = it)) },
            valueRange = ReaderFeatureCodec.PDF_CROP_MIN..ReaderFeatureCodec.PDF_CROP_MAX,
            steps = 15,
        )
    }
}

@Composable
fun AdvancedSection(
    state: ReaderFeatureState,
    onChange: (ReaderFeatureState) -> Unit,
    chromeState: ReaderChromeUiState,
    onChromeState: (ReaderChromeUiState) -> Unit,
) {
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
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
            Text(t("Font"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Box {
                TextButton(
                    onClick = { fontMenuOpen = true },
                    modifier = Modifier.koodoPressScale(),
                ) {
                    Text(fonts.firstOrNull { it.key == state.fontKey }?.label ?: t("Default"))
                }
                DropdownMenu(expanded = fontMenuOpen, onDismissRequest = { fontMenuOpen = false }) {
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
            Text(t("Brightness"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
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
        ReaderSettingSwitch(
            label = t("Keep screen on"),
            checked = state.keepScreenOn,
            onChange = { onChange(state.copy(keepScreenOn = it)) },
        )
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

@Composable
internal fun ReaderSettingSwitch(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}