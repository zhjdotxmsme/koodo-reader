package com.koodoreader.reader.metadata

import com.koodoreader.feature.translate.HttpTransport
import com.koodoreader.feature.translate.JsonValue
import com.koodoreader.feature.translate.MiniJson

/** https://openlibrary.org 公开搜索 API（无需 Key）。 */
object OpenLibraryBookMetadataProvider : BookMetadataSearchProvider {
    override val id: String = "open_library"

    private const val BASE = "https://openlibrary.org"
    private const val MAX = 8

    override suspend fun search(
        transport: HttpTransport,
        request: MetadataSearchRequest,
    ): List<FetchedBookMetadata> {
        request.normalizedIsbn()?.let { return searchByIsbn(transport, it) }
        val t = request.title.trim()
        val a = request.author.trim()
        if (t.isEmpty()) return emptyList()
        var url = "$BASE/search.json?title=${urlEncode(t)}&limit=$MAX"
        if (a.isNotEmpty()) url += "&author=${urlEncode(a)}"
        val body = httpGetText(
            transport,
            url,
            mapOf("User-Agent" to METADATA_USER_AGENT),
        ) ?: return emptyList()
        val root = MiniJson.parse(body) as? JsonValue.JsonObject ?: return emptyList()
        val docs = root.fields["docs"] as? JsonValue.JsonArray ?: return emptyList()
        val out = ArrayList<FetchedBookMetadata>()
        for (item in docs.items.take(MAX)) {
            val doc = item as? JsonValue.JsonObject ?: continue
            val name = doc.stringField("title")?.trim().orEmpty()
            if (name.isEmpty()) continue
            val authors = doc.stringListField("author_name")
            val publishers = doc.stringListField("publisher")
            val isbns = doc.stringListField("isbn")
            val coverId = doc.intField("cover_i")
            val cover = if (coverId != null) "https://covers.openlibrary.org/b/id/$coverId-L.jpg" else ""
            out += FetchedBookMetadata(
                name = name,
                author = authors.firstOrNull().orEmpty(),
                publisher = publishers.firstOrNull().orEmpty(),
                description = "",
                coverUrl = cover,
                isbn = isbns.firstOrNull().orEmpty(),
                source = id,
            )
        }
        return out
    }

    private suspend fun searchByIsbn(transport: HttpTransport, isbn: String): List<FetchedBookMetadata> {
        val body = httpGetText(
            transport,
            "$BASE/api/books?bibkeys=ISBN:$isbn&format=json&jscmd=data",
            mapOf("User-Agent" to METADATA_USER_AGENT),
        ) ?: return emptyList()
        val root = MiniJson.parse(body) as? JsonValue.JsonObject ?: return emptyList()
        val book = root.fields["ISBN:$isbn"] as? JsonValue.JsonObject ?: return emptyList()
        val title = book.stringField("title")?.trim().orEmpty()
        if (title.isEmpty()) return emptyList()
        val authors = (book.fields["authors"] as? JsonValue.JsonArray)?.items.orEmpty()
        val authorName = authors.firstOrNull()
            ?.let { it as? JsonValue.JsonObject }
            ?.stringField("name")
            .orEmpty()
        val publishers = (book.fields["publishers"] as? JsonValue.JsonArray)?.items.orEmpty()
        val publisher = publishers.firstOrNull()
            ?.let { it as? JsonValue.JsonObject }
            ?.stringField("name")
            .orEmpty()
        val coverObj = book.fields["cover"] as? JsonValue.JsonObject
        val cover = coverObj?.stringField("large")
            ?: coverObj?.stringField("medium")
            ?: coverObj?.stringField("small")
            ?: ""
        val description = book.stringField("notes")
            ?: book.stringField("subtitle")
            ?: ""
        return listOf(
            FetchedBookMetadata(
                name = title,
                author = authorName,
                publisher = publisher,
                description = description,
                coverUrl = cover,
                isbn = isbn,
                source = id,
            ),
        )
    }
}