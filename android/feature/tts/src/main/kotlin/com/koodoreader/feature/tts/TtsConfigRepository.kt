package com.koodoreader.feature.tts

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * TTS configuration persistence (DoD [1]) — **pure JVM half**.
 *
 * Desktop counterpart: `ConfigService.getReaderConfig("voiceName" | "voiceEngine"
 * | "voiceSpeed" | "voiceLocale")` — a flat string key/value store. Android stores
 * the same key names ([TtsConfigKeys]) in **DataStore Preferences**; the Android
 * adapter lives in `DataStoreTtsConfigStore.kt` so that everything in *this* file
 * compiles and unit-tests on a plain JVM (no Android SDK, no Robolectric) — the
 * P6 constraint "单测设计须 JVM only".
 *
 * Split:
 *  - [TtsConfigStore]            — the port (suspend load/save);
 *  - [TtsConfigCodec]            — pure `Map<String, String>` ↔ [TtsConfig]
 *                                  conversion (desktop string forms included);
 *  - [TtsConfigRepository]       — cached façade with serialised updates;
 *  - [InMemoryTtsConfigStore]    — test / preview double;
 *  - `DataStoreTtsConfigStore`   — the Android adapter (other file).
 */

/** Persistence port for [TtsConfig]. Implementations must be safe to call concurrently. */
interface TtsConfigStore {

    /** Reads the stored config; missing keys fall back to [TtsConfig.DEFAULT]. */
    suspend fun load(): TtsConfig

    /** Writes the config, normalising it first. */
    suspend fun save(config: TtsConfig)
}

/**
 * Pure key/value ↔ [TtsConfig] conversion.
 *
 * Every value is a **string**, mirroring the desktop `ConfigService` (which stores
 * `voiceSpeed` as `"1"`, not `1`) — so an Android-side value can be copied into a
 * desktop config verbatim, and a desktop config parses here without a type
 * mismatch. This is the wire format the mapping doc documents.
 */
object TtsConfigCodec {

    /** The key names written by [encode], in a stable order. */
    val keys: List<String> = listOf(
        TtsConfigKeys.VOICE_NAME,
        TtsConfigKeys.VOICE_ENGINE,
        TtsConfigKeys.VOICE_SPEED,
        TtsConfigKeys.VOICE_LOCALE,
        TtsConfigKeys.ENGINE_PACKAGE,
        TtsConfigKeys.PITCH,
        TtsConfigKeys.VOLUME,
        TtsConfigKeys.CHUNK_LENGTH,
    )

    /** Serialises [config] (normalised first) into the flat string map. */
    fun encode(config: TtsConfig): Map<String, String> {
        val c = config.normalized()
        return mapOf(
            TtsConfigKeys.VOICE_NAME to c.voiceName,
            TtsConfigKeys.VOICE_ENGINE to c.voiceEngine,
            TtsConfigKeys.VOICE_SPEED to formatFloat(c.voiceSpeed),
            TtsConfigKeys.VOICE_LOCALE to c.voiceLocale,
            TtsConfigKeys.ENGINE_PACKAGE to c.enginePackage,
            TtsConfigKeys.PITCH to formatFloat(c.pitch),
            TtsConfigKeys.VOLUME to formatFloat(c.volume),
            TtsConfigKeys.CHUNK_LENGTH to c.chunkLength.toString(),
        )
    }

    /**
     * Parses a flat map, tolerating absent keys, `null` values and the values the
     * desktop would have written (e.g. `voiceSpeed = ""`, `"abc"`, `"1.5"`).
     *
     * Every failure path degrades to the default instead of throwing — desktop
     * parity, where `parseFloat(getReaderConfig("voiceSpeed")) || 1` swallows
     * garbage.
     */
    fun decode(values: Map<String, String?>): TtsConfig = TtsConfig(
        voiceName = values[TtsConfigKeys.VOICE_NAME].orEmpty(),
        voiceEngine = values[TtsConfigKeys.VOICE_ENGINE].orEmpty().ifBlank { ENGINE_SYSTEM },
        voiceSpeed = TtsRate.fromDesktop(values[TtsConfigKeys.VOICE_SPEED]),
        voiceLocale = values[TtsConfigKeys.VOICE_LOCALE].orEmpty(),
        enginePackage = values[TtsConfigKeys.ENGINE_PACKAGE].orEmpty(),
        pitch = parseFloatOr(values[TtsConfigKeys.PITCH], TtsPitch.DEFAULT),
        volume = parseFloatOr(values[TtsConfigKeys.VOLUME], 1.0f),
        chunkLength = values[TtsConfigKeys.CHUNK_LENGTH]?.trim()?.toIntOrNull()
            ?.coerceIn(0, TtsConfig.MAX_CHUNK_LENGTH) ?: 0,
    ).normalized()

    private fun parseFloatOr(raw: String?, fallback: Float): Float {
        if (raw.isNullOrBlank()) return fallback
        val parsed = raw.trim().toFloatOrNull() ?: return fallback
        return if (parsed.isNaN()) fallback else parsed
    }

    /** Drops a trailing `.0` so `1.0f` is stored as `"1"` (the desktop UI stores `"1"`). */
    private fun formatFloat(value: Float): String =
        if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString()
}

/**
 * Configuration façade: cached reads + serialised read-modify-write updates.
 *
 * The desktop container re-reads `ConfigService` on every interaction; on Android
 * a DataStore read is a suspending flow, so the value is cached and only re-read
 * after [invalidate] (used when a backup restore rewrote the store).
 */
class TtsConfigRepository(private val store: TtsConfigStore) {

    private val mutex = Mutex()
    private var cached: TtsConfig? = null

    /** The current config (loads from [store] on first use). */
    suspend fun current(): TtsConfig = mutex.withLock {
        cached ?: store.load().normalized().also { cached = it }
    }

    /** Applies [transform] to the current config, persists it and returns the new value. */
    suspend fun update(transform: (TtsConfig) -> TtsConfig): TtsConfig = mutex.withLock {
        val base = cached ?: store.load()
        val next = transform(base).normalized()
        store.save(next)
        cached = next
        next
    }

    /** Restores the desktop defaults (the settings "reset" action). */
    suspend fun reset(): TtsConfig = mutex.withLock {
        val next = TtsConfig.DEFAULT
        store.save(next)
        cached = next
        next
    }

    /** Drops the cache and re-reads the store. */
    suspend fun invalidate(): TtsConfig {
        mutex.withLock { cached = null }
        return current()
    }

    companion object {
        /** DataStore file backing the Android implementation; one per app process. */
        const val DATASTORE_NAME = "koodo_tts_config"
    }
}

/** In-memory [TtsConfigStore] — unit tests, Compose previews, `selfCheck`. */
class InMemoryTtsConfigStore(initial: TtsConfig = TtsConfig.DEFAULT) : TtsConfigStore {

    private var value: TtsConfig = initial.normalized()

    /** Number of [save] calls; lets a test assert that an update path persisted. */
    var saveCount: Int = 0
        private set

    override suspend fun load(): TtsConfig = value

    override suspend fun save(config: TtsConfig) {
        value = config.normalized()
        saveCount++
    }
}
