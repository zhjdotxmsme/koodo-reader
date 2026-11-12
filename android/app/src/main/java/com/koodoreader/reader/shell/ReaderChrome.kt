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
    const val CHROME_ALPHA = 0.92f
    const val PROGRESS_TRACK_ALPHA = 0.12f
    const val HIGHLIGHT_ALPHA = 0.30f
}

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
        exit = fadeOut(tween(MotionTokens.NORMAL)) +
            slideOutVertically(tween(MotionTokens.NORMAL)) { -it },
    ) {
        Box(
            Modifier.fillMaxWidth().background(
                Brush.verticalGradient(
                    0f to MaterialTheme.colorScheme.surface.copy(alpha = ReaderColorTokens.CHROME_ALPHA),
                    1f to MaterialTheme.colorScheme.surface.copy(alpha = 0f),
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
        enter = fadeIn(tween(MotionTokens.NORMAL)) + slideInVertically(tween(MotionTokens.NORMAL)) { it },
        exit = fadeOut(tween(MotionTokens.NORMAL)) + slideOutVertically(tween(MotionTokens.NORMAL)) { it },
    ) {
        Column(
            Modifier.fillMaxWidth().background(
                Brush.verticalGradient(
                    0f to MaterialTheme.colorScheme.surface.copy(alpha = 0f),
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
