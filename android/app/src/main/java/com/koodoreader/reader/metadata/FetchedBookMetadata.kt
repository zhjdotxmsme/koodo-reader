package com.koodoreader.reader.metadata

/** One bibliographic hit from a public metadata API (no AI). */
data class FetchedBookMetadata(
    val name: String,
    val author: String,
    val publisher: String,
    val description: String,
    val coverUrl: String = "",
    val isbn: String = "",
    /** Provider id for UI: douban, open_library, google_books */
    val source: String = "",
)