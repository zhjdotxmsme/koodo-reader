package com.koodoreader.reader.shell

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
    var state by remember { mutableStateOf(store.load()) }
    var pattern by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }

    fun commit(next: ReaderFeatureState) {
        state = next
        store.save(next)
        ReaderTextPipeline.setRulesJson(next.textRulesJson)
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
