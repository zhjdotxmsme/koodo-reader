package com.koodoreader.core.designsystem

/**
 * Semantic colour values for the app shell (navigation, library, notes, stats,
 * settings) — NOT the reader page.
 *
 * The reader page keeps its own colours in [ThemeSpec], which mirrors the
 * desktop `themeList.tsx` / `themeUtil.ts` values so the Room and backup-zip
 * wire formats stay desktop-compatible. These tokens exist for the chrome
 * around the reader, and they are plain `Long` ARGB values so that:
 *   - this module stays a PURE KOTLIN JVM module (no Compose, no Android), and
 *   - contrast can be asserted in `selfCheck` on any JVM (see [Contrast]).
 * The Compose binding lives in `:core:ui` (`KoodoColors`), which wraps these
 * values in `androidx.compose.ui.graphics.Color`. Do not add Compose types here.
 *
 * Encoding: `0xAARRGGBB`. Every token except the deliberately translucent ones
 * must be fully opaque — [requireOpaque] enforces that.
 *
 * Because [ColorTokens] is a data class, light and dark are guaranteed to
 * declare the SAME slot set. A missing slot on one side is a compile error, not
 * a runtime surprise — that is why this is a class rather than a Map.
 */
data class ColorTokens(
    // ── Brand ────────────────────────────────────────────────────────────────
    /** Brand blue, carried over from the previous shell theme (#3A6EA5 light). */
    val primary: Long,
    val onPrimary: Long,
    val primaryContainer: Long,
    val onPrimaryContainer: Long,

    /**
     * Brand green (#6B8E23 light). Deliberately demoted to an accent: it is NOT
     * guaranteed to reach 4.5:1 as body text on [background], so it is only
     * valid for non-text accents (see [Contrast.ACCENT_MIN_RATIO]).
     */
    val secondary: Long,
    val onSecondary: Long,
    val secondaryContainer: Long,
    val onSecondaryContainer: Long,

    // ── Surfaces & text ──────────────────────────────────────────────────────
    val background: Long,
    val onBackground: Long,
    val surface: Long,
    val onSurface: Long,

    /**
     * Tonal elevation ramp. Hierarchy is expressed by these steps plus
     * [outlineVariant] hairlines — NOT by drop shadows.
     */
    val surfaceContainerLowest: Long,
    val surfaceContainerLow: Long,
    val surfaceContainer: Long,
    val surfaceContainerHigh: Long,
    val surfaceContainerHighest: Long,
    val surfaceDim: Long,
    val surfaceBright: Long,

    val surfaceVariant: Long,
    val onSurfaceVariant: Long,

    /** Hairline / separator colour. Decorative, so contrast is not asserted. */
    val outlineVariant: Long,
    val outline: Long,

    val error: Long,
    val onError: Long,
) {
    companion object {

        /** Light shell palette. Brand seeds match the previous `Theme.kt`. */
        val LIGHT = ColorTokens(
            primary = 0xFF3A6EA5,
            onPrimary = 0xFFFFFFFF,
            primaryContainer = 0xFFD6E4F7,
            onPrimaryContainer = 0xFF0B1B2E,
            secondary = 0xFF6B8E23,
            onSecondary = 0xFFFFFFFF,
            secondaryContainer = 0xFFDCE9C4,
            onSecondaryContainer = 0xFF1A2410,
            background = 0xFFF8F6F2,
            onBackground = 0xFF1A1C1E,
            surface = 0xFFF8F6F2,
            onSurface = 0xFF1A1C1E,
            surfaceContainerLowest = 0xFFFFFFFF,
            surfaceContainerLow = 0xFFF2EFE9,
            surfaceContainer = 0xFFECE9E3,
            surfaceContainerHigh = 0xFFE6E3DD,
            surfaceContainerHighest = 0xFFE0DDD7,
            surfaceDim = 0xFFDEDAD4,
            surfaceBright = 0xFFF8F6F2,
            surfaceVariant = 0xFFE0DDD7,
            onSurfaceVariant = 0xFF44474A,
            outlineVariant = 0xFFC4C7CA,
            outline = 0xFF74777A,
            error = 0xFFBA1A1A,
            onError = 0xFFFFFFFF,
        )

        /** Dark shell palette. Brand seeds match the previous `Theme.kt`. */
        val DARK = ColorTokens(
            primary = 0xFF9EC3E8,
            onPrimary = 0xFF0A1D30,
            primaryContainer = 0xFF26405C,
            onPrimaryContainer = 0xFFD3E4F7,
            secondary = 0xFFB5C98A,
            onSecondary = 0xFF1B2410,
            secondaryContainer = 0xFF3A4A1F,
            onSecondaryContainer = 0xFFDCE9C4,
            background = 0xFF16181D,
            onBackground = 0xFFE3E2E6,
            surface = 0xFF16181D,
            onSurface = 0xFFE3E2E6,
            surfaceContainerLowest = 0xFF0F1116,
            surfaceContainerLow = 0xFF1D2026,
            surfaceContainer = 0xFF212429,
            surfaceContainerHigh = 0xFF2B2E34,
            surfaceContainerHighest = 0xFF36393F,
            surfaceDim = 0xFF16181D,
            surfaceBright = 0xFF3C3F45,
            surfaceVariant = 0xFF44474A,
            onSurfaceVariant = 0xFFC4C7CA,
            outlineVariant = 0xFF44474A,
            outline = 0xFF8E9196,
            error = 0xFFFFB4AB,
            onError = 0xFF690005,
        )

        /** All palettes, for parity/contrast sweeps in tests and `selfCheck`. */
        val ALL = listOf(LIGHT, DARK)

        /**
         * Deterministic placeholder palette for books with no decodable cover.
         *
         * Lives here rather than in the library UI because it is a DESIGN value,
         * not a data value: the six entries are the brand blue/green plus four
         * harmonised companions. They were previously duplicated inline in the
         * library's BookCard, which is how a colour ends up with two owners.
         *
         * Shared across light and dark on purpose — a cover placeholder is an
         * identity cue for a specific book, so it must not change when the theme
         * flips.
         */
        val COVER_PLACEHOLDERS: List<Long> = listOf(
            0xFF3A6EA5, // brand blue
            0xFF6B8E23, // brand green
            0xFF9C5B4F,
            0xFF7A5C9E,
            0xFF2E7D6B,
            0xFF8A6D3B,
        )

        /**
         * Stable placeholder colour for [seed]. `abs` is taken on the Long
         * before narrowing: `Int.MIN_VALUE.hashCode()` would otherwise stay
         * negative and index out of bounds.
         */
        fun coverPlaceholderFor(seed: String): Long {
            val i = kotlin.math.abs(seed.hashCode().toLong()) % COVER_PLACEHOLDERS.size
            return COVER_PLACEHOLDERS[i.toInt()]
        }

        /**
         * Foreground/background pairs that must satisfy the WCAG AA normal-text
         * ratio (4.5:1). Asserted by `selfCheck` and `ColorTokensTest`.
         *
         * [secondary] is intentionally ABSENT: it is an accent, and the light
         * value only reaches ~3.5:1 on the background. Adding it here would
         * force either a worse brand colour or a meaningless threshold.
         */
        fun textPairs(t: ColorTokens): List<Triple<String, Long, Long>> = listOf(
            Triple("onBackground/background", t.onBackground, t.background),
            Triple("onSurface/surface", t.onSurface, t.surface),
            Triple("onSurfaceVariant/surface", t.onSurfaceVariant, t.surface),
            Triple("onSurfaceVariant/surfaceContainerLow", t.onSurfaceVariant, t.surfaceContainerLow),
            Triple("primary/background", t.primary, t.background),
            Triple("primary/surface", t.primary, t.surface),
            Triple("onPrimary/primary", t.onPrimary, t.primary),
            Triple("onPrimaryContainer/primaryContainer", t.onPrimaryContainer, t.primaryContainer),
            Triple("onSecondaryContainer/secondaryContainer", t.onSecondaryContainer, t.secondaryContainer),
            Triple("error/background", t.error, t.background),
            Triple("onError/error", t.onError, t.error),
        )

        /**
         * Pairs that must satisfy the WCAG non-text / graphical-object ratio
         * (3:1): accents and FUNCTIONAL borders.
         *
         * [outlineVariant] is deliberately absent. It is a decorative hairline
         * (M3 uses it for dividers), and WCAG exempts purely decorative
         * elements. Measured: the dark hairline sits at ~2.0:1 on
         * [surfaceContainerLowest], which is correct for a divider and would
         * fail a 3:1 gate for no user benefit. It is checked for mere
         * perceptibility instead — see `ColorTokensTest`.
         */
        fun accentPairs(t: ColorTokens): List<Triple<String, Long, Long>> = listOf(
            Triple("secondary/background (accent)", t.secondary, t.background),
            Triple("outline/background", t.outline, t.background),
            Triple("outline/surfaceContainerLowest", t.outline, t.surfaceContainerLowest),
        )
    }
}

/**
 * Every token must be fully opaque. Translucency in the token layer is a bug:
 * it makes contrast depend on whatever is composited underneath, which the
 * `selfCheck` ratios cannot reason about.
 *
 * @return the offending `slot=value` descriptions, empty when all are opaque.
 */
fun ColorTokens.requireOpaque(): List<String> = slotPairs()
    .filter { (_, v) -> ((v shr 24) and 0xFFL) != 0xFFL }
    .map { (name, v) -> "$name=${v.toString(16)}" }

/**
 * Every declared slot as `name to value`, in declaration order. Kept in one
 * place so the opacity / contrast sweeps cannot silently skip a newly added
 * slot: adding a field to [ColorTokens] means adding it here, and the parity
 * test fails until both light and dark are covered.
 */
fun ColorTokens.slotPairs(): List<Pair<String, Long>> = listOf(
    "primary" to primary,
    "onPrimary" to onPrimary,
    "primaryContainer" to primaryContainer,
    "onPrimaryContainer" to onPrimaryContainer,
    "secondary" to secondary,
    "onSecondary" to onSecondary,
    "secondaryContainer" to secondaryContainer,
    "onSecondaryContainer" to onSecondaryContainer,
    "background" to background,
    "onBackground" to onBackground,
    "surface" to surface,
    "onSurface" to onSurface,
    "surfaceContainerLowest" to surfaceContainerLowest,
    "surfaceContainerLow" to surfaceContainerLow,
    "surfaceContainer" to surfaceContainer,
    "surfaceContainerHigh" to surfaceContainerHigh,
    "surfaceContainerHighest" to surfaceContainerHighest,
    "surfaceDim" to surfaceDim,
    "surfaceBright" to surfaceBright,
    "surfaceVariant" to surfaceVariant,
    "onSurfaceVariant" to onSurfaceVariant,
    "outlineVariant" to outlineVariant,
    "outline" to outline,
    "error" to error,
    "onError" to onError,
)
