package com.koodoreader.core.designsystem

/**
 * Spacing scale, in dp.
 *
 * Material 3 ships colour, type and shape tokens but has NO spacing token —
 * a structural gap. Without one, every screen invents its own gaps, which is
 * exactly how the previous shell ended up stacking four unrelated actions into
 * a single top bar with hand-tuned padding.
 *
 * The scale is a 4dp grid, doubling at the top end:
 *   [xs]=4  [sm]=8  [md]=12  [lg]=16  [xl]=24  [xxl]=32
 *
 * Plain Ints (dp) — this module is pure JVM. `:core:ui` exposes them through a
 * `CompositionLocal`/object as Compose `Dp`.
 */
object SpaceTokens {

    val xs = 4
    val sm = 8
    val md = 12
    val lg = 16
    val xl = 24
    val xxl = 32

    /** The scale in ascending order, for validation and iteration. */
    val ALL = listOf(xs, sm, md, lg, xl, xxl)

    // ── Named application points ─────────────────────────────────────────────
    // Every constant below MUST be a member of [ALL]; SpaceTokensTest enforces
    // it. That is the whole point of having a scale — a "spacing" constant
    // picked off-grid is how the old layout accumulated magic numbers.

    /** Default horizontal screen padding (library grid content padding). */
    const val SCREEN_HORIZONTAL = 16

    /**
     * Vertical gap between list rows.
     *
     * The previous library list used 10dp, which is NOT on the 4dp grid. Snapped
     * to 8dp (the nearest scale step) rather than inventing a 10dp token.
     */
    const val LIST_GAP = 8

    /** Horizontal gap between book covers in the library grid. */
    const val GRID_GAP_HORIZONTAL = 12

    /** Vertical gap between cover rows in the library grid. */
    const val GRID_GAP_VERTICAL = 16

    /** Every named constant, so the on-grid invariant can be asserted. */
    val NAMED = listOf(SCREEN_HORIZONTAL, LIST_GAP, GRID_GAP_HORIZONTAL, GRID_GAP_VERTICAL)
}
