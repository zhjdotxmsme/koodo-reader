package com.koodoreader.reader.shell

import android.content.Context
import android.content.SharedPreferences
import com.koodoreader.core.designsystem.ThemeKind
import com.koodoreader.core.designsystem.ThemeSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Appearance settings persistence (settings page, spec
 * `docs/superpowers/specs/2026-09-29-settings-page-design.md` §3.2/§3.3).
 *
 * Two independent knobs, stored as plain key/value:
 *  - [AppThemeMode]   — the CHROME theme (library/reader UI, KoodoTheme);
 *  - [ThemeKind]      — the READER PAGE theme (book background/text colours),
 *                       mirroring the desktop preset list; `CUSTOM` is
 *                       persisted when the desktop config says so but is not
 *                       offered by the selector yet.
 *
 * Live-switching: both values are exposed as [StateFlow]s backed by an
 * OnSharedPreferenceChangeListener, so the theme layers
 * (NativeShellActivity / ComicViewerActivity / NativeEpubScreen) recolor
 * the moment the settings page writes — no activity restart. Instances
 * share the same backing file, so a write from one is observed by all.
 *
 * Deliberately NOT Room (schema.lock stays clean) and deliberately a small
 * class like [LibraryPrefs]: parse errors degrade to the default instead of
 * ever throwing into the reader.
 */

/** App UI theme selection; `SYSTEM` = follow the platform (KoodoTheme default). */
enum class AppThemeMode { SYSTEM, LIGHT, DARK }

/** Resolves the mode to a [KoodoTheme](com.koodoreader.core.ui.theme.KoodoTheme) `darkTheme` flag. Pure, JVM-testable. */
fun AppThemeMode.darkThemeOf(systemDark: Boolean): Boolean = when (this) {
    AppThemeMode.LIGHT -> false
    AppThemeMode.DARK -> true
    AppThemeMode.SYSTEM -> systemDark
}

/** Tolerant parser: garbage/whitespace → [AppThemeMode.SYSTEM]. */
fun parseAppThemeMode(raw: String?): AppThemeMode =
    runCatching { AppThemeMode.valueOf(raw?.trim()?.uppercase().orEmpty()) }
        .getOrDefault(AppThemeMode.SYSTEM)

/** Tolerant parser: garbage/whitespace → [ThemeKind.DEFAULT]. */
fun parseReaderThemeKind(raw: String?): ThemeKind =
    runCatching { ThemeKind.valueOf(raw?.trim()?.uppercase().orEmpty()) }
        .getOrDefault(ThemeKind.DEFAULT)

/** The built-in colour preset for a selector kind (CUSTOM falls back to DEFAULT). */
fun ThemeKind.builtInPreset(): ThemeSpec = when (this) {
    ThemeKind.DEFAULT -> ThemeSpec.DEFAULT_PRESET
    ThemeKind.PROTECT_EYE -> ThemeSpec.PROTECT_EYE_PRESET
    ThemeKind.NIGHT -> ThemeSpec.NIGHT_PRESET
    ThemeKind.CUSTOM -> ThemeSpec.DEFAULT_PRESET
}

class ShellAppearancePrefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val modeChanges = MutableStateFlow(parseAppThemeMode(sp.getString(KEY_APP_THEME_MODE, null)))
    private val readerThemeChanges = MutableStateFlow(parseReaderThemeKind(sp.getString(KEY_READER_THEME, null)))

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        when (key) {
            KEY_APP_THEME_MODE -> modeChanges.value = parseAppThemeMode(prefs.getString(KEY_APP_THEME_MODE, null))
            KEY_READER_THEME -> readerThemeChanges.value =
                parseReaderThemeKind(prefs.getString(KEY_READER_THEME, null))
        }
    }

    init {
        sp.registerOnSharedPreferenceChangeListener(listener)
    }

    /** Live value for the theme layers; also the initial write-through source. */
    val appThemeModeFlow: StateFlow<AppThemeMode> = modeChanges.asStateFlow()

    /** Live value for the reader-page colour. */
    val readerThemeKindFlow: StateFlow<ThemeKind> = readerThemeChanges.asStateFlow()

    var appThemeMode: AppThemeMode
        get() = modeChanges.value
        set(value) = sp.edit().putString(KEY_APP_THEME_MODE, value.name).apply()

    var readerThemeKind: ThemeKind
        get() = readerThemeChanges.value
        set(value) = sp.edit().putString(KEY_READER_THEME, value.name).apply()

    /** Release the change listener (route-scoped instances). */
    fun close() {
        sp.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        const val FILE_NAME = "shell_appearance"
        const val KEY_APP_THEME_MODE = "appThemeMode"
        const val KEY_READER_THEME = "readerThemeKind"
    }
}

/** Reader-page presets offered by the selector (CUSTOM is desktop-only for now). */
val READER_THEME_OPTIONS: List<ThemeKind> = listOf(
    ThemeKind.DEFAULT,
    ThemeKind.PROTECT_EYE,
    ThemeKind.NIGHT,
)
