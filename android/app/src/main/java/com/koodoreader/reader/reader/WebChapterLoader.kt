package com.koodoreader.reader.reader

import com.koodoreader.engine.htmlbook.HtmlDocument
import com.koodoreader.engine.htmlbook.MhtmlDocument
import com.koodoreader.engine.layout.SpineItem
import com.koodoreader.engine.layout.reader.ChapterDescriptor
import com.koodoreader.engine.layout.reader.ChapterLoader
import java.io.File

class WebChapterLoader private constructor(
    private val blocks: List<com.koodoreader.engine.layout.TextBlock>,
    private val title: String,
) : ChapterLoader {

    override fun descriptors(): List<ChapterDescriptor> {
        val chars = blocks.sumOf { it.text.length }
        return listOf(ChapterDescriptor(id = "web:0", title = title, level = 1, charCount = chars))
    }

    override fun load(id: String): SpineItem {
        require(id == "web:0") { "unknown chapter id: $id" }
        return SpineItem(index = 0, href = "web:0", blocks = blocks)
    }

    override fun close() = Unit

    companion object {
        fun open(file: File): WebChapterLoader {
            val ext = file.name.substringAfterLast('.', "").lowercase()
            val bytes = file.readBytes()
            val (blocks, docTitle) = if (ext == "mhtml" || ext == "mht") {
                MhtmlDocument.blocks(bytes) to null
            } else {
                val result = HtmlDocument.fromBytes(bytes)
                result.blocks to result.title
            }
            val label = docTitle?.takeIf { it.isNotBlank() }
                ?: file.name.substringBeforeLast('.')
            return WebChapterLoader(blocks, label)
        }
    }
}
