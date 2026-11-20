package com.koodoreader.reader.shell

import com.koodoreader.engine.gesture.TapAction
import com.koodoreader.engine.gesture.TapZoneGeometry
import com.koodoreader.engine.gesture.TapZoneLayout

/**
 * Density-independent gesture thresholds.
 *
 * Regression this fixes: every threshold used to be a raw pixel constant
 * (`minDx = 48f`, reveal `24f`, selection slop `24f`). On a 3× phone that is
 * 16dp / 8dp — a slightly off-axis tap turned the page, and a barely downward
 * drag popped the toolbar out. The same numbers are now expressed in dp and
 * converted once, so behaviour is identical on every density.
 */
object ReaderGestureMetrics {

    /** Movement above this is a drag, not a tap. */
    const val TAP_SLOP_DP = 8f

    /** Horizontal travel required before a drag counts as a page-turn swipe. */
    const val SWIPE_MIN_DP = 24f

    /** Downward travel from the top strip that reveals the hidden toolbar. */
    const val CHROME_REVEAL_DP = 24f

    /** Long-press delay used by the WebView hosts for text selection. */
    const val LONG_PRESS_MS = 500L

    fun tapSlopPx(density: Float): Float = TAP_SLOP_DP * safeDensity(density)

    fun swipeMinPx(density: Float): Float = SWIPE_MIN_DP * safeDensity(density)

    fun chromeRevealPx(density: Float): Float = CHROME_REVEAL_DP * safeDensity(density)

    private fun safeDensity(density: Float): Float =
        if (density.isFinite() && density > 0f) density else 1f
}

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
    CLOSE,
    ;

    companion object {
        /** Always visible on the reading TopAppBar. */
        val PRIMARY: List<ReaderChromeAction> = listOf(TOC, SEARCH, CLOSE)

        /** Overflow ("more") menu — must not hide [PRIMARY]. */
        val OVERFLOW: List<ReaderChromeAction> = listOf(FONT, BOOKMARK, TTS)
    }
}

object ReaderPageGestures {

    /** Toolbar starts hidden so the book opens full-screen. */
    const val CHROME_VISIBLE_ON_OPEN = false

    /**
     * Legacy x-only hit test, kept for callers (and tests) that have no y axis.
     * New code should call [onTapAt] so vertical layouts and RTL work.
     */
    fun onTap(x: Float, widthPx: Float, edgeFraction: Float = 1f / 3f): ReaderPageGesture =
        onTapAt(
            x = x,
            y = 0f,
            widthPx = widthPx,
            heightPx = 1f,
            layout = TapZoneLayout.EDGES,
            forwardRatio = edgeFraction,
            backwardRatio = edgeFraction,
        )

    /**
     * Two-dimensional hit test.
     *
     * @param mirrored right-to-left reading order (manga, Arabic, Hebrew).
     */
    fun onTapAt(
        x: Float,
        y: Float,
        widthPx: Float,
        heightPx: Float,
        layout: TapZoneLayout = TapZoneLayout.EDGES,
        forwardRatio: Float = 1f / 3f,
        backwardRatio: Float = forwardRatio,
        mirrored: Boolean = false,
    ): ReaderPageGesture {
        if (widthPx <= 0f || heightPx <= 0f) return ReaderPageGesture.TOGGLE_CHROME
        return when (
            TapZoneGeometry.resolve(
                x = x,
                y = y,
                viewportWidth = widthPx,
                viewportHeight = heightPx,
                layout = layout,
                forwardRatio = forwardRatio,
                backwardRatio = backwardRatio,
                mirrored = mirrored,
            )
        ) {
            TapAction.PREV_PAGE -> ReaderPageGesture.PREV_PAGE
            TapAction.NEXT_PAGE -> ReaderPageGesture.NEXT_PAGE
            TapAction.NONE -> ReaderPageGesture.TOGGLE_CHROME
        }
    }

    /** Convenience overload driven by the live user settings. */
    fun onTapAt(
        x: Float,
        y: Float,
        widthPx: Float,
        heightPx: Float,
        config: ReaderGestureConfig,
        mirrored: Boolean = false,
    ): ReaderPageGesture = onTapAt(
        x = x,
        y = y,
        widthPx = widthPx,
        heightPx = heightPx,
        layout = config.tapZoneLayout,
        forwardRatio = ReaderGestureCodec.clampedTapEdge(config),
        backwardRatio = ReaderGestureCodec.clampedTapBack(config),
        mirrored = mirrored,
    )

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
        edgeFraction: Float = 1f / 3f,
    ): ReaderPageGesture? = tapAtIfNotDrag(
        downX = downX,
        downY = downY,
        upX = upX,
        upY = upY,
        widthPx = widthPx,
        heightPx = 1f,
        slopPx = slopPx,
        layout = TapZoneLayout.EDGES,
        forwardRatio = edgeFraction,
        backwardRatio = edgeFraction,
    )

    /**
     * Two-dimensional variant of [tapIfNotDrag]: same slop gate, but the tap is
     * resolved against the configured layout.
     */
    fun tapAtIfNotDrag(
        downX: Float,
        downY: Float,
        upX: Float,
        upY: Float,
        widthPx: Float,
        heightPx: Float,
        slopPx: Float = 24f,
        layout: TapZoneLayout = TapZoneLayout.EDGES,
        forwardRatio: Float = 1f / 3f,
        backwardRatio: Float = forwardRatio,
        mirrored: Boolean = false,
    ): ReaderPageGesture? {
        if (kotlin.math.abs(upX - downX) > slopPx || kotlin.math.abs(upY - downY) > slopPx) {
            return null
        }
        return onTapAt(
            x = upX,
            y = upY,
            widthPx = widthPx,
            heightPx = heightPx,
            layout = layout,
            forwardRatio = forwardRatio,
            backwardRatio = backwardRatio,
            mirrored = mirrored,
        )
    }

    /**
     * Horizontal page-turn: finger left → next, finger right → previous.
     * Vertical motion (scroll / chrome reveal) wins when it dominates.
     */
    fun onHorizontalSwipe(
        dx: Float,
        dy: Float,
        minDx: Float = 48f,
        enabled: Boolean = true,
    ): ReaderPageGesture? {
        if (!enabled) return null
        if (kotlin.math.abs(dx) < minDx) return null
        if (kotlin.math.abs(dx) <= kotlin.math.abs(dy)) return null
        return if (dx < 0f) ReaderPageGesture.NEXT_PAGE else ReaderPageGesture.PREV_PAGE
    }

    /** android.view.KeyEvent.KEYCODE_VOLUME_UP */
    const val VOLUME_UP = 24

    /** android.view.KeyEvent.KEYCODE_VOLUME_DOWN */
    const val VOLUME_DOWN = 25

    fun onVolumeKey(
        keyCode: Int,
        enabled: Boolean = true,
        inverted: Boolean = false,
    ): ReaderPageGesture? {
        if (!enabled) return null
        return when (keyCode) {
            VOLUME_DOWN -> if (inverted) ReaderPageGesture.PREV_PAGE else ReaderPageGesture.NEXT_PAGE
            VOLUME_UP -> if (inverted) ReaderPageGesture.NEXT_PAGE else ReaderPageGesture.PREV_PAGE
            else -> null
        }
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
