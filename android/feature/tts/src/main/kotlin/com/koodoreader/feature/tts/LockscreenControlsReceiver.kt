package com.koodoreader.feature.tts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.KeyEvent
import androidx.core.content.ContextCompat

/**
 * Lock-screen / media-key entry point (DoD [3]).
 *
 * Why a receiver at all, when [MediaSessionController] already gets media keys?
 * Because delivery is not uniform across devices: the MediaSession receives media
 * buttons on stock Android, while some ROMs, headset stacks and car kits deliver
 * plain `android.intent.action.MEDIA_BUTTON` broadcasts — and the notification's
 * transport buttons are `PendingIntent`s that need a target. Both paths end up in
 * the same [TtsMediaCommandMapper] table, so there is exactly one place that knows
 * what a key means.
 *
 * The receiver is deliberately stateless: it maps, forwards to
 * [ForegroundTtsService], and does nothing else. Toggle resolution
 * (`PLAY_PAUSE` → play or pause) happens in the service, which owns the live
 * [TtsSessionController] state.
 */
class LockscreenControlsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val command = TtsMediaCommandMapper.from(intent.action, keyCodeOf(intent)) ?: return
        forward(context, command)
    }

    /** Extracts `KeyEvent.getKeyCode()` from a media-button broadcast, if present. */
    private fun keyCodeOf(intent: Intent): Int? {
        if (!TtsMediaCommandMapper.isMediaButtonAction(intent.action)) return null
        val event: KeyEvent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        }
        return event?.keyCode
    }

    /**
     * Hands the command to the playback service.
     *
     * When the service already runs (the normal case — a foreground service is what
     * keeps the audio alive), a plain `startService` is enough and avoids the
     * Android 12+ background-start restriction. When it does not run, the media key
     * is a user gesture, so `startForegroundService` is attempted; if the platform
     * still refuses (some OEM foreground-service policies), the broadcast is
     * dropped rather than crashing the app.
     */
    private fun forward(context: Context, command: TtsMediaCommand) {
        val intent = ForegroundTtsService.commandIntent(context, command)
        if (ForegroundTtsService.isRunning) {
            context.startService(intent)
            return
        }
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            // Nothing to do: the service cannot be started from the background here.
        }
    }

    companion object {

        /**
         * Actions this receiver handles — the manifest `<intent-filter>` the patch
         * adds is generated from this list so the filter and the code cannot drift.
         */
        val ACTIONS: List<String> = buildList {
            add(TtsMediaCommandMapper.ACTION_MEDIA_BUTTON)
            TtsMediaCommand.values().forEach { add(it.action) }
        }
    }
}
