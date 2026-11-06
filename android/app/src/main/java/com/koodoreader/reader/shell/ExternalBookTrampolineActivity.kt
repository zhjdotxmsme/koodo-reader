package com.koodoreader.reader.shell

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.koodoreader.reader.BuildConfig
import com.koodoreader.reader.imagehost.ComicViewerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Native-only entry for VIEW/SEND book intents (no WebView).
 *
 * Registered only on `-Ptarget=native` builds via [src/native/AndroidManifest.xml].
 */
class ExternalBookTrampolineActivity : ComponentActivity() {

    private val importSupervisor = SupervisorJob()
    private val importScope = CoroutineScope(importSupervisor + Dispatchers.Main.immediate)
    private var importJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        routeIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeIntent(intent)
    }

    private fun routeIntent(intent: Intent?) {
        val payload = intent?.let { ExternalBookIntentPayload.from(it) }
        if (payload == null) {
            finish()
            return
        }
        val displayName = IntentBookCache.queryDisplayName(contentResolver, payload.uri)
        when (
            IntentRoutePolicy.decide(
                payload.mimeType,
                displayName,
                payload.uri.lastPathSegment,
            )
        ) {
            IntentRoutePolicy.Route.NATIVE_COMIC -> openComic(payload)
            IntentRoutePolicy.Route.NATIVE_PDF,
            IntentRoutePolicy.Route.NATIVE_SHELL,
            -> importToNativeShell(payload.uri)
            IntentRoutePolicy.Route.UNSUPPORTED -> {
                toast("Unsupported book format")
                finish()
            }
            IntentRoutePolicy.Route.ISLAND -> {
                if (BuildConfig.ISLAND_PACKAGED) {
                    forwardToWebIsland(payload.uri, payload.mimeType)
                } else {
                    toast("Unrecognized formats are not supported in this build.")
                    finish()
                }
            }
        }
    }

    private fun openComic(payload: ExternalBookIntentPayload) {
        lifecycleScope.launch(Dispatchers.IO) {
            val file = IntentBookCache.copyToCache(
                contentResolver,
                cacheDir,
                payload.uri,
                payload.mimeType,
            )
            withContext(Dispatchers.Main) {
                if (file == null) {
                    toast("Could not read the shared file.")
                    finish()
                    return@withContext
                }
                startActivity(ComicViewerActivity.intent(this@ExternalBookTrampolineActivity, file))
                finish()
            }
        }
    }

    private fun importToNativeShell(uri: android.net.Uri) {
        if (importJob?.isActive == true) {
            finish()
            return
        }
        importJob = importScope.launch {
            ExternalBookOpener(application).open(uri).collect { state ->
                when (state) {
                    ExternalOpenState.Importing -> Unit
                    is ExternalOpenState.Open -> {
                        RecentBooks(SharedRecentPrefs(application)).markOpened(
                            state.bookKey,
                            System.currentTimeMillis(),
                        )
                        startActivity(
                            Intent(this@ExternalBookTrampolineActivity, NativeShellActivity::class.java)
                                .putExtra(NativeShellActivity.EXTRA_BOOK_KEY, state.bookKey)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        )
                        finish()
                    }
                    is ExternalOpenState.Failed -> {
                        toast(state.message)
                        finish()
                    }
                }
            }
        }
    }

    private fun forwardToWebIsland(uri: android.net.Uri, mimeType: String?) {
        startActivity(
            Intent(this, com.koodoreader.reader.MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setDataAndType(uri, mimeType)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finish()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        importSupervisor.cancel()
        super.onDestroy()
    }
}
