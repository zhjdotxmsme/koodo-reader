package com.koodoreader.reader.metadata

import com.koodoreader.feature.translate.HttpRequest
import com.koodoreader.feature.translate.HttpResponse
import com.koodoreader.feature.translate.HttpTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CnBookMetadataSearchTest {

    @Test
    fun `buildQuery prefers title and author`() {
        assertEquals("三体 刘慈欣", CnBookMetadataSearch.buildQuery("三体", "刘慈欣"))
        assertEquals("9787020002207", CnBookMetadataSearch.buildQuery("9787020002207", ""))
    }

    @Test
    fun `parse suggest json`() = runBlocking {
        val json = """
            [
              {"type":"b","id":"2567698","title":"三体","author_name":"刘慈欣",
               "url":"https://book.douban.com/subject/2567698/",
               "pic":"https://img3.doubanio.com/view/subject/s/public/s2768378.jpg"}
            ]
        """.trimIndent()
        val transport = object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse =
                HttpResponse(200, json)
        }
        val items = CnBookMetadataSearch.search(transport, "三体", "")
        assertEquals(1, items.size)
        assertEquals("三体", items[0].name)
        assertTrue(items[0].coverUrl.contains("doubanio.com"))
    }
}