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
 * @property topPx / bottomPx the same values in pixels — the pagination
 *   engine works in px, and the viewport it is given must already exclude the
 *   insets, otherwise the last line of a page is laid out under the
 *   navigation bar.
 */
data class ReaderContentInsets(
    val top: Dp,
    val bottom: Dp,
    val topPx: Int = 0,
    val bottomPx: Int = 0,
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
    return remember(top, bottom, density) {
        ReaderContentInsets(
            top = with(density) { top.toDp() },
            bottom = with(density) { bottom.toDp() },
            topPx = top,
            bottomPx = bottom,
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
