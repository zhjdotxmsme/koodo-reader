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
    /** Small controls / badges: 6dp per the UX-Pro-Max baseline. */
    val small = 6
    /** Cards and cover silhouettes: 12dp. */
    val medium = 12
    val large = 16
    val extraLarge = 28
    /** Pill / capsule shapes: 999dp (effectively fully rounded). */
    val capsule = 999

    /** The scale in ascending order, for validation and iteration. */
    val ALL = listOf(extraSmall, small, medium, large, extraLarge, capsule)

    /**
     * Material 3's own default radius steps, kept for reference only.
     *
     * This scale deliberately DIFFERS from [ALL]: the UX-Pro-Max baseline
     * replaces M3's 8dp "small" with 6dp and adds the 999dp capsule. Do not
     * assert that [ALL] equals this list — the two scales are independent.
     */
    val MATERIAL3_DEFAULTS = listOf(4, 8, 12, 16, 28)
}
