package com.koodoreader.core.designsystem

/**
 * Elevation / shadow tokens for the *card* layer of the shell.
 *
 * ## Why this is separate from the tonal ramp
 *
 * The shell's hierarchy is normally carried by the `surfaceContainer*` tonal
 * ramp (see [ColorTokens]) plus hairlines — flat, Material-3, no shadows.
 * But the book grid is the one place where a drop shadow genuinely earns its
 * keep: a book cover is a *physical object* standing on the shelf, and a flat
 * tile reads as a coloured rectangle rather than as a book.
 *
 * So the book card is the single deliberate exception, and its shadow is
 * defined here rather than inline, so the grid and the list row cannot drift
 * apart.
 *
 * ## Two-layer shadow system (UX-Pro-Max baseline)
 *
 * Only two shadow vocabularies are allowed:
 *   - **Floating** — `0 4 12` (offsetX 0, offsetY 4dp, blur 12dp): cards,
 *     covers, small elevated surfaces.
 *   - **Popup** — `0 12 32` (offsetX 0, offsetY 12dp, blur 32dp): bottom
 *     sheets, modals, dialogs.
 *
 * The old card contact shadow is folded into the floating layer.
 */
object ElevationTokens {

    /**
     * Shadow alpha for the floating layer, in 0..1.
     *
     * 0.18 — enough to separate the cover from the page behind it without the
     * cover looking like it is floating above a hole.
     */
    const val FLOATING_SHADOW_ALPHA = 0.18f

    /** Vertical shadow offset for the floating layer, in dp. */
    const val FLOATING_SHADOW_Y = 4f

    /** Shadow blur radius for the floating layer, in dp. */
    const val FLOATING_SHADOW_BLUR = 12f

    /**
     * Shadow spread for the floating layer, in dp.
     *
     * Negative spread tightens the shadow toward the card's own silhouette,
     * which is what makes it read as a contact shadow instead of a halo.
     */
    const val FLOATING_SHADOW_SPREAD = -2f

    /**
     * Shadow alpha for the popup layer, in 0..1.
     *
     * Slightly stronger than the floating layer because the popup surface is
     * higher in the z-order and farther from the background.
     */
    const val POPUP_SHADOW_ALPHA = 0.24f

    /** Vertical shadow offset for the popup layer, in dp. */
    const val POPUP_SHADOW_Y = 12f

    /** Shadow blur radius for the popup layer, in dp. */
    const val POPUP_SHADOW_BLUR = 32f

    /**
     * The elevated card's corner radius, in dp.
     *
     * 12dp — the iReader / Google Books cover radius, and the value the
     * requirement asks for. The cover art is clipped to the same radius so
     * the shadow follows the artwork rather than a rectangle.
     */
    const val CARD_CORNER_RADIUS = 12f

    /**
     * Shadow alpha for a card in dark mode, in 0..1.
     *
     * Lower than in light mode: on a dark page a black shadow is invisible,
     * so the separation has to come from a faint light halo instead.
     */
    const val CARD_SHADOW_ALPHA_DARK = 0.28f

    /** The light halo colour used for card separation in dark mode, 0xAARRGGBB. */
    const val CARD_HALO_DARK = 0x14FFFFFF
}
