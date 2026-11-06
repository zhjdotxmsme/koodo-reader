package com.koodoreader.reader.shell

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.koodoreader.core.designsystem.MotionTokens
import com.koodoreader.core.designsystem.SpaceTokens
import com.koodoreader.core.ui.theme.KoodoCardShape

/**
 * First-open reader coach: two tap-zone hints on a dimmed scrim. Tap anywhere
 * or "Got it" to dismiss; shown exactly once per install (SharedPreferences
 * flag shared by the EPUB and PDF reader entries).
 */
@Composable
fun rememberReaderFirstRunGuide(): Pair<Boolean, () -> Unit> {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    }
    var show by remember { mutableStateOf(!prefs.getBoolean(KEY_GUIDE_SHOWN, false)) }
    return show to {
        prefs.edit().putBoolean(KEY_GUIDE_SHOWN, true).apply()
        show = false
    }
}

private const val PREFS_FILE = "reader_onboarding"
private const val KEY_GUIDE_SHOWN = "guide_shown"

@Composable
fun ReaderFirstRunGuide(visible: Boolean, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(MotionTokens.NORMAL)),
        exit = fadeOut(tween(MotionTokens.NORMAL)),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            contentAlignment = Alignment.Center,
        ) {
            Surface(shape = KoodoCardShape) {
                Column(
                    modifier = Modifier.padding(SpaceTokens.xl.dp),
                    verticalArrangement = Arrangement.spacedBy(SpaceTokens.md.dp),
                ) {
                    Text(t("Tap left or right to turn pages"), style = MaterialTheme.typography.bodyLarge)
                    Text(t("Tap the center to toggle the toolbar"), style = MaterialTheme.typography.bodyLarge)
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp),
                    ) { Text(t("Got it")) }
                }
            }
        }
    }
}
