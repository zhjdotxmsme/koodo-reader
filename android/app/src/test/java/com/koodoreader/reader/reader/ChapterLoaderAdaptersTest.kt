package com.koodoreader.reader.reader

import com.koodoreader.engine.mobi.MobiBook
import com.koodoreader.engine.mobi.MobiCompression
import com.koodoreader.engine.mobi.MobiMetadata
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChapterLoaderAdaptersTest {

    @Test
    fun `mobi adapter exposes stable chapter ids and readable body`() {
        val book = parsedMobiFixture()
        val loader = MobiChapterLoader(book)
        val chapter = loader.load(loader.descriptors().first().id)
        assertTrue(chapter.blocks.any { it.text.contains("正文") })
        loader.close()
    }

    private fun parsedMobiFixture() = MobiBook(
        metadata = MobiMetadata(title = "测试"),
        text = "<h1>第一章</h1><p>正文段落一。</p><h2>第二节</h2><p>正文段落二。</p>",
        resources = emptyList(),
        cover = null,
        compression = MobiCompression.NONE,
        encoding = "UTF-8",
        pdbName = "test",
    )
}
