package com.koodoreader.reader.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Settings → Reading (settings page 2026-09-29 spec §3.3).
 *
 * One setting for now: the default font size. It reads and writes the SAME
 * SharedPreferences key the reader's A−/A+ control uses (`reader` file,
 * `fontScale`), so the two stay one fact source — no dual store, no drift.
 * The rest of the section stays honest about itself: only rows that work.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingSettingsRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("reader", 0) }
    var fontScale by remember { mutableFloatStateOf(prefs.getFloat("fontScale", 1.0f)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t("Reading")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("Back"))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(8.dp))

            val percent = (fontScale * 100).toInt().toString()
            Text(
                text = "${t("Font size")} · ${percent}%",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "与阅读器顶栏 A−/A+ 同一控制项",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Slider(
                value = fontScale,
                onValueChange = { fontScale = it },
                onValueChangeFinished = {
                    prefs.edit().putFloat("fontScale", fontScale).apply()
                },
                valueRange = 0.7f..2.5f,
                steps = 27,
            )
            Spacer(Modifier.height(16.dp))

            Text(
                text = "其余阅读行为（翻页模式等）随阅读器功能卡落地后加入本区",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
