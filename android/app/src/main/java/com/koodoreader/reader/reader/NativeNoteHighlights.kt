package com.koodoreader.reader.reader

import com.koodoreader.core.data.entity.NoteEntity
import com.koodoreader.engine.layout.CfiAddressing
import com.koodoreader.engine.layout.LayoutResult

object NativeNoteHighlights {

    /** blockIndex → ARGB highlight for lines on [pageIndex]. */
    fun blockColorsForPage(
        notes: List<NoteEntity>,
        layout: LayoutResult,
        pageIndex: Int,
    ): Map<Int, Long> = notes.mapNotNull { note ->
        val cfi = note.cfi ?: return@mapNotNull null
        val pos = CfiAddressing.fromCfi(cfi, layout) ?: return@mapNotNull null
        val line = layout.lineAt(pos) ?: return@mapNotNull null
        if (line.page != pageIndex) return@mapNotNull null
        line.blockIndex to (note.color ?: 0x99FFFFL)
    }.toMap()

    /** blockIndex → color for scroll mode (all pages in one layout). */
    fun blockColorsForLayout(
        notes: List<NoteEntity>,
        layout: LayoutResult,
    ): Map<Int, Long> = notes.mapNotNull { note ->
        val cfi = note.cfi ?: return@mapNotNull null
        val pos = CfiAddressing.fromCfi(cfi, layout) ?: return@mapNotNull null
        val line = layout.lineAt(pos) ?: return@mapNotNull null
        line.blockIndex to (note.color ?: 0x99FFFFL)
    }.toMap()
}
