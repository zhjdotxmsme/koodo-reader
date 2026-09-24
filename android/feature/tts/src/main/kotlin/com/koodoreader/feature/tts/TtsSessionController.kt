package com.koodoreader.feature.tts

/**
 * Playback state machine + sentence queue — **pure JVM core** of the reading
 * session (DoD [2]).
 *
 * The Android `ForegroundTtsService` is deliberately thin: it owns the notification,
 * the MediaSession and the `TextToSpeech` instance, and forwards every platform
 * event here. All decisions (when to speak, what to speak, what the lock screen
 * should show, when to record a resume point) happen in this class, which is
 * unit-tested with a fake [TtsSpeaker] — no emulator, no Android SDK.
 *
 * Desktop counterpart: `TTSUtil`'s `isPaused` / `pausedMidSentence` /
 * `audioPaths` triple plus the `nodeList` walk in
 * `src/components/textToSpeech/component.tsx` (`handleCustomRead` / `handleStop` /
 * `handlePlay`). Mapping of each desktop concept:
 *
 * | desktop | here |
 * |---|---|
 * | `nodeList[currentIndex]` | [current] / [utterances] |
 * | `isAudioOn` | [state].`isSessionActive` |
 * | `isPaused` | [state] == PAUSED / STOPPED |
 * | `pausedMidSentence` | re-speak the whole sentence on resume (see [play]) |
 * | `audioPaths` cache + `targetCacheCount = 10` | not needed — Android synthesises on demand, no audio files |
 * | walking to `nodeList.length` | [onUtteranceDone] → [chapterFinished] |
 *
 * DELIBERATE DERIVATION — desktop `TTSUtil.resumeAudio()` resumes *mid sentence*
 * (`pausedMidSentence`), because it controls a Howl player over a cached audio
 * file. `TextToSpeech` has no pause/resume: `stop()` ends the utterance. Android
 * therefore re-speaks the current sentence from its start on resume, which also
 * keeps the highlighting consistent. Documented in
 * `docs/p6-tts-semantics-mapping.md` §5.
 */

/**
 * Port to the audio producer.
 *
 * Android implementation: a thin wrapper over `TextToSpeech`
 * (`ForegroundTtsService.AndroidTtsSpeaker`). Tests use a recording fake.
 */
interface TtsSpeaker {

    /**
     * Speak [text]. The implementation must call back
     * [TtsSessionController.onUtteranceDone] / [TtsSessionController.onUtteranceError]
     * for [utteranceId] (the Android adapter does this from its
     * `UtteranceProgressListener`).
     */
    fun speak(text: String, utteranceId: String, rate: Float, pitch: Float, volume: Float)

    /** Abandon the current utterance immediately (`TextToSpeech.stop()`). */
    fun stop()

    /** True while audio is being produced (`TextToSpeech.isSpeaking()`). */
    fun isSpeaking(): Boolean
}

/**
 * The session: one chapter of sentences, the current index, and the state machine.
 *
 * Threading: not thread-safe by design — the service calls it from the main thread
 * (its command handling and the `UtteranceProgressListener` callbacks both land
 * there), and the pure tests call it from one thread. Everything that needs to be
 * shared across threads (the config, the resume store) is behind its own store.
 */
class TtsSessionController(
    private val speaker: TtsSpeaker,
    /**
     * Where to persist the resume point (DoD [4]). Called on pause, stop, chapter
     * finish and on every advanced sentence, so an unexpected process death still
     * leaves a usable bookmark. `null` disables recording (unit tests, previews).
     */
    private val resumeSink: TtsResumeSink? = null,
    /** Injectable clock for deterministic `updatedAt` assertions. */
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /** Current state; every transition is validated by [canTransitionTo]. */
    var state: TtsPlaybackState = TtsPlaybackState.IDLE
        private set

    /** Book being read; empty until [load]. */
    var bookKey: String = ""
        private set

    /** Title shown on the lock screen. */
    var bookTitle: String = ""
        private set

    /** Current chapter title, shown on the lock screen. */
    var chapterTitle: String = ""
        private set

    /** The sentence queue of the loaded chapter. */
    var utterances: List<TtsUtterance> = emptyList()
        private set

    /** Index of the sentence being spoken (desktop `currentIndex`). */
    var index: Int = 0
        private set

    /** Set when the queue ran out — the service then turns the page (desktop: `rendition.next()`). */
    var chapterFinished: Boolean = false
        private set

    /** Last error message, for the notification's error state and for logging. */
    var lastError: String? = null
        private set

    /** The sentence to speak now, or `null` when the queue is empty. */
    val current: TtsUtterance? get() = utterances.getOrNull(index)

    /**
     * Arms the queue for a chapter.
     *
     * Mirrors `handleGetText()`: the desktop slices the visible text into
     * sentences, drops everything before the resume index and walks the rest.
     *
     * @param startIndex first sentence to speak (from [BookmarkResumeController]).
     * @return the (unchanged) state; the session starts on [play].
     */
    fun load(
        bookKey: String,
        bookTitle: String,
        chapterTitle: String,
        utterances: List<TtsUtterance>,
        startIndex: Int = 0,
    ): TtsPlaybackState {
        this.bookKey = bookKey
        this.bookTitle = bookTitle
        this.chapterTitle = chapterTitle
        this.utterances = utterances
        this.index = startIndex.coerceIn(0, (utterances.size - 1).coerceAtLeast(0))
        this.chapterFinished = false
        this.lastError = null
        state = TtsPlaybackState.IDLE
        return state
    }

    /**
     * Starts or resumes speaking.
     *
     * Allowed from IDLE, PAUSED, STOPPED and PLAYING (a no-op, returns `false`).
     * Refused while PREPARING (the engine is still initialising — the service calls
     * [onEngineReady] and then [play]) and from ERROR, which requires a fresh
     * [load] so the user sees why playback failed instead of an instant retry loop.
     */
    fun play(): Boolean {
        if (utterances.isEmpty()) return false
        when (state) {
            TtsPlaybackState.PLAYING -> return false
            TtsPlaybackState.PREPARING, TtsPlaybackState.ERROR -> return false
            TtsPlaybackState.IDLE, TtsPlaybackState.PAUSED, TtsPlaybackState.STOPPED -> Unit
        }
        return speakCurrent()
    }

    /**
     * Pauses; the queue and the index survive, and the resume point is recorded.
     *
     * @return `true` when the call changed the state (was PLAYING/PREPARING).
     */
    fun pause(): Boolean {
        if (state != TtsPlaybackState.PLAYING && state != TtsPlaybackState.PREPARING) return false
        speaker.stop()
        state = TtsPlaybackState.PAUSED
        recordResumePoint()
        return true
    }

    /**
     * Stops the session; the queue is kept for a later [play] (desktop `handleStop`
     * keeps `nodeList` so "continue" restarts the same chapter).
     */
    fun stop(): Boolean {
        if (state == TtsPlaybackState.IDLE || state == TtsPlaybackState.STOPPED) return false
        speaker.stop()
        state = TtsPlaybackState.STOPPED
        recordResumePoint()
        return true
    }

    /**
     * Moves to the next sentence (lock-screen ⏭ / media key).
     *
     * While PLAYING the new sentence starts immediately — the same behaviour as
     * tapping ⏭ in the desktop player.
     *
     * @return `true` when the index actually moved.
     */
    fun next(): Boolean = moveTo(index + 1)

    /** Moves to the previous sentence (lock-screen ⏮ / media key), clamped at 0. */
    fun previous(): Boolean = moveTo(index - 1)

    /** The engine instance was created and is initialising (`TextToSpeech(onInit)` pending). */
    fun markEnginePreparing(): TtsPlaybackState {
        if (state != TtsPlaybackState.PLAYING) state = TtsPlaybackState.PREPARING
        return state
    }

    /** `TextToSpeech.onInit(SUCCESS)`: leaves PREPARING, ready to [play] (or resume speaking). */
    fun onEngineReady(): TtsPlaybackState {
        lastError = null
        if (state == TtsPlaybackState.PREPARING) state = TtsPlaybackState.IDLE
        return state
    }

    /** `TextToSpeech.onInit(ERROR)` or a synthesis failure. */
    fun onEngineError(message: String): TtsPlaybackState {
        lastError = message
        state = TtsPlaybackState.ERROR
        return state
    }

    /**
     * The current utterance finished.
     *
     * Advances to the next sentence, or — at the end of the chapter — leaves the
     * session STOPPED with [chapterFinished] set, exactly like the desktop loop
     * breaking out of `nodeList` and calling `rendition.nextChapter()`.
     */
    fun onUtteranceDone(): Boolean {
        if (utterances.isEmpty()) return false
        if (index + 1 >= utterances.size) {
            chapterFinished = true
            state = TtsPlaybackState.STOPPED
            recordResumePoint()
            return false
        }
        index += 1
        state = TtsPlaybackState.PLAYING
        recordResumePoint()
        speak(index)
        return true
    }

    /** Synthesis failed for the current utterance — desktop shows "Audio loading failed, stopped playback". */
    fun onUtteranceError(message: String): Boolean {
        lastError = message
        state = TtsPlaybackState.ERROR
        return true
    }

    /** Snapshot for the notification, the MediaSession and the Compose control sheet. */
    fun snapshot(positionMs: Long = 0L): TtsPlaybackSnapshot = TtsPlaybackSnapshot.of(
        state = state,
        bookKey = bookKey,
        bookTitle = bookTitle,
        chapterTitle = chapterTitle,
        utteranceIndex = index,
        utteranceCount = utterances.size,
        positionMs = positionMs,
    )

    /**
     * Whether a transition is legal — the transition table, spelled out so a test
     * can pin it and a future UI can grey out impossible actions.
     */
    fun canTransitionTo(target: TtsPlaybackState): Boolean = when (state) {
        TtsPlaybackState.IDLE -> target == TtsPlaybackState.PREPARING ||
            target == TtsPlaybackState.PLAYING ||
            target == TtsPlaybackState.IDLE

        TtsPlaybackState.PREPARING -> target == TtsPlaybackState.PLAYING ||
            target == TtsPlaybackState.PAUSED ||
            target == TtsPlaybackState.STOPPED ||
            target == TtsPlaybackState.IDLE ||
            target == TtsPlaybackState.ERROR

        TtsPlaybackState.PLAYING -> target == TtsPlaybackState.PAUSED ||
            target == TtsPlaybackState.STOPPED ||
            target == TtsPlaybackState.PLAYING ||
            target == TtsPlaybackState.ERROR

        TtsPlaybackState.PAUSED -> target == TtsPlaybackState.PLAYING ||
            target == TtsPlaybackState.STOPPED ||
            target == TtsPlaybackState.PAUSED

        TtsPlaybackState.STOPPED -> target == TtsPlaybackState.PLAYING ||
            target == TtsPlaybackState.STOPPED

        TtsPlaybackState.ERROR -> target == TtsPlaybackState.IDLE ||
            target == TtsPlaybackState.PREPARING ||
            target == TtsPlaybackState.ERROR
    }

    // ---------------------------------------------------------------- internals

    /** Speaks [index]; a no-op when the queue is empty. */
    private fun speakCurrent(): Boolean {
        val target = current ?: return false
        chapterFinished = false
        recordResumePoint()
        state = TtsPlaybackState.PLAYING
        speak(target.index)
        return true
    }

    private fun moveTo(target: Int): Boolean {
        if (utterances.isEmpty()) return false
        val clamped = target.coerceIn(0, utterances.size - 1)
        if (clamped == index) return false
        index = clamped
        chapterFinished = false
        when (state) {
            TtsPlaybackState.PLAYING -> speak(index)
            TtsPlaybackState.STOPPED -> state = TtsPlaybackState.IDLE
            else -> Unit
        }
        recordResumePoint()
        return true
    }

    private fun speak(target: Int) {
        val utterance = utterances.getOrNull(target) ?: return
        speaker.speak(
            text = utterance.text,
            utteranceId = utteranceId(utterance),
            rate = lastRate,
            pitch = lastPitch,
            volume = lastVolume,
        )
    }

    /** Voice parameters currently in force (set by [applyConfig]). */
    private var lastRate: Float = TtsRate.DEFAULT
    private var lastPitch: Float = TtsPitch.DEFAULT
    private var lastVolume: Float = 1.0f

    /**
     * Applies the persisted voice parameters (DoD [1]: 语速 / 语调 / 音量).
     *
     * The service calls this on start and whenever the settings change; the values
     * take effect from the next utterance, like `TextToSpeech.setSpeechRate` does.
     */
    fun applyConfig(config: TtsConfig): TtsConfig {
        val normalized = config.normalized()
        lastRate = normalized.voiceSpeed
        lastPitch = normalized.pitch
        lastVolume = normalized.volume
        return normalized
    }

    /** The voice parameters currently in force (for the settings UI / lock screen). */
    fun voiceParameters(): Triple<Float, Float, Float> = Triple(lastRate, lastPitch, lastVolume)

    /** Stable utterance id: `bookKey:index`, which is also what a resume log shows. */
    fun utteranceId(utterance: TtsUtterance): String = "$bookKey:${utterance.index}"

    private fun recordResumePoint() {
        val sink = resumeSink ?: return
        val utterance = current ?: return
        sink.onResumePoint(
            bookKey = bookKey,
            utteranceIndex = utterance.index,
            anchorText = utterance.text,
            updatedAt = clock(),
        )
    }
}
