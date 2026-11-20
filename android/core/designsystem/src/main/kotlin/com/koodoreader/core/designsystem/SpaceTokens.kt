package com.koodoreader.core.designsystem

/**
 * Spacing scale, in dp.
 *
 * Material 3 has no spacing token, which is why the previous shell accumulated
 * hand-tuned padding values. Every gap in the app should come from here.
 *
 * The reader-specific entries live alongside the shell ones because the two
 * live in the same visual system: a reader page margin and a library card
 * padding must agree, or the app stops feeling like one product.
 */
object SpaceTokens {
    const val xs = 4
    const val sm = 8
    const val md = 16
    const val lg = 24
    const val xl = 32
    val ALL = listOf(xs, sm, md, lg, xl)

    const val SCREEN_HORIZONTAL = md
    const val LIST_ITEM_HORIZONTAL = md
    const val LIST_ITEM_VERTICAL = 12

    /**
     * Horizontal gap between book covers in the library grid.
     *
     * 12dp is the iReader / Google Books grid value: wide enough that two
     * neighbouring covers read as separate objects, narrow enough that a
     * 4-column phone grid still fits.
     */
    const val GRID_GAP_HORIZONTAL = 12

    /**
     * Vertical gap between cover rows.
     *
     * 16dp — larger than the horizontal gap, because a cover is a tall block:
     * the eye needs more separation between rows than between columns to pick
     * out a single book.
     */
    const val GRID_GAP_VERTICAL = md

    /** Gap between a cover and its title text. */
    const val COVER_TO_TEXT = sm

    /** Gap between text lines. */
    const val TEXT_LINE_GAP = xs

    const val GROUP_TITLE_GAP = sm
    const val GROUP_GAP = lg

    /**
     * Default horizontal reader inset, in dp.
     *
     * 20dp keeps body text off the screen edge. On a 360dp-wide phone this
     * leaves a 320dp column — roughly 18 CJK glyphs per line at the default
     * 17sp size, which is the classic Chinese book measure (15–20 characters).
     */
    const val READER_HORIZONTAL_DEFAULT = 20

    /**
     * Extra space between the status bar and the first line of body text, in dp.
     *
     * 8dp keeps the text clear of the clock/battery row without wasting a
     * whole line of the page.
     */
    const val READER_TOP_EXTRA = sm

    /**
     * Extra space between the last line of body text and the navigation bar, in dp.
     *
     * 16dp — larger than the top inset, because the bottom of a page is where
     * the thumb rests and the gesture bar sits; a cramped last line is
     * uncomfortable to read and easy to mis-tap.
     */
    const val READER_BOTTOM_EXTRA = md

    const val TOP_BAR_HEIGHT = 56

    /** The horizontal reader margins the settings page offers. */
    val READER_HORIZONTAL_OPTIONS = listOf(12, 16, 20, 24)

    /**
     * Gap between the reader page and the navigation-bar inset, in dp.
     *
     * Kept separate from [READER_BOTTOM_EXTRA] so the two can move
     * independently: the gesture bar is a *system* inset (it varies by
     * device), while this is our own breathing room.
     */
    const val READER_NAV_BAR_CLEARANCE = 4

    /**
     * Corner radius of the small format badge drawn on a book cover, in dp.
     *
     * 4dp: the badge is a 10dp-tall pill, and a larger radius would eat the
     * glyphs.
     */
    const val BADGE_RADIUS = 4

    /** Horizontal padding inside a format/progress badge, in dp. */
    const val BADGE_HORIZONTAL_PADDING = 4

    /** Vertical padding inside a format/progress badge, in dp. */
    const val BADGE_VERTICAL_PADDING = 2

    /** Gap between a badge and the cover edge, in dp. */
    const val BADGE_EDGE_INSET = 6
}
