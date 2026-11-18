package com.koodoreader.reader.reader

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * Hides native [View] surfaces (WebView / Foliate) while modal reader UI is open
 * so Compose sheets are not painted underneath hardware-accelerated layers.
 */
@Composable
fun ReaderNativeSurfaceObscurer(obscured: Boolean, vararg surfaces: View?) {
    val targets = surfaces.filterNotNull()
    if (targets.isEmpty()) return
    DisposableEffect(obscured, targets) {
        val prior = targets.map { it.visibility }
        targets.forEach { view ->
            view.visibility = if (obscured) View.INVISIBLE else View.VISIBLE
        }
        onDispose {
            targets.forEachIndexed { index, view -> view.visibility = prior[index] }
        }
    }
}