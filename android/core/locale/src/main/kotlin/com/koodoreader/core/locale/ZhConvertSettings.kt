package com.koodoreader.core.locale

/**
 * Persisted 简繁转换 configuration.
 *
 * Desktop interop: [mode] is stored under the desktop reader-config key
 * `convertChinese` using the desktop wire values (see [ZhConvertMode.wire]),
 * so a desktop profile/value maps 1:1. [userDictionary] is the OpenCC-format
 * conversion dictionary the user maintains (this is the "转换字典持久化" payload);
 * when [userDictionaryEnabled] is false it is kept on disk but not applied.
 */
data class ZhConvertSettings(
    val mode: ZhConvertMode = ZhConvertMode.AUTO,
    val userDictionary: String = "",
    val userDictionaryEnabled: Boolean = false,
) {
    /** Effective engine for this configuration (seed tables + user dictionary). */
    fun engine(): ZhConvertEngine = ZhConvertEngine.fromSeed(
        if (userDictionaryEnabled) userDictionary else null,
    )

    val hasUserDictionary: Boolean get() = userDictionaryEnabled && userDictionary.isNotBlank()

    companion object {
        /** Desktop default: `convertChinese == ""`. */
        val DEFAULT = ZhConvertSettings()
    }
}

/**
 * Preferences wire codec — the *only* place that knows the storage keys, so a
 * DataStore<Preferences>, a SharedPreferences file and a unit-test map all
 * round-trip identically.
 *
 * | key | type | desktop parity |
 * |---|---|---|
 * | `convertChinese` | string | **yes** — desktop reader config key, same values |
 * | `zhConvertUserDict` | string | android-only (OpenCC-format dictionary text) |
 * | `zhConvertUserDictEnabled` | string ("true"/"false") | android-only |
 */
object ZhConvertPrefsCodec {

    /** Desktop reader-config key for the tri-state (`""`, "Simplified To Traditional", …). */
    const val KEY_MODE = "convertChinese"

    /** User conversion dictionary, OpenCC `key<TAB>value` lines. */
    const val KEY_USER_DICT = "zhConvertUserDict"

    /** Whether [KEY_USER_DICT] is applied. */
    const val KEY_USER_DICT_ENABLED = "zhConvertUserDictEnabled"

    /** All keys owned by this feature (used to clear/export just this feature). */
    val KEYS: List<String> = listOf(KEY_MODE, KEY_USER_DICT, KEY_USER_DICT_ENABLED)

    fun encode(settings: ZhConvertSettings): Map<String, String> = linkedMapOf(
        KEY_MODE to settings.mode.wire,
        KEY_USER_DICT to settings.userDictionary,
        KEY_USER_DICT_ENABLED to settings.userDictionaryEnabled.toString(),
    )

    /** Missing keys fall back to the desktop defaults; unknown mode strings → AUTO. */
    fun decode(values: Map<String, String>): ZhConvertSettings = ZhConvertSettings(
        mode = ZhConvertMode.fromWire(values[KEY_MODE]),
        userDictionary = values[KEY_USER_DICT] ?: "",
        userDictionaryEnabled = values[KEY_USER_DICT_ENABLED]?.trim()?.lowercase() == "true",
    )

    /** Desktop reader config value → settings (unknown value → AUTO, desktop default). */
    fun fromDesktopConfig(value: String?): ZhConvertSettings =
        ZhConvertSettings(mode = ZhConvertMode.fromWire(value))

    /** Settings → desktop reader config value. */
    fun toDesktopConfig(settings: ZhConvertSettings): String = settings.mode.wire
}

/**
 * Persistence port for [ZhConvertSettings].
 *
 * The Android app binds this to `androidx.datastore` (DataStore<Preferences>),
 * which is an Android/AAR dependency this pure-JVM module deliberately does not
 * carry — see docs/p6-zh-locale-design.md §4.1 for the five-line binding and
 * [InMemoryZhConvertSettingsStore] for the JVM/test implementation. Callers run
 * [read]/[write] off the main thread (Dispatchers.IO) — the API is synchronous
 * on purpose so the module needs no coroutines dependency.
 */
interface ZhConvertSettingsStore {

    /** Current settings; never throws — storage failures degrade to [ZhConvertSettings.DEFAULT]. */
    fun read(): ZhConvertSettings

    /** Persists [settings] and returns the value actually stored. */
    fun write(settings: ZhConvertSettings): ZhConvertSettings

    /** Removes every key owned by this feature (desktop default restored). */
    fun clear()
}

/** Map-backed store: used by JVM tests and by the framework-free self-check. */
class InMemoryZhConvertSettingsStore(
    initial: ZhConvertSettings = ZhConvertSettings.DEFAULT,
) : ZhConvertSettingsStore {

    private var values: Map<String, String> = ZhConvertPrefsCodec.encode(initial)

    override fun read(): ZhConvertSettings = ZhConvertPrefsCodec.decode(values)

    override fun write(settings: ZhConvertSettings): ZhConvertSettings {
        values = ZhConvertPrefsCodec.encode(settings)
        return read()
    }

    override fun clear() {
        values = emptyMap()
    }

    /** Raw preferences snapshot (test/inspection helper). */
    fun raw(): Map<String, String> = values
}

/**
 * Reader-side holder: owns the current settings, notifies listeners on change
 * and hands out a ready [ZhConvertEngine]. Compose bridges [addListener] to
 * `mutableStateOf`, so the conversion mode flips live while reading.
 */
class ZhConvertSettingsRepository(
    private val store: ZhConvertSettingsStore = InMemoryZhConvertSettingsStore(),
) {
    private val listeners = ArrayList<(ZhConvertSettings) -> Unit>(2)

    /** Last persisted settings; [refresh] re-reads the store. */
    var settings: ZhConvertSettings = store.read()
        private set

    /** Engine for [settings] — rebuilt whenever the dictionary/mode changes. */
    fun engine(): ZhConvertEngine = settings.engine()

    fun setMode(mode: ZhConvertMode): ZhConvertSettings =
        apply(settings.copy(mode = mode))

    fun setUserDictionary(text: String, enabled: Boolean = true): ZhConvertSettings =
        apply(settings.copy(userDictionary = text, userDictionaryEnabled = enabled))

    fun set(settings: ZhConvertSettings): ZhConvertSettings = apply(settings)

    fun refresh(): ZhConvertSettings {
        settings = store.read()
        notifyListeners()
        return settings
    }

    fun clear(): ZhConvertSettings {
        store.clear()
        settings = store.read()
        notifyListeners()
        return settings
    }

    fun addListener(listener: (ZhConvertSettings) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (ZhConvertSettings) -> Unit) {
        listeners.remove(listener)
    }

    private fun apply(next: ZhConvertSettings): ZhConvertSettings {
        settings = store.write(next)
        notifyListeners()
        return settings
    }

    private fun notifyListeners() {
        for (listener in ArrayList(listeners)) listener(settings)
    }
}
