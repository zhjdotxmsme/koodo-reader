package com.koodoreader.reader.debug

import android.app.Activity
import android.app.Application
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.didichuxing.doraemonkit.DoKit
import com.didichuxing.doraemonkit.kit.main.MainIconDoKitView
import com.koodoreader.reader.BuildConfig

/**
 * Wraps DoKit (DoraemonKit) initialization so that the debug toolkit is only
 * enabled in debug builds and never leaks into release builds.
 *
 * DoKit is integrated as a `debugImplementation` dependency; the no-op artifact
 * is used for release, but this wrapper additionally guards the init call with
 * [BuildConfig.DEBUG] and swallows any unexpected initialization errors so the
 * app can still launch if the toolkit is incompatible with a future AGP or
 * Android version.
 */
object DoKitInitializer {

    private const val TAG = "DoKitInitializer"

    /**
     * Whether the initializer is allowed to run. Replaceable in unit tests so
     * the decision can be forced without relying on [BuildConfig.DEBUG].
     *
     * `internal` + [VisibleForTesting]: production code inside this module
     * could still change it, but no other module can, and the annotation
     * states that it exists for tests.
     */
    @JvmStatic
    @VisibleForTesting
    internal var enabledProvider: () -> Boolean = { BuildConfig.DEBUG }

    /**
     * The actual DoKit install logic. Replaceable in unit tests to avoid
     * touching the real Android framework or DoKit internals.
     */
    @JvmStatic
    @VisibleForTesting
    internal var installer: (Application) -> Unit = { application ->
        DoKit.Builder(application)
            .productId("") // platform features are not used; keep empty.
            .disableUpload() // do not send any app info to DoKit servers.
            // Show the draggable floating icon. That icon IS the entry point:
            // tapping it opens DoKit's tool panel (layout boundaries, view
            // hierarchy, CPU/memory).
            //
            // This was previously false, with a comment claiming DoKit opens
            // on a shake instead. It does not: the `dokitx` artifact contains
            // no accelerometer/shake detector at all (verified by scanning the
            // AAR — the only "shake" symbols are TLS handshakes), so hiding
            // the icon left the toolkit with NO way to be opened. The tool
            // panel is attached by tapping the main icon.
            .alwaysShowMainIcon(true)
            .build()
    }

    /**
     * Attaches DoKit's floating icon so the tool panel can be opened.
     *
     * `DoKit.Builder(...).build()` only INITIALISES DoKit — it puts nothing on
     * screen. The icon is attached afterwards, and it must be attached to a
     * live Activity.
     *
     * [DoKit.show] would find that Activity reflectively through
     * ActivityThread, which is blocked on modern Android (hidden-API
     * restrictions), so we attach the icon ourselves through
     * [DoKit.launchFloating] with the Activity we already have.
     *
     * Safe to call repeatedly: DoKit re-attaches the same view instead of
     * adding a second one.
     */
    @JvmStatic
    fun showFloatingIcon(activity: Activity) {
        if (!enabledProvider()) {
            return
        }
        try {
            DoKit.launchFloating(MainIconDoKitView::class.java)
            Log.i(TAG, "DoKit floating icon attached to ${activity.javaClass.simpleName}.")
        } catch (error: Exception) {
            // A debug tool must never take the app down.
            Log.e(TAG, "Failed to show the DoKit floating icon.", error)
        }
    }

    /**
     * Initializes DoKit when [enabledProvider] returns true.
     *
     * @return true if initialization was attempted and succeeded, false if it
     *         was skipped or the installer threw.
     */
    @JvmStatic
    fun install(application: Application): Boolean {
        if (!enabledProvider()) {
            Log.i(TAG, "DoKit disabled for this build/flavor.")
            return false
        }
        return try {
            installer(application)
            Log.i(TAG, "DoKit initialized.")
            true
        } catch (error: Exception) {
            // DoKit is a debug-only tool; never crash the app because of it.
            // Only Exception is caught on purpose: swallowing an Error (an
            // OutOfMemoryError, or the NoClassDefFoundError of a broken
            // dependency) would hide the very failure this guard exists for.
            Log.e(TAG, "Failed to initialize DoKit, continuing without it.", error)
            false
        }
    }
}
