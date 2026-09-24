package com.koodoreader.core.designsystem

/**
 * Complete theme specification for the reader.
 *
 * Default values are extracted from the desktop source:
 *   - themeList.tsx backgroundList / textList (built-in preset colours)
 *   - themeUtil.ts — applies CSS via ConfigService "backgroundColor" /
 *     "textColor" keys
 *   - backgroundUtil.ts — backgroundImage stored as optional data-URI
 *     or official-background-{index} id
 *
 * @property kind           Which preset or custom slot this spec fills.
 * @property backgroundColor  Background colour in "#RRGGBB" or
 *                           "rgba(R,G,B,A)" format, matching the desktop
 *                           ConfigService "backgroundColor" key.
 * @property backgroundImage  Optional background image: either a local
 *                           data-URI ("data:image/…;base64,…") or an
 *                           official-background id string
 *                           ("official-background-{index}").
 * @property foregroundColor Text / foreground colour in the same format.
 * @property name            Display name, exposed to the user or i18n system.
 */
data class ThemeSpec(
    val kind: ThemeKind = ThemeKind.DEFAULT,
    val backgroundColor: String = DEFAULT_PRESET.backgroundColor,
    val backgroundImage: String? = null,
    val foregroundColor: String = DEFAULT_PRESET.foregroundColor,
    val name: String = "Default",
) {
    companion object {

        /** Default (day) preset — white background, black text. */
        val DEFAULT_PRESET = ThemeSpec(
            kind = ThemeKind.DEFAULT,
            backgroundColor = "rgba(255,255,255,1)",
            backgroundImage = null,
            foregroundColor = "rgba(0,0,0,1)",
            name = "Default",
        )

        /** Eye-protection preset — soft green background, dark green text. */
        val PROTECT_EYE_PRESET = ThemeSpec(
            kind = ThemeKind.PROTECT_EYE,
            backgroundColor = "rgba(197, 231, 207,1)",
            backgroundImage = null,
            foregroundColor = "rgba(54, 80, 62,1)",
            name = "Eye Protection",
        )

        /** Night preset — dark grey background, white text. */
        val NIGHT_PRESET = ThemeSpec(
            kind = ThemeKind.NIGHT,
            backgroundColor = "rgba(44, 47, 49,1)",
            backgroundImage = null,
            foregroundColor = "rgba(255,255,255,1)",
            name = "Night",
        )

        /**
         * All three built-in presets, in display order.
         * The order must match the desktop KookitConfig.PresetThemeList index.
         */
        val BUILT_IN_PRESETS = listOf(
            DEFAULT_PRESET,
            PROTECT_EYE_PRESET,
            NIGHT_PRESET,
        )
    }
}
