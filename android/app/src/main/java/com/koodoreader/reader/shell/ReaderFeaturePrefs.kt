package com.koodoreader.reader.shell

import android.content.Context
import android.content.SharedPreferences

/**
 * SharedPreferences backing for [ReaderFeatureState].
 *
 * Same `reader` file as the original font-scale slider, so A−/A+ and the
 * settings page stay one fact source. Values are stored as strings so the
 * JVM codec can round-trip without Android types.
 */
class ReaderFeaturePrefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): ReaderFeatureState {
        val map = LinkedHashMap<String, String>()
        for (key in KEYS) {
            val value = readAsString(key) ?: continue
            map[key] = value
        }
        return ReaderFeatureCodec.fromMap(map)
    }

    fun save(state: ReaderFeatureState) {
        val editor = sp.edit()
        for ((key, value) in ReaderFeatureCodec.toMap(state)) {
            editor.putString(key, value)
        }
        editor.apply()
    }

    fun register(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        sp.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregister(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        sp.unregisterOnSharedPreferenceChangeListener(listener)
    }

    /**
     * `fontScale` used to be a Float; other keys may be Boolean. Read whatever
     * is there so a settings upgrade doesn't crash or drop the user's size.
     */
    private fun readAsString(key: String): String? {
        if (!sp.contains(key)) return null
        return try {
            sp.getString(key, null)
        } catch (_: ClassCastException) {
            try {
                sp.getFloat(key, Float.NaN).takeIf { !it.isNaN() }?.toString()
            } catch (_: ClassCastException) {
                try {
                    sp.getBoolean(key, false).toString()
                } catch (_: ClassCastException) {
                    try {
                        sp.getInt(key, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }?.toString()
                    } catch (_: ClassCastException) {
                        null
                    }
                }
            }
        }
    }

    companion object {
        const val PREFS_NAME = "reader"

        /** Must include every key [ReaderFeatureCodec.toMap] writes, or load() drops it. */
        internal val KEYS: Set<String> =
            ReaderFeatureCodec.toMap(ReaderFeatureState()).keys
    }
}
