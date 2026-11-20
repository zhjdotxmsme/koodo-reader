package com.koodoreader.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import com.koodoreader.core.designsystem.CjkTypography

/**
 * UI type scale for the shell (navigation, library, notes, stats, settings).
 *
 * IMPORTANT: this is NOT the reader typography. Book text is laid out by
 * `:core:designsystem`'s `TypographyTokens`, whose defaults mirror the desktop
 * reader config (fontSize 17, lineHeight 1.6 ratio, ...). Keeping the two apart
 * is what stops a shell restyle from moving text inside a book.
 */
private val UiFamily = FontFamily.Default

/**
 * CJK-aware line height trimming.
 *
 * Compose's default line-height behaviour reserves extra leading below the
 * baseline, which for Chinese text opens an obvious gap between the descender
 * area and the next line — CJK glyphs sit almost entirely inside the em box,
 * so that reserved space reads as wasted air rather than as leading.
 *
 * [LineHeightStyle] with a zero `trim` on both sides keeps the line box tight
 * to the glyphs while the `lineHeight` value still controls the overall rhythm.
 */
private val CjkLineHeightStyle = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun style(
    size: Int,
    line: Int,
    weight: FontWeight = FontWeight.Normal,
    letterSpacing: Double = 0.0,
) = TextStyle(
    fontFamily = UiFamily,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
    letterSpacing = letterSpacing.sp,
    lineHeightStyle = CjkLineHeightStyle,
)

val KoodoTypography = Typography(
    titleLarge = style(20, 28, FontWeight.SemiBold),
    titleMedium = style(16, 24, FontWeight.SemiBold),
    titleSmall = style(14, 20, FontWeight.Medium),
    bodyLarge = style(16, 26),
    bodyMedium = style(15, 24),
    bodySmall = style(13, 20),
    labelLarge = style(14, 20, FontWeight.Medium),
    labelMedium = style(13, 18),
    labelSmall = style(12, 16),
)

/**
 * Section header inside a settings group (see `KoodoSettingsSection`).
 * Small, muted and uppercase-ish without forcing caps on CJK text.
 */
val SectionHeaderTextStyle = TextStyle(
    fontFamily = UiFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 13.sp,
    letterSpacing = 0.5.sp,
    lineHeightStyle = CjkLineHeightStyle,
)

/**
 * The small badge drawn on a book cover (format label, reading progress).
 *
 * Deliberately tiny and tight: a badge competes with the cover artwork for
 * attention, so it uses the smallest legible size with slightly positive
 * tracking to stay readable against an arbitrary background.
 */
val CoverBadgeTextStyle = TextStyle(
    fontFamily = UiFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 10.sp,
    lineHeight = 12.sp,
    letterSpacing = 0.2.sp,
    lineHeightStyle = CjkLineHeightStyle,
)

/**
 * The reader's body-text style, for the parts of the reader that DO use
 * Compose text (chapter titles in the TOC, the bottom progress label, the
 * selection sheet's excerpt preview).
 *
 * It mirrors the reader's own typography — [CjkTypography] — rather than the
 * shell scale, so a chapter title inside the reader reads like part of the
 * book, not like part of the app chrome.
 */
val ReaderBodyTextStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontSize = 17.sp,
    lineHeight = (17 * CjkTypography.LINE_HEIGHT_RATIO).sp,
    letterSpacing = CjkTypography.LETTER_SPACING_SP.sp,
    lineHeightStyle = CjkLineHeightStyle,
)
