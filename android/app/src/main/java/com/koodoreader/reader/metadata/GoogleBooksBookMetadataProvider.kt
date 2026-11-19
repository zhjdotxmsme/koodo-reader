package com.koodoreader.reader.metadata

import com.koodoreader.feature.translate.HttpTransport
import com.koodoreader.feature.translate.JsonValue
import com.koodoreader.feature.translate.MiniJson

/** Google Books volumes API（公开查询，无需 Key；国内网络可能不可用）。 */
object GoogleBooksBookMetadataProvider : BookMetadataSearchProvider {
    override val id: String = "google_books"

    private const val BASE = "https://www.googleapis.com/books/v1/volumes"
    private const val MAX = 8

    override suspend fun search(
        transport: HttpTransport,
        request: MetadataSearchRequest,
    ): List<FetchedBookMetadata> {
        val normalizedIsbn = request.normalizedIsbn()
        val q = if (normalizedIsbn != null) {
            "isbn:$normalizedIsbn"
        } else {
            val t = request.title.trim()
            val a = request.author.trim()
            if (t.isEmpty()) return emptyList()
            buildString {
                // A SPACE is Google's canonical AND separator in `q`; a literal
                // "+" would be percent-encoded to %2B and break the query.
                if (t.isNotEmpty()) append("intitle:").append(t)
                if (a.isNotEmpty()) {
                    if (isNotEmpty()) append(' ')
                    append("inauthor:").append(a)
                }
            }
        }
        val body = httpGetText(
            transport,
            "$BASE?q=${urlEncode(q)}&maxResults=$MAX",
            mapOf("User-Agent" to METADATA_USER_AGENT),
        ) ?: return emptyList()
        val root = MiniJson.parse(body) as? JsonValue.JsonObject ?: return emptyList()
        val items = root.fields["items"] as? JsonValue.JsonArray ?: return emptyList()
        val out = ArrayList<FetchedBookMetadata>()
        val fallbackIsbn = request.normalizedIsbn().orEmpty()
        for (item in items.items.take(MAX)) {
            val obj = item as? JsonValue.JsonObject ?: continue
            val info = obj.fields["volumeInfo"] as? JsonValue.JsonObject ?: continue
            val name = info.stringField("title")?.trim().orEmpty()
            if (name.isEmpty()) continue
            val authors = info.stringListField("authors")
            val isbn = (info.fields["industryIdentifiers"] as? JsonValue.JsonArray)
                ?.items
                ?.mapNotNull { it as? JsonValue.JsonObject }
                ?.firstNotNullOfOrNull { id ->
                    val type = id.stringField("type").orEmpty()
                    val value = id.stringField("identifier").orEmpty()
                    if (type.contains("ISBN", ignoreCase = true)) value else null
                }
                ?: fallbackIsbn
            val links = info.fields["imageLinks"] as? JsonValue.JsonObject
            val cover = links?.stringField("thumbnail")
                ?: links?.stringField("smallThumbnail")
                ?: ""
            out += FetchedBookMetadata(
                name = name,
                author = authors.firstOrNull().orEmpty(),
                publisher = info.stringField("publisher").orEmpty(),
                description = info.stringField("description").orEmpty().take(500),
                coverUrl = cover.replace("http://", "https://"),
                isbn = isbn,
                source = id,
            )
        }
        return out
    }
}