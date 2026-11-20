package com.koodoreader.core.designsystem

/**
 * Reader-page theme kinds, aligned with the desktop preset theme system.
 *
 * The desktop uses KookitConfig.PresetThemeList (defined in the kookit
 * library), which contains the three built-in presets.  The Android side
 * mirrors those presets here so the codec can round-trip theme selections
 * without depending on the kookit library.
 *
 * ## Native-only additions
 *
 * [PAPER] and [SEPIA] have **no desktop counterpart**. They exist because the
 * desktop preset list is a wire-format contract (the preset *index* is stored
 * in the reader config), so new presets cannot simply be appended to it —
 * appending would shift the indices and make a config written by one build
 * mean a different theme in another.
 *
 * The native-only presets therefore live in their own enum entries that are
 * mapped onto the desktop's `CUSTOM` slot when persisted: the colour pair is
 * written to the desktop's `backgroundColor` / `textColor` keys, which the
 * desktop already understands. The colour survives the round-trip; only the
 * *name* of the preset does not.
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
     * Warm paper background (#E9D8BC) with deep brown text.
     *
     * The "paper book" look: a cream page rather than a white screen, with a
     * brown-black ink colour that is warmer and less harsh than pure black.
     * Native-only — persisted through the desktop CUSTOM slot.
     */
    PAPER,

    /**
     * Warm sepia background (#F4ECD8) with dark brown text.
     *
     * A lighter, yellower relative of [PAPER]. Native-only.
     */
    SEPIA,

    /**
     * User-custom theme (background colour / background image / text colour
     * stored as user preferences, not a preset).
     */
    CUSTOM,
}
