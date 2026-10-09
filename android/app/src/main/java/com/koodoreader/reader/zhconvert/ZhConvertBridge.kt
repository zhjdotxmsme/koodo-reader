package com.koodoreader.reader.zhconvert

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.koodoreader.core.locale.ZhConvertMode
import com.koodoreader.core.locale.ZhConvertPrefsCodec
import com.koodoreader.core.locale.ZhConvertSettings
import com.koodoreader.core.locale.ZhConvertSettingsRepository
import com.koodoreader.core.locale.ZhConvertSettingsStore
import com.koodoreader.reader.epubhost.PagedDocumentSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private val Context.appZhConvertDataStore by preferencesDataStore(name = "zhconvert_prefs")

/**
 * App-side DataStore binding for the pure-JVM `:core:locale` module
 * (docs/p6-zh-locale-design.md §4.1): the module owns the codec and the
 * [ZhConvertSettingsStore] port; this class is the DataStore adapter — one
 * `runBlocking` around the store's synchronous API (see [ZhConvertSettingsStore]).
 */
class DataStoreZhConvertSettingsStore(context: Context) : ZhConvertSettingsStore {

    private val dataStore = context.appZhConvertDataStore

    // Store calls originate off the main thread (UI writes on IO dispatchers,
    // repo refresh); runBlocking keeps the port's synchronous contract.
    override fun read(): ZhConvertSettings {
        val values = LinkedHashMap<String, String>()
        runBlocking {
            val prefs = dataStore.data.first()
            for (name in ZhConvertPrefsCodec.KEYS) {
                val raw = prefs[stringPreferencesKey(name)]
                if (raw !is String) continue
                values[name] = raw
            }
        }
        return ZhConvertPrefsCodec.decode(values)
    }

    override fun write(settings: ZhConvertSettings): ZhConvertSettings {
        runBlocking {
            dataStore.edit { prefs ->
                for ((name, value) in ZhConvertPrefsCodec.encode(settings)) {
                    prefs[stringPreferencesKey(name)] = value
                }
            }
        }
        return ZhConvertPrefsCodec.decode(ZhConvertPrefsCodec.encode(settings))
    }

    override fun clear() {
        runBlocking {
            dataStore.edit { prefs ->
                for (name in ZhConvertPrefsCodec.KEYS) {
                    prefs.remove(stringPreferencesKey(name))
                }
            }
        }
    }
}

/**
 * Process-wide Simplified/Traditional 转换 bridge (one per app process).
 *
 *  - [install] runs once from [com.koodoreader.reader.KoodoReaderApp.onCreate];
 *  - AppearanceRoute 「简繁转换」 reads [repository] settings and calls
 *    [setMode];
 *  - [setReaderLanguage] keeps AUTO mode in step with the i18n language
 *    (wired from [com.koodoreader.reader.shell.I18nState]);
 *  - every settings change (re)installs [PagedDocumentSession.textTransform],
 *    the single render-pipeline hook all text formats funnel through.
 *
 * Before [install] (JVM unit tests) the hook stays null: the reader renders
 * source text untouched.
 */
object ZhConvertBridge {

    @Volatile
    private var installed = false

    @Volatile
    var repository: ZhConvertSettingsRepository? = null
        private set

    /** Reader language ("system" | "en" | "zh-CN"); consulted by AUTO mode. */
    @Volatile
    var readerLanguage: String? = null
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun install(context: Context) {
        if (installed) return
        installed = true
        // The repository constructor loads the persisted settings once
        // (runBlocking over the DataStore emit — a single small prefs file);
        // from there repo.settings is an in-memory mirror.
        val repo = ZhConvertSettingsRepository(store(context.applicationContext))
        repository = repo
        repo.addListener {
            // Fires synchronously on the writing thread — main after a UI
            // click. The write() already persisted, so the listener only
            // publishes + re-arms the hook; both are pure memory work and
            // must not do a blocking DataStore read on the main thread.
            _settings.value = it
            applyTransform()
        }
        scope.launch { _settings.value = repo.settings }
        applyTransform() // initial hook from in-memory DEFAULT (AUTO)
    }

    private fun store(context: Context) = DataStoreZhConvertSettingsStore(context)

    private val _settings = MutableStateFlow<ZhConvertSettings?>(null)

    /** Persisted settings (null until the first load emits; UI uses AUTO meanwhile). */
    val settings: StateFlow<ZhConvertSettings?> = _settings.asStateFlow()

    /** (Re)wires [PagedDocumentSession.textTransform] from current settings. */
    fun applyTransform() {
        val repo = repository ?: return
        val settings = repo.settings
        val engine = repo.engine()
        val language = readerLanguage
        PagedDocumentSession.textTransform = { text -> engine.convert(text, settings.mode, language) }
    }

    fun setMode(mode: ZhConvertMode) {
        repository?.setMode(mode)
    }

    fun setReaderLanguage(language: String) {
        readerLanguage = language
        applyTransform()
    }
}
