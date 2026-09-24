package com.koodoreader.feature.tts

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.provider.Settings
import android.speech.tts.TextToSpeech

/**
 * Android implementation of [TtsEngineProvider] — the framework-facing half of the
 * engine enumeration (DoD [1]).
 *
 * Primary query is `PackageManager.queryIntentActivities` on
 * `android.intent.action.TTS_SERVICE` — the same intent the platform uses to
 * discover engines, and therefore the one that lists `com.google.android.tts`,
 * OEM engines and any third-party engine the user installed.
 *
 * PACKAGE VISIBILITY (Android 11+): `queryIntentActivities` only returns packages
 * the app can see, so the patch adds the mandatory manifest entry:
 * ```xml
 * <queries><intent><action android:name="android.intent.action.TTS_SERVICE" /></intent></queries>
 * ```
 * Without it the enumeration silently degrades to the default engine alone.
 *
 * The class holds no resources; every call is safe from a background thread (the
 * picker loads it off the UI thread).
 */
class EngineEnumerator(private val context: Context) : TtsEngineProvider {

    /** Ranked installed engines. */
    override fun engines(): List<TtsEngineInfo> = TtsEngineRanker.rank(queryIntentActivities())

    /**
     * Raw `queryIntentActivities` pass (exposed for the DoD check, logging and the
     * "no engine found" empty state).
     *
     * A failing `PackageManager` returns an empty list instead of throwing: a
     * broken engine must not take the voice picker down with it.
     */
    fun queryIntentActivities(): List<TtsEngineInfo> {
        val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        val infos: List<ResolveInfo> = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.queryIntentActivities(intent, 0)
            }
        } catch (e: Exception) {
            emptyList()
        }
        return infos.map { info ->
            val pkg = info.activityInfo?.packageName.orEmpty()
            val label = try {
                info.loadLabel(context.packageManager).toString()
            } catch (e: Exception) {
                pkg
            }
            TtsEngineInfo(packageName = pkg, label = label.ifBlank { pkg })
        }.filter { it.packageName.isNotBlank() }
    }

    /**
     * The "preferred engine" in system settings.
     *
     * `TextToSpeech.getDefaultEngine()` is an *instance* method in the platform —
     * there is no static accessor — and constructing an engine only to read a
     * setting would connect to the synthesis service. Read the very same secure
     * setting the framework reads (`Settings.Secure.TTS_DEFAULT_SYNTH`).
     */
    override fun defaultEnginePackage(): String? = try {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH)
            ?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    /** True when the package is installed at all (covers engines that answer no intent). */
    override fun isInstalled(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        if (engines().any { it.packageName == packageName }) return true
        return try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0) != null
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Engines reported by an **already initialised** `TextToSpeech` instance.
     *
     * Used by [ForegroundTtsService] after `onInit` to validate that the bound
     * engine is still present — without constructing a second, short-lived
     * synthesizer just to ask.
     */
    fun fromSynthesizer(tts: TextToSpeech): List<TtsEngineInfo> {
        val engines = try {
            tts.engines
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        return TtsEngineRanker.rank(
            engines.map { engine ->
                TtsEngineInfo(
                    packageName = engine.name,
                    label = engine.label?.toString()?.ifBlank { engine.name } ?: engine.name,
                )
            },
        )
    }
}
