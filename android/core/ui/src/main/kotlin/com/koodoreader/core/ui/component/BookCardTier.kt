package com.koodoreader.core.ui.component

/**
 * How much information a library grid tile can carry (P1-14).
 *
 * The grid picks its column count from the window width, so on a phone the
 * same tile is ~104dp wide while on a tablet it can be 200dp+. Drawing both
 * identically wastes the tablet and crowds the phone: a 104dp tile with a
 * progress badge is a badge with a thumbnail behind it.
 */
enum class BookCardTier {
    /** Narrow tile: smaller title, no progress badge (it would cover the art). */
    COMPACT,

    /** Roomy tile: the full treatment. */
    REGULAR,
    ;

    companion object {
        /**
         * Below this width a tile drops the progress badge.
         *
         * A 3-column phone grid on a 360dp screen leaves ~104dp per tile; a
         * 2-column grid on a 7" tablet leaves ~330dp. 140dp sits between them
         * and above the 120dp comfortable minimum for two lines of body text.
         */
        const val REGULAR_MIN_WIDTH_DP = 140f

        fun forWidthDp(widthDp: Float): BookCardTier =
            if (widthDp.isFinite() && widthDp >= REGULAR_MIN_WIDTH_DP) REGULAR else COMPACT
    }
}
