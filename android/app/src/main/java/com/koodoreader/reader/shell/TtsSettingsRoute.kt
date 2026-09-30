package com.koodoreader.reader.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.koodoreader.feature.tts.DataStoreTtsConfigStore
import com.koodoreader.feature.tts.TtsConfig
import com.koodoreader.feature.tts.TtsConfigRepository
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → Text to speech (settings page 2026-09-29 spec §3.6).
 *
 * Speaks through the platform TTS engine; the three sliders bind to
 * [TtsConfig] (voiceSpeed / pitch / volume) and persist through
 * [TtsConfigRepository] → the DataStore adapter (the SAME store the reader's
 * TTS service reads — one fact source). Voice/engine pickers need the device
 * TTS service and stay a later card, so they are listed as a note, not as
 * dead rows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsSettingsRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val repository = remember {
        TtsConfigRepository(DataStoreTtsConfigStore.from(context.applicationContext))
    }

    var rate by remember { mutableFloatStateOf(TtsConfig.DEFAULT.voiceSpeed) }
    var pitch by remember { mutableFloatStateOf(TtsConfig.DEFAULT.pitch) }
    var volume by remember { mutableFloatStateOf(TtsConfig.DEFAULT.volume) }

    var loaded by remember { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(repository) {
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            val config = repository.current()
            rate = config.voiceSpeed
            pitch = config.pitch
            volume = config.volume
        }
        loaded = true
    }

    fun persist() {
        // The three sliders are the only mutable fields here; everything else
        // (voice/engine/locale) keeps its current value.
        MainScope().launch {
            repository.update { current ->
                current.copy(voiceSpeed = rate, pitch = pitch, volume = volume)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t("Text to speech")) },
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
            if (!loaded) {
                Text(t("Loading"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }

            Text(
                text = "${t("Speed")} · ${formatRate(rate)}",
                style = MaterialTheme.typography.titleMedium,
            )
            Slider(
                value = rate,
                onValueChange = { rate = it },
                onValueChangeFinished = { persist() },
                valueRange = 0.5f..2.0f,
                steps = 5,
            )

            Spacer(Modifier.height(8.dp))
            Text(
                text = "${t("Pitch")} · ${pitch.toString()}",
                style = MaterialTheme.typography.titleMedium,
            )
            Slider(
                value = pitch,
                onValueChange = { pitch = it },
                onValueChangeFinished = { persist() },
                valueRange = 0.5f..2.0f,
                steps = 5,
            )

            Spacer(Modifier.height(8.dp))
            Text(
                text = "${t("Volume")} · ${(volume * 100).toInt()}%",
                style = MaterialTheme.typography.titleMedium,
            )
            Slider(
                value = volume,
                onValueChange = { volume = it },
                onValueChangeFinished = { persist() },
                valueRange = 0f..1f,
                steps = 9,
            )

            Spacer(Modifier.height(16.dp))
            TextButton(onClick = {
                MainScope().launch {
                    val reset = repository.reset()
                    rate = reset.voiceSpeed
                    pitch = reset.pitch
                    volume = reset.volume
                }
            }) { Text(t("Reset")) }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "音色与引擎选择依赖设备 TTS 服务，随后续功能卡加入",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** `1.25x` style label for the rate value. */
private fun formatRate(value: Float): String =
    if (value == value.toInt().toFloat()) "${value.toInt()}x" else "${value}x"
