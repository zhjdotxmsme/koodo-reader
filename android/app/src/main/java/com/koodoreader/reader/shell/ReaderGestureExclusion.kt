package com.koodoreader.reader.shell

import android.graphics.Rect
import android.os.Build
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView

/**
 * Keeps the left page-turn band usable on gesture-navigation devices.
 *
 * Android 10+ reserves a ~20dp strip on each screen edge for the system
 * back gesture. Without an exclusion the first tap of every left-band
 * interaction is swallowed by the OS and the reader looks unresponsive.
 *
 * Two rules keep this from becoming a trap:
 *  - only the **page-turn bands** are excluded (never the whole screen), so the
 *    back gesture still works from the chrome band;
 *  - the excluded strip is capped at [MAX_EDGE_DP], well under the platform's
 *    ~200dp per-edge budget, and is removed as soon as the reader is disposed.
 */
object ReaderGestureExclusion {

    /** Wider than a real thumb, narrow enough to leave the back gesture reachable. */
    const val MAX_EDGE_DP = 32f

    fun rects(
        widthPx: Float,
        heightPx: Float,
        edgeFraction: Float,
        density: Float = 1f,
    ): List<Rect> {
        if (widthPx <= 0f || heightPx <= 0f) return emptyList()
        val maxEdgePx = MAX_EDGE_DP * if (density > 0f) density else 1f
        val band = (widthPx * edgeFraction.coerceIn(0f, 0.5f)).coerceAtMost(maxEdgePx)
        if (band <= 0f) return emptyList()
        val w = widthPx.toInt()
        val h = heightPx.toInt()
        return listOf(
            Rect(0, 0, band.toInt(), h),
            Rect(w - band.toInt(), 0, w, h),
        )
    }

    /** Applies (or clears) the exclusion rects. Safe on API < 29: no-op. */
    fun apply(
        view: View,
        widthPx: Float,
        heightPx: Float,
        edgeFraction: Float,
        enabled: Boolean,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val density = view.resources.displayMetrics.density
        val rects = if (enabled) rects(widthPx, heightPx, edgeFraction, density) else emptyList()
        if (view.systemGestureExclusionRects == rects) return
        view.systemGestureExclusionRects = rects
    }

    fun clear(view: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (view.systemGestureExclusionRects.isEmpty()) return
        view.systemGestureExclusionRects = emptyList()
    }

    /**
     * Installs an exclusion that follows the view's size and the live gesture
     * config. Returns the listener so the caller can remove it on dispose.
     */
    fun install(view: View): View.OnLayoutChangeListener {
        val listener = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val config = ReaderGestureRuntime.config
            apply(
                view = v,
                widthPx = v.width.toFloat(),
                heightPx = v.height.toFloat(),
                edgeFraction = ReaderGestureCodec.clampedTapBack(config),
                enabled = true,
            )
        }
        view.addOnLayoutChangeListener(listener)
        view.post { listener.onLayoutChange(view, 0, 0, view.width, view.height, 0, 0, 0, 0) }
        return listener
    }
}

/**
 * Compose-side variant for surfaces that have no WebView (Canvas/PDF/comic):
 * excludes the two page-turn bands of the hosting Compose view.
 */
@Composable
fun rememberReaderGestureExclusion(
    edgeFraction: Float,
    enabled: Boolean = true,
): Modifier {
    val view = LocalView.current
    DisposableEffect(view, enabled, edgeFraction) {
        attachExclusion(view, edgeFraction, enabled)
        onDispose { ReaderGestureExclusion.clear(view) }
    }
    return Modifier.onSizeChanged { size ->
        attachExclusion(view, edgeFraction, enabled)
    }
}

private fun attachExclusion(view: View, edgeFraction: Float, enabled: Boolean) {
    ReaderGestureExclusion.apply(
        view = view,
        widthPx = view.width.toFloat(),
        heightPx = view.height.toFloat(),
        edgeFraction = edgeFraction,
        enabled = enabled,
    )
}
