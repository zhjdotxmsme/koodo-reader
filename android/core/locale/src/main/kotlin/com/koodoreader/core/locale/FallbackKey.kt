package com.koodoreader.core.locale

import com.koodoreader.core.common.Localization

/**
 * A react-i18next translation key, identical to the desktop keys
 * ("key 与桌面一致", ADR-004) — and the **terminal step** of the fallback chain:
 * when neither the selected locale nor English has the key, the key itself is
 * rendered, exactly like the desktop does for a missing translation.
 *
 * Typing the chain entry point prevents "translate an already translated
 * string" bugs: values are plain [String], keys must be wrapped explicitly.
 */
@JvmInline
value class FallbackKey(val raw: String) {

    init {
        require(raw.isNotBlank()) { "translation key must not be blank" }
    }

    override fun toString(): String = raw

    companion object {
        /** Wraps [raw] (desktop key text). */
        fun of(raw: String): FallbackKey = FallbackKey(raw)
    }
}

/** Desktop-parity locale codes and preference values. */
object LocaleCodes {

    /** Fallback language of the chain (ADR-004: 所选 → en → key). */
    const val FALLBACK: String = Localization.FALLBACK_LANGUAGE

    /** Regression-mandated subset bundled with the APK (ADR-004). */
    const val ZH_CN = "zh-CN"

    /** Traditional-Chinese locales that select the TW conversion stage. */
    val TRADITIONAL_CHINESE = listOf("zh-TW", "zh-HK", "zh-MO")

    /** `LibraryPrefs.language` sentinel: follow the system locale. */
    const val SYSTEM = "system"

    /** `zh_cn` → `zh-CN`, blank → `en` (delegates to the P1 implementation). */
    fun normalize(raw: String): String = Localization.normalize(raw)

    /** True for any `zh*` locale. */
    fun isChinese(code: String?): Boolean =
        code != null && normalize(code).lowercase().startsWith("zh")
}
