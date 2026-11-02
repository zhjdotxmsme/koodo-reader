package com.koodoreader.core.importer

/** Bookshelf fields read from a book's own metadata. */
data class BookMetadata(
    val name: String?,
    val author: String?,
    val description: String?,
    val publisher: String?,
)
