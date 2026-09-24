package com.koodoreader.feature.tts

import android.content.Context
import android.content.Intent
import android.os.Build
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent

/**
 * MediaSession / lock-screen integration (DoD [2] + [3]).
 *
 * The desktop app has no equivalent — it is an Electron window with on-screen
 * transport buttons only. Android needs a `MediaSessionCompat` because it is what
 * makes the system show the lock-screen card, route headset and Bluetooth media
 * keys to the app, and survive the screen turning off.
 *
 * This class is *glue*: it owns one `MediaSessionCompat`, translates commands into
 * [TtsMediaCommand] and translates the pure [TtsPlaybackSnapshot] into
 * `PlaybackStateCompat` / `MediaMetadataCompat`. All decisions (what the toggle
 * means, what the card says) live in `TtsMediaCommand.kt` and are unit-tested.
 *
 * @param context any context; the session lives as long as this object.
 * @param stateProvider current playback state, used to resolve a media-key toggle.
 * @param onCommand receives every transported command.
 */
class MediaSessionController(
    private val context: Context,
    private val stateProvider: () -> TtsPlaybackState,
    private val onCommand: (TtsMediaCommand) -> Unit,
) {

    private val session: MediaSessionCompat = MediaSessionCompat(context, TAG)

    init {
        session.setCallback(object : MediaSessionCompat.Callback() {

            override fun onPlay() = onCommand(TtsMediaCommand.PLAY)

            override fun onPause() = onCommand(TtsMediaCommand.PAUSE)

            override fun onStop() = onCommand(TtsMediaCommand.STOP)

            override fun onSkipToNext() = onCommand(TtsMediaCommand.NEXT)

            override fun onSkipToPrevious() = onCommand(TtsMediaCommand.PREVIOUS)

            override fun onFastForward() = onCommand(TtsMediaCommand.FAST_FORWARD)

            override fun onRewind() = onCommand(TtsMediaCommand.REWIND)

            /**
             * Headset / Bluetooth / car-kit media keys.
             *
             * The toggle is resolved against the live state ([TtsMediaCommandMapper.resolveToggle])
             * — a single click is `KEYCODE_MEDIA_PLAY_PAUSE` regardless of what the
             * user wants it to do.
             */
            override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                val keyEvent: KeyEvent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    mediaButtonEvent?.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    mediaButtonEvent?.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                }
                val command = TtsMediaCommandMapper.fromKeyCode(keyEvent?.keyCode)
                    ?: return super.onMediaButtonEvent(mediaButtonEvent)
                onCommand(TtsMediaCommandMapper.resolveToggle(command, stateProvider()))
                return true
            }
        })
        session.setFlags(
            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS,
        )
        session.isActive = true
    }

    /** Makes the session (in)active — inactive while no book is loaded. */
    fun setActive(active: Boolean) {
        session.isActive = active
    }

    /**
     * The session token, for `MediaStyle` in the foreground notification (that is
     * what ties the notification's transport buttons to this session).
     */
    val token: MediaSessionCompat.Token get() = session.sessionToken

    /**
     * The activity a lock-screen tap opens, when the host can provide one
     * (`packageManager.getLaunchIntentForPackage`).
     */
    fun setSessionActivity(intent: Intent) {
        session.setSessionActivity(
            android.app.PendingIntent.getActivity(
                context,
                0,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            ),
        )
    }

    /**
     * Publishes the current playback state to the lock screen.
     *
     * @param snapshot pure state (book, chapter, sentence index, progress).
     * @param speed speaking rate, so a future seek-by-rate UI has it.
     */
    fun update(snapshot: TtsPlaybackSnapshot, speed: Float = TtsRate.DEFAULT) {
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, TtsMediaMetadata.title(snapshot))
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, TtsMediaMetadata.subtitle(snapshot))
                .putString(
                    MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION,
                    TtsMediaMetadata.description(snapshot),
                )
                .putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, snapshot.utteranceIndex.toLong())
                .putLong(MediaMetadataCompat.METADATA_KEY_NUM_TRACKS, snapshot.utteranceCount.toLong())
                .build(),
        )
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(TRANSPORT_ACTIONS)
                .setState(playbackStateCode(snapshot.state), snapshot.positionMs, speed.coerceAtLeast(0.1f))
                .build(),
        )
    }

    /** Called when the session dies; `MediaSessionCompat` keeps the framework binder alive otherwise. */
    fun release() {
        session.isActive = false
        session.release()
    }

    companion object {

        const val TAG = "KoodoReaderTts"

        /**
         * What the lock-screen card offers: play/pause/stop/skip/seek plus the
         * chapter-level scrub the P6 card asks for ("锁屏控制 UI").
         */
        private const val TRANSPORT_ACTIONS: Long =
            PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_FAST_FORWARD or
                PlaybackStateCompat.ACTION_REWIND or
                PlaybackStateCompat.ACTION_SEEK_TO

        /** [TtsPlaybackState] → `PlaybackStateCompat.STATE_*`. */
        fun playbackStateCode(state: TtsPlaybackState): Int = when (state) {
            TtsPlaybackState.IDLE -> PlaybackStateCompat.STATE_NONE
            TtsPlaybackState.PREPARING -> PlaybackStateCompat.STATE_BUFFERING
            TtsPlaybackState.PLAYING -> PlaybackStateCompat.STATE_PLAYING
            TtsPlaybackState.PAUSED -> PlaybackStateCompat.STATE_PAUSED
            TtsPlaybackState.STOPPED -> PlaybackStateCompat.STATE_STOPPED
            TtsPlaybackState.ERROR -> PlaybackStateCompat.STATE_ERROR
        }
    }
}
