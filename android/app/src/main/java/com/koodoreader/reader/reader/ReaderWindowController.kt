package com.koodoreader.reader.reader

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.WindowManager
import com.koodoreader.reader.shell.ReaderFeatureCodec
import com.koodoreader.reader.shell.ReaderFeatureState

/**
 * Applies window-level reader presentation (brightness / keep-screen-on /
 * orientation) and restores the system behaviour on leave. Brightness < 0
 * means "follow system" and maps to BRIGHTNESS_OVERRIDE_NONE.
 */
object ReaderWindowController {

    fun apply(activity: Activity, state: ReaderFeatureState) {
        activity.window.attributes = activity.window.attributes.apply {
            screenBrightness = if (state.brightness < 0f) {
                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            } else {
                state.brightness.coerceIn(0f, 1f)
            }
        }
        activity.window.decorView.keepScreenOn = state.keepScreenOn
        activity.requestedOrientation = when (state.orientation) {
            ReaderFeatureCodec.ORIENTATION_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            ReaderFeatureCodec.ORIENTATION_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    fun reset(activity: Activity) {
        activity.window.attributes = activity.window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        activity.window.decorView.keepScreenOn = false
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
}
