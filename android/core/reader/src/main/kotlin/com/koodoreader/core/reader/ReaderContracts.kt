package com.koodoreader.core.reader

import java.io.File

/**
 * Pure JVM contracts between [core:reader] domain models and format engines.
 * Implementations live in `engine:*` / `core:importer`; UI must not parse books directly.
 */
data class ParsedBook(
    val title: String?,
    val author: String?,
    val chapters: List<ChapterDescriptor>,
)

data class ChapterDescriptor(
    val id: String,
    val title: String?,
    val level: Int,
)

data class ChapterContent(
    val id: String,
    val plainText: String,
)

data class TextContent(
    val text: String,
    val charCount: Int,
)

data class ReaderLayout(
    val fontScale: Float = 1f,
    val lineHeight: Float = 1.5f,
)

data class ReaderPage(
    val pageIndex: Int,
    val lineCount: Int,
)

interface BookParser {
    fun parse(file: File): ParsedBook
}

/** Domain chapter source; not [com.koodoreader.engine.layout.reader.ChapterLoader]. */
interface ReaderChapterSource {
    fun descriptors(): List<ChapterDescriptor>
    fun load(id: String): ChapterContent
}

interface TextExtractor {
    fun extract(chapter: ChapterContent): TextContent
}

interface PageRenderer {
    fun render(content: ChapterContent, settings: ReaderLayout): ReaderPage
}

interface AnnotationMapper {
    fun toStorageAnchor(bookKey: String, anchor: TextAnchor): String
}
