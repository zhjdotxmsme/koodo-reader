package com.koodoreader.engine.text

import com.koodoreader.engine.layout.TextBlock
import com.koodoreader.engine.layout.SpineItem
import com.koodoreader.engine.layout.reader.ChapterDescriptor
import com.koodoreader.engine.layout.reader.ChapterLoader

/**
 * [ChapterLoader] over a [TextChapterIndex]: descriptors are cheap; [load] decodes
 * exactly one chapter byte range from [TextSource].
 */
class TextChapterLoader(
    private val source: TextSource,
    private val index: TextChapterIndex,
    private val decoder: WindowedTextDecoder,
) : ChapterLoader {

    override fun descriptors(): List<ChapterDescriptor> =
        index.spans.map { span ->
            ChapterDescriptor(
                id = span.id,
                title = span.title.ifEmpty { null },
                level = span.level,
                charCount = span.charCount,
            )
        }

    override fun load(id: String): SpineItem {
        val span = index.span(id)
        val start = span.byteStart + index.bomLength
        val text = decoder.decodeWindow(source, start, span.byteEnd)
        val order = index.indexOf(id)
        val blocks = if (text.isEmpty()) {
            emptyList()
        } else {
            listOf(TextBlock.of(text, elementIndex = 0))
        }
        return SpineItem(index = order, href = span.id, title = span.title.ifEmpty { null }, blocks = blocks)
    }

    override fun close() = Unit

    companion object {
        fun open(source: TextSource, index: TextChapterIndex): TextChapterLoader {
            val charset = StreamingTextIndex.resolveCharset(index.charset, StreamingTextIndex.Options.Default)
            return TextChapterLoader(source, index, WindowedTextDecoder(charset))
        }
    }
}
