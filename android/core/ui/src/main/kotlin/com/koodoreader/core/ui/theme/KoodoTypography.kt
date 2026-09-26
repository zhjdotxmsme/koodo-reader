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
 * reader config (fontSize 17, lineHeight 1.5 ratio, ...). Keeping the two apart
 * is what stops a shell restyle from moving text inside a book.
 *
 * Material 3's defaults are kept for the type *sizes*; only the family and the
 * weights that the shell actually uses are pinned, so this stays close to a
 * standard M3 app rather than inventing a second type system.
 */
private val UiFamily = FontFamily.Default

val KoodoTypography = Typography().let { base ->
    Typography(
        titleLarge = base.titleLarge.copy(fontFamily = UiFamily, fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontFamily = UiFamily, fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontFamily = UiFamily, fontWeight = FontWeight.Medium),
        bodyLarge = base.bodyLarge.copy(fontFamily = UiFamily),
        bodyMedium = base.bodyMedium.copy(fontFamily = UiFamily),
        bodySmall = base.bodySmall.copy(fontFamily = UiFamily),
        labelLarge = base.labelLarge.copy(fontFamily = UiFamily, fontWeight = FontWeight.Medium),
        labelMedium = base.labelMedium.copy(fontFamily = UiFamily),
        labelSmall = base.labelSmall.copy(fontFamily = UiFamily),
    )
}

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
