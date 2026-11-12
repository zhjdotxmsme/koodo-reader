package com.koodoreader.reader.epubhost

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.koodoreader.core.designsystem.SpaceTokens
import com.koodoreader.core.designsystem.ThemeKind
import com.koodoreader.core.ui.theme.ThemeSpecBridge
import com.koodoreader.reader.shell.READER_THEME_OPTIONS
import com.koodoreader.reader.shell.ReaderFeatureCodec
import com.koodoreader.reader.shell.builtInPreset
import com.koodoreader.reader.shell.t

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ReaderAppearanceSheet(
    selected: ThemeKind,
    fontScale: Float,
    lineHeight: Float,
    marginDp: Int,
    scrolled: Boolean,
    onTheme: (ThemeKind) -> Unit,
    onFontScale: (Float) -> Unit,
    onLineHeight: (Float) -> Unit,
    onMarginDp: (Int) -> Unit,
    onScrolled: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SpaceTokens.READER_HORIZONTAL_DEFAULT.dp)
                .padding(top = SpaceTokens.sm.dp, bottom = SpaceTokens.md.dp),
        ) {
            Text(t("Appearance"), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(SpaceTokens.LIST_ITEM_VERTICAL.dp))
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
                    val active = selected == kind
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
                                    if (active) {
                                        Modifier.border(2.dp, fg, CircleShape)
                                    } else {
                                        Modifier
                                    },
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
            Text(t("Layout"), style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                TextButton(onClick = { onScrolled(true) }) {
                    Text(t("Scrolled") + if (scrolled) " ✓" else "")
                }
                TextButton(onClick = { onScrolled(false) }) {
                    Text(t("Paginated") + if (!scrolled) " ✓" else "")
                }
            }
            Spacer(Modifier.height(SpaceTokens.md.dp))
            Text(
                "${t("Font size")} · ${(fontScale * 100).toInt()}%",
                style = MaterialTheme.typography.titleMedium,
            )
            Slider(
                value = fontScale,
                onValueChange = onFontScale,
                valueRange = ReaderFeatureCodec.FONT_SCALE_MIN..ReaderFeatureCodec.FONT_SCALE_MAX,
                steps = 7,
            )
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
                        label = { Text("$margin dp") },
                    )
                }
            }
            Spacer(Modifier.height(SpaceTokens.md.dp))
        }
    }
}
