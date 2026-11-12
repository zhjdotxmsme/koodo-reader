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

object ReaderContentInsetPolicy {
    fun topPx(statusBarPx: Int, density: Float): Int =
        statusBarPx + (SpaceTokens.READER_TOP_EXTRA * density).roundToInt()
    fun bottomPx(navigationBarPx: Int, density: Float): Int =
        navigationBarPx + (SpaceTokens.READER_BOTTOM_EXTRA * density).roundToInt()
}
