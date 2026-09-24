package com.koodoreader.core.designsystem

/**
 * Aggregates every dimension of the reader's visual appearance into a single
 * serialisable config object.
 *
 * This is the root type handed to [AppearanceCodec] for JSON serialisation
 * (Room persistence / backup-zip interchange with the desktop).  All
 * field names in the JSON wire format match the desktop ConfigService keys
 * exactly so the two platforms can share the same backup file format.
 *
 * @param typography        Typography tokens (font size, line height, letter
 *                          spacing, margins, text alignment, paragraph spacing).
 * @param theme             Theme (kind, background colour/image, foreground colour).
 * @param fontCatalogEntry  Selected font from the font catalog.
 * @param marginHorizontalPx  Horizontal page margin override in pixels
 *                            (mirrors desktop ConfigService "margin" key,
 *                            stored as a separate field for fine-grained control).
 * @param marginVerticalPx    Vertical page margin override in pixels.
 * @param letterSpacingPx     Standalone letter-spacing override in pixels
 *                            (duplicated from typography.letterSpacingPx for
 *                            historical codec compatibility).
 */
data class ReaderAppearanceConfig(
    val typography: TypographyTokens = TypographyTokens(),
    val theme: ThemeSpec = ThemeSpec.DEFAULT_PRESET,
    val fontCatalogEntry: FontCatalogEntry = FontCatalogEntry.BUILT_IN,
    val marginHorizontalPx: Int = 0,
    val marginVerticalPx: Int = 0,
    val letterSpacingPx: Float = 0f,
) {
    companion object {
        /** All desktop ConfigService reader-config keys that feed into this aggregate. */
        val CONFIG_KEYS = listOf(
            TypographyTokens.KEY_FONT_SIZE,
            TypographyTokens.KEY_LINE_HEIGHT,
            TypographyTokens.KEY_LETTER_SPACING,
            TypographyTokens.KEY_MARGIN,
            TypographyTokens.KEY_PARA_SPACING,
            TypographyTokens.KEY_TEXT_ALIGN,
        )
    }
}
