package com.koodoreader.reader.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.koodoreader.reader.BuildConfig
import com.koodoreader.reader.R

/**
 * Settings → About (settings page 2026-09-29 spec §3.7).
 *
 * Version + open-source licence + crash-log location (the global crash logger
 * wrote those lines specifically so a user can grab them without adb). No
 * analytics, no account — this build is local-first by design.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t("About")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_back), contentDescription = t("Back"))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Text("Readme Reader", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))

            // Reuses the existing desktop key ("Version: ") rather than adding a
            // near-duplicate one.
            Text(
                text = t("Version") + "${BuildConfig.VERSION_NAME} (#${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text(text = t("License"), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "AGPL-3.0 · Koodo Reader & kookit engine",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(
                onClick = {
                    uriHandler.openUri("https://github.com/zhengxiaotian/koodo-reader")
                },
            ) { Text(t("GitHub repository")) }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text(text = t("Crash log"), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                // filesDir/crash — written by the global crash logger (see
                // KoodoReaderApp). Shown as text on purpose: copying out of the
                // app sandbox needs adb/sdcard move, which we don't pretend to
                // handle here.
                text = context.filesDir.absolutePath + "/crash/",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
