package com.koodoreader.feature.tts

/**
 * TTS engine enumeration — **pure JVM half** (DoD [1]).
 *
 * Desktop counterpart: the desktop app never enumerates platform engines;
 * `getAllVoices(plugins)` (`src/utils/common.ts`) lists *plugin* voices and the
 * `system` branch is the browser's `speechSynthesis`. On Android the equivalent of
 * "which voices exist" is (1) which TTS **engines** are installed, and (2) which
 * **voices** an engine exposes.
 *
 * [TtsEngineProvider] isolates the framework query so the ranking / resolution
 * rules are testable on a plain JVM with a fake provider (P6 constraint: unit
 * tests must not need the Android SDK). The Android implementation is
 * `EngineEnumerator` (other file), which answers via
 * `PackageManager.queryIntentActivities(Intent(TTS_SERVICE))`.
 */

/** One installed TTS engine. */
data class TtsEngineInfo(
    /** Engine package name, e.g. `com.google.android.tts`. */
    val packageName: String,
    /** Label to show in the picker; falls back to [packageName]. */
    val label: String = packageName,
    /** Higher is preferred; see [TtsEngineRanker.priorityOf]. */
    val priority: Int = TtsEngineRanker.priorityOf(packageName),
)

/**
 * Port over the platform engine query.
 *
 * The Android implementation must be safe to call off the main thread; the fake in
 * the test sources returns a fixed list.
 */
interface TtsEngineProvider {

    /** Installed engines, in any order (callers rank them). */
    fun engines(): List<TtsEngineInfo>

    /** The engine the platform would use when none is configured, or `null`. */
    fun defaultEnginePackage(): String?

    /** True when [packageName] is installed and answers the TTS service intent. */
    fun isInstalled(packageName: String): Boolean =
        packageName.isNotBlank() && engines().any { it.packageName == packageName }
}

/**
 * Deterministic engine ranking — pure logic, so the picker order never depends on
 * `PackageManager` return order (which varies per device and per Android version).
 *
 * Preference order:
 *  1. `com.google.android.tts` (the reference engine the Android port targets);
 *  2. other well-known OEM engines;
 *  3. everything else, alphabetically by package name.
 */
object TtsEngineRanker {

    /** The engine assumed by default: the Google Speech Services package. */
    const val GOOGLE_TTS = "com.google.android.tts"

    private val KNOWN_PRIORITY: Map<String, Int> = mapOf(
        GOOGLE_TTS to 100,
        "com.google.android.tts.service" to 100,
        "com.samsung.android.tts" to 80,
        "com.huawei.tts" to 80,
        "com.miui.tts" to 80,
        "com.xiaomi.tts" to 80,
        "com.oppo.tts" to 70,
        "com.vivo.tts" to 70,
        "com.baidu.duer.tts" to 60,
        "com.iflytek.tts" to 60,
        "com.iflytek.speechcloud" to 60,
    )

    /** Priority for [packageName]; unknown engines get `0`. */
    fun priorityOf(packageName: String): Int = KNOWN_PRIORITY[packageName.trim()] ?: 0

    /** De-duplicates by package name and sorts by priority (desc), then package name. */
    fun rank(engines: List<TtsEngineInfo>): List<TtsEngineInfo> = engines
        .filter { it.packageName.isNotBlank() }
        .distinctBy { it.packageName }
        .sortedWith(compareByDescending<TtsEngineInfo> { it.priority }.thenBy { it.packageName })

    /** The engine to bind by default, or `null` when nothing is installed. */
    fun preferred(engines: List<TtsEngineInfo>): TtsEngineInfo? = rank(engines).firstOrNull()

    /** Builds ranked [TtsEngineInfo] entries from bare package names (tests, quick checks). */
    fun fromPackages(packages: List<String>): List<TtsEngineInfo> =
        rank(packages.map { TtsEngineInfo(packageName = it, label = it) })
}

/**
 * Which engine package `TextToSpeech` should be constructed with.
 *
 * Resolution order — the fallbacks matter because the user can uninstall an engine
 * (or restore a config from another device) between sessions:
 *  1. the configured package, when it is still installed;
 *  2. the platform default engine (`TextToSpeech.getDefaultEngine()`);
 *  3. the highest-ranked installed engine;
 *  4. `null` → construct `TextToSpeech` without an engine and let the platform pick.
 *
 * A blank/unknown configured engine therefore never disables listening.
 */
object TtsEngineResolver {

    /** @return the package to bind, or `null` for "let the platform choose". */
    fun resolveEnginePackage(provider: TtsEngineProvider, configured: String): String? {
        val wanted = configured.trim()
        if (wanted.isNotEmpty() && provider.isInstalled(wanted)) return wanted

        val default = provider.defaultEnginePackage()?.trim()
        if (!default.isNullOrEmpty()) return default

        return TtsEngineRanker.preferred(provider.engines())?.packageName
    }

    /**
     * The engine a stored config names, annotated for logging/UI: equals
     * [resolveEnginePackage] plus the reason, which the picker shows as a subtitle.
     */
    fun describeResolution(provider: TtsEngineProvider, configured: String): TtsEngineResolution {
        val wanted = configured.trim()
        if (wanted.isNotEmpty() && provider.isInstalled(wanted)) {
            return TtsEngineResolution(wanted, TtsEngineResolution.Source.CONFIGURED)
        }
        val default = provider.defaultEnginePackage()?.trim()
        if (!default.isNullOrEmpty()) {
            return TtsEngineResolution(default, TtsEngineResolution.Source.PLATFORM_DEFAULT)
        }
        val fallback = TtsEngineRanker.preferred(provider.engines())?.packageName
        return if (fallback == null) {
            TtsEngineResolution(null, TtsEngineResolution.Source.PLATFORM_CHOICE)
        } else {
            TtsEngineResolution(fallback, TtsEngineResolution.Source.HIGHEST_RANKED)
        }
    }
}

/** Outcome of [TtsEngineResolver.describeResolution]. */
data class TtsEngineResolution(
    val packageName: String?,
    val source: Source,
) {
    enum class Source {
        /** The configured engine is installed and is used as-is. */
        CONFIGURED,

        /** Configured engine missing → the platform default engine. */
        PLATFORM_DEFAULT,

        /** No platform default → the highest-ranked installed engine. */
        HIGHEST_RANKED,

        /** Nothing installed → construct `TextToSpeech` with no engine. */
        PLATFORM_CHOICE,
    }
}
