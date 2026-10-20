package com.koodoreader.reader.epubhost

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
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.koodoreader.core.designsystem.ThemeKind
import com.koodoreader.core.ui.theme.ThemeSpecBridge
import com.koodoreader.reader.shell.READER_THEME_OPTIONS
import com.koodoreader.reader.shell.ReaderFeatureCodec
import com.koodoreader.reader.shell.builtInPreset
import com.koodoreader.reader.shell.t

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderAppearanceSheet(
    selected: ThemeKind,
    fontScale: Float,
    lineHeight: Float,
    marginPx: Float,
    scrolled: Boolean,
    onTheme: (ThemeKind) -> Unit,
    onFontScale: (Float) -> Unit,
    onLineHeight: (Float) -> Unit,
    onMargin: (Float) -> Unit,
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(t("Appearance"), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Text(t("Theme"), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
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
                            .padding(8.dp),
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
                        Spacer(Modifier.height(6.dp))
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
            Spacer(Modifier.height(16.dp))
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
            Spacer(Modifier.height(16.dp))
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
            Text(
                "${t("Line height")} · ${"%.2f".format(lineHeight)}",
                style = MaterialTheme.typography.titleMedium,
            )
            Slider(
                value = lineHeight,
                onValueChange = onLineHeight,
                valueRange = ReaderFeatureCodec.LINE_HEIGHT_MIN..ReaderFeatureCodec.LINE_HEIGHT_MAX,
                steps = 3,
            )
            Text(
                "${t("Margin")} · ${marginPx.toInt()} px",
                style = MaterialTheme.typography.titleMedium,
            )
            Slider(
                value = marginPx,
                onValueChange = onMargin,
                valueRange = ReaderFeatureCodec.MARGIN_MIN..ReaderFeatureCodec.MARGIN_MAX,
                steps = 23,
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}
