package com.koodoreader.reader.metadata

import com.koodoreader.feature.translate.HttpTransport
import com.koodoreader.feature.translate.JsonValue
import com.koodoreader.feature.translate.MiniJson

/** 豆瓣读书 subject_suggest（国内，非官方开放接口）。 */
object DoubanBookMetadataProvider : BookMetadataSearchProvider {
    override val id: String = "douban"

    private const val SUGGEST = "https://book.douban.com/j/subject_suggest"
    private const val MAX = 8

    override suspend fun search(
        transport: HttpTransport,
        request: MetadataSearchRequest,
    ): List<FetchedBookMetadata> {
        val isbn = request.normalizedIsbn()
        val query = when {
            isbn != null -> isbn
            else -> MetadataSearchQuery.build(request.title, request.author)
        }
        if (query.isEmpty()) return emptyList()
        val body = httpGetText(
            transport,
            "$SUGGEST?q=${urlEncode(query)}",
            mapOf(
                "User-Agent" to METADATA_USER_AGENT,
                "Referer" to "https://book.douban.com/",
                "Accept" to "application/json",
            ),
        ) ?: return emptyList()
        val root = MiniJson.parse(body) as? JsonValue.JsonArray ?: return emptyList()
        val out = ArrayList<FetchedBookMetadata>()
        for (item in root.items.take(MAX)) {
            val obj = item as? JsonValue.JsonObject ?: continue
            val type = obj.stringField("type")
            if (type != null && type != "b") continue
            val name = obj.stringField("title")?.trim().orEmpty()
            if (name.isEmpty()) continue
            val bookAuthor = obj.stringField("author_name").orEmpty().trim()
            val cover = obj.stringField("pic")
                ?.trim()
                ?.replace("\\", "")
                .orEmpty()
            out += FetchedBookMetadata(
                name = name,
                author = bookAuthor,
                publisher = "",
                description = "",
                coverUrl = cover,
                isbn = isbn.orEmpty(),
                source = id,
            )
        }
        return out
    }
}