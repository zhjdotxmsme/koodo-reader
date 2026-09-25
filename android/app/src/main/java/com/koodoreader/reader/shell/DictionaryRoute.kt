package com.koodoreader.reader.shell

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.koodoreader.feature.dictionary.DictRepository
import com.koodoreader.feature.dictionary.ui.DictManagementScreen
import com.koodoreader.feature.dictionary.ui.DictManagementState
import com.koodoreader.feature.dictionary.ui.DictStrings
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Host of the P6 dictionary manager (`feature:dictionary`).
 *
 * Same shape as [StatsRoute]: the module owns the repository and the Compose
 * screen, this file owns the Android side (where the files live, the SAF picker,
 * i18n) and nothing else. See docs/android-completeness-2026-09-24.md §15.
 *
 * Deliberately local-only: `cloudDicts` stays empty, which the screen renders as
 * "no download section" (it only shows that block when the list is non-empty).
 * The cloud catalogue needs the desktop `dicts/manifest.json` asset and an HTTP
 * `OnDemandDownloader`, neither of which the module ships yet — a Download
 * button that cannot download would be worse than no section at all.
 */
@Composable
fun DictionaryRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    val i18n = LocalI18n.current
    val language by i18n.language.collectAsState()
    @Suppress("UNUSED_EXPRESSION")
    language
    val str: (String) -> String = { key -> i18n.localization.t(key) }
    val scope = rememberCoroutineScope()

    // Desktop layout parity: everything lives under filesDir/dict/ (see
    // DictRepository's storage layout), so a desktop import folder can be moved
    // over unchanged.
    val repository = remember(app) { DictRepository(app.filesDir) }
    var state by remember { mutableStateOf(DictManagementState()) }

    fun reload() {
        state = state.copy(dicts = repository.dicts())
    }

    LaunchedEffect(repository) { withContext(Dispatchers.IO) { reload() } }
    DisposableEffect(repository) {
        onDispose { runCatching { repository.close() } }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                importDictionaries(context, repository, uris)
            }
            reload()
            Toast.makeText(context, importSummary(str, outcome), Toast.LENGTH_LONG).show()
        }
    }

    DictManagementScreen(
        state = state,
        onToggleEnabled = { id, enabled ->
            scope.launch {
                withContext(Dispatchers.IO) { repository.setEnabled(id, enabled) }
                reload()
            }
        },
        onSetDefault = { id ->
            scope.launch {
                withContext(Dispatchers.IO) { repository.setDefault(id) }
                reload()
            }
        },
        onMove = { id, delta ->
            scope.launch {
                withContext(Dispatchers.IO) { repository.move(id, delta) }
                reload()
            }
        },
        onDelete = { id ->
            scope.launch {
                val removed = withContext(Dispatchers.IO) { repository.delete(id) }
                reload()
                if (!removed) {
                    // No locale key exists for "Delete failed"; the desktop reuses
                    // the generic import-failure string in the same dialog.
                    Toast.makeText(context, str("Import failed"), Toast.LENGTH_SHORT).show()
                }
            }
        },
        // `*/*` + OpenMultipleDocuments: document providers report .mdx/.mdd as
        // octet-stream, and a narrowed filter greys them out (same reasoning as
        // the book picker in MainActivity).
        onImportClick = { picker.launch(arrayOf("*/*")) },
        onDownloadClick = { item ->
            Toast.makeText(context, str("Download failed") + ": " + item.name, Toast.LENGTH_SHORT).show()
        },
        onBack = onBack,
        strings = DictStrings(
            title = str("Dictionary"),
            back = str("Back"),
            importDict = str("Import dictionary"),
            empty = str("No dictionary installed"),
            defaultDict = str("Default dictionary"),
            setDefault = str("Set default"),
            moveUp = str("Move up"),
            moveDown = str("Move down"),
            delete = str("Delete"),
            downloadSection = str("Download open dictionaries"),
            download = str("Download"),
            alreadyInstalled = str("Dictionary already downloaded"),
            sourceBundled = str("Bundled"),
            sourceCloud = str("Cloud"),
            sourceImported = str("Imported"),
        ),
    )
}

/** Outcome of one import batch, for the toast. */
data class DictImportOutcome(val imported: Int, val failed: Int) {
    val total: Int get() = imported + failed
}

/**
 * Stage every picked document in the cache and hand it to [DictRepository].
 *
 * `.mdd` files are staged but never registered on their own: the repository
 * copies a `.mdd` sibling when the matching `.mdx` is installed, so registering
 * one would add a phantom row. A companion that fails to copy means the
 * dictionary imports without its images — the dictionary itself still counts as
 * imported, which is the outcome the user cares about.
 */
internal fun importDictionaries(
    context: Context,
    repository: DictRepository,
    uris: List<Uri>,
): DictImportOutcome {
    val picked = uris
        .map { DictionaryImport.sanitizeName(dictionaryDisplayName(context, it)) to it }
        .sortedBy { (name, _) -> if (DictionaryImport.isResourceCompanion(name)) 0 else 1 }

    val staged = LinkedHashMap<String, File>()
    var imported = 0
    var failed = 0

    for ((name, uri) in picked) {
        val target = DictionaryImport.stagingFile(context.cacheDir, name)
        val ok = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: error("cannot read $uri")
        }.isSuccess
        if (ok) {
            staged[name] = target
        } else if (!DictionaryImport.isResourceCompanion(name)) {
            failed++
        }
    }

    for ((name, target) in staged) {
        if (DictionaryImport.isResourceCompanion(name)) continue
        val ok = runCatching {
            repository.installFromFile(target, name = target.nameWithoutExtension)
        }.isSuccess
        if (ok) imported++ else failed++
    }

    // Staged copies are only needed for the duration of the import.
    runCatching {
        File(context.cacheDir, DictionaryImport.STAGING_DIR).listFiles()?.forEach { it.delete() }
    }
    return DictImportOutcome(imported = imported, failed = failed)
}

/** `Import: 2 · Import failed: 1` — keys the library screen already uses. */
private fun importSummary(str: (String) -> String, outcome: DictImportOutcome): String {
    val base = "${str("Import")}: ${outcome.imported}"
    return if (outcome.failed > 0) "$base · ${str("Import failed")}: ${outcome.failed}" else base
}

/** SAF display name, falling back to the last path segment. */
private fun dictionaryDisplayName(context: Context, uri: Uri): String {
    val fromProvider = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()
    return fromProvider ?: uri.lastPathSegment?.substringAfterLast('/') ?: "dictionary.mdx"
}
