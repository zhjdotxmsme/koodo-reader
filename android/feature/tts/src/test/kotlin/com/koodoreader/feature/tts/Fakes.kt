package com.koodoreader.feature.tts

/**
 * Test doubles for the P6 TTS unit tests.
 *
 * Both are pure JVM: the fake engine provider replaces
 * `PackageManager.queryIntentActivities`, the fake speaker replaces
 * `TextToSpeech`. Nothing here needs the Android SDK, an emulator or Robolectric —
 * which is the P6 constraint for the test design.
 */

/** In-memory [TtsEngineProvider] — stands in for `EngineEnumerator`. */
class FakeTtsEngineProvider(
    installed: List<String> = emptyList(),
    private val defaultEngine: String? = null,
    labels: Map<String, String> = emptyMap(),
) : TtsEngineProvider {

    private val installedEngines: List<TtsEngineInfo> =
        installed.map { TtsEngineInfo(packageName = it, label = labels[it] ?: it) }

    override fun engines(): List<TtsEngineInfo> = installedEngines

    override fun defaultEnginePackage(): String? = defaultEngine

    /** Mirrors the Android implementation's "answers the intent OR is installed" contract. */
    override fun isInstalled(packageName: String): Boolean =
        packageName.isNotBlank() && installedEngines.any { it.packageName == packageName }
}

/** A [TtsSpeaker] that records calls instead of making sound. */
class FakeTtsSpeaker : TtsSpeaker {

    data class Spoken(val text: String, val utteranceId: String, val rate: Float, val pitch: Float, val volume: Float)

    val spoken: MutableList<Spoken> = mutableListOf()

    var stopCount: Int = 0
        private set

    var speaking: Boolean = false
        private set

    override fun speak(text: String, utteranceId: String, rate: Float, pitch: Float, volume: Float) {
        spoken += Spoken(text, utteranceId, rate, pitch, volume)
        speaking = true
    }

    override fun stop() {
        stopCount++
        speaking = false
    }

    override fun isSpeaking(): Boolean = speaking

    /** The text of the most recent utterance, or `null`. */
    fun lastText(): String? = spoken.lastOrNull()?.text
}

/** [TtsResumeSink] that records the anchors the session controller reported. */
class RecordingResumeSink : TtsResumeSink {

    data class Point(val bookKey: String, val utteranceIndex: Int, val anchorText: String, val updatedAt: Long)

    val points: MutableList<Point> = mutableListOf()

    override fun onResumePoint(bookKey: String, utteranceIndex: Int, anchorText: String, updatedAt: Long) {
        points += Point(bookKey, utteranceIndex, anchorText, updatedAt)
    }

    fun lastIndex(): Int? = points.lastOrNull()?.utteranceIndex
}
