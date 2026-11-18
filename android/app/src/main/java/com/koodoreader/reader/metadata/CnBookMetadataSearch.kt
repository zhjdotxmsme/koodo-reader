package com.koodoreader.reader.metadata

import com.koodoreader.feature.translate.HttpTransport

/** @deprecated 请使用 [PublicMetadataSearch]；保留兼容单测与旧调用。 */
object CnBookMetadataSearch {

    @Deprecated("Use MetadataSearchQuery.build")
    fun buildQuery(title: String, author: String): String =
        MetadataSearchQuery.build(title, author)

    suspend fun search(
        transport: HttpTransport,
        title: String,
        author: String,
    ): List<FetchedBookMetadata> =
        DoubanBookMetadataProvider.search(transport, MetadataSearchRequest(title, author))
}