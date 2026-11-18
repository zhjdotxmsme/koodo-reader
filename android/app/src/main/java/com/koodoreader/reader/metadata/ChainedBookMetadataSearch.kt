package com.koodoreader.reader.metadata

import com.koodoreader.feature.translate.HttpTransport

/**
 * 依次请求多个公开书目源；单个失败或空结果时继续下一个，合并去重后返回。
 * 有 ISBN 时优先：豆瓣 API → Open Library → Google Books → 豆瓣联想 → 书名检索。
 */
object ChainedBookMetadataSearch {

    private const val MAX_TOTAL = 12

    private val isbnFirst: List<BookMetadataSearchProvider> = listOf(
        DoubanOpenApiBookMetadataProvider,
        OpenLibraryBookMetadataProvider,
        GoogleBooksBookMetadataProvider,
        DoubanBookMetadataProvider,
    )

    private val titleFirst: List<BookMetadataSearchProvider> = listOf(
        DoubanBookMetadataProvider,
        OpenLibraryBookMetadataProvider,
        GoogleBooksBookMetadataProvider,
    )

    suspend fun search(
        transport: HttpTransport,
        request: MetadataSearchRequest,
    ): List<FetchedBookMetadata> {
        if (!request.hasUsableQuery()) return emptyList()
        val providers = if (request.normalizedIsbn() != null) isbnFirst else titleFirst
        val merged = LinkedHashMap<String, FetchedBookMetadata>()
        for (provider in providers) {
            val batch = runCatching {
                provider.search(transport, request)
            }.getOrElse { emptyList() }
            for (item in batch) {
                val key = dedupeKey(item)
                if (!merged.containsKey(key)) {
                    merged[key] = item
                }
            }
            if (merged.size >= MAX_TOTAL) break
        }
        return merged.values.take(MAX_TOTAL).toList()
    }

    internal fun dedupeKey(item: FetchedBookMetadata): String {
        val isbn = item.isbn.trim().lowercase()
        if (isbn.isNotEmpty()) return "isbn:$isbn"
        val n = item.name.trim().lowercase()
        val a = item.author.trim().lowercase()
        return "$n|$a"
    }
}