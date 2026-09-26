package com.koodoreader.core.designsystem

/**
 * Corner-radius scale, in dp.
 *
 * Values match the Material 3 shape defaults so that a Compose theme built from
 * them (see `:core:ui` `KoodoShapes`) does not have to translate between two
 * vocabularies. The chosen mapping for this app:
 *   - cards and bottom sheets → [large] (16dp)
 *   - controls, chips and text fields → [medium] (12dp) / [small] (8dp)
 *   - inline hairlines and small badges → [extraSmall] (4dp)
 *
 * Cards at 16dp and chips at 8dp are a deliberate match for the reference
 * reading app's `rounded-box` / `rounded-btn` scale (16 / 8).
 *
 * These are plain Ints (dp) because this module is pure JVM — no Compose
 * `Dp` type. `:core:ui` performs the `.dp` conversion.
 */
object ShapeTokens {

    val extraSmall = 4
    val small = 8
    val medium = 12
    val large = 16
    val extraLarge = 28

    /** The scale in ascending order, for validation and iteration. */
    val ALL = listOf(extraSmall, small, medium, large, extraLarge)

    /** Material 3's own defaults, kept explicit so drift is detectable. */
    val MATERIAL3_DEFAULTS = listOf(4, 8, 12, 16, 28)
}
