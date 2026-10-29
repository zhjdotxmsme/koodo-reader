package com.koodoreader.reader.shell

/**
 * Foliate spread (dual-page) vs PDF side-by-side both use [ReaderFeatureState.doublePage].
 * For reflowable books, spread needs paginated flow and two columns — not the same
 * knob as [ReaderFeatureState.columnsPerPage] on a single page.
 */
object ReaderSpreadPolicy {

    /** Dual-page spread requires paginated mode in foliate-js. */
    fun foliateScrolled(features: ReaderFeatureState): Boolean =
        if (features.doublePage) false else features.scrolled

    fun foliateMaxColumnCount(features: ReaderFeatureState): Int =
        if (features.doublePage) 2
        else ReaderPerformancePolicy.effectiveMaxColumns(features).coerceIn(1, 2)
}
