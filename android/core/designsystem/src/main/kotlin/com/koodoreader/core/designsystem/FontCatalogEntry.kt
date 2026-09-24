package com.koodoreader.core.designsystem

/**
 * A single font entry that appears in the reader font-picker dropdown.
 *
 * Key naming follows the desktop font system exactly:
 *   - `fontUtil.getFontIds()` → list stored under ConfigService "fontList" key
 *   - `fontUtil.getFontMeta(id)` → metadata stored under ConfigService "customFonts"
 *     object config
 *   - `FontItem` (core/common/FontItem.kt) mirrors the desktop FontItem interface
 *     so the same JSON shape is readable on both sides.
 *
 * Built-in / system fonts use the same key format as the desktop:
 *   - "Built-in font" (the empty-selection sentinel)
 *   - CSS generic family names: "serif", "sans-serif", "monospace"
 *     (used by android/app/src/main/java/.../FontManager.kt CSS→native mapping)
 *   - Custom font keys: whatever the user imported (stored in customFonts)
 *
 * The [key] field is the stable identifier persisted in ConfigService / Room.
 *
 * @property key        Stable key, matching the desktop fontList key exactly
 *                      (e.g. "serif", "Built-in font", or a custom id).
 * @property displayName Localised display name — may be an i18n key string.
 * @property fontFamily Optional CSS font-family value for WebView rendering;
 *                      null for system/generic entries where the platform default
 *                      applies.
 */
data class FontCatalogEntry(
    val key: String,
    val displayName: String,
    val fontFamily: String? = null,
) {
    companion object {
        /** Sentinel value representing the built-in browser/app font. */
        val BUILT_IN = FontCatalogEntry(
            key = "Built-in font",
            displayName = "Built-in font",
            fontFamily = null,
        )

        /**
         * CSS generic family keys used as fallback in the reader WebView.
         * These must match the keys consumed by FontManager.kt.
         */
        val CSS_FAMILY_KEYS = listOf("serif", "sans-serif", "monospace")

        /**
         * Known desktop font keys extracted from the bundled font catalog
         * (src/constants/fontConfig.ts) for use in tests as canonical
         * reference values.
         */
        val KNOWN_BUNDLED_KEYS = listOf(
            // LXGW WenKai (霞鹜文楷)
            "LXGWWenKai-Light",
            "LXGWWenKai-Medium",
            "LXGWWenKai-Regular",
            "LXGWWenKaiMono-Light",
            "LXGWWenKaiMono-Medium",
            "LXGWWenKaiMono-Regular",
            // Noto Sans SC
            "NotoSansSC-Light",
            "NotoSansSC-Medium",
            "NotoSansSC-Regular",
            // Noto Serif SC
            "NotoSerifSC-Light",
            "NotoSerifSC-Medium",
            "NotoSerifSC-Regular",
            // Inter
            "Inter-Regular",
            "Inter-Medium",
            // Lora
            "Lora-Regular",
            // Montserrat
            "Montserrat-Regular",
            // Noto Sans / Noto Serif (Latin)
            "NotoSans-Regular",
            "NotoSerif-Regular",
            // Roboto
            "Roboto-Regular",
            "Roboto-Medium",
            // PT Serif
            "PTSerif-Regular",
            // Playfair Display
            "PlayfairDisplay-Regular",
            // EB Garamond
            "EBGaramond-Regular",
            // Quicksand
            "Quicksand-Regular",
        )
    }
}
