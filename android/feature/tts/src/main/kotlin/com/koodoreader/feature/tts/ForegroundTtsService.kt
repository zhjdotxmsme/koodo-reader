package com.koodoreader.feature.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.koodoreader.engine.text.TextSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Foreground service that keeps listening alive while the screen is off (DoD [2]).
 *
 * Desktop counterpart: none — the desktop app only speaks while its window holds
 * the audio element, and closing the reader stops the audio. Android must keep
 * speaking when the user locks the phone, which the platform only allows for a
 * **foreground service** with a `mediaPlayback` type and an ongoing notification.
 *
 * The service is intentionally thin. It owns exactly three Android things:
 *  1. the `TextToSpeech` instance ([AndroidTtsSpeaker] implements the pure
 *     [TtsSpeaker] port),
 *  2. the notification / foreground lifecycle,
 *  3. the `MediaSessionCompat` ([MediaSessionController]).
 *
 * Every decision — which sentence, in what order, what state we are in, what gets
 * persisted as the resume point — lives in [TtsSessionController] and
 * [BookmarkResumeController], which are plain JVM classes with unit tests. That is
 * what the P6 constraint ("单测设计须 JVM only") buys: the state machine is tested
 * without this service ever being instantiated.
 *
 * Host contract (the reader app binds, the notification/media keys use intents):
 * ```kotlin
 * bindService(Intent(this, ForegroundTtsService::class.java), conn, BIND_AUTO_CREATE)
 * // then, from the connection:
 * service.loadChapterFromSource(bookKey, title, chapterTitle, textSource, decoder)
 * service.play()
 * ```
 * Registration (manifest entries + permissions) is in `docs/patches/p6-tts.patch`.
 */
class ForegroundTtsService : Service(), TextToSpeech.OnInitListener {

    /** In-process access for the reader: `onServiceConnected` → `(binder as LocalBinder).service()` */
    inner class LocalBinder : Binder() {
        fun service(): ForegroundTtsService = this@ForegroundTtsService
    }

    private val binder = LocalBinder()

    /** Everything asynchronous (DataStore reads/writes) runs here. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Utterance callbacks arrive on a binder thread; the controller wants the main one. */
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var engineProvider: EngineEnumerator
    private lateinit var configRepository: TtsConfigRepository
    private lateinit var resumeController: BookmarkResumeController
    private lateinit var mediaSession: MediaSessionController
    private lateinit var speaker: AndroidTtsSpeaker
    private lateinit var session: TtsSessionController

    private var tts: TextToSpeech? = null
    private var lastConfig: TtsConfig = TtsConfig.DEFAULT
    private var audioFocusRequest: AudioFocusRequest? = null
    private var utteranceStartedAt: Long = 0L

    /**
     * +1 / −1 when a lock-screen scrub asked for the next / previous chapter. The
     * service cannot turn a page by itself (that is the reader's job), so it parks
     * the request here and notifies [hostCommandListener].
     */
    private var pendingChapterJump: Int = 0

    /** Set by the reader while it is bound: supplies the position stored in the anchor. */
    private var readingPositionProvider: (() -> TtsReadingPosition?)? = null

    /** Set by the reader: notified about commands it must handle itself (chapter jumps). */
    private var hostCommandListener: ((TtsMediaCommand) -> Unit)? = null

    /** Set by the host: i18n lookup (`com.koodoreader.core.common.Localization::t`). */
    private var translate: ((String) -> String)? = null

    /**
     * Notification small icon. Defaults to the module's monochrome
     * `ic_tts_notification` (a notification icon is drawn as a silhouette, so a
     * full-colour framework drawable would render as a blob); a host can
     * override it via [setNotificationIcon].
     */
    private var notificationIcon: Int = R.drawable.ic_tts_notification

    private var channelName: String = "Koodo Reader listening"

    override fun onCreate() {
        super.onCreate()
        engineProvider = EngineEnumerator(this)
        configRepository = TtsConfigRepository(DataStoreTtsConfigStore.from(this))
        resumeController = BookmarkResumeController(DataStoreTtsResumeStore.from(this))
        speaker = AndroidTtsSpeaker()
        session = TtsSessionController(
            speaker = speaker,
            resumeSink = resumeController.sink(scope) { readingPositionProvider?.invoke() },
        )
        mediaSession = MediaSessionController(this, stateProvider = { session.state }) { dispatch(it) }
        createNotificationChannel()
        isRunning = true
        scope.launch {
            val config = configRepository.current()
            lastConfig = config
            lastAppliedEnginePackage = config.enginePackage
            session.applyConfig(config)
            createEngine(config)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val keyCode = intent?.getIntExtra(EXTRA_KEY_CODE, NO_KEY_CODE)?.takeIf { it != NO_KEY_CODE }
        val command = TtsMediaCommandMapper.from(intent?.action, keyCode)
        if (command == null) {
            // Not ours (or a foreign media key): never keep a foreground service alive for it.
            stopSelf(startId)
            return START_NOT_STICKY
        }
        dispatch(command)
        return START_STICKY
    }

    // ------------------------------------------------------------ TextToSpeech

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            session.onEngineReady()
            val engine = tts
            if (engine != null) {
                applyVoice(engine, lastConfig)
                engine.setSpeechRate(lastConfig.voiceSpeed)
                engine.setPitch(lastConfig.pitch)
            }
        } else {
            // Desktop parity: `toast.error(t("Audio loading failed, stopped playback"))`.
            session.onEngineError("TextToSpeech initialisation failed (status=$status)")
        }
        refreshUi()
    }

    private val progressListener = object : UtteranceProgressListener() {

        override fun onStart(utteranceId: String?) {
            mainHandler.post {
                utteranceStartedAt = System.currentTimeMillis()
                refreshUi()
            }
        }

        override fun onDone(utteranceId: String?) {
            mainHandler.post {
                utteranceStartedAt = System.currentTimeMillis()
                session.onUtteranceDone()
                if (session.chapterFinished) {
                    // End of chapter: the reader turns the page (desktop `rendition.nextChapter()`).
                    pendingChapterJump = 1
                    hostCommandListener?.invoke(TtsMediaCommand.NEXT)
                }
                refreshUi()
            }
        }

        @Deprecated("Deprecated in Java", ReplaceWith("onError(utteranceId, -1)"))
        override fun onError(utteranceId: String?) {
            mainHandler.post { onSynthesisError(utteranceId, -1) }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            mainHandler.post { onSynthesisError(utteranceId, errorCode) }
        }
    }

    private fun onSynthesisError(utteranceId: String?, errorCode: Int) {
        session.onUtteranceError("synthesis failed (code=$errorCode) for $utteranceId")
        refreshUi()
    }

    private fun createEngine(config: TtsConfig) {
        val enginePackage = TtsEngineResolver.resolveEnginePackage(engineProvider, config.enginePackage)
        session.markEnginePreparing()
        tts?.shutdown()
        tts = if (enginePackage != null) {
            TextToSpeech(this, this, enginePackage)
        } else {
            // Nothing installed/known: let the platform choose.
            TextToSpeech(this, this)
        }
        tts?.setOnUtteranceProgressListener(progressListener)
    }

    /**
     * Picks the voice the config asks for (desktop `nativeVoices.find(name) ||
     * nativeVoices[0]`), falling back to `setLanguage` when the engine exposes no
     * voice list.
     */
    private fun applyVoice(engine: TextToSpeech, config: TtsConfig) {
        val voices = try {
            engine.voices?.toList().orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
        val options = voices.map {
            TtsVoiceOption(
                name = it.name,
                locale = it.locale?.toLanguageTag().orEmpty(),
                enginePackage = config.enginePackage,
            )
        }
        val picked = pickVoice(options, config)
        val voice = voices.firstOrNull { it.name == picked?.name }
        if (voice != null) {
            engine.voice = voice
            return
        }
        val locale = config.voiceLocale.takeIf { it.isNotBlank() }?.let { Locale.forLanguageTag(it) }
        if (locale != null) {
            try {
                engine.language = locale
            } catch (e: Exception) {
                // Unsupported locale: the engine keeps its default voice.
            }
        }
    }

    /** The audio-producing port implementation consumed by [TtsSessionController]. */
    private inner class AndroidTtsSpeaker : TtsSpeaker {

        override fun speak(text: String, utteranceId: String, rate: Float, pitch: Float, volume: Float) {
            val engine = tts ?: return
            engine.setSpeechRate(rate.coerceIn(TtsRate.MIN, TtsRate.MAX))
            engine.setPitch(pitch.coerceIn(TtsPitch.MIN, TtsPitch.MAX))
            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume.coerceIn(0f, 1f))
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            }
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        }

        override fun stop() {
            tts?.stop()
        }

        override fun isSpeaking(): Boolean = tts?.isSpeaking == true
    }

    // ---------------------------------------------------------------- commands

    /** Handles a transport command (from media keys, the notification, or the host UI). */
    private fun dispatch(raw: TtsMediaCommand) {
        val command = TtsMediaCommandMapper.resolveToggle(raw, session.state)
        when (command) {
            TtsMediaCommand.PLAY -> {
                if (session.utterances.isEmpty()) {
                    // Nothing loaded: let the host open the reader and load a chapter.
                    hostCommandListener?.invoke(TtsMediaCommand.PLAY)
                } else {
                    requestAudioFocus()
                    ensureForeground()
                    session.play()
                }
            }

            TtsMediaCommand.PAUSE -> session.pause()

            TtsMediaCommand.STOP -> {
                session.stop()
                abandonAudioFocus()
                stopForegroundCompat()
            }

            TtsMediaCommand.NEXT -> session.next()

            TtsMediaCommand.PREVIOUS -> session.previous()

            TtsMediaCommand.FAST_FORWARD -> {
                pendingChapterJump = 1
                hostCommandListener?.invoke(TtsMediaCommand.FAST_FORWARD)
            }

            TtsMediaCommand.REWIND -> {
                pendingChapterJump = -1
                hostCommandListener?.invoke(TtsMediaCommand.REWIND)
            }

            // Already resolved above; listed so a new enum entry fails the build here.
            TtsMediaCommand.PLAY_PAUSE -> Unit
        }
        refreshUi()
    }

    // ------------------------------------------------------------ host surface

    /** Loads a chapter from already-split utterances. */
    fun loadChapter(
        bookKey: String,
        bookTitle: String,
        chapterTitle: String,
        utterances: List<TtsUtterance>,
        startIndex: Int = 0,
    ) {
        session.load(bookKey, bookTitle, chapterTitle, utterances, startIndex)
        refreshUi()
    }

    /**
     * Loads a chapter from plain text, splitting it with [TtsSentenceSplitter] and
     * resuming at the stored anchor (DoD [4]) unless [startIndex] is given.
     */
    fun loadChapter(
        bookKey: String,
        bookTitle: String,
        chapterTitle: String,
        text: String,
        startIndex: Int = AUTO_RESUME,
    ) {
        scope.launch {
            val config = configRepository.current()
            lastConfig = config
            session.applyConfig(config)
            val utterances = TtsSentenceSplitter.splitWithOffsets(
                text,
                config.chunkLength.takeIf { it > 0 },
            )
            val start = if (startIndex == AUTO_RESUME) {
                resumeController.restore(bookKey, utterances.map { it.text }).sentenceIndex
            } else {
                startIndex
            }
            session.load(bookKey, bookTitle, chapterTitle, utterances, start)
            refreshUi()
        }
    }

    /**
     * Loads a chapter straight from `:engine:text`'s [TextSource] — the intended
     * integration point for the native reader (it already holds the chapter window).
     */
    fun loadChapterFromSource(
        bookKey: String,
        bookTitle: String,
        chapterTitle: String,
        source: TextSource,
        decode: (ByteArray) -> String,
        autoResume: Boolean = true,
    ) {
        scope.launch {
            val config = configRepository.current()
            lastConfig = config
            session.applyConfig(config)
            val sentenceSource = TextSourceSentenceSource(source, config.chunkLength, decode)
            val utterances = sentenceSource.utterances()
            val start = if (autoResume) resumeController.restore(bookKey, sentenceSource).sentenceIndex else 0
            session.load(bookKey, bookTitle, chapterTitle, utterances, start)
            refreshUi()
        }
    }

    /** Starts / resumes speaking (host UI button, same path as a media key). */
    fun play() = dispatch(TtsMediaCommand.PLAY)

    fun pause() = dispatch(TtsMediaCommand.PAUSE)

    fun stop() = dispatch(TtsMediaCommand.STOP)

    fun next() = dispatch(TtsMediaCommand.NEXT)

    fun previous() = dispatch(TtsMediaCommand.PREVIOUS)

    /** Lock-screen scrub request since the last call: `+1` / `-1` / `0`. */
    fun consumeChapterJump(): Int {
        val jump = pendingChapterJump
        pendingChapterJump = 0
        return jump
    }

    /** Current playback snapshot for the reader UI (highlighting, progress HUD). */
    fun snapshot(): TtsPlaybackSnapshot = session.snapshot(positionMs())

    /** State for the Compose control sheet (pure value, see [TtsControlUiState]). */
    fun controlState(): TtsControlUiState = TtsControlUiState.of(snapshot(), lastConfig, engineLabel())

    /** The state machine itself, so the reader can read/replace the queue synchronously. */
    fun sessionController(): TtsSessionController = session

    /** The resume store, for the reader's "continue listening" entry point. */
    suspend fun lastListenedBookKey(): String? = resumeController.lastBookKey()

    /** Persists a settings change and applies it to the live session. */
    fun updateConfig(transform: (TtsConfig) -> TtsConfig) {
        scope.launch {
            val config = configRepository.update(transform)
            lastConfig = config
            session.applyConfig(config)
            tts?.setSpeechRate(config.voiceSpeed)
            tts?.setPitch(config.pitch)
            // A changed engine package needs a fresh synthesizer.
            if (config.enginePackage != lastAppliedEnginePackage) {
                lastAppliedEnginePackage = config.enginePackage
                createEngine(config)
            }
            refreshUi()
        }
    }

    private var lastAppliedEnginePackage: String = ""

    fun setReadingPositionProvider(provider: (() -> TtsReadingPosition?)?) {
        readingPositionProvider = provider
    }

    fun setHostCommandListener(listener: ((TtsMediaCommand) -> Unit)?) {
        hostCommandListener = listener
    }

    fun setTranslate(translate: ((String) -> String)?) {
        this.translate = translate
    }

    fun setNotificationIcon(iconRes: Int) {
        notificationIcon = iconRes
    }

    fun setChannelName(name: String) {
        channelName = name
        createNotificationChannel()
    }

    /** Detaches from the reader (the host calls this from `onDestroy`). */
    fun shutdown() {
        session.stop()
        stopForegroundCompat()
        stopSelf()
    }

    override fun onDestroy() {
        isRunning = false
        session.stop()
        tts?.stop()
        tts?.shutdown()
        tts = null
        mediaSession.release()
        abandonAudioFocus()
        scope.cancel()
        super.onDestroy()
    }

    // ----------------------------------------------------------- notification

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            channelName,
            // Listening is background audio: no sound, no heads-up, but visible on the lock screen.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = channelName
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    private fun ensureForeground() {
        val notification = buildNotification(session.snapshot(positionMs()))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0)
        }
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    /** Rebuilds the lock-screen card; drops the notification once nothing is loaded. */
    private fun refreshUi() {
        val snapshot = session.snapshot(positionMs())
        mediaSession.update(snapshot, lastConfig.voiceSpeed)
        when {
            snapshot.state.isSessionActive -> ensureForeground()
            snapshot.state == TtsPlaybackState.ERROR -> ensureForeground()
            else -> Unit
        }
    }

    private fun buildNotification(snapshot: TtsPlaybackSnapshot): Notification {
        val style = androidx.media.app.NotificationCompat.MediaStyle()
            .setMediaSession(mediaSession.token)
            .setShowActionsInCompactView(0, 1, 2)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(notificationIcon)
            .setContentTitle(TtsMediaMetadata.title(snapshot))
            .setContentText(TtsMediaMetadata.subtitle(snapshot))
            .setSubText(TtsMediaMetadata.description(snapshot, errorTextKey()))
            .setContentIntent(contentIntent())
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_previous, t("Previous"), notificationAction(TtsMediaCommand.PREVIOUS))
            .addAction(
                if (snapshot.state == TtsPlaybackState.PLAYING) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play,
                t(if (snapshot.state == TtsPlaybackState.PLAYING) "Pause" else "Play"),
                notificationAction(TtsMediaCommand.PLAY_PAUSE),
            )
            .addAction(android.R.drawable.ic_media_next, t("Next"), notificationAction(TtsMediaCommand.NEXT))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, t("Stop"), notificationAction(TtsMediaCommand.STOP))
            .setStyle(style)
            .setProgress(100, snapshot.progressPercent, false)
            .build()
    }

    private fun errorTextKey(): String? =
        if (session.state == TtsPlaybackState.ERROR) "Audio loading failed, stopped playback" else null

    private fun t(key: String): String = translate?.invoke(key) ?: key

    /** Wraps a command in a broadcast to [LockscreenControlsReceiver] (same table as a real media key). */
    private fun notificationAction(command: TtsMediaCommand): PendingIntent {
        val intent = Intent(this, LockscreenControlsReceiver::class.java).setAction(command.action)
        return PendingIntent.getBroadcast(
            this,
            command.ordinal,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Tapping the card opens the app (the reader restores its own position). */
    private fun contentIntent(): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(
            this,
            0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun engineLabel(): String = TtsEngineResolver
        .describeResolution(engineProvider, lastConfig.enginePackage)
        .packageName
        ?: TtsVoiceCatalog.SYSTEM_LABEL

    /** Milliseconds since the current utterance started; drives the lock-screen position. */
    private fun positionMs(): Long = when (session.state) {
        TtsPlaybackState.PLAYING, TtsPlaybackState.PAUSED ->
            if (utteranceStartedAt == 0L) 0L else (System.currentTimeMillis() - utteranceStartedAt).coerceAtLeast(0L)

        else -> 0L
    }

    // ------------------------------------------------------------- audio focus

    private fun requestAudioFocus() {
        val manager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setOnAudioFocusChangeListener { change ->
                    mainHandler.post {
                        if (change == AudioManager.AUDIOFOCUS_LOSS ||
                            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                        ) {
                            session.pause()
                            refreshUi()
                        }
                    }
                }
                .build()
                .also { audioFocusRequest = it }
            try {
                manager.requestAudioFocus(request)
            } catch (e: Exception) {
                // Focus is best-effort: never block playback on it.
            }
        } else {
            @Suppress("DEPRECATION")
            try {
                manager.requestAudioFocus(
                    { change ->
                        mainHandler.post {
                            if (change == AudioManager.AUDIOFOCUS_LOSS ||
                                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                            ) {
                                session.pause()
                                refreshUi()
                            }
                        }
                    },
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN,
                )
            } catch (e: Exception) {
                // Ignored, see above.
            }
        }
    }

    private fun abandonAudioFocus() {
        val manager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { manager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(null)
        }
    }

    companion object {

        /** Notification id (stable; one listening session at a time). */
        const val NOTIFICATION_ID = 4711

        /** Notification channel id; the patch's manifest does not need it, the service creates it. */
        const val CHANNEL_ID = "koodo_tts_playback"

        /** `Intent` extra carrying a media-button key code. */
        const val EXTRA_KEY_CODE = "com.koodoreader.tts.extra.KEY_CODE"

        /** Sentinel for "no key code in this intent". */
        const val NO_KEY_CODE = Int.MIN_VALUE

        /** [loadChapter] `startIndex` value meaning "resume from the stored anchor". */
        const val AUTO_RESUME = -1

        /** True while the instance is alive; the receiver uses it to avoid a background FGS start. */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** Builds the intent that drives the service with [command]. */
        fun commandIntent(context: Context, command: TtsMediaCommand): Intent =
            Intent(context, ForegroundTtsService::class.java).setAction(command.action)

        /** Convenience for the reader UI: `ForegroundTtsService.command(this, TtsMediaCommand.NEXT)`. */
        fun command(context: Context, command: TtsMediaCommand) {
            context.startService(commandIntent(context, command))
        }

        /** Convenience for a media-key forward with an explicit key code. */
        fun commandIntent(context: Context, keyCode: Int): Intent =
            commandIntent(context, TtsMediaCommand.PLAY_PAUSE).putExtra(EXTRA_KEY_CODE, keyCode)
    }
}
