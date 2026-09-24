package com.koodoreader.reader.shell

import android.content.Context
import com.koodoreader.core.common.FlatJson
import com.koodoreader.core.common.Localization
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Compose-side i18n state: bundles the desktop locale subset (en + zh-CN,
 * synced into assets by scripts/sync-locales-android.js), exposes `t(key)`
 * via [LocalI18n], and persists the language choice ("system" | code).
 *
 * Language switching is live: [localization] is a mutable singleton and
 * [language] drives recomposition of every `t()` call site.
 */
class I18nState private constructor(
    val localization: Localization,
    initialLanguage: String,
) {
    private val _language = MutableStateFlow(initialLanguage)
    val language: StateFlow<String> = _language

    fun setLanguage(raw: String) {
        localization.language = raw
        _language.value = localization.language
    }

    companion object {
        const val SYSTEM = "system"
        /** Picker choices (subset bundled by ADR-004). */
        val CHOICES = listOf(SYSTEM, "en", "zh-CN")

        fun create(context: Context): I18nState {
            val catalogs = HashMap<String, Map<String, String>>()
            for (code in listOf("en", "zh-CN")) {
                runCatching {
                    context.assets.open("locales/$code.json").use { input ->
                        catalogs[code] = FlatJson.parse(input.readBytes().decodeToString())
                    }
                }
            }
            val prefs = LibraryPrefs(context)
            val saved = prefs.language
            val resolved = if (saved != SYSTEM) saved else systemLanguage(context)
            val localization = Localization(catalogs, resolved)
            return I18nState(localization, localization.language)
        }

        fun systemLanguage(context: Context): String {
            val locale = context.resources.configuration.locales[0]
            return if (locale.country.isNullOrBlank()) {
                locale.language
            } else {
                "${locale.language}-${locale.country}"
            }
        }
    }
}
