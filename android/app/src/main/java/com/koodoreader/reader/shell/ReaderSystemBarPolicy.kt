package com.koodoreader.reader.shell

import com.koodoreader.core.designsystem.SpaceTokens
import kotlin.math.roundToInt

object ReaderSystemBarPolicy {
    fun shouldHide(fullScreen: Boolean, chromeVisible: Boolean): Boolean =
        fullScreen || !chromeVisible
}

object ReaderContentInsetPolicy {
    fun topPx(statusBarPx: Int, density: Float): Int =
        statusBarPx + (SpaceTokens.READER_TOP_EXTRA * density).roundToInt()
    fun bottomPx(navigationBarPx: Int, density: Float): Int =
        navigationBarPx + (SpaceTokens.READER_BOTTOM_EXTRA * density).roundToInt()
}
