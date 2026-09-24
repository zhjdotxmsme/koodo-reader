package com.koodoreader.core.designsystem

/**
 * Reader theme kinds, aligned with the desktop preset theme system.
 *
 * The desktop uses KookitConfig.PresetThemeList (defined in the kookit
 * library), which contains the three built-in presets.  The Android side
 * mirrors those presets here so the codec can round-trip theme selections
 * without depending on the kookit library.
 *
 * @see ThemeSpec
 */
enum class ThemeKind {
    /** White background + black text — default for new readers. */
    DEFAULT,

    /** Green-tinted background ("eye protection") from the desktop preset list. */
    PROTECT_EYE,

    /** Dark background + light text — night reading preset. */
    NIGHT,

    /**
     * User-custom theme (background colour / background image / text colour
     * stored as user preferences, not a preset).
     */
    CUSTOM,
}
