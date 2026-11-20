package com.koodoreader.reader.shell

import android.app.Activity
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Build
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.koodoreader.core.ui.theme.LocalKoodoShellColorScheme

/**
 * The reader page's immersive insets.
 *
 * @property top clearance above the first body-text line (status bar + 8dp).
 * @property bottom clearance below the last body-text line (gesture bar + 16dp).
 * @property left / right clearance for a landscape navigation bar or a display
 *   cutout. These are **zero in portrait**, so nothing changes there.
 * @property topPx / bottomPx / startPx / endPx the same values in pixels — the
 *   pagination engine works in px, and the viewport it is given must already
 *   exclude the insets, otherwise the last line of a page is laid out under the
 *   navigation bar.
 */
data class ReaderContentInsets(
    val top: Dp,
    val bottom: Dp,
    val left: Dp = Dp(0f),
    val right: Dp = Dp(0f),
    val topPx: Int = 0,
    val bottomPx: Int = 0,
    val startPx: Int = 0,
    val endPx: Int = 0,
)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun rememberReaderContentInsets(): ReaderContentInsets {
    val density = LocalDensity.current
    val top = ReaderContentInsetPolicy.topPx(
        WindowInsets.statusBarsIgnoringVisibility.getTop(density), density.density,
    )
    val bottom = ReaderContentInsetPolicy.bottomPx(
        WindowInsets.navigationBarsIgnoringVisibility.getBottom(density), density.density,
    )
    // Landscape: the navigation bar moves to one side and the cutout may sit on
    // the other. Portrait reports 0 here, so this is a no-op there.
    val layoutDirection = LocalLayoutDirection.current
    val left = WindowInsets.navigationBarsIgnoringVisibility.getLeft(density, layoutDirection)
    val right = WindowInsets.navigationBarsIgnoringVisibility.getRight(density, layoutDirection)
    return remember(top, bottom, left, right, density) {
        ReaderContentInsets(
            top = with(density) { top.toDp() },
            bottom = with(density) { bottom.toDp() },
            left = with(density) { left.toDp() },
            right = with(density) { right.toDp() },
            topPx = top,
            bottomPx = bottom,
            startPx = left,
            endPx = right,
        )
    }
}

@Suppress("DEPRECATION")
@Composable
fun ReaderSystemBars(fullScreen: Boolean, chromeVisible: Boolean, readerBackground: Color) {
    val view = LocalView.current
    val window = (view.context as? Activity)?.window ?: return
    val controller = WindowCompat.getInsetsController(window, view)
    val shellScheme = LocalKoodoShellColorScheme.current
    val currentShellScheme = rememberUpdatedState(shellScheme)
    val readerDarkIcons = readerBackground.luminance() > 0.5f

    SideEffect {
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 28) {
            val attrs = window.attributes
            attrs.layoutInDisplayCutoutMode = ReaderSystemBarPolicy.displayCutoutMode(Build.VERSION.SDK_INT)
            window.attributes = attrs
        }
        window.setBackgroundDrawable(ColorDrawable(readerBackground.toArgb()))
        controller.isAppearanceLightStatusBars = readerDarkIcons
        controller.isAppearanceLightNavigationBars = readerDarkIcons
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (ReaderSystemBarPolicy.shouldHide(fullScreen, chromeVisible)) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    DisposableEffect(view) {
        onDispose {
            val scheme = currentShellScheme.value
            val darkIcons = scheme.background.luminance() > 0.5f
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.isAppearanceLightStatusBars = darkIcons
            controller.isAppearanceLightNavigationBars = darkIcons
            window.setBackgroundDrawable(ColorDrawable(scheme.background.toArgb()))
        }
    }
}
