package com.koodoreader.reader.reader

import com.koodoreader.engine.layout.reader.TextLocation

interface AnnotationStore {
    fun saveHighlight(bookKey: String, location: TextLocation, color: Long)

    fun saveNote(bookKey: String, location: TextLocation, text: String, color: Long)

    fun addBookmark(bookKey: String, location: TextLocation, label: String)
}
