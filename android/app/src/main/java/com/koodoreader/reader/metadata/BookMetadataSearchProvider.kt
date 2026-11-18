package com.koodoreader.reader.metadata

import com.koodoreader.feature.translate.HttpTransport

interface BookMetadataSearchProvider {
    val id: String
    suspend fun search(
        transport: HttpTransport,
        request: MetadataSearchRequest,
    ): List<FetchedBookMetadata>
}