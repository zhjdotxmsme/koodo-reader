package com.koodoreader.reader.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.koodoreader.engine.gesture.GestureEngine
import com.koodoreader.engine.gesture.GestureMode
import com.koodoreader.engine.gesture.GestureResult
import com.koodoreader.engine.gesture.ReaderConfig

/**
 * Native reader screen (P2).
 *
 * Displays a page-number indicator and applies the [GestureEngine] gesture
 * layer. The actual text rendering (engine/layout) lands in a separate
 * P2 task; this screen is the gesture-integration host.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NativeReaderScreen(
    bookKey: String,
    onBack: () -> Unit,
    viewModel: LibraryViewModel = viewModel(),
) {
    var currentPage by remember { mutableIntStateOf(0) }
    val engine = remember {
        val config = ReaderConfig(
            viewportWidthPx = 400f,
            viewportHeightPx = 700f,
            totalPages = 20,
            mode = GestureMode.PAGE_TURN,
        )
        GestureEngine(config)
    }

    fun onGestureResult(result: GestureResult) {
        when (result) {
            is GestureResult.PageTurn -> currentPage = result.targetPage
            is GestureResult.Overscroll -> { /* spring-back handled by animation */ }
            else -> {}
        }
    }

    val gestureModifier = ReaderGestureModifier(engine = engine, onResult = ::onGestureResult)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reader (P2)") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                val next = (currentPage + 1).coerceIn(0, 19)
                currentPage = next
                engine.setCurrentPage(next)
            }) {
                Text("next ")
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
        ) {
            // Page indicator overlay
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "Page  / 20",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Swipe left/right to turn pages.\n" +
                        "Tap left side = prev, right side = next.\n" +
                        "Fling for inertia; edge bounce at boundaries.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }

            // Gesture layer (applied to the whole content area)
            Box(modifier = gestureModifier.fillMaxSize()) {
                // In a full implementation this Box would host the text-rendering
                // composable (engine/layout). For the gesture task, the
                // placeholder demonstrates that the gesture modifier works.
            }
        }
    }
}