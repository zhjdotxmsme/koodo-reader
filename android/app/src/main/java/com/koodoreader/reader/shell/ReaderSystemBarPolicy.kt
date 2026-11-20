package com.koodoreader.reader.shell

import android.view.WindowManager
import com.koodoreader.core.designsystem.SpaceTokens
import kotlin.math.roundToInt

object ReaderSystemBarPolicy {
    fun shouldHide(fullScreen: Boolean, chromeVisible: Boolean): Boolean =
        fullScreen || !chromeVisible

    fun displayCutoutMode(sdkInt: Int): Int =
        if (sdkInt >= 28) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        } else {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
        }
}

/**
 * The reader page's **immersive insets**: how much room the body text keeps
 * clear of the system bars.
 *
 * ## The rule
 *
 *  - top    = status-bar height + 8dp
 *  - bottom = navigation-bar (gesture) height + 16dp
 *
 * The bottom gets more room than the top on purpose. The top inset only has
 * to clear the clock and battery icons; the bottom has to clear the gesture
 * bar *and* stay clear of the thumb that rests there while reading, and it is
 * where a cramped last line gets mis-tapped.
 *
 * ## Why the insets are subtracted from the viewport, not just padded
 *
 * The pagination engine computes the page height from the viewport it is
 * given. If the viewport is the full window but the text is drawn inside a
 * padded container, the engine thinks it has more room than it does and puts
 * the last line *under* the navigation bar. Subtracting the insets from the
 * viewport keeps the page break and the drawn text in agreement — the text
 * ends exactly where the engine said the page ends.
 */
object ReaderContentInsetPolicy {

    /** Extra clearance above the first line, below the status bar. */
    const val TOP_EXTRA_DP = SpaceTokens.READER_TOP_EXTRA

    /** Extra clearance below the last line, above the navigation bar. */
    const val BOTTOM_EXTRA_DP = SpaceTokens.READER_BOTTOM_EXTRA

    fun topPx(statusBarPx: Int, density: Float): Int =
        statusBarPx + (TOP_EXTRA_DP * density).roundToInt()

    fun bottomPx(navigationBarPx: Int, density: Float): Int =
        navigationBarPx + (BOTTOM_EXTRA_DP * density).roundToInt()

    /**
     * The viewport the pagination engine should use: the window size minus the
     * immersive insets.
     *
     * @return the reduced size, never smaller than 1px on either axis.
     */
    fun contentSize(
        widthPx: Int,
        heightPx: Int,
        topPx: Int,
        bottomPx: Int,
    ): Pair<Int, Int> = (widthPx - topPx).coerceAtLeast(1) to (heightPx - bottomPx).coerceAtLeast(1)
}
