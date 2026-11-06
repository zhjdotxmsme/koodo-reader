package com.koodoreader.reader.reader

import com.koodoreader.engine.layout.reader.TextLocation

object NoOpAnnotationStore : AnnotationStore {
    override fun saveHighlight(bookKey: String, location: TextLocation, color: Long) = Unit

    override fun saveNote(bookKey: String, location: TextLocation, text: String, color: Long) = Unit

    override fun addBookmark(bookKey: String, location: TextLocation, label: String) = Unit
}
