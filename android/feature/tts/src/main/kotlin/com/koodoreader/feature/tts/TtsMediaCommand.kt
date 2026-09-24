package com.koodoreader.feature.tts

/**
 * Lock-screen / media-key command vocabulary — **pure JVM** (DoD [3]).
 *
 * Desktop counterpart: `src/components/textToSpeech/component.tsx` binds play /
 * pause / previous / next to on-screen buttons only — the desktop app has no
 * media-key or lock-screen integration at all, so this vocabulary is new. What it
 * must stay compatible with is the *Android* contract: the intents a headset, a
 * Bluetooth car kit or the lock-screen card deliver
 * (`android.intent.action.MEDIA_BUTTON` with a `KeyEvent`, or the
 * `PendingIntent`s of the notification's actions).
 *
 * The mapping table lives here — not in the BroadcastReceiver — so it is unit
 * tested on a plain JVM without an emulator. Key codes are mirrored as plain
 * numbers ([KEYCODE_MEDIA_PLAY_PAUSE] …) so this file needs no `android.*` import;
 * they are stable API-level-1 constants from `android.view.KeyEvent`.
 */

/** What the transport should do. */
enum class TtsMediaCommand(
    /**
     * Intent action of the notification / receiver entry point. Namespaced under
     * `com.koodoreader.tts.action.` so it can never collide with a platform action.
     */
    val action: String,
) {
    PLAY("com.koodoreader.tts.action.PLAY"),
    PAUSE("com.koodoreader.tts.action.PAUSE"),

    /**
     * Single "toggle" command — the notification's play/pause button and headset
     * single-click both deliver this; the receiver resolves it against the current
     * playback state.
     */
    PLAY_PAUSE("com.koodoreader.tts.action.PLAY_PAUSE"),

    STOP("com.koodoreader.tts.action.STOP"),
    NEXT("com.koodoreader.tts.action.NEXT"),
    PREVIOUS("com.koodoreader.tts.action.PREVIOUS"),

    /** Headset double-click / lock-screen scrub: jump forward one chapter boundary. */
    FAST_FORWARD("com.koodoreader.tts.action.FAST_FORWARD"),

    /** Headset triple-click / lock-screen scrub: jump back one chapter boundary. */
    REWIND("com.koodoreader.tts.action.REWIND"),
}

/**
 * Intent action → [TtsMediaCommand] mapping table.
 *
 * Two inputs are considered, in this order:
 *  1. an explicit action string (notification `PendingIntent`, custom broadcast) —
 *     matched exactly against [TtsMediaCommand.action];
 *  2. a media-button key code (delivered in
 *     `Intent.EXTRA_KEY_EVENT` → `KeyEvent.getKeyCode()`).
 *
 * Anything unrecognised maps to `null`: the receiver then ignores the broadcast
 * instead of guessing, which is what keeps a stray media key from starting
 * playback in another app's session.
 */
object TtsMediaCommandMapper {

    /** `android.content.Intent.ACTION_MEDIA_BUTTON` (API-level-1 constant, inlined). */
    const val ACTION_MEDIA_BUTTON: String = "android.intent.action.MEDIA_BUTTON"

    /** `android.view.KeyEvent` media key codes, inlined to keep this file Android-free. */
    const val KEYCODE_MEDIA_PREVIOUS = 88
    const val KEYCODE_MEDIA_NEXT = 87
    const val KEYCODE_MEDIA_PLAY_PAUSE = 85
    const val KEYCODE_MEDIA_PAUSE = 127
    const val KEYCODE_MEDIA_STOP = 86
    const val KEYCODE_MEDIA_PLAY = 126
    const val KEYCODE_MEDIA_FAST_FORWARD = 90
    const val KEYCODE_MEDIA_REWIND = 89
    const val KEYCODE_HEADSETHOOK = 79

    /** Key-code → command table (the "media key" half). */
    private val KEY_CODES: Map<Int, TtsMediaCommand> = mapOf(
        KEYCODE_MEDIA_PLAY to TtsMediaCommand.PLAY,
        KEYCODE_MEDIA_PAUSE to TtsMediaCommand.PAUSE,
        KEYCODE_MEDIA_PLAY_PAUSE to TtsMediaCommand.PLAY_PAUSE,
        KEYCODE_HEADSETHOOK to TtsMediaCommand.PLAY_PAUSE,
        KEYCODE_MEDIA_STOP to TtsMediaCommand.STOP,
        KEYCODE_MEDIA_NEXT to TtsMediaCommand.NEXT,
        KEYCODE_MEDIA_PREVIOUS to TtsMediaCommand.PREVIOUS,
        KEYCODE_MEDIA_FAST_FORWARD to TtsMediaCommand.FAST_FORWARD,
        KEYCODE_MEDIA_REWIND to TtsMediaCommand.REWIND,
    )

    /** Action string → command table (the "notification button / custom broadcast" half). */
    private val ACTIONS: Map<String, TtsMediaCommand> =
        TtsMediaCommand.values().associateBy { it.action }

    /** Resolves a media-button key code; `null` for non-media keys. */
    fun fromKeyCode(keyCode: Int?): TtsMediaCommand? = keyCode?.let { KEY_CODES[it] }

    /** Resolves an explicit action string; `null` for unknown/foreign actions. */
    fun fromAction(action: String?): TtsMediaCommand? = action?.let { ACTIONS[it] }

    /** True when [action] is the platform media-button delivery action. */
    fun isMediaButtonAction(action: String?): Boolean = action == ACTION_MEDIA_BUTTON

    /**
     * Full resolution used by `LockscreenControlsReceiver`.
     *
     * @param action the broadcast action; media-button presses arrive as
     *   [ACTION_MEDIA_BUTTON], the notification buttons as [TtsMediaCommand.action].
     * @param keyCode `KeyEvent.getKeyCode()`, when the broadcast carries one.
     * @return the command to run, or `null` when the broadcast is not ours.
     */
    fun from(action: String?, keyCode: Int?): TtsMediaCommand? =
        fromAction(action) ?: fromKeyCode(keyCode)

    /**
     * Resolves the toggle command against the current state.
     *
     * [TtsMediaCommand.PLAY_PAUSE] means "the opposite of what is happening now",
     * which needs the playback state — hence a separate call:
     *  - playing / preparing → [TtsMediaCommand.PAUSE];
     *  - anything else → [TtsMediaCommand.PLAY] (also covers a paused or stopped
     *    session, i.e. what a single headset click does in every music player).
     */
    fun resolveToggle(command: TtsMediaCommand, state: TtsPlaybackState): TtsMediaCommand = when {
        command != TtsMediaCommand.PLAY_PAUSE -> command
        state == TtsPlaybackState.PLAYING || state == TtsPlaybackState.PREPARING -> TtsMediaCommand.PAUSE
        else -> TtsMediaCommand.PLAY
    }
}

/**
 * Lock-screen / notification text, derived purely from [TtsPlaybackSnapshot].
 *
 * Keeping it here (not in `MediaSessionController`) means the strings the user sees
 * on the lock screen are unit-tested, and the Android layer only does
 * `MediaMetadataCompat.Builder().putString(...)`.
 */
object TtsMediaMetadata {

    /** Shown when a session has no book title yet (service started from a media key). */
    const val FALLBACK_TITLE = "Koodo Reader"

    fun title(snapshot: TtsPlaybackSnapshot): String =
        snapshot.bookTitle.ifBlank { FALLBACK_TITLE }

    /** Chapter name, falling back to the empty string (the card then shows only the title). */
    fun subtitle(snapshot: TtsPlaybackSnapshot): String = snapshot.chapterTitle

    /** `"12/84"` — the sentence counter the notification shows as sub-text. */
    fun progressLabel(snapshot: TtsPlaybackSnapshot): String =
        if (!snapshot.hasQueue) "" else "${snapshot.utteranceIndex + 1}/${snapshot.utteranceCount}"

    /**
     * Sub-text under the chapter name: the sentence counter plus, while errored, the
     * failure reason — desktop parity for the toast
     * `t("Audio loading failed, stopped playback")`.
     */
    fun description(snapshot: TtsPlaybackSnapshot, errorKey: String? = null): String {
        val label = progressLabel(snapshot)
        val error = errorKey.orEmpty()
        return when {
            error.isNotEmpty() && label.isNotEmpty() -> "$label · $error"
            error.isNotEmpty() -> error
            else -> label
        }
    }
}

/**
 * Everything the Compose control sheet needs, derived from pure values.
 *
 * The sheet (`TtsControlSheet`) renders this; the strings are **i18n keys identical
 * to the desktop ones** (`Play` / `Resume` / `Pause` / `Stop` / `Previous` / `Next`
 * / `Speed` / `Voice` / `Please select`), resolved by the host's
 * `com.koodoreader.core.common.Localization`, so the Android UI cannot drift from
 * the desktop wording (ADR-004).
 */
data class TtsControlUiState(
    /** Key to pass to `t()` for the main (play/pause) button. */
    val primaryLabelKey: String,
    /** Command the main button dispatches (already toggle-resolved). */
    val primaryCommand: TtsMediaCommand,
    /** True while audio is being produced. */
    val isPlaying: Boolean,
    /** False when there is nothing to read (no chapter loaded). */
    val canPlay: Boolean,
    /** 0–100, drives the progress bar. */
    val progressPercent: Int,
    /** `"12/84"` or `""`. */
    val progressLabel: String,
    /** Currently selected voice name; empty → show `Please select`. */
    val voiceName: String,
    /** True when [voiceName] is empty (desktop shows `t("Please select")`). */
    val needsVoiceSelection: Boolean,
    /** Speaking rate (desktop `voiceSpeed`). */
    val speed: Float,
    /** `TextToSpeech` pitch. */
    val pitch: Float,
    /** 0.0–1.0. */
    val volume: Float,
    /** Label of the engine the session speaks through. */
    val engineLabel: String,
    /** Error key while the session is in ERROR, else `""`. */
    val errorKey: String,
) {
    companion object {

        /** Speed slider bounds — the desktop UI slider range for `voiceSpeed`. */
        const val SPEED_MIN = TtsRate.MIN
        const val SPEED_MAX = TtsRate.MAX

        /**
         * Desktop parity for the primary button
         * (`textToSpeech/component.tsx`):
         * ```tsx
         * this.state.isAudioOn
         *   ? this.state.isPaused ? t("Resume") : t("Pause")
         *   : t("Play")
         * ```
         */
        fun of(
            snapshot: TtsPlaybackSnapshot,
            config: TtsConfig,
            engineLabel: String = TtsVoiceCatalog.labelFor(config.voiceEngine),
        ): TtsControlUiState {
            val paused = snapshot.state == TtsPlaybackState.PAUSED ||
                snapshot.state == TtsPlaybackState.STOPPED
            val labelKey = when {
                snapshot.state == TtsPlaybackState.PLAYING || snapshot.state == TtsPlaybackState.PREPARING -> "Pause"
                paused -> "Resume"
                else -> "Play"
            }
            val primary = when {
                snapshot.state == TtsPlaybackState.PLAYING || snapshot.state == TtsPlaybackState.PREPARING ->
                    TtsMediaCommand.PAUSE

                snapshot.state == TtsPlaybackState.PAUSED -> TtsMediaCommand.PLAY
                else -> TtsMediaCommand.PLAY
            }
            return TtsControlUiState(
                primaryLabelKey = labelKey,
                primaryCommand = primary,
                isPlaying = snapshot.state == TtsPlaybackState.PLAYING,
                canPlay = snapshot.hasQueue,
                progressPercent = snapshot.progressPercent,
                progressLabel = TtsMediaMetadata.progressLabel(snapshot),
                voiceName = config.voiceName,
                needsVoiceSelection = config.voiceName.isBlank(),
                speed = config.voiceSpeed,
                pitch = config.pitch,
                volume = config.volume,
                engineLabel = engineLabel,
                errorKey = if (snapshot.state == TtsPlaybackState.ERROR) {
                    "Audio loading failed, stopped playback"
                } else {
                    ""
                },
            )
        }
    }
}
