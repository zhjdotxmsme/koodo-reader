package com.koodoreader.reader.reader

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * Hides native [View] surfaces (WebView / Foliate) while modal reader UI is open
 * so Compose sheets are not painted underneath hardware-accelerated layers.
 *
 * ## What it must not be used for
 *
 * A surface that carries *reader content the user is currently acting on* must
 * never be listed here. `INVISIBLE` does not stop the hardware layer from
 * showing its last composited frame — the text stays on screen, dimmed by the
 * sheet's scrim, so the call looks like it worked. What it actually stops is
 * any *new* frame: the WebView is detached from input and stops painting, so a
 * selection the browser would highlight, or a reflow repaint, never appears.
 *
 * That is why the selection menus (EPUB and PDF reflow) are excluded from the
 * obscured set: the user picked words and must see them marked while choosing
 * an action. Sheets that replace the page entirely (control center, notes) can
 * still obscure it.
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