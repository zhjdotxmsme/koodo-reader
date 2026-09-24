package com.koodoreader.feature.tts

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/**
 * Android DataStore adapters for the TTS stores (DoD [1] config, DoD [4] resume).
 *
 * This is the only file that turns [TtsConfigStore] / [TtsResumeStore] into
 * something backed by the device: it is deliberately kept free of decision logic
 * (that lives in `TtsConfigRepository.kt` and `BookmarkResumeController.kt`, both
 * pure JVM and unit-tested).
 *
 * Values are stored under the **desktop key names** ([TtsConfigKeys]), so
 * `adb shell` inspection — and a future desktop-side profile import — sees the
 * familiar `voiceName` / `voiceSpeed` pairs.
 *
 * Registration: this file is part of `:feature:tts`; nothing else is needed once
 * `docs/patches/p6-tts.patch` wires the module into the host build.
 */

/** Process-wide DataStore handles. Delegates must be top-level properties. */
private val Context.ttsConfigDataStore: DataStore<Preferences> by
    preferencesDataStore(name = TtsConfigRepository.DATASTORE_NAME)

private val Context.ttsResumeDataStore: DataStore<Preferences> by
    preferencesDataStore(name = DataStoreTtsResumeStore.DATASTORE_NAME)

/**
 * [TtsConfigStore] over DataStore Preferences.
 *
 * Reads flatten `Preferences` into the `Map<String, String?>` shape
 * [TtsConfigCodec.decode] expects; values of any other type are ignored (they can
 * only come from a foreign writer).
 */
class DataStoreTtsConfigStore(private val dataStore: DataStore<Preferences>) : TtsConfigStore {

    override suspend fun load(): TtsConfig {
        val prefs = dataStore.data.first()
        val flat = HashMap<String, String?>(prefs.asMap().size)
        for ((key, value) in prefs.asMap()) {
            flat[key.name] = value as? String
        }
        return TtsConfigCodec.decode(flat)
    }

    override suspend fun save(config: TtsConfig) {
        val encoded = TtsConfigCodec.encode(config)
        dataStore.edit { prefs ->
            for ((key, value) in encoded) {
                prefs[stringPreferencesKey(key)] = value
            }
        }
    }

    companion object {
        /** Builds the app-scoped store. */
        fun from(context: Context): DataStoreTtsConfigStore =
            DataStoreTtsConfigStore(context.applicationContext.ttsConfigDataStore)
    }
}

/**
 * [TtsResumeStore] over DataStore Preferences — the reading-position bookmark used
 * by "断点续播" (DoD [4]).
 *
 * One flat key per anchored field, prefixed with the book key so several books can
 * be remembered, plus a `last` pointer holding the most recently updated book (what
 * the "continue listening" entry point resumes).
 */
class DataStoreTtsResumeStore(private val dataStore: DataStore<Preferences>) : TtsResumeStore {

    override suspend fun load(bookKey: String): TtsResumeAnchor? {
        val prefs = dataStore.data.first().asMap()
        fun string(key: String): String? = prefs[stringPreferencesKey(bookPrefix(bookKey) + key)] as? String
        val spine = string(KEY_SPINE)?.toIntOrNull() ?: return null
        return TtsResumeAnchor(
            bookKey = bookKey,
            spineIndex = spine.coerceAtLeast(0),
            cfi = string(KEY_CFI).orEmpty(),
            chapterPercent = string(KEY_CHAPTER_PERCENT)?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f,
            totalPercent = string(KEY_TOTAL_PERCENT)?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f,
            sentenceIndex = string(KEY_SENTENCE)?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
            anchorText = string(KEY_ANCHOR).orEmpty(),
            updatedAt = string(KEY_UPDATED_AT)?.toLongOrNull() ?: 0L,
        )
    }

    override suspend fun save(anchor: TtsResumeAnchor) {
        val prefix = bookPrefix(anchor.bookKey)
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey(prefix + KEY_SPINE)] = anchor.spineIndex.toString()
            prefs[stringPreferencesKey(prefix + KEY_CFI)] = anchor.cfi
            prefs[stringPreferencesKey(prefix + KEY_CHAPTER_PERCENT)] = anchor.chapterPercent.toString()
            prefs[stringPreferencesKey(prefix + KEY_TOTAL_PERCENT)] = anchor.totalPercent.toString()
            prefs[stringPreferencesKey(prefix + KEY_SENTENCE)] = anchor.sentenceIndex.toString()
            prefs[stringPreferencesKey(prefix + KEY_ANCHOR)] = anchor.anchorText
            prefs[stringPreferencesKey(prefix + KEY_UPDATED_AT)] = anchor.updatedAt.toString()
            // "Continue listening" pointer.
            prefs[stringPreferencesKey(KEY_LAST_BOOK)] = anchor.bookKey
        }
    }

    override suspend fun clear(bookKey: String) {
        val prefix = bookPrefix(bookKey)
        dataStore.edit { prefs ->
            for (key in ANCHOR_KEYS) {
                prefs.remove(stringPreferencesKey(prefix + key))
            }
            if (prefs[stringPreferencesKey(KEY_LAST_BOOK)] == bookKey) {
                prefs.remove(stringPreferencesKey(KEY_LAST_BOOK))
            }
        }
    }

    /** Book key of the most recently anchored session, or `null`. */
    override suspend fun lastBookKey(): String? =
        dataStore.data.first().asMap()[stringPreferencesKey(KEY_LAST_BOOK)] as? String

    companion object {
        const val DATASTORE_NAME = "koodo_tts_resume"

        private const val KEY_SPINE = "spineIndex"
        private const val KEY_CFI = "cfi"
        private const val KEY_CHAPTER_PERCENT = "chapterPercent"
        private const val KEY_TOTAL_PERCENT = "totalPercent"
        private const val KEY_SENTENCE = "sentenceIndex"
        private const val KEY_ANCHOR = "anchorText"
        private const val KEY_UPDATED_AT = "updatedAt"
        private const val KEY_LAST_BOOK = "lastBookKey"

        private val ANCHOR_KEYS = listOf(
            KEY_SPINE, KEY_CFI, KEY_CHAPTER_PERCENT, KEY_TOTAL_PERCENT,
            KEY_SENTENCE, KEY_ANCHOR, KEY_UPDATED_AT,
        )

        private fun bookPrefix(bookKey: String): String = "book.$bookKey."

        /** Builds the app-scoped store. */
        fun from(context: Context): DataStoreTtsResumeStore =
            DataStoreTtsResumeStore(context.applicationContext.ttsResumeDataStore)
    }
}
