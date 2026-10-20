package com.koodoreader.reader.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.koodoreader.feature.stats.platform.ReadingSessionWiring
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Credits wall-clock reading time into [ReadingSessionWiring] while this
 * composition is on screen. Idle gaps are clamped by the repository
 * (desktop `ReadingTimeUtil` parity).
 */
@Composable
fun RememberReadingSession(bookKey: String) {
    val context = LocalContext.current
    val repo = remember(context) { ReadingSessionWiring.repository(context) }
    DisposableEffect(bookKey) {
        if (bookKey.isBlank()) {
            return@DisposableEffect onDispose { }
        }
        repo.begin(bookKey)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            while (isActive) {
                delay(TICK_MILLIS)
                repo.tick()
            }
        }
        onDispose {
            scope.cancel()
            CoroutineScope(Dispatchers.IO).launch { repo.end() }
        }
    }
}

private const val TICK_MILLIS = 5_000L
