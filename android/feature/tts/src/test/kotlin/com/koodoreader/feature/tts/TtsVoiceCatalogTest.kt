package com.koodoreader.feature.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DoD [1] — the 15 desktop voice labels + Android engine resolution and native
 * voice pick.
 *
 * The count assertion is the acceptance criterion ("15 个 voice 标签"); the
 * resolution assertions pin the desktop fallback behaviour
 * (`textToSpeech/component.tsx`: an unresolvable voice falls back to `system`).
 */
class TtsVoiceCatalogTest {

    @Test
    fun `catalog carries exactly the fifteen desktop voice plugins`() {
        assertEquals(15, TtsVoiceCatalog.labels.size)
        assertEquals(15, TtsVoiceCatalog.labels.map { it.key }.toSet().size)
        assertTrue(TtsVoiceCatalog.labels.all { it.key.endsWith("-voice-plugin") })
        assertTrue(TtsVoiceCatalog.labels.all { it.displayName.isNotBlank() })
        // The desktop catalog order is preserved (grep `type: "voice"` in catalog.ts).
        assertEquals("azure-tts-voice-plugin", TtsVoiceCatalog.labels.first().key)
        assertEquals("coquitts-voice-plugin", TtsVoiceCatalog.labels.last().key)
        assertEquals("豆包 TTS", TtsVoiceCatalog.byKey("volcengine-tts-voice-plugin")?.displayName)
        assertEquals(5, TtsVoiceCatalog.labels.count { it.selfHosted })
    }

    @Test
    fun `label lookup covers system, plugins, legacy and unknown engines`() {
        assertEquals(TtsVoiceCatalog.SYSTEM_LABEL, TtsVoiceCatalog.labelFor(ENGINE_SYSTEM))
        assertEquals(TtsVoiceCatalog.SYSTEM_LABEL, TtsVoiceCatalog.labelFor(""))
        assertEquals("Azure TTS", TtsVoiceCatalog.labelFor("azure-tts-voice-plugin"))
        assertTrue(TtsVoiceCatalog.labelFor(TtsVoiceCatalog.LEGACY_OFFICIAL_AI_PLUGIN).contains("removed"))
        // Unknown keys render verbatim, like an unmatched desktop voice name.
        assertEquals("some-engine", TtsVoiceCatalog.labelFor("some-engine"))
        assertNull(TtsVoiceCatalog.byKey("not-a-plugin"))
    }

    @Test
    fun `resolve reports system, android engine and desktop-only selections`() {
        val system = TtsVoiceCatalog.resolve(TtsConfig())
        assertTrue(system is TtsSelection.Ok)
        assertEquals(TtsEngineKind.SYSTEM, (system as TtsSelection.Ok).engineKind)

        val android = TtsVoiceCatalog.resolve(
            TtsConfig(enginePackage = TtsEngineRanker.GOOGLE_TTS),
        )
        assertEquals(TtsEngineKind.ANDROID_ENGINE, (android as TtsSelection.Ok).engineKind)

        val desktop = TtsVoiceCatalog.resolve(TtsConfig(voiceEngine = "openai-tts-voice-plugin"))
        assertTrue(desktop is TtsSelection.DesktopOnly)
        assertEquals("OpenAI TTS", (desktop as TtsSelection.DesktopOnly).displayName)

        val unknown = TtsVoiceCatalog.resolve(TtsConfig(voiceEngine = "mystery-engine"))
        assertTrue(unknown is TtsSelection.UnknownEngine)
        assertEquals("mystery-engine", (unknown as TtsSelection.UnknownEngine).engine)
    }

    @Test
    fun `blank engine is inferred as system like the desktop container does`() {
        assertEquals(ENGINE_SYSTEM, TtsVoiceCatalog.inferEngine(""))
        assertEquals(ENGINE_SYSTEM, TtsVoiceCatalog.inferEngine("   "))
        assertEquals("azure-tts-voice-plugin", TtsVoiceCatalog.inferEngine(" azure-tts-voice-plugin "))
        assertTrue(TtsVoiceCatalog.isDesktopPlugin("multitts-voice-plugin"))
        assertFalse(TtsVoiceCatalog.isDesktopPlugin(ENGINE_SYSTEM))
    }

    @Test
    fun `picker lists system first, installed engines next and plugins last`() {
        val installed = TtsEngineRanker.fromPackages(
            listOf("com.samsung.android.tts", TtsEngineRanker.GOOGLE_TTS),
        )
        val entries = TtsVoiceCatalog.pickerEntries(installed)
        assertEquals(1 + installed.size + 15, entries.size)

        assertEquals(ENGINE_SYSTEM, entries.first().engine)
        assertTrue(entries.first().available)

        // Installed engines keep the ranked order: Google before Samsung.
        assertEquals(TtsEngineRanker.GOOGLE_TTS, entries[1].engine)
        assertEquals(TtsEngineKind.ANDROID_ENGINE, entries[1].kind)
        assertEquals(TtsEngineRanker.GOOGLE_TTS, entries[1].packageName)
        assertEquals("com.samsung.android.tts", entries[2].engine)

        // Desktop-only plugins stay visible but unavailable (config compatibility).
        val plugins = entries.filter { it.kind == TtsEngineKind.DESKTOP_VOICE_PLUGIN }
        assertEquals(15, plugins.size)
        assertTrue(plugins.none { it.available })
    }

    @Test
    fun `voice pick follows desktop name-then-locale-then-first order`() {
        val voices = listOf(
            TtsVoiceOption("en-us-x-tpf-local", "en-US", TtsEngineRanker.GOOGLE_TTS),
            TtsVoiceOption("zh-cn-x-aaa-local", "zh-CN", TtsEngineRanker.GOOGLE_TTS),
            TtsVoiceOption("zh-cn-x-bbb-local", "zh-CN", TtsEngineRanker.GOOGLE_TTS),
        )
        // 1. exact name wins (desktop `item.name === voiceName`).
        assertEquals(
            "zh-cn-x-bbb-local",
            pickVoice(voices, TtsConfig(voiceName = "zh-cn-x-bbb-local"))?.name,
        )
        // 1b. a package-qualified engine name may carry a suffix.
        assertEquals(
            "zh-cn-x-aaa-local",
            pickVoice(voices, TtsConfig(voiceName = "x-aaa-local"))?.name,
        )
        // 2. full locale tag, then language prefix.
        assertEquals("en-us-x-tpf-local", pickVoice(voices, TtsConfig(voiceLocale = "en-US"))?.name)
        assertEquals("zh-cn-x-aaa-local", pickVoice(voices, TtsConfig(voiceLocale = "zh"))?.name)
        // 3. fall back to the first voice the engine lists (desktop `nativeVoices[0]`).
        assertEquals("en-us-x-tpf-local", pickVoice(voices, TtsConfig())?.name)
        assertNull(pickVoice(emptyList(), TtsConfig()))
    }
}
