package com.koodoreader.reader.shell

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.koodoreader.core.ui.theme.KoodoTheme
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
        ReaderGestureRuntime.config = ReaderGesturePrefs(applicationContext).load()
        val i18n = I18nState.create(this)
        // P6 简繁 AUTO mode needs the reader language before the first layout.
        com.koodoreader.reader.zhconvert.ZhConvertBridge.setReaderLanguage(i18n.language.value)
        val assetHost = startAssetServer()
        prefetchReflowEngine(assetHost)
        val appearance = ShellAppearancePrefs(applicationContext)
        setContent {
            CompositionLocalProvider(LocalI18n provides i18n) {
                // Settings「外观」writes the same backing file; the flow re-emits
                // and this recolors without an activity restart.
                val appMode by appearance.appThemeModeFlow.collectAsStateWithLifecycle()
                KoodoTheme(darkTheme = appMode.darkThemeOf(isSystemInDarkTheme())) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        ShellScaffold(assets = assetHost)
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val g = ReaderGestureRuntime.config
        val gesture = ReaderPageGestures.onVolumeKey(
            event.keyCode,
            enabled = g.volumePageTurn,
            inverted = g.volumeInverted,
        ) ?: return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (ReaderPageTurn.dispatch(gesture)) return true
        }
        if (event.action == KeyEvent.ACTION_UP && ReaderPageTurn.isBound()) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        assetServer.stop()
        super.onDestroy()
    }

    /** Warm foliate bootstrap assets on the loopback server (M2). */
    private fun prefetchReflowEngine(host: ReaderAssetHost) {
        if (!host.available) return
        val base = host.baseUrl.trimEnd('/')
        Thread {
            for (path in FOLIATE_WARM_PATHS) {
                runCatching {
                    java.net.URL("$base/$path").openStream().use { it.readBytes() }
                }
            }
        }.start()
    }

    private companion object {
        const val TAG = "NativeShellActivity"
        val FOLIATE_WARM_PATHS = listOf(
            "foliate/index.html",
            "foliate/engine.mjs",
            "foliate/view.js",
        )
    }
}
