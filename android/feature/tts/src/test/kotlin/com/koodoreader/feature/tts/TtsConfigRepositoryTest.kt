package com.koodoreader.feature.tts

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DoD [1] — configuration persistence (DataStore key/value layer).
 *
 * The DataStore adapter itself cannot run on a plain JVM, but everything it
 * delegates to (the codec and the repository façade) can — and that is where the
 * desktop-compatibility risk lives, so that is what is pinned here.
 */
class TtsConfigRepositoryTest {

    @Test
    fun `encode uses the desktop reader-config key names`() {
        val encoded = TtsConfigCodec.encode(TtsConfig(voiceName = "zh-CN-XiaoxiaoNeural", voiceSpeed = 1.0f))
        assertEquals("zh-CN-XiaoxiaoNeural", encoded[TtsConfigKeys.VOICE_NAME])
        assertEquals("system", encoded[TtsConfigKeys.VOICE_ENGINE])
        // Desktop stores the speed as the string "1" (the UI stores voiceSpeed || "1").
        assertEquals("1", encoded[TtsConfigKeys.VOICE_SPEED])
        assertEquals(TtsConfigCodec.keys.size, encoded.size)
    }

    @Test
    fun `decode parses a desktop profile and keeps fractional speed`() {
        val desktop = mapOf(
            "voiceName" to "xiaoxiao",
            "voiceEngine" to "azure-tts-voice-plugin",
            "voiceSpeed" to "1.5",
            "voiceLocale" to "zh-CN",
        )
        val config = TtsConfigCodec.decode(desktop)
        assertEquals("xiaoxiao", config.voiceName)
        assertEquals("azure-tts-voice-plugin", config.voiceEngine)
        assertEquals(1.5f, config.voiceSpeed, 0.0001f)
        assertEquals("zh-CN", config.voiceLocale)
    }

    @Test
    fun `decode degrades to defaults for missing and garbage values`() {
        // Desktop parity: parseFloat(x) || 1 — empty, non-numeric and negative all mean 1.
        assertEquals(TtsRate.DEFAULT, TtsConfigCodec.decode(mapOf("voiceSpeed" to "")).voiceSpeed, 0.0001f)
        assertEquals(TtsRate.DEFAULT, TtsConfigCodec.decode(mapOf("voiceSpeed" to "abc")).voiceSpeed, 0.0001f)
        assertEquals(TtsRate.DEFAULT, TtsConfigCodec.decode(mapOf("voiceSpeed" to "0")).voiceSpeed, 0.0001f)
        assertEquals(TtsRate.DEFAULT, TtsConfigCodec.decode(emptyMap()).voiceSpeed, 0.0001f)
        assertEquals(ENGINE_SYSTEM, TtsConfigCodec.decode(mapOf("voiceEngine" to null)).voiceEngine)
        assertEquals(0, TtsConfigCodec.decode(mapOf("ttsChunkLength" to "-5")).chunkLength)
        assertEquals(1.0f, TtsConfigCodec.decode(mapOf("ttsVolume" to "7")).volume, 0.0001f)
    }

    @Test
    fun `decode clamps out-of-range values instead of throwing`() {
        val config = TtsConfigCodec.decode(
            mapOf(
                "voiceSpeed" to "99",
                "ttsPitch" to "9",
                "ttsVolume" to "-3",
                "ttsChunkLength" to "999999",
            ),
        )
        assertEquals(TtsRate.MAX, config.voiceSpeed, 0.0001f)
        assertEquals(TtsPitch.MAX, config.pitch, 0.0001f)
        assertEquals(0.0f, config.volume, 0.0001f)
        assertEquals(TtsConfig.MAX_CHUNK_LENGTH, config.chunkLength)
    }

    @Test
    fun `codec round trips every field`() = runBlocking {
        val original = TtsConfig(
            voiceName = "com.google.android.tts:en-us-x-tpf-local",
            voiceEngine = ENGINE_SYSTEM,
            voiceSpeed = 1.25f,
            voiceLocale = "en-US",
            enginePackage = TtsEngineRanker.GOOGLE_TTS,
            pitch = 1.1f,
            volume = 0.8f,
            chunkLength = 120,
        )
        val decoded = TtsConfigCodec.decode(TtsConfigCodec.encode(original))
        assertEquals(original.voiceName, decoded.voiceName)
        assertEquals(original.enginePackage, decoded.enginePackage)
        assertEquals(original.voiceSpeed, decoded.voiceSpeed, 0.0001f)
        assertEquals(original.pitch, decoded.pitch, 0.0001f)
        assertEquals(original.volume, decoded.volume, 0.0001f)
        assertEquals(original.chunkLength, decoded.chunkLength)
    }

    @Test
    fun `repository caches, persists updates and resets`() = runBlocking {
        val store = InMemoryTtsConfigStore(TtsConfig(voiceSpeed = 2.0f))
        val repository = TtsConfigRepository(store)

        assertEquals(2.0f, repository.current().voiceSpeed, 0.0001f)
        // Cached: no extra store round trip while nothing changed.
        repository.current()
        assertEquals(0, store.saveCount)

        val updated = repository.update { it.copy(voiceSpeed = 1.5f, voiceName = "v1") }
        assertEquals(1.5f, updated.voiceSpeed, 0.0001f)
        assertEquals(1, store.saveCount)
        assertEquals(1.5f, store.load().voiceSpeed, 0.0001f)

        val reset = repository.reset()
        assertEquals(TtsConfig.DEFAULT, reset)
        assertEquals(2, store.saveCount)

        // A restored backup invalidates the cache.
        store.save(TtsConfig(voiceSpeed = 0.75f))
        assertEquals(0.75f, repository.invalidate().voiceSpeed, 0.0001f)
        assertNotEquals(0.75f, TtsConfig.DEFAULT.voiceSpeed)
    }

    @Test
    fun `normalized config survives an engine package switch`() {
        val config = TtsConfig(voiceEngine = "  ", voiceSpeed = Float.NaN, pitch = Float.NaN)
            .normalized()
        assertEquals(ENGINE_SYSTEM, config.voiceEngine)
        assertEquals(TtsRate.DEFAULT, config.voiceSpeed, 0.0001f)
        assertEquals(TtsPitch.DEFAULT, config.pitch, 0.0001f)
        assertTrue(config.usesSystemEngine)
        assertFalse(TtsConfig(voiceEngine = "azure-tts-voice-plugin").usesSystemEngine)
        assertTrue(TtsConfig(voiceEngine = "azure-tts-voice-plugin").usesDesktopVoicePlugin)
    }

    @Test
    fun `desktop speed percent conversion matches the electron plugin call`() {
        // ttsUtil.ts: TTSUtil.cacheAudio(index, speed * 100 - 100, ...)
        assertEquals(0, TtsRate.desktopPercent(1.0f))
        assertEquals(50, TtsRate.desktopPercent(1.5f))
        assertEquals(-40, TtsRate.desktopPercent(0.6f))
        assertEquals(1.0f, TtsRate.fromDesktop("1"), 0.0001f)
        assertEquals(1.0f, TtsRate.fromDesktop(null), 0.0001f)
    }
}
