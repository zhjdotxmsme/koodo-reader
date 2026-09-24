package com.koodoreader.feature.tts

/**
 * Core value types of the Android text-to-speech feature (`:feature:tts`, P6).
 *
 * Everything in this file is **pure Kotlin** — no `android.*` import — so the JVM
 * unit tests exercise it without the Android SDK (same rationale as the
 * `:engine:*` / `:core:*` modules). The Android-only pieces (TextToSpeech,
 * MediaSessionCompat, DataStore, Compose) live in their own files and consume
 * exactly these types.
 *
 * Desktop sources mirrored (see `docs/p6-tts-semantics-mapping.md`):
 *  - `src/utils/reader/ttsUtil.ts`
 *  - `src/components/textToSpeech/component.tsx`
 *  - `src/utils/common.ts` (`splitSentences`, `detectLocalLanguage`)
 */

/**
 * `voiceEngine` value meaning "the platform speech synthesizer".
 *
 * Desktop: `window.speechSynthesis` (the `system` branch of
 * `textToSpeech/component.tsx` → `handleSystemRead`). Android: `TextToSpeech`
 * bound to [TtsConfig.enginePackage], or the platform default engine when that
 * is empty.
 */
const val ENGINE_SYSTEM: String = "system"

/**
 * Reader-config keys consumed by the desktop TTS UI, mirrored **verbatim** so a
 * settings export/import stays wire-compatible (`ConfigService.getReaderConfig`
 * on the desktop side, DataStore here).
 */
object TtsConfigKeys {
    /** Desktop `getReaderConfig("voiceName")`. */
    const val VOICE_NAME = "voiceName"

    /** Desktop `getReaderConfig("voiceEngine")` — [ENGINE_SYSTEM] or a voice-plugin key. */
    const val VOICE_ENGINE = "voiceEngine"

    /**
     * Desktop `getReaderConfig("voiceSpeed")` — stored as a **string**
     * (`value={ConfigService.getReaderConfig("voiceSpeed") || "1"}`), converted
     * with `parseFloat(...) || 1`. Android stores a number but accepts both.
     */
    const val VOICE_SPEED = "voiceSpeed"

    /** Desktop `getReaderConfig("voiceLocale")` — defaults to `navigator.language`. */
    const val VOICE_LOCALE = "voiceLocale"

    /** Android-only: concrete `TextToSpeech` engine package (`com.google.android.tts`, …). */
    const val ENGINE_PACKAGE = "ttsEnginePackage"

    /** Android-only: `TextToSpeech.setPitch` (the desktop UI has no pitch control). */
    const val PITCH = "ttsPitch"

    /** Android-only: `TextToSpeech` `KEY_PARAM_VOLUME` (desktop uses the OS mixer). */
    const val VOLUME = "ttsVolume"

    /** Android-only: sentence chunk-length override for [TtsSentenceSplitter]. */
    const val CHUNK_LENGTH = "ttsChunkLength"
}

/**
 * Desktop speed semantics.
 *
 * Desktop keeps `voiceSpeed` as a multiplier with `1` as "normal" and hands
 * plugins `speed * 100 - 100` (a percentage delta) — see
 * `textToSpeech/component.tsx` (`TTSUtil.cacheAudio(index, speed * 100 - 100, …)`).
 * Android's `TextToSpeech.setSpeechRate` also takes `1.0` as normal, so the only
 * conversion needed is the plugin-percent form kept for logging/tests.
 */
object TtsRate {
    /** `TextToSpeech.setSpeechRate` rejects values <= 0; 0.1 is the practical floor. */
    const val MIN = 0.1f

    /** Above ~3x the platform engines start dropping utterances. */
    const val MAX = 3.0f

    const val DEFAULT = 1.0f

    /** Desktop: `speed * 100 - 100`, the value passed to a voice plugin. */
    fun desktopPercent(speed: Float): Int = Math.round(speed * 100f) - 100

    /**
     * Desktop: `parseFloat(ConfigService.getReaderConfig("voiceSpeed")) || 1` —
     * missing value, empty string and `NaN` all fall back to [DEFAULT].
     */
    fun fromDesktop(raw: String?): Float {
        if (raw.isNullOrBlank()) return DEFAULT
        val parsed = raw.trim().toFloatOrNull() ?: return DEFAULT
        if (parsed.isNaN() || parsed <= 0f) return DEFAULT
        return parsed.coerceIn(MIN, MAX)
    }
}

/** Pitch bounds (Android `setPitch` is happy with these; extremes sound broken). */
object TtsPitch {
    const val MIN = 0.5f
    const val MAX = 2.0f
    const val DEFAULT = 1.0f
}

/**
 * The complete speaking configuration — the Android equivalent of the desktop
 * reader-config quartet `voiceName` / `voiceEngine` / `voiceSpeed` / `voiceLocale`
 * plus the Android-only engine / pitch / volume / chunk knobs.
 *
 * Immutable on purpose: [normalized] is the only way to obtain a value that is
 * safe to hand to `TextToSpeech`.
 */
data class TtsConfig(
    /** Selected voice name; empty means "let the engine pick" (desktop shows 请选择). */
    val voiceName: String = "",
    /** [ENGINE_SYSTEM] or a desktop voice-plugin key (see [TtsVoiceCatalog]). */
    val voiceEngine: String = ENGINE_SYSTEM,
    /** Speaking rate, `1.0` = normal (desktop `voiceSpeed`). */
    val voiceSpeed: Float = TtsRate.DEFAULT,
    /** BCP-47 locale tag used to pick a matching Android voice (desktop `voiceLocale`). */
    val voiceLocale: String = "",
    /** Android TTS engine package; empty means the platform default engine. */
    val enginePackage: String = "",
    /** `TextToSpeech.setPitch` value. */
    val pitch: Float = TtsPitch.DEFAULT,
    /** `KEY_PARAM_VOLUME`, 0.0–1.0. */
    val volume: Float = 1.0f,
    /** `0` = derive from the detected language (desktop `splitSentences` default). */
    val chunkLength: Int = 0,
) {

    /** True when speaking goes through the platform synthesizer rather than a desktop plugin. */
    val usesSystemEngine: Boolean get() = voiceEngine == ENGINE_SYSTEM

    /** True when the voice engine is a desktop-only plugin — listed, but never spoken here. */
    val usesDesktopVoicePlugin: Boolean
        get() = !usesSystemEngine && TtsVoiceCatalog.isDesktopPlugin(voiceEngine)

    /**
     * Clamp every field into a range the platform accepts.
     *
     * Mirrors the desktop UI's tolerance of garbage config values
     * (`parseFloat(...) || 1`): a bad or absent value degrades to the default
     * instead of throwing.
     */
    fun normalized(): TtsConfig = copy(
        voiceName = voiceName.trim(),
        voiceEngine = voiceEngine.trim().ifEmpty { ENGINE_SYSTEM },
        voiceSpeed = if (voiceSpeed.isNaN()) TtsRate.DEFAULT else voiceSpeed.coerceIn(TtsRate.MIN, TtsRate.MAX),
        voiceLocale = voiceLocale.trim(),
        enginePackage = enginePackage.trim(),
        pitch = if (pitch.isNaN()) TtsPitch.DEFAULT else pitch.coerceIn(TtsPitch.MIN, TtsPitch.MAX),
        volume = if (volume.isNaN()) 1.0f else volume.coerceIn(0.0f, 1.0f),
        chunkLength = chunkLength.coerceIn(0, MAX_CHUNK_LENGTH),
    )

    companion object {
        /** Upper bound for [chunkLength]; long enough for a paragraph, short enough to stay responsive. */
        const val MAX_CHUNK_LENGTH = 600

        /** Desktop first-run defaults: no voice picked yet, system engine, normal speed. */
        val DEFAULT = TtsConfig()
    }
}

/**
 * Playback state of the reading session.
 *
 * Desktop equivalent is the `isAudioOn` / `isPaused` / `pausedMidSentence` triple
 * in `TTSUtil` + `textToSpeech/component.tsx`; on Android the same states are
 * what the MediaSession and the foreground notification report.
 */
enum class TtsPlaybackState {
    /** No session: nothing queued, the foreground service may stop. */
    IDLE,

    /** Engine is initialising or the next utterance is being queued. */
    PREPARING,

    /** An utterance is being spoken. */
    PLAYING,

    /** Paused mid-chapter; the queue and the resume anchor survive. */
    PAUSED,

    /** Stopped by the user; the queue is dropped but the resume anchor is kept. */
    STOPPED,

    /** Engine initialisation or synthesis failed. */
    ERROR;

    /** Desktop `state.isAudioOn` — a session is live. */
    val isSessionActive: Boolean get() = this == PREPARING || this == PLAYING || this == PAUSED

    /** Desktop `TTSUtil.isPaused` (paused or stopped, i.e. not producing audio). */
    val desktopIsPaused: Boolean get() = this == PAUSED || this == STOPPED

    /** True when a `play` command should continue the existing queue instead of restarting it. */
    val resumesQueue: Boolean get() = this == PAUSED
}

/**
 * One speakable chunk: a sentence plus where it came from.
 *
 * Desktop equivalent is an entry of `textToSpeech/component.tsx`'s `nodeList`
 * (`{ text, voiceName, voiceEngine }`) combined with the running `currentIndex`
 * that `highlightAudioNode` highlights. [charStart]/[charEnd] are the offsets of
 * [text] inside the chapter's plain text, which is what makes the highlight (and
 * the resume anchor) exact on Android.
 */
data class TtsUtterance(
    /** Position in the chapter queue, 0-based (desktop `currentIndex`). */
    val index: Int,
    /** Sentence text after text rules were applied. */
    val text: String,
    /** Offset of [text] in the chapter text, inclusive. */
    val charStart: Int,
    /** Offset of [text] in the chapter text, exclusive. */
    val charEnd: Int,
) {
    init {
        require(index >= 0) { "index must be >= 0 (was $index)" }
        require(charStart >= 0) { "charStart must be >= 0 (was $charStart)" }
        require(charEnd >= charStart) { "charEnd ($charEnd) must be >= charStart ($charStart)" }
    }

    /** Length of the highlighted span. */
    val length: Int get() = charEnd - charStart
}

/**
 * Snapshot handed to the notification / MediaSession / Compose control sheet.
 *
 * Pure so the lock-screen UI can be rendered and unit-tested from a plain JVM
 * value: the Android layer only translates it into `PlaybackStateCompat` and
 * `NotificationCompat` builders.
 */
data class TtsPlaybackSnapshot(
    val state: TtsPlaybackState = TtsPlaybackState.IDLE,
    val bookKey: String = "",
    val bookTitle: String = "",
    val chapterTitle: String = "",
    /** 0-based index of the utterance being spoken. */
    val utteranceIndex: Int = 0,
    /** Total utterances in the current chapter queue; `0` when unknown. */
    val utteranceCount: Int = 0,
    /** Wall-clock position inside the chapter, milliseconds (lock-screen seek bar). */
    val positionMs: Long = 0L,
    /** Progressive position for the lock-screen card, 0–100. */
    val progressPercent: Int = 0,
) {
    init {
        require(positionMs >= 0L) { "positionMs must be >= 0 (was $positionMs)" }
        require(progressPercent in 0..100) { "progressPercent must be in 0..100 (was $progressPercent)" }
    }

    /** The sentence currently spoken, or `null` when the queue is idle/exhausted. */
    val hasQueue: Boolean get() = utteranceCount > 0

    companion object {
        /** Idle snapshot used before a session starts (and after it is stopped). */
        val IDLE = TtsPlaybackSnapshot()

        /** Derives the lock-screen progress from the utterance index. */
        fun of(
            state: TtsPlaybackState,
            bookKey: String,
            bookTitle: String,
            chapterTitle: String,
            utteranceIndex: Int,
            utteranceCount: Int,
            positionMs: Long,
        ): TtsPlaybackSnapshot {
            val percent = if (utteranceCount <= 0) {
                0
            } else {
                ((utteranceIndex + 1).toDouble() / utteranceCount.toDouble() * 100.0).toInt().coerceIn(0, 100)
            }
            return TtsPlaybackSnapshot(
                state = state,
                bookKey = bookKey,
                bookTitle = bookTitle,
                chapterTitle = chapterTitle,
                utteranceIndex = utteranceIndex.coerceAtLeast(0),
                utteranceCount = utteranceCount.coerceAtLeast(0),
                positionMs = positionMs.coerceAtLeast(0L),
                progressPercent = percent,
            )
        }
    }
}

/**
 * The reading position a resume anchor carries — **field-for-field identical** to
 * `com.koodoreader.engine.toc.ReadingPosition` (the P2 progress model: book key,
 * spine index, chapter-local CFI, chapter and book percentages).
 *
 * WHY NOT IMPORT THE ENGINE TYPE: `:engine:toc` is a P2 module and, at the time of
 * writing, is not yet part of the committed host build; importing it here would make
 * `:feature:tts` un-buildable on its own. The two models are structurally identical,
 * so the host adapter is a one-liner — and `docs/p6-tts-semantics-mapping.md` §5
 * shows it:
 * ```kotlin
 * // With :engine:toc on the classpath, in the reader host:
 * TtsReadingPosition(pos.bookKey, pos.spineIndex, pos.cfi, pos.chapterPercent, pos.totalPercent)
 * ```
 * If the P2 module is already wired into the build, `TtsReadingPosition` can be
 * replaced by `ReadingPosition` by changing this file and
 * `BookmarkResumeController` only — no other call site names the type.
 */
data class TtsReadingPosition(
    /** Stable book key (desktop `Book.key`). */
    val bookKey: String,
    /** Current spine / chapter index, 0-based. */
    val spineIndex: Int,
    /** Chapter-local CFI, or `""` when the reader has none (ADR-002). */
    val cfi: String,
    /** Position inside the chapter, 0.0–1.0. */
    val chapterPercent: Float,
    /** Position inside the book, 0.0–1.0. */
    val totalPercent: Float,
) {
    init {
        require(bookKey.isNotBlank()) { "bookKey must not be blank" }
        require(spineIndex >= 0) { "spineIndex must be >= 0 (was $spineIndex)" }
        require(chapterPercent in 0f..1f) { "chapterPercent must be in [0,1] (was $chapterPercent)" }
        require(totalPercent in 0f..1f) { "totalPercent must be in [0,1] (was $totalPercent)" }
    }
}
