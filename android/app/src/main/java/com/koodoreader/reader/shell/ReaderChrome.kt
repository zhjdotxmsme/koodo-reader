package com.koodoreader.reader.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.koodoreader.core.designsystem.MotionTokens
import com.koodoreader.core.designsystem.SizeTokens
import com.koodoreader.core.designsystem.SpaceTokens
import com.koodoreader.core.ui.component.koodoPressScale
import com.koodoreader.core.ui.theme.KoodoMotion
import com.koodoreader.reader.R

object ReaderColorTokens {
    /**
     * The opacity of the chrome gradient at its opaque end.
     *
     * 80% — the toolbar must read as a distinct surface, but a fully opaque
     * band would be a hard edge cutting the page in half. At 80% the page
     * shows faintly through the chrome, so the toolbar feels like a sheet of
     * glass laid over the text rather than a wall pasted on top.
     */
    const val CHROME_ALPHA = 0.80f

    /** The opacity at the far (fully transparent) end of the gradient. */
    const val CHROME_ALPHA_FADE = 0f

    const val PROGRESS_TRACK_ALPHA = 0.12f

    /**
     * The opacity of a highlight band painted behind body text.
     *
     * 30% — see `HighlightPalette` in `:engine:annotate` for the full
     * rationale. Kept here as well because the reader chrome paints its own
     * highlights (selection previews) with the same rule.
     */
    const val HIGHLIGHT_ALPHA = 0.30f
}

/**
 * The reader's top toolbar.
 *
 * ## The gradient
 *
 * The toolbar is not a solid bar: it is a vertical gradient that starts at
 * 80% opacity of the reader's own surface colour and fades to fully
 * transparent. The text underneath therefore dissolves into the chrome
 * instead of being cut off by it — which is the whole point of the
 * immersive reader: the page is the content, the toolbar is a temporary
 * overlay on top of it.
 *
 * The gradient is drawn on a container that spans the status-bar height as
 * well, so the status bar's clock sits on the same wash as the toolbar and
 * the two read as one surface.
 *
 * ## The motion
 *
 * 200ms of fade + a slide from the top edge, on both entry and exit, using
 * the decelerate curve on entry and the accelerate curve on exit: the bar
 * arrives gently and leaves briskly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderTopBar(
    visible: Boolean,
    title: @Composable () -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(MotionTokens.NORMAL, easing = KoodoMotion.Decelerate)) +
            slideInVertically(tween(MotionTokens.NORMAL, easing = KoodoMotion.Decelerate)) { -it },
        exit = fadeOut(tween(MotionTokens.NORMAL, easing = KoodoMotion.Accelerate)) +
            slideOutVertically(tween(MotionTokens.NORMAL, easing = KoodoMotion.Accelerate)) { -it },
    ) {
        Box(
            Modifier.fillMaxWidth().background(
                Brush.verticalGradient(
                    0f to MaterialTheme.colorScheme.surface.copy(alpha = ReaderColorTokens.CHROME_ALPHA),
                    0.72f to MaterialTheme.colorScheme.surface.copy(alpha = ReaderColorTokens.CHROME_ALPHA * 0.35f),
                    1f to MaterialTheme.colorScheme.surface.copy(alpha = ReaderColorTokens.CHROME_ALPHA_FADE),
                )
            ).statusBarsPadding()
        ) {
            TopAppBar(
                modifier = Modifier.height(SpaceTokens.TOP_BAR_HEIGHT.dp),
                title = title,
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.koodoPressScale()) {
                        Icon(painterResource(R.drawable.ic_back), contentDescription = t("Back"))
                    }
                },
                actions = {
                    actions()
                    IconButton(onClick = onClose, modifier = Modifier.koodoPressScale()) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = t("Close"))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
    }
}

/**
 * The reader's bottom progress strip.
 *
 * Mirrors [ReaderTopBar]: the gradient runs the other way (transparent at the
 * top, opaque at the bottom) so the strip dissolves into the page above it
 * and sits solidly on the navigation bar below.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ReaderBottomProgress(
    visible: Boolean,
    fraction: Float,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(MotionTokens.NORMAL, easing = KoodoMotion.Decelerate)) +
            slideInVertically(tween(MotionTokens.NORMAL, easing = KoodoMotion.Decelerate)) { it },
        exit = fadeOut(tween(MotionTokens.NORMAL, easing = KoodoMotion.Accelerate)) +
            slideOutVertically(tween(MotionTokens.NORMAL, easing = KoodoMotion.Accelerate)) { it },
    ) {
        Column(
            Modifier.fillMaxWidth().background(
                Brush.verticalGradient(
                    0f to MaterialTheme.colorScheme.surface.copy(alpha = ReaderColorTokens.CHROME_ALPHA_FADE),
                    0.28f to MaterialTheme.colorScheme.surface.copy(alpha = ReaderColorTokens.CHROME_ALPHA * 0.35f),
                    1f to MaterialTheme.colorScheme.surface.copy(alpha = ReaderColorTokens.CHROME_ALPHA),
                )
            ).windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility).clickable(onClick = onClick)
        ) {
            LinearProgressIndicator(
                progress = { fraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(SizeTokens.PROGRESS_STROKE.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = ReaderColorTokens.PROGRESS_TRACK_ALPHA),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = SpaceTokens.md.dp, vertical = SpaceTokens.sm.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                actions()
            }
        }
    }
}
