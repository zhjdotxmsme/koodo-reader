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

        /**
         * Default (day) preset — warm off-white paper, deep brown-black ink.
         *
         * Deliberately avoids pure black/white (#000000/#FFFFFF) per the mobile
         * UI/UX baseline: pure values feel like a back-lit screen rather than a
         * printed page. The slight warmth keeps the day theme readable and
         * cohesive with the native-only Paper/Sepia presets.
         */
        val DEFAULT_PRESET = ThemeSpec(
            kind = ThemeKind.DEFAULT,
            backgroundColor = "rgba(247, 245, 240,1)",
            backgroundImage = null,
            foregroundColor = "rgba(31, 27, 24,1)",
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

        /**
         * Night preset — dark grey background, white text.
         *
         * The background is #121212 rather than pure black: a pure-black page
         * next to a bright page makes the display's own contrast smear the
         * glyph edges (halation), which is exactly what a night theme is
         * supposed to avoid. The foreground is a *dimmed* off-white
         * (#D8D6D1), not #FFFFFF — full-brightness white on a dark field is
         * the other half of the same problem.
         */
        val NIGHT_PRESET = ThemeSpec(
            kind = ThemeKind.NIGHT,
            backgroundColor = "rgba(18, 18, 18,1)",
            backgroundImage = null,
            foregroundColor = "rgba(216, 214, 209,1)",
            name = "Night",
        )

        /**
         * Paper preset — warm cream page (#E9D8BC) with deep brown ink.
         *
         * The "paper book" look: the page is a sheet of tinted paper, not a
         * lit screen, and the ink is a warm brown-black that reads like print
         * rather than like pixels. Native-only preset.
         */
        val PAPER_PRESET = ThemeSpec(
            kind = ThemeKind.PAPER,
            backgroundColor = "rgba(233, 216, 188,1)",
            backgroundImage = null,
            foregroundColor = "rgba(58, 42, 28,1)",
            name = "Paper",
        )

        /**
         * Sepia preset — a lighter, yellower relative of [PAPER_PRESET].
         *
         * Native-only preset.
         */
        val SEPIA_PRESET = ThemeSpec(
            kind = ThemeKind.SEPIA,
            backgroundColor = "rgba(244, 236, 216,1)",
            backgroundImage = null,
            foregroundColor = "rgba(61, 46, 30,1)",
            name = "Sepia",
        )

        /**
         * The three **desktop** built-in presets, in display order.
         * The order must match the desktop KookitConfig.PresetThemeList index.
         *
         * Native-only presets are deliberately NOT in this list: the list is
         * indexed by the desktop wire format, so appending to it would shift
         * every stored preset index. Use [ALL_PRESETS] for the UI selector.
         */
        val BUILT_IN_PRESETS = listOf(
            DEFAULT_PRESET,
            PROTECT_EYE_PRESET,
            NIGHT_PRESET,
        )

        /**
         * Every preset the native reader offers, in display order.
         *
         * The desktop three come first (so their persisted indices stay
         * aligned), followed by the native-only paper/sepia pair.
         */
        val ALL_PRESETS = listOf(
            DEFAULT_PRESET,
            PROTECT_EYE_PRESET,
            NIGHT_PRESET,
            PAPER_PRESET,
            SEPIA_PRESET,
        )
    }
}
