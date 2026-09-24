package com.koodoreader.core.common

/**
 * Desktop-parity translation lookup. Keys are the react-i18next keys used by
 * the desktop app ("key 与桌面一致"); the lookup chain is
 * selected language → English → the key itself (desktop parity for missing
 * keys, which render as their English source text there).
 *
 * Pure JVM: catalogs are supplied by the caller (Android loads the bundled
 * asset JSONs via [FlatJson]; JVM tests build maps inline).
 */
class Localization(
    catalogs: Map<String, Map<String, String>>,
    language: String,
) {
    private val catalogs: Map<String, Map<String, String>> = catalogs
        .mapValues { it.value.toMap() }
        .toMap()

    var language: String = language
        set(value) {
            field = normalize(value)
        }

    init {
        require(catalogs.containsKey(FALLBACK_LANGUAGE) || catalogs.isNotEmpty()) {
            "catalogs must contain at least one language"
        }
        this.language = normalize(language)
    }

    /** Translate [key]; falls back to English, then to the key itself. */
    fun t(key: String): String {
        val direct = catalogs[language]?.get(key)
        if (direct != null) return direct
        if (language != FALLBACK_LANGUAGE) {
            val en = catalogs[FALLBACK_LANGUAGE]?.get(key)
            if (en != null) return en
        }
        return key
    }

    /** Supported language codes present in the catalog set (e.g. "en", "zh-CN"). */
    fun availableLanguages(): List<String> = catalogs.keys.sorted()

    companion object {
        const val FALLBACK_LANGUAGE = "en"

        /** "zh-cn" / "ZH_CN" → "zh-CN" style normalization; unknown → "en". */
        fun normalize(raw: String): String {
            if (raw.isBlank()) return FALLBACK_LANGUAGE
            val parts = raw.trim().replace('_', '-').split('-')
            return when (parts.size) {
                1 -> parts[0].lowercase()
                else -> parts[0].lowercase() + "-" + parts.drop(1).joinToString("-") { p ->
                    if (p.length == 4) p.uppercase() else p.uppercase()
                }
            }
        }
    }
}
