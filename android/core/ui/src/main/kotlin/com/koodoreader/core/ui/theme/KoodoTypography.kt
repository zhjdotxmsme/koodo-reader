package com.koodoreader.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * UI type scale for the shell (navigation, library, notes, stats, settings).
 *
 * IMPORTANT: this is NOT the reader typography. Book text is laid out by
 * `:core:designsystem`'s `TypographyTokens`, whose defaults mirror the desktop
 * reader config (fontSize 17, lineHeight 1.6 ratio, ...). Keeping the two apart
 * is what stops a shell restyle from moving text inside a book.
 */
private val UiFamily = FontFamily.Default
private fun style(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = UiFamily, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight)

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
)
