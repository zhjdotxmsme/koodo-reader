package com.koodoreader.reader.reader

import com.koodoreader.core.data.entity.NoteEntity
import com.koodoreader.engine.annotate.HighlightPalette
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
}
