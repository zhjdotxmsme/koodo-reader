package com.koodoreader.reader.shell

import android.content.Context
import android.content.SharedPreferences
import com.koodoreader.core.common.FontItem
import com.koodoreader.core.common.NativeFontKeys
import org.json.JSONArray

/**
 * Native font metadata store — desktop config-key parity
 * (`fontList` + `customFonts`), so a migrated desktop config keeps working.
 * Metadata only: font bytes live in filesDir/fonts (FontManager).
 */
class FontPrefs private constructor(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("font_prefs", Context.MODE_PRIVATE)

    fun installed(): List<FontItem> {
        val raw = sp.getString(NativeFontKeys.FONT_LIST_KEY, null) ?: return emptyList()
        val mapRaw = sp.getString(NativeFontKeys.CUSTOM_FONTS_MAP, "{}") ?: "{}"
        return runCatching {
            val ids = JSONArray(raw)
            val map = org.json.JSONObject(mapRaw)
            (0 until ids.length()).mapNotNull { i ->
                val id = ids.getString(i)
                val o = map.optJSONObject(id) ?: return@mapNotNull null
                FontItem(
                    id = o.optString("id", id),
                    label = o.optString("label", id),
                    value = o.optString("value", id),
                    type = o.optString("type", "ttf"),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun addFont(item: FontItem) = mutate { ids, map ->
        ids.put(item.id)
        map.put(
            item.id,
            org.json.JSONObject()
                .put("id", item.id).put("label", item.label)
                .put("value", item.value).put("type", item.type),
        )
    }

    fun removeFont(key: String) = mutate { ids, map ->
        removeString(ids, key)
        map.remove(key)
    }

    private fun mutate(block: (JSONArray, org.json.JSONObject) -> Unit) {
        val ids = JSONArray(sp.getString(NativeFontKeys.FONT_LIST_KEY, null) ?: "[]")
        val map = org.json.JSONObject(sp.getString(NativeFontKeys.CUSTOM_FONTS_MAP, "{}"))
        block(ids, map)
        sp.edit()
            .putString(NativeFontKeys.FONT_LIST_KEY, ids.toString())
            .putString(NativeFontKeys.CUSTOM_FONTS_MAP, map.toString())
            .apply()
    }

    private fun removeString(arr: JSONArray, value: String): JSONArray {
        val out = JSONArray()
        for (i in 0 until arr.length()) if (arr.getString(i) != value) out.put(arr.getString(i))
        return out
    }

    companion object {
        @Volatile private var instance: FontPrefs? = null

        fun get(context: Context): FontPrefs =
            instance ?: synchronized(this) {
                instance ?: FontPrefs(context.applicationContext).also { instance = it }
            }
    }
}
