package com.koodoreader.reader.metadata

import com.koodoreader.feature.translate.HttpRequest
import com.koodoreader.feature.translate.HttpResponse
import com.koodoreader.feature.translate.HttpTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainedBookMetadataSearchTest {

    @Test
    fun `fallback when douban fails`() = runBlocking {
        val olJson = """
            {"docs":[{"title":"The Hobbit","author_name":["Tolkien"],"cover_i":123}]}
        """.trimIndent()
        val transport = object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse = when {
                request.url.contains("douban.com") -> HttpResponse(503, "")
                request.url.contains("openlibrary.org") -> HttpResponse(200, olJson)
                else -> HttpResponse(404, "")
            }
        }
        val items = ChainedBookMetadataSearch.search(
            transport,
            MetadataSearchRequest("Hobbit", "", ""),
        )
        assertEquals(1, items.size)
        assertEquals("open_library", items[0].source)
    }

    @Test
    fun `isbn mode prefers open library when douban api fails`() = runBlocking {
        val olIsbn = """
            {"ISBN:9780451524935":{"title":"1984","authors":[{"name":"George Orwell"}],
             "publishers":[{"name":"Signet"}],"cover":{"large":"https://covers.example/x.jpg"}}}
        """.trimIndent()
        val transport = object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse = when {
                request.url.contains("api.douban.com") -> HttpResponse(403, """{"code":104,"msg":"invalid apikey"}""")
                request.url.contains("openlibrary.org/api/books") -> HttpResponse(200, olIsbn)
                else -> HttpResponse(404, "")
            }
        }
        val items = ChainedBookMetadataSearch.search(
            transport,
            MetadataSearchRequest("", "", "9780451524935"),
        )
        assertEquals(1, items.size)
        assertEquals("open_library", items[0].source)
        assertEquals("1984", items[0].name)
    }

    @Test
    fun dedupeKey_uses_isbn_when_present() {
        val key = ChainedBookMetadataSearch.dedupeKey(
            FetchedBookMetadata("A", "B", "", "", isbn = "9780451524935", source = "x"),
        )
        assertTrue(key.startsWith("isbn:"))
    }
}