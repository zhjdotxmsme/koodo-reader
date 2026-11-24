package com.koodoreader.reader.reader

import com.koodoreader.engine.layout.reader.TextLocation

interface AnnotationStore {
    fun saveHighlight(bookKey: String, location: TextLocation, color: Long)

    fun saveNote(bookKey: String, location: TextLocation, text: String, color: Long)

    fun addBookmark(bookKey: String, location: TextLocation, label: String)

    /**
     * Whether the given selection already carries a highlight.
     *
     * The selection sheet uses this to decide between offering the highlight
     * colours (not highlighted yet) and offering "Delete" (already
     * highlighted). Without it, tapping a colour on an already-highlighted
     * range would insert a second, overlapping highlight.
     *
     * Returns false when the store cannot answer (wrong book, no data).
     */
    fun isHighlighted(bookKey: String, location: TextLocation): Boolean
}
