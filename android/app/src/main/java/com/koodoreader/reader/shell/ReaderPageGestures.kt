package com.koodoreader.reader.shell

/**
 * Pure gesture + chrome layout for the native reader screens.
 *
 * Kept off Compose so JVM tests can pin the contract: long-press (and
 * double-tap) open the selection menu; the TOC button is a primary chrome
 * action so it cannot overflow off a phone-sized TopAppBar.
 */
enum class ReaderPageGesture {
    PREV_PAGE,
    NEXT_PAGE,
    TOGGLE_CHROME,
    SELECT,
}

enum class ReaderChromeAction {
    TOC,
    SEARCH,
    BOOKMARK,
    FONT,
    TTS,
    ;

    companion object {
        /** Always visible on the reading TopAppBar. */
        val PRIMARY: List<ReaderChromeAction> = listOf(TOC, SEARCH)

        /** Overflow ("more") menu — must not hide [PRIMARY]. */
        val OVERFLOW: List<ReaderChromeAction> = listOf(FONT, BOOKMARK, TTS)
    }
}

object ReaderPageGestures {

    /** Toolbar starts hidden so the book opens full-screen. */
    const val CHROME_VISIBLE_ON_OPEN = false

    fun onTap(x: Float, widthPx: Float, edgeFraction: Float = 1f / 3f): ReaderPageGesture {
        if (widthPx <= 0f) return ReaderPageGesture.TOGGLE_CHROME
        val edge = (widthPx * edgeFraction).coerceAtLeast(1f)
        return when {
            x < edge -> ReaderPageGesture.PREV_PAGE
            x > widthPx - edge -> ReaderPageGesture.NEXT_PAGE
            else -> ReaderPageGesture.TOGGLE_CHROME
        }
    }

    fun onLongPress(): ReaderPageGesture = ReaderPageGesture.SELECT

    fun onDoubleTap(): ReaderPageGesture = ReaderPageGesture.SELECT

    /** Downward swipe from the top strip reveals the hidden toolbar. */
    fun revealChromeOnSwipeDown(dyPx: Float, thresholdPx: Float = 24f): Boolean =
        dyPx >= thresholdPx

    fun hideSystemBars(chromeVisible: Boolean): Boolean = !chromeVisible

    /**
     * WebView eats Compose taps; host-side ACTION_UP uses this so a stationary
     * press can toggle chrome without fighting scroll.
     */
    fun tapIfNotDrag(
        downX: Float,
        downY: Float,
        upX: Float,
        upY: Float,
        widthPx: Float,
        slopPx: Float = 24f,
    ): ReaderPageGesture? {
        if (kotlin.math.abs(upX - downX) > slopPx || kotlin.math.abs(upY - downY) > slopPx) {
            return null
        }
        return onTap(upX, widthPx)
    }

    /**
     * Horizontal page-turn: finger left → next, finger right → previous.
     * Vertical motion (scroll / chrome reveal) wins when it dominates.
     */
    fun onHorizontalSwipe(dx: Float, dy: Float, minDx: Float = 48f): ReaderPageGesture? {
        if (kotlin.math.abs(dx) < minDx) return null
        if (kotlin.math.abs(dx) <= kotlin.math.abs(dy)) return null
        return if (dx < 0f) ReaderPageGesture.NEXT_PAGE else ReaderPageGesture.PREV_PAGE
    }

    /** android.view.KeyEvent.KEYCODE_VOLUME_UP */
    const val VOLUME_UP = 24

    /** android.view.KeyEvent.KEYCODE_VOLUME_DOWN */
    const val VOLUME_DOWN = 25

    fun onVolumeKey(keyCode: Int): ReaderPageGesture? = when (keyCode) {
        VOLUME_DOWN -> ReaderPageGesture.NEXT_PAGE
        VOLUME_UP -> ReaderPageGesture.PREV_PAGE
        else -> null
    }
}

/**
 * The open reader screen binds here so [NativeShellActivity] can swallow
 * volume keys and turn the page instead of changing media volume.
 */
object ReaderPageTurn {
    @Volatile
    private var handler: ((ReaderPageGesture) -> Boolean)? = null

    fun bind(handler: (ReaderPageGesture) -> Boolean) {
        this.handler = handler
    }

    fun unbind() {
        handler = null
    }

    fun dispatch(gesture: ReaderPageGesture): Boolean = handler?.invoke(gesture) == true

    fun isBound(): Boolean = handler != null
}
