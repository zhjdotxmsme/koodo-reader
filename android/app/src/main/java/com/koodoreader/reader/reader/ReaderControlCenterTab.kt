package com.koodoreader.reader.reader

/**
 * Tabs inside [ReaderControlCenterSheet]. JVM tests pin visibility per [ReaderFormatKind].
 */
enum class ReaderControlCenterTab {
    APPEARANCE,
    LAYOUT,
    PDF,
    ADVANCED,
    ;

    /** i18n key passed to [com.koodoreader.reader.shell.t]. */
    fun titleKey(): String = when (this) {
        APPEARANCE -> "Appearance"
        LAYOUT -> "Layout"
        PDF -> "PDF display"
        ADVANCED -> "Advanced"
    }
}

object ReaderControlCenterTabs {

    fun forFormat(format: ReaderFormatKind, useFoliate: Boolean = true): List<ReaderControlCenterTab> =
        when (format) {
            ReaderFormatKind.PDF -> listOf(
                ReaderControlCenterTab.APPEARANCE,
                ReaderControlCenterTab.LAYOUT,
                ReaderControlCenterTab.PDF,
                ReaderControlCenterTab.ADVANCED,
            )
            ReaderFormatKind.REFLOW -> buildList {
                add(ReaderControlCenterTab.APPEARANCE)
                add(ReaderControlCenterTab.LAYOUT)
                add(ReaderControlCenterTab.ADVANCED)
            }
            ReaderFormatKind.TEXT,
            ReaderFormatKind.COMIC,
            -> listOf(
                ReaderControlCenterTab.APPEARANCE,
                ReaderControlCenterTab.LAYOUT,
                ReaderControlCenterTab.ADVANCED,
            )
        }

    fun defaultTab(
        format: ReaderFormatKind,
        initial: ReaderControlCenterTab? = null,
    ): ReaderControlCenterTab {
        val tabs = forFormat(format)
        if (initial != null && initial in tabs) return initial
        return tabs.first()
    }
}