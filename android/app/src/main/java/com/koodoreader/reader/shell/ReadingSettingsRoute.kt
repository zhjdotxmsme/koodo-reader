package com.koodoreader.reader.shell

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.koodoreader.engine.feature.TextRule
import com.koodoreader.engine.feature.TextRuleJson

/**
 * Settings → Reading: font, line height, margin, and the P6 enhancements
 * (bionic, ruler, paragraph mode, RSVP, text rules).
 *
 * One [ReaderFeatureState] persisted by [ReaderFeaturePrefs] — the same store
 * the reader screen reads, so A−/A+ and these sliders cannot drift.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingSettingsRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { ReaderFeaturePrefs(context) }
    val gestureStore = remember(context) { ReaderGesturePrefs(context) }
    var state by remember { mutableStateOf(store.load()) }
    var gesture by remember { mutableStateOf(gestureStore.load()) }
    var pattern by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }

    fun commit(next: ReaderFeatureState) {
        state = next
        store.save(next)
        ReaderTextPipeline.setRulesJson(next.textRulesJson)
    }

    fun commitGesture(next: ReaderGestureConfig) {
        gesture = next
        gestureStore.save(next)
    }

    val fontPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val item = FontManager.importFont(context, uri) ?: return@rememberLauncherForActivityResult
        commit(state.copy(fontKey = item.id))
    }

    val rules = remember(state.textRulesJson) {
        if (state.textRulesJson.isBlank()) emptyList()
        else TextRuleJson.importRules(state.textRulesJson).rules
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t("Reading")) },
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
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(8.dp))

            Text(t("Performance profile"), style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(onClick = {
                    commit(state.copy(perfTier = ReaderPerformancePolicy.TIER_NORMAL))
                }) {
                    Text(
                        t("Normal") +
                            if (state.perfTier == ReaderPerformancePolicy.TIER_NORMAL) " ✓" else "",
                    )
                }
                TextButton(onClick = {
                    commit(
                        state.copy(
                            perfTier = ReaderPerformancePolicy.TIER_LITE,
                            columnsPerPage = 1,
                        ),
                    )
                }) {
                    Text(
                        t("Lite (low memory)") +
                            if (state.perfTier == ReaderPerformancePolicy.TIER_LITE) " ✓" else "",
                    )
                }
            }

            Text(t("Reader gestures"), style = MaterialTheme.typography.titleMedium)
            Text(
                text = "${t("Tap zone width")} · ${"%.0f".format(ReaderGestureCodec.clampedTapEdge(gesture) * 100)}%",
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = gesture.tapEdgeFraction,
                onValueChange = {
                    commitGesture(gesture.copy(tapEdgeFraction = it))
                },
                valueRange = ReaderGestureCodec.TAP_EDGE_MIN..ReaderGestureCodec.TAP_EDGE_MAX,
                steps = 5,
            )
            FeatureSwitch(
                title = t("Swipe to turn pages"),
                checked = gesture.swipePageTurn,
                onChecked = { commitGesture(gesture.copy(swipePageTurn = it)) },
            )
            FeatureSwitch(
                title = t("Volume keys turn pages"),
                checked = gesture.volumePageTurn,
                onChecked = { commitGesture(gesture.copy(volumePageTurn = it)) },
            )
            FeatureSwitch(
                title = t("Invert volume turn direction"),
                checked = gesture.volumeInverted,
                onChecked = { commitGesture(gesture.copy(volumeInverted = it)) },
            )

            Spacer(Modifier.height(8.dp))
            val percent = (state.fontScale * 100).toInt().toString()
            Text(
                text = "${t("Font size")} · ${percent}%",
                style = MaterialTheme.typography.titleMedium,
            )
            Slider(
                value = state.fontScale,
                onValueChange = { commit(state.copy(fontScale = it)) },
                valueRange = ReaderFeatureCodec.FONT_SCALE_MIN..ReaderFeatureCodec.FONT_SCALE_MAX,
                steps = 27,
            )

            Text(t("Font"), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { commit(state.copy(fontKey = "")) }) {
                Text(t("Built-in font") + if (state.fontKey.isBlank()) " ✓" else "")
            }
            FontCatalog.bundled.forEach { font ->
                TextButton(onClick = { commit(state.copy(fontKey = font.key)) }) {
                    Text(font.label + if (state.fontKey == font.key) " ✓" else "")
                }
            }
            FontManager.installedCustom(context).forEach { item ->
                TextButton(onClick = { commit(state.copy(fontKey = item.id)) }) {
                    Text(item.label + if (state.fontKey == item.id) " ✓" else "")
                }
            }
            TextButton(onClick = { fontPicker.launch(arrayOf("font/ttf", "font/otf", "*/*")) }) {
                Text(t("Import local font"))
            }

            FeatureSwitch(
                title = t("Two-column layout"),
                checked = ReaderFeatureCodec.clampedColumns(state) >= 2,
                onChecked = { on -> commit(state.copy(columnsPerPage = if (on) 2 else 1)) },
            )
            FeatureSwitch(
                title = t("Double-page view"),
                checked = state.doublePage,
                onChecked = { commit(state.copy(doublePage = it)) },
            )

            Text(t("PDF display"), style = MaterialTheme.typography.titleMedium)
            FeatureSwitch(
                title = t("PDF reflow view"),
                checked = state.pdfReflow,
                onChecked = { commit(state.copy(pdfReflow = it)) },
            )
            Text(
                text = "${t("PDF margin crop")} · ${ReaderFeatureCodec.clampedPdfCrop(state).toInt()} px",
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = state.pdfCropMarginPx,
                onValueChange = { commit(state.copy(pdfCropMarginPx = it)) },
                valueRange = ReaderFeatureCodec.PDF_CROP_MIN..ReaderFeatureCodec.PDF_CROP_MAX,
                steps = 15,
            )

            Text(
                text = "${t("Line height")} · ${"%.2f".format(ReaderFeatureCodec.clampedLineHeight(state))}",
                style = MaterialTheme.typography.titleMedium,
            )
            Slider(
                value = state.lineHeightRatio,
                onValueChange = { commit(state.copy(lineHeightRatio = it)) },
                valueRange = ReaderFeatureCodec.LINE_HEIGHT_MIN..ReaderFeatureCodec.LINE_HEIGHT_MAX,
                steps = 3,
            )

            Text(
                text = "${t("Margin")} · ${ReaderFeatureCodec.clampedMargin(state).toInt()} px",
                style = MaterialTheme.typography.titleMedium,
            )
            Slider(
                value = state.marginHorizontalPx,
                onValueChange = { commit(state.copy(marginHorizontalPx = it)) },
                valueRange = ReaderFeatureCodec.MARGIN_MIN..ReaderFeatureCodec.MARGIN_MAX,
                steps = 23,
            )

            Spacer(Modifier.height(8.dp))
            FeatureSwitch(
                title = t("Fast reading mode (make the first half of the word bold)"),
                checked = state.bionic,
                onChecked = { commit(state.copy(bionic = it)) },
            )
            FeatureSwitch(
                title = t("Enable reading ruler"),
                checked = state.ruler,
                onChecked = { commit(state.copy(ruler = it)) },
            )
            if (state.ruler) {
                Text(
                    text = "${t("Reading ruler height (lines)")} · ${state.rulerLineHeight.toInt()}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = state.rulerLineHeight,
                    onValueChange = { commit(state.copy(rulerLineHeight = it)) },
                    valueRange = 1f..20f,
                    steps = 18,
                )
            }
            FeatureSwitch(
                title = t("Enable paragraph mode"),
                checked = state.paragraphMode,
                onChecked = { commit(state.copy(paragraphMode = it)) },
            )
            FeatureSwitch(
                title = t("Enable speed reading"),
                checked = state.speedReading,
                onChecked = { commit(state.copy(speedReading = it)) },
            )
            if (state.speedReading) {
                Text(
                    text = "${t("Enable speed reading")} · ${ReaderFeatureCodec.clampedWpm(state)} WPM",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = state.speedWpm.toFloat(),
                    onValueChange = { commit(state.copy(speedWpm = it.toInt())) },
                    valueRange = ReaderFeatureCodec.SPEED_WPM_MIN.toFloat()..ReaderFeatureCodec.SPEED_WPM_MAX.toFloat(),
                    steps = 15,
                )
            }

            Spacer(Modifier.height(16.dp))
            FeatureSwitch(
                title = t("Custom book style (CSS)"),
                checked = state.customBookCssEnabled,
                onChecked = { commit(state.copy(customBookCssEnabled = it)) },
            )
            if (state.customBookCssEnabled) {
                OutlinedTextField(
                    value = state.customBookCss,
                    onValueChange = { commit(state.copy(customBookCss = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(t("Enter custom CSS here")) },
                    minLines = 4,
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(t("Text rules"), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = pattern,
                onValueChange = { pattern = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(t("Match pattern")) },
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = replacement,
                onValueChange = { replacement = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(t("Replacement")) },
                singleLine = true,
            )
            TextButton(
                onClick = {
                    val p = pattern.trim()
                    if (p.isEmpty()) return@TextButton
                    val nextRules = rules + TextRule(
                        id = System.currentTimeMillis().toString(),
                        pattern = p,
                        replacement = replacement,
                    )
                    pattern = ""
                    replacement = ""
                    commit(state.copy(textRulesJson = TextRuleJson.exportRules(nextRules)))
                },
            ) { Text(t("Add replacement rule")) }
            rules.forEach { rule ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${rule.pattern} → ${rule.replacement}",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(
                        onClick = {
                            val nextRules = rules.filterNot { it.id == rule.id }
                            commit(state.copy(textRulesJson = TextRuleJson.exportRules(nextRules)))
                        },
                    ) { Text(t("Delete")) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun FeatureSwitch(
    title: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
