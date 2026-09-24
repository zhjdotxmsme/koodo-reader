package com.koodoreader.feature.tts

/**
 * The 15 desktop voice plugins, reduced to the labels the Android UI can show.
 *
 * WHY LABELS: the desktop plugins (`plugins/main/voice/`, listed in
 * `src/utils/plugins/catalog.ts` as `type: "voice"`) are Electron-side HTTP /
 * CLI clients for cloud or self-hosted synthesis back ends. P6 deliberately does
 * **not** port the plugin runtime (the card says so explicitly). What has to
 * survive is *config compatibility*: `voiceEngine` is persisted in the desktop
 * reader config, so an imported/restored profile may still name a plugin key.
 * Android resolves such a key to [TtsEngineKind.DESKTOP_VOICE_PLUGIN] — it is
 * shown in the voice picker, marked unavailable, and speaking falls back to the
 * system synthesizer instead of failing (desktop parity detail: the desktop code
 * already falls back to `system` when a voice cannot be resolved —
 * `textToSpeech/component.tsx`, `ConfigService.setReaderConfig("voiceEngine", "system")`).
 *
 * Pure Kotlin: no `android.*` import, fully unit-tested.
 *
 * Desktop source of truth for the keys/display names: `src/utils/plugins/catalog.ts`
 * (grep `type: "voice"` → 15 entries).
 */
data class TtsVoiceLabel(
    /** Desktop plugin key, i.e. the `voiceEngine` value. */
    val key: String,
    /** Desktop `displayName`. */
    val displayName: String,
    /**
     * Locale the plugin advertises: `*` = multilingual, otherwise a BCP-47
     * prefix. Only a UI hint — the actual Android voice comes from the engine.
     */
    val localeHint: String,
    /** True for back ends the user must run themselves (LAN address / local server). */
    val selfHosted: Boolean = false,
)

/** How a `voiceEngine` value is realised on Android. */
enum class TtsEngineKind {
    /** Platform speech synthesizer — `TextToSpeech` with [TtsConfig.enginePackage] (or the default engine). */
    SYSTEM,

    /** A concrete installed Android TTS engine package (`com.google.android.tts`, …). */
    ANDROID_ENGINE,

    /** A desktop-only voice plugin key: listed for config compatibility, never synthesised here. */
    DESKTOP_VOICE_PLUGIN,
}

/** Result of validating a [TtsConfig] against the catalog. */
sealed class TtsSelection {

    /** Speaks through the platform synthesizer. */
    data class Ok(val engineKind: TtsEngineKind, val voiceName: String) : TtsSelection()

    /**
     * The config names a desktop voice plugin. Desktop behaviour when no voice
     * matches is to fall back to `system` (`textToSpeech/component.tsx`), so this
     * is a *recoverable* state for the UI, not an error.
     */
    data class DesktopOnly(val pluginKey: String, val displayName: String) : TtsSelection()

    /** No recognisable engine and none can be inferred. */
    data class UnknownEngine(val engine: String) : TtsSelection()
}

/**
 * Voice/engine catalog + the small selection rules the desktop container applies
 * when a stored config is incomplete.
 */
object TtsVoiceCatalog {

    /**
     * The desktop voice plugin keys, in `catalog.ts` order.
     *
     * Keep this list at exactly 15 entries — the P6 acceptance criterion is
     * "15 个 voice 标签" and `TtsVoiceCatalogTest` pins the count.
     */
    val labels: List<TtsVoiceLabel> = listOf(
        TtsVoiceLabel("azure-tts-voice-plugin", "Azure TTS", "*"),
        TtsVoiceLabel("amazon-polly-voice-plugin", "Amazon Polly", "*"),
        TtsVoiceLabel("minimax-tts-voice-plugin", "MiniMax TTS", "zh-CN"),
        TtsVoiceLabel("openai-tts-voice-plugin", "OpenAI TTS", "*"),
        TtsVoiceLabel("qwen-tts-voice-plugin", "千问 TTS", "zh-CN"),
        TtsVoiceLabel("zhipu-tts-voice-plugin", "智谱 TTS", "zh-CN"),
        TtsVoiceLabel("elevenlabs-tts-voice-plugin", "ElevenLabs TTS", "*"),
        TtsVoiceLabel("grok-tts-voice-plugin", "grok TTS", "*"),
        TtsVoiceLabel("mimo-tts-voice-plugin", "MiMo TTS", "zh-CN"),
        TtsVoiceLabel("volcengine-tts-voice-plugin", "豆包 TTS", "zh-CN"),
        // The five self-hosted / on-device back ends from the desktop catalog.
        TtsVoiceLabel("multitts-voice-plugin", "MultiTTS", "*", selfHosted = true),
        TtsVoiceLabel("ttsserver-voice-plugin", "TTS Server", "*", selfHosted = true),
        TtsVoiceLabel("chatttsui-voice-plugin", "ChatTTS UI", "zh-CN", selfHosted = true),
        TtsVoiceLabel("chattts-voice-plugin", "ChatTTS", "zh-CN", selfHosted = true),
        TtsVoiceLabel("coquitts-voice-plugin", "Coqui TTS", "*", selfHosted = true),
    )

    /**
     * The removed official cloud voice engine.
     *
     * Still present in the desktop code (`src/store/actions/manager.tsx` resets
     * `voiceEngine` to this value, and `textToSpeech/component.tsx` branches on
     * it). Android treats it like any other desktop plugin: legacy label,
     * system fallback. It is NOT part of [labels] (that list is the 15 plugins).
     */
    const val LEGACY_OFFICIAL_AI_PLUGIN = "official-ai-voice-plugin"

    /** All plugin keys, including the removed official one. */
    val keys: Set<String> = (labels.map { it.key } + LEGACY_OFFICIAL_AI_PLUGIN).toSet()

    /** Label shown for the platform synthesizer (desktop picker: "System"). */
    const val SYSTEM_LABEL = "System"

    private val byKey: Map<String, TtsVoiceLabel> = labels.associateBy { it.key }

    /** The label for [key], or `null` when it is not a desktop voice plugin. */
    fun byKey(key: String): TtsVoiceLabel? = byKey[key.trim()]

    /** True when [engine] is a desktop-only voice plugin key. */
    fun isDesktopPlugin(engine: String): Boolean = keys.contains(engine.trim())

    /**
     * Human-readable engine name for the picker / lock-screen card.
     *
     * Unknown keys are returned verbatim (desktop does the same: an unmatched
     * voice simply renders its stored name).
     */
    fun labelFor(engine: String): String {
        val key = engine.trim()
        return when {
            key.isEmpty() || key == ENGINE_SYSTEM -> SYSTEM_LABEL
            key == LEGACY_OFFICIAL_AI_PLUGIN -> "$LEGACY_OFFICIAL_AI_PLUGIN (removed)"
            else -> byKey[key]?.displayName ?: key
        }
    }

    /**
     * Desktop parity: `voiceEngine` stored empty but a voice name is present.
     *
     * Desktop fills the engine from the matched voice, defaulting to `system`
     * (`textToSpeech/component.tsx`):
     * ```ts
     * if (!voiceEngine && voiceName) {
     *   let voice = this.voices.find((item) => item.name === voiceName);
     *   ConfigService.setReaderConfig("voiceEngine", voice ? voice.plugin : "system");
     * }
     * ```
     * On Android the identical fallback is `system` — an installed Android engine
     * never carries a desktop plugin key, so there is nothing else to infer.
     */
    fun inferEngine(engine: String, enginePackage: String = ""): String = when {
        engine.isBlank() -> ENGINE_SYSTEM
        engine.trim() == ENGINE_SYSTEM && enginePackage.isNotBlank() -> ENGINE_SYSTEM
        else -> engine.trim()
    }

    /** Resolve how a stored [TtsConfig] will actually be spoken on Android. */
    fun resolve(config: TtsConfig): TtsSelection {
        val engine = inferEngine(config.voiceEngine, config.enginePackage)
        return when {
            engine == ENGINE_SYSTEM -> TtsSelection.Ok(
                if (config.enginePackage.isBlank()) TtsEngineKind.SYSTEM else TtsEngineKind.ANDROID_ENGINE,
                config.voiceName.trim(),
            )

            isDesktopPlugin(engine) -> TtsSelection.DesktopOnly(engine, labelFor(engine))
            else -> TtsSelection.UnknownEngine(engine)
        }
    }

    /**
     * Engines the picker offers, given the Android engines [EngineEnumerator] found.
     *
     * Order: system synthesizer first, then the installed engines (already ranked),
     * then the desktop plugin labels as unavailable entries. Desktop plugin keys
     * stay visible so a restored config is never silently rewritten.
     */
    fun pickerEntries(installed: List<TtsEngineInfo>): List<TtsEngineOption> {
        val options = ArrayList<TtsEngineOption>(1 + installed.size + labels.size)
        options += TtsEngineOption(
            engine = ENGINE_SYSTEM,
            label = SYSTEM_LABEL,
            kind = TtsEngineKind.SYSTEM,
            available = true,
        )
        for (engine in installed) {
            options += TtsEngineOption(
                engine = engine.packageName,
                label = engine.label,
                kind = TtsEngineKind.ANDROID_ENGINE,
                available = true,
                packageName = engine.packageName,
            )
        }
        for (label in labels) {
            options += TtsEngineOption(
                engine = label.key,
                label = label.displayName,
                kind = TtsEngineKind.DESKTOP_VOICE_PLUGIN,
                available = false,
                localeHint = label.localeHint,
            )
        }
        return options
    }
}

/** One row of the voice/engine picker. */
data class TtsEngineOption(
    /** `voiceEngine` value to persist: [ENGINE_SYSTEM], an engine package, or a plugin key. */
    val engine: String,
    /** Display name. */
    val label: String,
    val kind: TtsEngineKind,
    /** False for desktop-only plugin keys — shown greyed out. */
    val available: Boolean,
    /** Set for [TtsEngineKind.ANDROID_ENGINE]. */
    val packageName: String = "",
    val localeHint: String = "*",
)

/**
 * One voice offered by the bound `TextToSpeech` engine (Android side of the
 * desktop `nativeVoices` list in `textToSpeech/component.tsx`).
 */
data class TtsVoiceOption(
    /** `android.speech.tts.Voice.getName()` — what gets persisted as `voiceName`. */
    val name: String,
    /** BCP-47 tag (`Voice.getLocale().toLanguageTag()`), e.g. `zh-CN`. */
    val locale: String,
    /** Engine package that offers the voice. */
    val enginePackage: String = "",
)

/**
 * Native voice selection — desktop parity with the `nativeVoices` handling in
 * `textToSpeech/component.tsx`:
 *
 * ```ts
 * if (!voiceName) voiceName = this.nativeVoices[0]?.name;
 * let voice = this.nativeVoices.find((voice) => voice.name === voiceName);
 * ```
 *
 * Resolution order:
 *  1. exact [TtsConfig.voiceName] match (`item.name === voiceName`);
 *  2. locale match on [TtsConfig.voiceLocale] — full tag first, then language
 *     prefix — because an Android TTS engine lists many voices of the *same*
 *     language and a bare "first voice" would otherwise speak the wrong one;
 *  3. the first voice ([TtsVoiceOption] order, i.e. the engine's own order).
 *
 * @return the voice to hand to `TextToSpeech.setVoice`, or `null` when the engine
 *   exposes no voices (then the service falls back to `setLanguage`).
 */
fun pickVoice(voices: List<TtsVoiceOption>, config: TtsConfig): TtsVoiceOption? {
    if (voices.isEmpty()) return null
    val wantedName = config.voiceName.trim()
    if (wantedName.isNotEmpty()) {
        voices.firstOrNull { it.name == wantedName }?.let { return it }
        // The engine may qualify names with the package; accept a suffix match.
        voices.firstOrNull { it.name.endsWith(wantedName, ignoreCase = true) }?.let { return it }
    }
    val wantedLocale = config.voiceLocale.trim()
    if (wantedLocale.isNotEmpty()) {
        voices.firstOrNull { it.locale.equals(wantedLocale, ignoreCase = true) }?.let { return it }
        val wantedLanguage = wantedLocale.substringBefore('-').substringBefore('_')
        voices.firstOrNull {
            it.locale.substringBefore('-').substringBefore('_').equals(wantedLanguage, ignoreCase = true)
        }?.let { return it }
    }
    return voices.first()
}
