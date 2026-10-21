package com.koodoreader.reader.shell

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Hide the status/navigation bars while the reading toolbar is down.
 * A system-edge swipe still shows them transiently.
 */
@Composable
fun ReaderImmersiveBars(chromeVisible: Boolean) {
    val view = LocalView.current
    DisposableEffect(chromeVisible) {
        applySystemBars(view, hide = ReaderPageGestures.hideSystemBars(chromeVisible))
        onDispose { restoreSystemBars(view) }
    }
}

/**
 * Thin top strip: swipe down to reveal chrome without fighting page scroll.
 */
@Composable
fun ReaderChromeRevealStrip(
    chromeVisible: Boolean,
    modifier: Modifier = Modifier,
    onReveal: () -> Unit,
) {
    if (chromeVisible) return
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(80.dp)
            .pointerInput(onReveal) {
                detectVerticalDragGestures { _, dragAmount ->
                    if (ReaderPageGestures.revealChromeOnSwipeDown(dragAmount)) {
                        onReveal()
                    }
                }
            },
    )
}

private fun applySystemBars(view: View, hide: Boolean) {
    val window = (view.context as? Activity)?.window ?: return
    // Content must draw edge-to-edge, or hiding the status bar leaves a black gap.
    WindowCompat.setDecorFitsSystemWindows(window, false)
    if (Build.VERSION.SDK_INT >= 28) {
        val attrs = window.attributes
        attrs.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        window.attributes = attrs
    }
    val controller = WindowCompat.getInsetsController(window, view)
    if (hide) {
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    } else {
        controller.show(WindowInsetsCompat.Type.systemBars())
    }
}

private fun restoreSystemBars(view: View) {
    val window = (view.context as? Activity)?.window ?: return
    WindowCompat.setDecorFitsSystemWindows(window, true)
    WindowCompat.getInsetsController(window, view)
        .show(WindowInsetsCompat.Type.systemBars())
}
