package com.koodoreader.reader.reader

import com.koodoreader.core.data.entity.NoteEntity
import com.koodoreader.engine.annotate.HighlightPalette
import com.koodoreader.engine.annotate.SidemarkGeometry
import com.koodoreader.engine.layout.CfiAddressing
import com.koodoreader.engine.layout.LayoutResult

object NativeNoteHighlights {

    /**
     * blockIndex → ARGB highlight for lines on [pageIndex].
     *
     * @param dark whether the reader page is in dark mode — the 30%-alpha
     *   highlight tint is chosen per mode so the band stays visible on a night
     *   page (a 30%-alpha yellow over #121212 is effectively invisible).
     */
    fun blockColorsForPage(
        notes: List<NoteEntity>,
        layout: LayoutResult,
        pageIndex: Int,
        dark: Boolean = false,
    ): Map<Int, Long> = notes.mapNotNull { note ->
        val cfi = note.cfi ?: return@mapNotNull null
        val pos = CfiAddressing.fromCfi(cfi, layout) ?: return@mapNotNull null
        val line = layout.lineAt(pos) ?: return@mapNotNull null
        if (line.page != pageIndex) return@mapNotNull null
        line.blockIndex to HighlightPalette.paintArgb(note.color, dark)
    }.toMap()

    /** blockIndex → color for scroll mode (all pages in one layout). */
    fun blockColorsForLayout(
        notes: List<NoteEntity>,
        layout: LayoutResult,
        dark: Boolean = false,
    ): Map<Int, Long> = notes.mapNotNull { note ->
        val cfi = note.cfi ?: return@mapNotNull null
        val pos = CfiAddressing.fromCfi(cfi, layout) ?: return@mapNotNull null
        val line = layout.lineAt(pos) ?: return@mapNotNull null
        line.blockIndex to HighlightPalette.paintArgb(note.color, dark)
    }.toMap()

    /**
     * blockIndex → page-margin tick colour, derived from the band colours of
     * the same page (P1-10).
     *
     * Every highlight gets a tick. Deriving it from the band map rather than
     * re-resolving CFIs keeps the two in lockstep: the tick cannot point at a
     * line the band is not on, and a highlight that moves or is deleted takes
     * its tick with it.
     */
    fun noteMarkColorsForPage(
        notes: List<NoteEntity>,
        layout: LayoutResult,
        pageIndex: Int,
        dark: Boolean = false,
    ): Map<Int, Long> = SidemarkGeometry.tickColors(
        blockColorsForPage(notes, layout, pageIndex, dark),
        dark,
    )

    /** [noteMarkColorsForPage] for scroll mode (all pages in one layout). */
    fun noteMarkColorsForLayout(
        notes: List<NoteEntity>,
        layout: LayoutResult,
        dark: Boolean = false,
    ): Map<Int, Long> = SidemarkGeometry.tickColors(
        blockColorsForLayout(notes, layout, dark),
        dark,
    )
}
