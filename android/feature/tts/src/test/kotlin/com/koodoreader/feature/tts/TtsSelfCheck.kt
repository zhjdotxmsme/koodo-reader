package com.koodoreader.feature.tts

import com.koodoreader.engine.text.ByteArrayTextSource
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * Framework-free verification runner for `:feature:tts`.
 *
 * Same idea as `:engine:toc:selfCheck` / `:core:designsystem:selfCheck`: it runs the
 * pure-logic acceptance checks without JUnit, so the behaviour can be verified on a
 * plain JVM even when the Android test task is unavailable (`:feature:tts` is an
 * Android library, so `testDebugUnitTest` needs the SDK; this main class does not).
 *
 * Run it from a throwaway pure-JVM source set (see
 * `docs/p6-tts-semantics-mapping.md` §7) or from the Android unit-test classpath:
 * ```
 * java -cp <test-classes>:<kotlin-stdlib>:<coroutines-core> \
 *      com.koodoreader.feature.tts.TtsSelfCheckKt
 * ```
 * Exit code 0 = every check passed. The same assertions exist as JUnit tests; this
 * is the no-framework path for CI images without the Android SDK.
 */
fun main() {
    var failures = 0

    fun check(name: String, condition: Boolean) {
        if (condition) {
            println("ok   $name")
        } else {
            failures++
            println("FAIL $name")
        }
    }

    // --- DoD [1]: 15 voice labels, engine ranking, config persistence ---------
    check("catalog has the 15 desktop voice labels", TtsVoiceCatalog.labels.size == 15)
    check(
        "engine ranking prefers com.google.android.tts",
        TtsEngineRanker.fromPackages(listOf("aa.tts", TtsEngineRanker.GOOGLE_TTS))
            .first().packageName == TtsEngineRanker.GOOGLE_TTS,
    )
    check(
        "uninstalled engine falls back to the platform default",
        TtsEngineResolver.resolveEnginePackage(
            FakeTtsEngineProvider(installed = listOf("com.samsung.android.tts"), defaultEngine = "com.samsung.android.tts"),
            "com.uninstalled.tts",
        ) == "com.samsung.android.tts",
    )
    val codecRoundTrip = TtsConfigCodec.decode(TtsConfigCodec.encode(TtsConfig(voiceSpeed = 1.5f, pitch = 1.2f)))
    check("config codec round trips speed and pitch", codecRoundTrip.voiceSpeed == 1.5f && codecRoundTrip.pitch == 1.2f)
    check("desktop speed percent matches ttsUtil", TtsRate.desktopPercent(1.5f) == 50)

    // --- DoD [1]: sentence segmentation (desktop splitSentences parity) -------
    check(
        "english text chunks at 150 characters",
        TtsSentenceSplitter.defaultMaxLength(TtsLanguageDetector.detect("Hello world.")) == 150,
    )
    check(
        "chinese text chunks at 50 characters",
        TtsSentenceSplitter.defaultMaxLength(TtsLanguageDetector.detect("这是一本中文书。")) == 50,
    )
    check(
        "sentence split keeps quotes attached",
        TtsSentenceSplitter.split("Hello world. \"How are you?\"", 150) ==
            listOf("Hello world.", "\"How are you?\""),
    )

    // --- DoD [2]: playback state machine -------------------------------------
    val speaker = FakeTtsSpeaker()
    val sink = RecordingResumeSink()
    val session = TtsSessionController(speaker, sink, clock = { 1L })
    session.load(
        "book-1",
        "Book",
        "Chapter 1",
        listOf(
            TtsUtterance(0, "One.", 0, 4),
            TtsUtterance(1, "Two.", 5, 9),
        ),
    )
    session.applyConfig(TtsConfig(voiceSpeed = 1.5f))
    check("play starts the first sentence", session.play() && speaker.lastText() == "One.")
    check("play reports PLAYING", session.state == TtsPlaybackState.PLAYING)
    check("utterance completion advances the queue", session.onUtteranceDone() && session.index == 1)
    check("pause stops the speaker and records a resume point", session.pause() && speaker.stopCount == 1)
    check("pause persisted the current sentence index", sink.lastIndex() == 1)
    check("resume re-speaks the same sentence", session.play() && speaker.spoken.size == 3)
    check(
        "finishing the last sentence finishes the chapter",
        !session.onUtteranceDone() && session.chapterFinished && session.state == TtsPlaybackState.STOPPED,
    )

    // --- DoD [3]: media keys / lock-screen mapping ---------------------------
    check(
        "media key code 85 toggles",
        TtsMediaCommandMapper.fromKeyCode(85) == TtsMediaCommand.PLAY_PAUSE,
    )
    check(
        "toggle resolves to pause while playing",
        TtsMediaCommandMapper.resolveToggle(TtsMediaCommand.PLAY_PAUSE, TtsPlaybackState.PLAYING) ==
            TtsMediaCommand.PAUSE,
    )
    check(
        "notification action string maps back to the command",
        TtsMediaCommandMapper.fromAction(TtsMediaCommand.NEXT.action) == TtsMediaCommand.NEXT,
    )
    check(
        "lock screen title falls back to the app name",
        TtsMediaMetadata.title(TtsPlaybackSnapshot.IDLE) == TtsMediaMetadata.FALLBACK_TITLE,
    )
    check(
        "control sheet mirrors the desktop transport button",
        TtsControlUiState.of(TtsPlaybackSnapshot.IDLE, TtsConfig()).primaryLabelKey == "Play",
    )

    // --- DoD [4]: resume ------------------------------------------------------
    runBlocking {
        val store = InMemoryTtsResumeStore()
        val controller = BookmarkResumeController(store) { 5L }
        val text = "Alpha beta. Gamma delta."
        val source = TextSourceSentenceSource(ByteArrayTextSource(text.toByteArray(Charsets.UTF_8))) {
            it.toString(Charsets.UTF_8)
        }
        check("engine text source splits into sentences", source.sentences().size == 2)
        controller.record(
            "book-2",
            utteranceIndex = 1,
            anchorText = "Gamma delta.",
            position = TtsReadingPosition("book-2", 3, "/4/2:10", 0.5f, 0.25f),
        )
        val point = controller.restore("book-2", source)
        check("resume anchor matches by text", point.sentenceIndex == 1 && point.matchedByText)
        check("resume anchor keeps the engine toc position", store.load("book-2")?.spineIndex == 3)
        val unmatched = controller.restore("book-2", listOf("Completely", "different", "text"))
        check("numeric fallback clamps into the new queue", unmatched.sentenceIndex == 1)
    }

    if (failures > 0) {
        println("TtsSelfCheck: $failures check(s) FAILED")
        exitProcess(1)
    }
    println("TtsSelfCheck: all checks passed")
}
