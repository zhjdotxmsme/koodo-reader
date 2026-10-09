package com.koodoreader.reader.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.koodoreader.feature.tts.DataStoreTtsConfigStore
import com.koodoreader.feature.tts.EngineEnumerator
import com.koodoreader.feature.tts.TtsConfig
import com.koodoreader.feature.tts.TtsConfigRepository
import com.koodoreader.feature.tts.TtsEngineKind
import com.koodoreader.feature.tts.TtsEngineOption
import com.koodoreader.feature.tts.TtsVoiceCatalog
import kotlinx.coroutines.launch

/**
 * Settings → Text to speech (settings page 2026-09-29 spec §3.6).
 *
 * Speaks through the platform TTS engine; the three sliders bind to
 * [TtsConfig] (voiceSpeed / pitch / volume) and persist through
 * [TtsConfigRepository] → the DataStore adapter (the SAME store the reader's
 * TTS service reads — one fact source).
 *
 * The "Voice" section lists what [TtsVoiceCatalog.pickerEntries] produces from
 * [EngineEnumerator]: the system synthesizer + every installed Android TTS
 * engine + the 15 desktop plugin keys (greyed out, config-compat). Tapping a row
 * persists both [TtsConfig.voiceEngine] and [TtsConfig.enginePackage] so the
 * foreground service and the lock-screen card bind to the same engine after a
 * restart (P6 card follow-up wiring).
 *
 * The `MainScope().launch` callsites in `persist()` / `Reset` were intentionally
 * left untouched; the dedicated refactor (`t-mv0hf34m-3j1k07`) moves the whole
 * screen to `ReaderViewModel.viewModelScope`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsSettingsRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val repository = remember {
        TtsConfigRepository(DataStoreTtsConfigStore.from(context.applicationContext))
    }
    val scope = rememberCoroutineScope()

    var rate by remember { mutableFloatStateOf(TtsConfig.DEFAULT.voiceSpeed) }
    var pitch by remember { mutableFloatStateOf(TtsConfig.DEFAULT.pitch) }
    var volume by remember { mutableFloatStateOf(TtsConfig.DEFAULT.volume) }

    var loaded by remember { mutableStateOf(false) }
    var currentEngine by remember { mutableStateOf("") }
    var currentEnginePackage by remember { mutableStateOf("") }
    var voiceEntries by remember { mutableStateOf<List<TtsEngineOption>>(emptyList()) }
    var voiceEntriesLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(repository) {
        val config = repository.current()
        rate = config.voiceSpeed
        pitch = config.pitch
        volume = config.volume
        currentEngine = config.voiceEngine
        currentEnginePackage = config.enginePackage
        loaded = true
    }

    LaunchedEffect(Unit) {
        // PackageManager.queryIntentActivities is synchronous and fast, but the
        // helper is allowed to touch I/O — keep it off the UI thread so a slow
        // package manager (rare vendor forks) can't jank the slider.
        val installed = try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                EngineEnumerator(context.applicationContext).engines()
            }
        } catch (_: Exception) {
            emptyList()
        }
        voiceEntries = TtsVoiceCatalog.pickerEntries(installed)
        voiceEntriesLoaded = true
    }

    fun persistSliders() {
        // Three-sliders only; voice / engine / locale keep their current value.
        // Left at `MainScope` for the dedicated refactor card (see file header).
        kotlinx.coroutines.MainScope().launch {
            repository.update { current ->
                current.copy(voiceSpeed = rate, pitch = pitch, volume = volume)
            }
        }
    }

    fun resetAll() {
        kotlinx.coroutines.MainScope().launch {
            val reset = repository.reset()
            rate = reset.voiceSpeed
            pitch = reset.pitch
            volume = reset.volume
            currentEngine = reset.voiceEngine
            currentEnginePackage = reset.enginePackage
        }
    }

    fun selectEngine(option: TtsEngineOption) {
        // Desktop plugin keys are persisted unchanged so a config carried over
        // from the desktop app resolves back to the same key (catalog says so
        // explicitly) — `ForegroundTtsService` falls back to the system
        // synthesizer instead of failing. Voice picker uses the new
        // `rememberCoroutineScope()` rather than the existing `MainScope()` so
        // cancellation tracks the route's recomposition.
        scope.launch {
            repository.update { stored ->
                stored.copy(
                    voiceEngine = option.engine,
                    enginePackage = if (option.kind == TtsEngineKind.ANDROID_ENGINE) option.packageName else "",
                    voiceName = "",
                    voiceLocale = "",
                )
            }
            currentEngine = option.engine
            currentEnginePackage = if (option.kind == TtsEngineKind.ANDROID_ENGINE) option.packageName else ""
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
                onValueChangeFinished = { persistSliders() },
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
                onValueChangeFinished = { persistSliders() },
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
                onValueChangeFinished = { persistSliders() },
                valueRange = 0f..1f,
                steps = 9,
            )

            Spacer(Modifier.height(16.dp))
            TextButton(onClick = { resetAll() }) { Text(t("Reset")) }

            Spacer(Modifier.height(8.dp))
            VoicePickerSection(
                entries = voiceEntries,
                loaded = voiceEntriesLoaded,
                currentEngine = currentEngine,
                currentEnginePackage = currentEnginePackage,
                onSelect = { selectEngine(it) },
            )
        }
    }
}

/**
 * "Voice" / "Engine" picker — lists what [TtsVoiceCatalog.pickerEntries]
 * produced, with the currently stored row pre-selected.
 *
 * Material 3 Row + RadioButton pattern, matching the rest of the settings
 * routes (see ADR-004). Unavailable (desktop plugin) rows show greyed label
 * instead of the package subtitle; the click still persists so a restored
 * desktop config survives round-trip.
 */
@Composable
private fun VoicePickerSection(
    entries: List<TtsEngineOption>,
    loaded: Boolean,
    currentEngine: String,
    currentEnginePackage: String,
    onSelect: (TtsEngineOption) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = t("Voice"),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        when {
            !loaded -> Text(
                text = t("Loading"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            entries.isEmpty() -> Text(
                text = t("No engines available"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> entries.forEach { option ->
                VoicePickerRow(
                    option = option,
                    selected = optionMatches(option, currentEngine, currentEnginePackage),
                    onSelect = onSelect,
                )
            }
        }
    }
}

/**
 * A picker row. Tap area covers the whole row; uses [Modifier.selectable] so
 * TalkBack announces the radio semantics.
 */
@Composable
private fun VoicePickerRow(
    option: TtsEngineOption,
    selected: Boolean,
    onSelect: (TtsEngineOption) -> Unit,
) {
    val labelColor = if (option.available) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val subtitle = when (option.kind) {
        TtsEngineKind.SYSTEM -> t("Platform synthesizer")
        TtsEngineKind.ANDROID_ENGINE -> option.packageName
        TtsEngineKind.DESKTOP_VOICE_PLUGIN -> when {
            option.localeHint == "*" -> t("Desktop only")
            else -> "${t("Desktop only")} · ${option.localeHint}"
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = { onSelect(option) },
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = option.label,
                style = MaterialTheme.typography.bodyLarge,
                color = labelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Matches a stored config back to its picker row (engine + package for Android engines). */
private fun optionMatches(
    option: TtsEngineOption,
    currentEngine: String,
    currentEnginePackage: String,
): Boolean = when (option.kind) {
    TtsEngineKind.SYSTEM -> currentEngine.isBlank() || currentEngine == option.engine
    TtsEngineKind.ANDROID_ENGINE -> currentEngine == option.engine ||
        (currentEnginePackage.isNotBlank() && currentEnginePackage == option.packageName)
    TtsEngineKind.DESKTOP_VOICE_PLUGIN -> currentEngine == option.engine
}

/** `1.25x` style label for the rate value. */
private fun formatRate(value: Float): String =
    if (value == value.toInt().toFloat()) "${value.toInt()}x" else "${value}x"
