package com.koodoreader.reader.shell

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier

/**
 * Native shell entry (P1, dual-track: docs/android-native-migration.md §2.1).
 *
 * Launched instead of the WebView host when the APK is built with
 * `-Ptarget=native` (see the LauncherAlias activity-alias in the manifest).
 * Contents: bookshelf (Room-backed) + reader placeholder; the real native
 * reading engine lands in P2.
 */
class NativeShellActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i18n = I18nState.create(this)
        setContent {
            CompositionLocalProvider(LocalI18n provides i18n) {
                KoodoShellTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        ShellNavHost()
                    }
                }
            }
        }
    }
}
