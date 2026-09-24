package com.koodoreader.feature.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DoD [1] — engine enumeration and selection, tested through the pure
 * [TtsEngineProvider] port with a fake (no `PackageManager`, no Android SDK).
 *
 * This is the abstraction the P6 constraint asks for: `EngineEnumerator` is only
 * the `queryIntentActivities` adapter; ranking, de-duplication and the
 * "configured engine was uninstalled" fallback chain live here and are pinned by
 * these tests.
 */
class EngineProviderTest {

    @Test
    fun `ranker prefers google tts then oem then alphabetical`() {
        val ranked = TtsEngineRanker.fromPackages(
            listOf(
                "zz.example.tts",
                "com.samsung.android.tts",
                TtsEngineRanker.GOOGLE_TTS,
                "aa.example.tts",
            ),
        )
        assertEquals(
            listOf(
                TtsEngineRanker.GOOGLE_TTS,
                "com.samsung.android.tts",
                "aa.example.tts",
                "zz.example.tts",
            ),
            ranked.map { it.packageName },
        )
        assertEquals(100, TtsEngineRanker.priorityOf(TtsEngineRanker.GOOGLE_TTS))
        assertEquals(0, TtsEngineRanker.priorityOf("zz.example.tts"))
        assertEquals(TtsEngineRanker.GOOGLE_TTS, TtsEngineRanker.preferred(ranked)?.packageName)
        assertNull(TtsEngineRanker.preferred(emptyList()))
    }

    @Test
    fun `ranker de-duplicates and ignores blank packages`() {
        val ranked = TtsEngineRanker.rank(
            listOf(
                TtsEngineInfo(packageName = TtsEngineRanker.GOOGLE_TTS, label = "Speech Services"),
                TtsEngineInfo(packageName = TtsEngineRanker.GOOGLE_TTS, label = "duplicate"),
                TtsEngineInfo(packageName = "   "),
            ),
        )
        assertEquals(1, ranked.size)
        assertEquals("Speech Services", ranked.first().label)
        // `TtsEngineInfo` derives the priority from the package name by default.
        assertEquals(100, TtsEngineInfo(packageName = TtsEngineRanker.GOOGLE_TTS).priority)
    }

    @Test
    fun `fake provider answers isInstalled from its installed list`() {
        val provider = FakeTtsEngineProvider(
            installed = listOf(TtsEngineRanker.GOOGLE_TTS),
            defaultEngine = TtsEngineRanker.GOOGLE_TTS,
        )
        assertTrue(provider.isInstalled(TtsEngineRanker.GOOGLE_TTS))
        assertFalse(provider.isInstalled("com.samsung.android.tts"))
        assertFalse(provider.isInstalled(""))
    }

    @Test
    fun `resolver keeps the configured engine when it is still installed`() {
        val provider = FakeTtsEngineProvider(
            installed = listOf(TtsEngineRanker.GOOGLE_TTS, "com.samsung.android.tts"),
            defaultEngine = TtsEngineRanker.GOOGLE_TTS,
        )
        assertEquals(
            "com.samsung.android.tts",
            TtsEngineResolver.resolveEnginePackage(provider, "com.samsung.android.tts"),
        )
        val resolution = TtsEngineResolver.describeResolution(provider, "com.samsung.android.tts")
        assertEquals(TtsEngineResolution.Source.CONFIGURED, resolution.source)
    }

    @Test
    fun `resolver falls back to the platform default when the configured engine is gone`() {
        val provider = FakeTtsEngineProvider(
            installed = listOf("com.samsung.android.tts", TtsEngineRanker.GOOGLE_TTS),
            defaultEngine = "com.samsung.android.tts",
        )
        assertEquals(
            "com.samsung.android.tts",
            TtsEngineResolver.resolveEnginePackage(provider, "com.uninstalled.tts"),
        )
        assertEquals(
            TtsEngineResolution.Source.PLATFORM_DEFAULT,
            TtsEngineResolver.describeResolution(provider, "com.uninstalled.tts").source,
        )
    }

    @Test
    fun `resolver falls back to the highest ranked engine and then to the platform choice`() {
        // No platform default recorded, but engines are installed → highest ranked wins.
        val withEngines = FakeTtsEngineProvider(
            installed = listOf("aa.example.tts", TtsEngineRanker.GOOGLE_TTS),
        )
        assertEquals(
            TtsEngineRanker.GOOGLE_TTS,
            TtsEngineResolver.resolveEnginePackage(withEngines, ""),
        )
        assertEquals(
            TtsEngineResolution.Source.HIGHEST_RANKED,
            TtsEngineResolver.describeResolution(withEngines, "").source,
        )

        // Nothing installed at all → null, i.e. `TextToSpeech(context, listener)`.
        val empty = FakeTtsEngineProvider()
        assertNull(TtsEngineResolver.resolveEnginePackage(empty, "com.uninstalled.tts"))
        val resolution = TtsEngineResolver.describeResolution(empty, "com.uninstalled.tts")
        assertNull(resolution.packageName)
        assertEquals(TtsEngineResolution.Source.PLATFORM_CHOICE, resolution.source)
    }

    @Test
    fun `engine options feed the picker with labels from the platform`() {
        val provider = FakeTtsEngineProvider(
            installed = listOf(TtsEngineRanker.GOOGLE_TTS),
            defaultEngine = TtsEngineRanker.GOOGLE_TTS,
            labels = mapOf(TtsEngineRanker.GOOGLE_TTS to "Speech Services by Google"),
        )
        val entries = TtsVoiceCatalog.pickerEntries(provider.engines())
        assertEquals("Speech Services by Google", entries[1].label)
        assertEquals(TtsEngineKind.ANDROID_ENGINE, entries[1].kind)
        assertEquals(ENGINE_SYSTEM, entries.first().engine)
    }
}
