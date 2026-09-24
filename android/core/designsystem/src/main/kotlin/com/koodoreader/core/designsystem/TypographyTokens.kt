package com.koodoreader.core.designsystem

/**
 * Typography tokens aligned with the desktop reader config.
 *
 * Default values are extracted from:
 *   - sliderList (dropdownList.tsx): fontSize 17, letterSpacing 0, margin 0,
 *     paraSpacing 0, scale 1, brightness 1
 *   - dropdownList.tsx lineHeight options: default empty = 1.5 ratio
 *   - dropdownList.tsx textAlign options: default empty = "left"
 *
 * All pixel fields are stored as Int (px) to stay compatible with the
 * integer-based desktop ConfigService JSON values.
 */
data class TypographyTokens(
    /** Font size in SP, matching the desktop ConfigService "fontSize" key. Default 17. */
    val fontSizeSp: Float = 17f,

    /**
     * Line height as a unitless ratio (e.g. 1.5 → 1.5 × fontSize).
     * Desktop dropdownList: "" (=1.5), "1", "1.25", "1.5", "1.75", "2".
     */
    val lineHeightRatio: Float = 1.5f,

    /**
     * Letter spacing in pixels. Desktop ConfigService "letterSpacing" key.
     * Slider range 0–20, step 1; default 0.
     */
    val letterSpacingPx: Float = 0f,

    /**
     * First-line text indent in pixels. Not exposed in the desktop UI (fixed 0).
     */
    val textIndentPx: Int = 0,

    /**
     * Paragraph spacing before each paragraph, in pixels.
     * Desktop ConfigService "paraSpacing" key; slider range 0–120, step 1.
     */
    val paragraphSpacingBeforePx: Int = 0,

    /**
     * Paragraph spacing after each paragraph, in pixels.
     * Currently stored identically to paragraphSpacingBeforePx on the desktop.
     */
    val paragraphSpacingAfterPx: Int = 0,

    /** Left page/column margin in pixels. Desktop ConfigService "margin" key; range –40–80. */
    val marginLeftPx: Int = 0,

    /** Right page/column margin in pixels. Same range as marginLeftPx. */
    val marginRightPx: Int = 0,

    /** Top margin in pixels. Same range as marginLeftPx. */
    val marginTopPx: Int = 0,

    /** Bottom margin in pixels. Same range as marginLeftPx. */
    val marginBottomPx: Int = 0,

    /**
     * Text alignment, matching desktop CSS text-align values.
     * Desktop dropdownList: "" (=left), "Left", "Justify", "Right".
     */
    val textAlign: String = "left",
) {
    companion object {
        /** Desktop ConfigService key for font size. */
        const val KEY_FONT_SIZE = "fontSize"

        /** Desktop ConfigService key for line height. */
        const val KEY_LINE_HEIGHT = "lineHeight"

        /** Desktop ConfigService key for letter spacing. */
        const val KEY_LETTER_SPACING = "letterSpacing"

        /** Desktop ConfigService key for margin. */
        const val KEY_MARGIN = "margin"

        /** Desktop ConfigService key for paragraph spacing. */
        const val KEY_PARA_SPACING = "paraSpacing"

        /** Desktop ConfigService key for text alignment. */
        const val KEY_TEXT_ALIGN = "textAlign"

        /** Valid textAlign values (desktop CSS-compatible). */
        val VALID_TEXT_ALIGNS = listOf("left", "center", "justify", "right")
    }
}
