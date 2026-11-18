package com.koodoreader.reader.metadata

data class MetadataSearchRequest(
    val title: String,
    val author: String,
    val isbn: String = "",
) {
    fun normalizedIsbn(): String? {
        val compact = isbn.trim().replace("-", "").replace(" ", "")
        return compact.takeIf { MetadataSearchQuery.looksLikeIsbn(compact) }
    }

    fun hasUsableQuery(): Boolean =
        normalizedIsbn() != null ||
            MetadataSearchQuery.build(title, author).isNotEmpty()
}