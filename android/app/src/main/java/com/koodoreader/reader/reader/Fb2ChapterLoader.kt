package com.koodoreader.reader.reader

import com.koodoreader.engine.fb2.Fb2Document
import com.koodoreader.engine.layout.SpineItem
import com.koodoreader.engine.layout.reader.ChapterDescriptor
import com.koodoreader.engine.layout.reader.ChapterLoader
import java.io.File

/** FB2 → [ChapterLoader]; one chapter per file until section-aware TOC lands. */
class Fb2ChapterLoader(
    private val document: Fb2Document.Result,
    private val fileLabel: String,
) : ChapterLoader {

    override fun descriptors(): List<ChapterDescriptor> {
        val title = document.title?.takeIf { it.isNotBlank() } ?: fileLabel
        val chars = document.blocks.sumOf { it.text.length }
        return listOf(
            ChapterDescriptor(id = "fb2:0", title = title, level = 1, charCount = chars),
        )
    }

    override fun load(id: String): SpineItem {
        require(id == "fb2:0") { "unknown chapter id: $id" }
        return SpineItem(index = 0, href = "fb2:0", blocks = document.blocks)
    }

    override fun close() = Unit

    companion object {
        fun open(file: File): Fb2ChapterLoader {
            val result = Fb2Document.fromBytes(file.readBytes())
            val label = file.name.substringBeforeLast('.')
            return Fb2ChapterLoader(result, label)
        }
    }
}
