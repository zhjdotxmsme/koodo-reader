package com.koodoreader.feature.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DoD [3] — the lock-screen / media-key mapping table and the strings the card
 * shows. All pure: the receiver and the MediaSession are adapters over it.
 */
class TtsMediaCommandTest {

    @Test
    fun `media key codes map to transport commands`() {
        assertEquals(TtsMediaCommand.PLAY, TtsMediaCommandMapper.fromKeyCode(126))
        assertEquals(TtsMediaCommand.PAUSE, TtsMediaCommandMapper.fromKeyCode(127))
        assertEquals(TtsMediaCommand.PLAY_PAUSE, TtsMediaCommandMapper.fromKeyCode(85))
        // A headset single click is reported as HEADSETHOOK on many devices.
        assertEquals(TtsMediaCommand.PLAY_PAUSE, TtsMediaCommandMapper.fromKeyCode(79))
        assertEquals(TtsMediaCommand.STOP, TtsMediaCommandMapper.fromKeyCode(86))
        assertEquals(TtsMediaCommand.NEXT, TtsMediaCommandMapper.fromKeyCode(87))
        assertEquals(TtsMediaCommand.PREVIOUS, TtsMediaCommandMapper.fromKeyCode(88))
        assertEquals(TtsMediaCommand.FAST_FORWARD, TtsMediaCommandMapper.fromKeyCode(90))
        assertEquals(TtsMediaCommand.REWIND, TtsMediaCommandMapper.fromKeyCode(89))
        // Non-media keys are ignored, never guessed.
        assertNull(TtsMediaCommandMapper.fromKeyCode(4))
        assertNull(TtsMediaCommandMapper.fromKeyCode(null))
    }

    @Test
    fun `explicit actions map exactly and unknown actions are ignored`() {
        for (command in TtsMediaCommand.values()) {
            assertEquals(command, TtsMediaCommandMapper.fromAction(command.action))
        }
        assertNull(TtsMediaCommandMapper.fromAction("android.intent.action.SOMETHING_ELSE"))
        assertNull(TtsMediaCommandMapper.fromAction(null))
        assertTrue(TtsMediaCommandMapper.isMediaButtonAction(TtsMediaCommandMapper.ACTION_MEDIA_BUTTON))
        assertFalse(TtsMediaCommandMapper.isMediaButtonAction(TtsMediaCommand.PLAY.action))
    }

    @Test
    fun `resolution prefers the explicit action over the key code`() {
        assertEquals(
            TtsMediaCommand.NEXT,
            TtsMediaCommandMapper.from(TtsMediaCommand.NEXT.action, 127),
        )
        assertEquals(
            TtsMediaCommand.PLAY_PAUSE,
            TtsMediaCommandMapper.from(TtsMediaCommandMapper.ACTION_MEDIA_BUTTON, 85),
        )
        assertNull(TtsMediaCommandMapper.from(TtsMediaCommandMapper.ACTION_MEDIA_BUTTON, 4))
        assertNull(TtsMediaCommandMapper.from("foreign.action", null))
    }

    @Test
    fun `toggle resolves against the live playback state`() {
        assertEquals(
            TtsMediaCommand.PAUSE,
            TtsMediaCommandMapper.resolveToggle(TtsMediaCommand.PLAY_PAUSE, TtsPlaybackState.PLAYING),
        )
        assertEquals(
            TtsMediaCommand.PAUSE,
            TtsMediaCommandMapper.resolveToggle(TtsMediaCommand.PLAY_PAUSE, TtsPlaybackState.PREPARING),
        )
        assertEquals(
            TtsMediaCommand.PLAY,
            TtsMediaCommandMapper.resolveToggle(TtsMediaCommand.PLAY_PAUSE, TtsPlaybackState.PAUSED),
        )
        assertEquals(
            TtsMediaCommand.PLAY,
            TtsMediaCommandMapper.resolveToggle(TtsMediaCommand.PLAY_PAUSE, TtsPlaybackState.STOPPED),
        )
        assertEquals(
            TtsMediaCommand.PLAY,
            TtsMediaCommandMapper.resolveToggle(TtsMediaCommand.PLAY_PAUSE, TtsPlaybackState.IDLE),
        )
        // Non-toggle commands pass through untouched.
        assertEquals(
            TtsMediaCommand.NEXT,
            TtsMediaCommandMapper.resolveToggle(TtsMediaCommand.NEXT, TtsPlaybackState.PLAYING),
        )
    }

    @Test
    fun `lock screen text falls back like the desktop notification`() {
        assertEquals(TtsMediaMetadata.FALLBACK_TITLE, TtsMediaMetadata.title(TtsPlaybackSnapshot.IDLE))
        val snapshot = TtsPlaybackSnapshot.of(
            state = TtsPlaybackState.PLAYING,
            bookKey = "book-1",
            bookTitle = "The Book",
            chapterTitle = "Chapter 7",
            utteranceIndex = 1,
            utteranceCount = 4,
            positionMs = 0L,
        )
        assertEquals("The Book", TtsMediaMetadata.title(snapshot))
        assertEquals("Chapter 7", TtsMediaMetadata.subtitle(snapshot))
        assertEquals("2/4", TtsMediaMetadata.progressLabel(snapshot))
        assertEquals("2/4", TtsMediaMetadata.description(snapshot))
        // Errored sessions append the desktop failure message.
        assertEquals(
            "2/4 · Audio loading failed, stopped playback",
            TtsMediaMetadata.description(snapshot, "Audio loading failed, stopped playback"),
        )
        assertEquals("", TtsMediaMetadata.progressLabel(TtsPlaybackSnapshot.IDLE))
    }

    @Test
    fun `control sheet state mirrors the desktop transport button`() {
        val config = TtsConfig(voiceName = "zh-cn-x-aaa-local", voiceSpeed = 1.5f)

        val idle = TtsControlUiState.of(TtsPlaybackSnapshot.IDLE, config)
        assertEquals("Play", idle.primaryLabelKey)
        assertEquals(TtsMediaCommand.PLAY, idle.primaryCommand)
        assertFalse(idle.canPlay)
        assertFalse(idle.needsVoiceSelection)
        assertEquals("", idle.errorKey)

        val playingSnapshot = TtsPlaybackSnapshot.of(
            state = TtsPlaybackState.PLAYING,
            bookKey = "book-1",
            bookTitle = "Book",
            chapterTitle = "Chapter 1",
            utteranceIndex = 0,
            utteranceCount = 10,
            positionMs = 0L,
        )
        val playing = TtsControlUiState.of(playingSnapshot, config)
        assertEquals("Pause", playing.primaryLabelKey)
        assertEquals(TtsMediaCommand.PAUSE, playing.primaryCommand)
        assertTrue(playing.isPlaying)
        assertTrue(playing.canPlay)
        assertEquals(10, playing.progressPercent)
        assertEquals(1.5f, playing.speed, 0.0001f)
        assertEquals(TtsPitch.DEFAULT, playing.pitch, 0.0001f)

        val paused = TtsControlUiState.of(playingSnapshot.copy(state = TtsPlaybackState.PAUSED), config)
        assertEquals("Resume", paused.primaryLabelKey)
        assertEquals(TtsMediaCommand.PLAY, paused.primaryCommand)

        val errored = TtsControlUiState.of(
            playingSnapshot.copy(state = TtsPlaybackState.ERROR),
            TtsConfig(),
        )
        assertEquals("Audio loading failed, stopped playback", errored.errorKey)
        // Desktop shows `t("Please select")` until a voice is chosen.
        assertTrue(errored.needsVoiceSelection)
        assertEquals(TtsVoiceCatalog.SYSTEM_LABEL, errored.engineLabel)

        val pluginConfig = TtsConfig(voiceEngine = "azure-tts-voice-plugin")
        assertEquals("Azure TTS", TtsControlUiState.of(TtsPlaybackSnapshot.IDLE, pluginConfig).engineLabel)
        assertEquals(TtsRate.MIN, TtsControlUiState.SPEED_MIN, 0.0001f)
        assertEquals(TtsRate.MAX, TtsControlUiState.SPEED_MAX, 0.0001f)
    }
}
