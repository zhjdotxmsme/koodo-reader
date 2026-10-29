package com.koodoreader.reader.shell

import android.content.Context
import android.content.SharedPreferences

class ReaderGesturePrefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): ReaderGestureConfig {
        val map = LinkedHashMap<String, String>()
        for (key in KEYS) {
            sp.getString(key, null)?.let { map[key] = it }
        }
        return ReaderGestureCodec.fromMap(map)
    }

    fun save(config: ReaderGestureConfig) {
        sp.edit().apply {
            for ((key, value) in ReaderGestureCodec.toMap(config)) {
                putString(key, value)
            }
        }.apply()
        ReaderGestureRuntime.config = config
    }

    companion object {
        const val PREFS_NAME = "reader_gestures"

        internal val KEYS: Set<String> =
            ReaderGestureCodec.toMap(ReaderGestureConfig()).keys
    }
}
