package com.koodoreader.feature.tts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Lock-screen control UI, in-app half (DoD [3]).
 *
 * "弹窗骨架" on purpose: the layout and the state wiring are complete and driven by
 * the pure [TtsControlUiState], while the platform surfaces the user actually
 * touches when the phone is locked (the ongoing notification and the MediaSession
 * card) are built by [ForegroundTtsService] / [MediaSessionController]. Both paths
 * emit the same [TtsMediaCommand]s, so the in-app sheet and the lock-screen card
 * can never drift apart.
 *
 * Conventions this file follows (see CLAUDE.md / ADR-004):
 *  - **no hard-coded user-visible text**: every label goes through [translate],
 *    which the host wires to `com.koodoreader.core.common.Localization::t`; the keys
 *    are the desktop ones (`Play` / `Resume` / `Pause` / `Stop` / `Previous` / `Next`
 *    / `Speed` / `Voice` / `Please select`), so the wording matches the desktop UI
 *    and the catalogs stay shared;
 *  - **no icon dependency**: `material-icons-core` does not carry a full transport
 *    set, and the labels are needed for accessibility anyway;
 *  - **no Android service reference**: the sheet takes state + callbacks, so it is
 *    previewable and usable from any host screen.
 *
 * @param state pure UI state, build it with [TtsControlUiState.of].
 * @param onCommand transport command (play / pause / next / previous / stop).
 * @param onSpeedChange speaking-rate change, persisted by the host through
 *   `ForegroundTtsService.updateConfig { it.copy(voiceSpeed = value) }`.
 * @param onPitchChange pitch change (`TextToSpeech.setPitch`, Android-only knob).
 * @param onVolumeChange 0.0–1.0 volume change (`KEY_PARAM_VOLUME`).
 * @param onVoiceClick opens the voice/engine picker (`TtsVoiceCatalog.pickerEntries`).
 * @param onDismiss closes the sheet.
 * @param translate i18n lookup; defaults to identity (key == label), like the
 *   desktop fallback for a missing catalog entry.
 */
@Composable
fun TtsControlSheet(
    state: TtsControlUiState,
    onCommand: (TtsMediaCommand) -> Unit,
    onDismiss: () -> Unit,
    onSpeedChange: (Float) -> Unit = {},
    onPitchChange: (Float) -> Unit = {},
    onVolumeChange: (Float) -> Unit = {},
    onVoiceClick: () -> Unit = {},
    translate: (String) -> String = { it },
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = if (state.progressLabel.isEmpty()) {
                        translate("Text to speech")
                    } else {
                        state.progressLabel
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = state.engineLabel,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

                if (state.errorKey.isNotEmpty()) {
                    Text(
                        text = translate(state.errorKey),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                // Voice row — desktop shows `t("Please select")` until a voice is picked.
                TextButton(onClick = onVoiceClick, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = translate("Voice") + ": " + (
                            if (state.needsVoiceSelection) translate("Please select") else state.voiceName
                            ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Transport row: the same commands the lock-screen card and media keys send.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { onCommand(TtsMediaCommand.PREVIOUS) }) {
                        Text(translate("Previous"))
                    }
                    TextButton(
                        onClick = { onCommand(state.primaryCommand) },
                        enabled = state.canPlay,
                    ) {
                        Text(translate(state.primaryLabelKey))
                    }
                    TextButton(onClick = { onCommand(TtsMediaCommand.NEXT) }) {
                        Text(translate("Next"))
                    }
                    TextButton(onClick = { onCommand(TtsMediaCommand.STOP) }) {
                        Text(translate("Stop"))
                    }
                }

                // Rate / pitch / volume (DoD [1]: 语速 / 语调 / 音量).
                LabelledSlider(
                    label = translate("Speed"),
                    value = state.speed,
                    valueRange = TtsControlUiState.SPEED_MIN..TtsControlUiState.SPEED_MAX,
                    onValueChange = onSpeedChange,
                )
                LabelledSlider(
                    label = translate("Pitch"),
                    value = state.pitch,
                    valueRange = TtsPitch.MIN..TtsPitch.MAX,
                    onValueChange = onPitchChange,
                )
                LabelledSlider(
                    label = translate("Volume"),
                    value = state.volume,
                    valueRange = 0f..1f,
                    onValueChange = onVolumeChange,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(translate("Done")) }
        },
    )
}

/** Label + slider row; the label is the i18n key resolved by the caller. */
@Composable
private fun LabelledSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = label, style = MaterialTheme.typography.bodySmall)
            Text(text = formatValue(value), style = MaterialTheme.typography.bodySmall)
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

/** `1` instead of `1.0`, `1.5` instead of `1.50` — matches the desktop speed display. */
private fun formatValue(value: Float): String =
    if (value == value.toInt().toFloat()) value.toInt().toString() else String.format("%.2f", value)
