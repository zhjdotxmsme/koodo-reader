package com.koodoreader.reader.metadata

import com.koodoreader.feature.translate.HttpRequest
import com.koodoreader.feature.translate.HttpResponse
import com.koodoreader.feature.translate.HttpTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenLibraryBookMetadataProviderTest {

    @Test
    fun `parse search json`() = runBlocking {
        val json = """
            {"numFound":1,"docs":[{"title":"1984","author_name":["George Orwell"],
             "publisher":["Secker"],"isbn":["9780451524935"],"cover_i":7222246}]}
        """.trimIndent()
        val transport = object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse =
                HttpResponse(200, json)
        }
        val items = OpenLibraryBookMetadataProvider.search(
            transport,
            MetadataSearchRequest("1984", "", ""),
        )
        assertEquals(1, items.size)
        assertEquals("George Orwell", items[0].author)
        assertEquals("open_library", items[0].source)
    }
}