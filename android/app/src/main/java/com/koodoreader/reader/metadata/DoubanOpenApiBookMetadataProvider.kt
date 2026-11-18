package com.koodoreader.reader.metadata

import com.koodoreader.feature.translate.HttpTransport
import com.koodoreader.feature.translate.JsonValue
import com.koodoreader.feature.translate.MiniJson

/**
 * 豆瓣开放平台 ISBN 接口 `api.douban.com/v2/book/isbn/{isbn}`。
 * 无 API Key 时多数请求会失败，链式检索会自动 fallback 到其它源。
 */
object DoubanOpenApiBookMetadataProvider : BookMetadataSearchProvider {
    override val id: String = "douban_api"

    private const val BASE = "https://api.douban.com/v2/book/isbn"

    override suspend fun search(
        transport: HttpTransport,
        request: MetadataSearchRequest,
    ): List<FetchedBookMetadata> {
        val isbn = request.normalizedIsbn() ?: return emptyList()
        val body = httpGetText(
            transport,
            "$BASE/$isbn",
            mapOf(
                "User-Agent" to METADATA_USER_AGENT,
                "Accept" to "application/json",
            ),
        ) ?: return emptyList()
        val root = MiniJson.parse(body) as? JsonValue.JsonObject ?: return emptyList()
        if (root.stringField("code") != null) return emptyList()
        val name = root.stringField("title")?.trim().orEmpty()
        if (name.isEmpty()) return emptyList()
        val authors = root.stringListField("author")
        val author = authors.joinToString(", ").ifEmpty {
            root.stringField("author")?.trim().orEmpty()
        }
        val cover = root.stringField("image")
            ?.trim()
            ?.replace("\\", "")
            .orEmpty()
        val publisher = root.stringField("publisher").orEmpty().trim()
        val description = root.stringField("summary").orEmpty().trim()
        return listOf(
            FetchedBookMetadata(
                name = name,
                author = author,
                publisher = publisher,
                description = description.take(800),
                coverUrl = cover,
                isbn = isbn,
                source = id,
            ),
        )
    }
}