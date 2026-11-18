package com.koodoreader.reader.metadata

import com.koodoreader.feature.translate.HttpTransport
import java.io.File

/** 多公开源链式检索入口（带可选本地缓存）。 */
object PublicMetadataSearch {

    suspend fun search(
        transport: HttpTransport,
        title: String,
        author: String,
        isbn: String = "",
        cacheDir: File? = null,
    ): List<FetchedBookMetadata> {
        val request = MetadataSearchRequest(title, author, isbn)
        if (!request.hasUsableQuery()) return emptyList()
        MetadataSearchCache.read(cacheDir, request)?.let { return it }
        val items = ChainedBookMetadataSearch.search(transport, request)
        MetadataSearchCache.write(cacheDir, request, items)
        return items
    }
}