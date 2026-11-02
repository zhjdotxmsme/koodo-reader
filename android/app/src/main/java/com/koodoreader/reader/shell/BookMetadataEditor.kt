package com.koodoreader.reader.shell

import com.koodoreader.core.data.entity.BookEntity
import com.koodoreader.core.importer.BookMetadata

object BookMetadataEditor {
    fun validate(book: BookEntity, draft: BookMetadata): BookEntity {
        val title = draft.name?.trim().orEmpty()
        require(title.isNotEmpty()) { "Book name must not be blank" }
        return book.copy(
            name = title,
            author = draft.author?.trim()?.ifEmpty { null },
            description = draft.description?.trim()?.ifEmpty { null },
            publisher = draft.publisher?.trim()?.ifEmpty { null },
        )
    }
}
