package com.koodoreader.reader.reader

import com.koodoreader.engine.docx.DocxDocument
import com.koodoreader.engine.layout.SpineItem
import com.koodoreader.engine.layout.reader.ChapterDescriptor
import com.koodoreader.engine.layout.reader.ChapterLoader
import java.io.File

class DocxChapterLoader(
    private val document: DocxDocument.Result,
    private val fileLabel: String,
) : ChapterLoader {

    override fun descriptors(): List<ChapterDescriptor> {
        val title = document.title?.takeIf { it.isNotBlank() } ?: fileLabel
        val chars = document.blocks.sumOf { it.text.length }
        return listOf(
            ChapterDescriptor(id = "docx:0", title = title, level = 1, charCount = chars),
        )
    }

    override fun load(id: String): SpineItem {
        require(id == "docx:0") { "unknown chapter id: $id" }
        return SpineItem(index = 0, href = "docx:0", blocks = document.blocks)
    }

    override fun close() = Unit

    companion object {
        fun open(file: File): DocxChapterLoader {
            val result = DocxDocument.fromFile(file)
            return DocxChapterLoader(result, file.name.substringBeforeLast('.'))
        }
    }
}
