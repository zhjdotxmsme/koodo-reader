package com.koodoreader.feature.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DoD [2] — the playback state machine.
 *
 * `ForegroundTtsService` is not instantiated here (it needs the framework); the
 * service is a thin adapter over this class, so covering the transitions, the queue
 * walk and the resume-point bookkeeping covers the listening behaviour itself.
 * Desktop reference: `TTSUtil.isPaused` / `pausedMidSentence` and the
 * `nodeList` walk in `textToSpeech/component.tsx`.
 */
class TtsSessionControllerTest {

    private fun utterances(vararg texts: String): List<TtsUtterance> =
        texts.mapIndexed { index, text ->
            TtsUtterance(index = index, text = text, charStart = index * 100, charEnd = index * 100 + text.length)
        }

    private fun controller(
        speaker: FakeTtsSpeaker = FakeTtsSpeaker(),
        sink: RecordingResumeSink? = null,
    ): TtsSessionController = TtsSessionController(speaker, sink, clock = { 42L })

    @Test
    fun `load arms the queue and leaves the session idle`() {
        val speaker = FakeTtsSpeaker()
        val session = controller(speaker)
        val state = session.load("book-1", "Book", "Chapter 1", utterances("One.", "Two.", "Three."))

        assertEquals(TtsPlaybackState.IDLE, state)
        assertEquals(3, session.utterances.size)
        assertEquals(0, session.index)
        assertEquals("One.", session.current?.text)
        assertTrue(speaker.spoken.isEmpty())
        assertFalse(session.chapterFinished)
    }

    @Test
    fun `play speaks the current sentence and reports playing`() {
        val speaker = FakeTtsSpeaker()
        val session = controller(speaker)
        session.load("book-1", "Book", "Chapter 1", utterances("One.", "Two."))

        assertTrue(session.play())
        assertEquals(TtsPlaybackState.PLAYING, session.state)
        assertEquals(1, speaker.spoken.size)
        assertEquals("One.", speaker.lastText())
        // Utterance ids are what the UtteranceProgressListener matches on.
        assertEquals("book-1:0", speaker.spoken.first().utteranceId)
        // Playing twice is a no-op (the transport button is idempotent).
        assertFalse(session.play())
        assertEquals(1, speaker.spoken.size)
    }

    @Test
    fun `play is refused while preparing and in error state`() {
        val speaker = FakeTtsSpeaker()
        val session = controller(speaker)
        session.load("book-1", "Book", "Chapter 1", utterances("One."))

        session.markEnginePreparing()
        assertEquals(TtsPlaybackState.PREPARING, session.state)
        assertFalse(session.play())
        assertEquals(TtsPlaybackState.IDLE, session.onEngineReady())
        assertTrue(session.play())

        session.onUtteranceError("boom")
        assertEquals(TtsPlaybackState.ERROR, session.state)
        assertFalse(session.play())
        assertEquals("boom", session.lastError)
        // A fresh queue clears the error, mirroring "restart listening".
        session.load("book-1", "Book", "Chapter 1", utterances("One."))
        assertEquals(TtsPlaybackState.IDLE, session.state)
        assertNull(session.lastError)
    }

    @Test
    fun `pause stops the speaker, keeps the queue and records a resume point`() {
        val speaker = FakeTtsSpeaker()
        val sink = RecordingResumeSink()
        val session = controller(speaker, sink)
        session.load("book-1", "Book", "Chapter 1", utterances("One.", "Two."))
        session.play()

        assertTrue(session.pause())
        assertEquals(TtsPlaybackState.PAUSED, session.state)
        assertEquals(1, speaker.stopCount)
        assertEquals(0, sink.lastIndex())
        assertEquals(42L, sink.points.last().updatedAt)
        // Pausing twice changes nothing.
        assertFalse(session.pause())

        // Resume re-speaks the same sentence (Android has no mid-utterance resume).
        assertTrue(session.play())
        assertEquals(TtsPlaybackState.PLAYING, session.state)
        assertEquals(2, speaker.spoken.size)
        assertEquals("One.", speaker.lastText())
    }

    @Test
    fun `next and previous move the index and are clamped`() {
        val speaker = FakeTtsSpeaker()
        val session = controller(speaker)
        session.load("book-1", "Book", "Chapter 1", utterances("One.", "Two.", "Three."))

        assertFalse(session.previous())
        assertTrue(session.next())
        assertEquals(1, session.index)
        assertEquals(2, session.index.let { session.next(); session.index })
        assertFalse(session.next())
        assertTrue(session.previous())
        assertEquals(1, session.index)
        // While idle the move does not speak; while playing it does.
        assertTrue(speaker.spoken.isEmpty())
        session.play()
        session.next()
        assertEquals("Three.", speaker.lastText())
    }

    @Test
    fun `utterance completion walks the queue and finishes the chapter`() {
        val speaker = FakeTtsSpeaker()
        val sink = RecordingResumeSink()
        val session = controller(speaker, sink)
        session.load("book-1", "Book", "Chapter 1", utterances("One.", "Two."))
        session.play()

        assertTrue(session.onUtteranceDone())
        assertEquals(1, session.index)
        assertEquals("Two.", speaker.lastText())
        assertFalse(session.chapterFinished)

        // Last sentence done: the chapter is finished, the reader turns the page.
        assertFalse(session.onUtteranceDone())
        assertTrue(session.chapterFinished)
        assertEquals(TtsPlaybackState.STOPPED, session.state)
        assertEquals(1, sink.lastIndex())
    }

    @Test
    fun `stop keeps the queue for a later resume and is idempotent`() {
        val speaker = FakeTtsSpeaker()
        val sink = RecordingResumeSink()
        val session = controller(speaker, sink)
        session.load("book-1", "Book", "Chapter 1", utterances("One.", "Two."))
        session.play()

        assertTrue(session.stop())
        assertEquals(TtsPlaybackState.STOPPED, session.state)
        // The resume point is persisted even on an explicit stop (desktop keeps nodeList).
        assertEquals(0, sink.lastIndex())
        assertFalse(session.stop())
        // Desktop: the next play restarts from the current index, not the chapter start.
        session.next()
        assertEquals(TtsPlaybackState.IDLE, session.state)
        assertTrue(session.play())
        assertEquals("Two.", speaker.lastText())
    }

    @Test
    fun `voice parameters from the config reach the speaker`() {
        val speaker = FakeTtsSpeaker()
        val session = controller(speaker)
        session.load("book-1", "Book", "Chapter 1", utterances("One."))
        session.applyConfig(TtsConfig(voiceSpeed = 1.5f, pitch = 1.2f, volume = 0.5f))
        session.play()

        val spoken = speaker.spoken.single()
        assertEquals(1.5f, spoken.rate, 0.0001f)
        assertEquals(1.2f, spoken.pitch, 0.0001f)
        assertEquals(0.5f, spoken.volume, 0.0001f)
        val (rate, pitch, volume) = session.voiceParameters()
        assertEquals(1.5f, rate, 0.0001f)
        assertEquals(1.2f, pitch, 0.0001f)
        assertEquals(0.5f, volume, 0.0001f)
        // Out-of-range values are clamped, never handed to the engine.
        session.applyConfig(TtsConfig(voiceSpeed = 99f, pitch = -3f, volume = 5f))
        val (clampedRate, clampedPitch, clampedVolume) = session.voiceParameters()
        assertEquals(TtsRate.MAX, clampedRate, 0.0001f)
        assertEquals(TtsPitch.MIN, clampedPitch, 0.0001f)
        assertEquals(1.0f, clampedVolume, 0.0001f)
    }

    @Test
    fun `snapshot drives the lock screen`() {
        val session = controller()
        session.load("book-1", "The Book", "Chapter 7", utterances("One.", "Two.", "Three.", "Four."))
        session.play()
        session.next()

        val snapshot = session.snapshot(positionMs = 1500L)
        assertEquals(TtsPlaybackState.PLAYING, snapshot.state)
        assertEquals("book-1", snapshot.bookKey)
        assertEquals("The Book", snapshot.bookTitle)
        assertEquals("Chapter 7", snapshot.chapterTitle)
        assertEquals(1, snapshot.utteranceIndex)
        assertEquals(4, snapshot.utteranceCount)
        assertEquals(1500L, snapshot.positionMs)
        assertEquals(50, snapshot.progressPercent)
        assertEquals("2/4", TtsMediaMetadata.progressLabel(snapshot))
        assertTrue(snapshot.hasQueue)
    }

    @Test
    fun `transition table matches the documented state machine`() {
        val session = controller()
        assertTrue(session.canTransitionTo(TtsPlaybackState.PLAYING))
        assertFalse(session.canTransitionTo(TtsPlaybackState.PAUSED))

        session.load("book-1", "Book", "Chapter 1", utterances("One."))
        session.play()
        assertTrue(session.canTransitionTo(TtsPlaybackState.PAUSED))
        assertFalse(session.canTransitionTo(TtsPlaybackState.IDLE))

        session.pause()
        assertTrue(session.canTransitionTo(TtsPlaybackState.PLAYING))
        assertFalse(session.canTransitionTo(TtsPlaybackState.ERROR))

        session.stop()
        assertTrue(session.canTransitionTo(TtsPlaybackState.PLAYING))
        assertFalse(session.canTransitionTo(TtsPlaybackState.PAUSED))

        session.onEngineError("nope")
        assertTrue(session.canTransitionTo(TtsPlaybackState.IDLE))
        assertFalse(session.canTransitionTo(TtsPlaybackState.PLAYING))
    }
}
