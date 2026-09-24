package com.koodoreader.reader.shell

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.koodoreader.reader.LocalAssetServer

/**
 * Native shell entry (P1, dual-track: docs/android-native-migration.md §2.1).
 *
 * Launched instead of the WebView host when the APK is built with
 * `-Ptarget=native` (see the LauncherAlias activity-alias in the manifest).
 * Contents: bookshelf (Room-backed) + the format readers.
 *
 * It also owns the [LocalAssetServer]: the native PDF reader (P3) renders
 * through pdf.js, which fetches both its own assets (`assets/pdfengine/`) and
 * the book itself over the loopback origin. The server is started here, before
 * composition, so a reader screen can publish its book synchronously; when it
 * cannot start, the readers show an explicit unavailable state instead of
 * failing obscurely.
 */
class NativeShellActivity : ComponentActivity() {

    private val assetServer by lazy { LocalAssetServer(assets) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i18n = I18nState.create(this)
        val assetHost = startAssetServer()
        setContent {
            CompositionLocalProvider(LocalI18n provides i18n) {
                KoodoShellTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        ShellNavHost(assets = assetHost)
                    }
                }
            }
        }
    }

    /** Start the loopback origin; a failure downgrades the readers, never the shelf. */
    private fun startAssetServer(): ReaderAssetHost {
        return runCatching {
            if (!assetServer.isRunning) assetServer.start()
            ReaderAssetHost(assetServer.baseUrl(), assetServer)
        }.getOrElse {
            Log.w(TAG, "loopback asset server unavailable; native readers will report it", it)
            ReaderAssetHost.NONE
        }
    }

    override fun onDestroy() {
        assetServer.stop()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "NativeShellActivity"
    }
}
