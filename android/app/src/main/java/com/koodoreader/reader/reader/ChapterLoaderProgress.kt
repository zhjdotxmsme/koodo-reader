package com.koodoreader.reader.reader

import com.koodoreader.engine.layout.CfiAddressing
import com.koodoreader.engine.layout.LayoutPosition
import com.koodoreader.engine.layout.reader.ChapterLoader
import com.koodoreader.engine.layout.reader.ReaderFrame
import com.koodoreader.engine.layout.reader.TextLocation

object ChapterLoaderProgress {

    fun initialLocation(loader: ChapterLoader, storedCfi: String?): TextLocation {
        val descriptors = loader.descriptors()
        if (descriptors.isEmpty()) return TextLocation("", 0)
        val fallback = TextLocation(descriptors.first().id, 0)
        if (storedCfi.isNullOrBlank()) return fallback
        val pos = CfiAddressing.fromCfi(storedCfi) ?: return fallback
        val chapterId = chapterIdForSpineIndex(loader, pos.spineIndex) ?: return fallback
        return TextLocation(chapterId, pos.charOffset.coerceAtLeast(0))
    }

    fun cfiForLocation(loader: ChapterLoader, location: TextLocation): String? {
        val descriptors = loader.descriptors()
        val order = descriptors.indexOfFirst { it.id == location.chapterId }
        if (order < 0) return null
        val spineIndex = order + 1
        val item = loader.load(location.chapterId)
        var cursor = 0
        for (block in item.blocks) {
            val end = cursor + block.text.length
            if (location.start <= end) {
                val charOffset = (location.start - cursor).coerceAtLeast(0)
                return CfiAddressing.toCfi(LayoutPosition(spineIndex, block.elementIndex, charOffset))
            }
            cursor = end + 1
        }
        return CfiAddressing.toCfi(LayoutPosition(spineIndex, -1, 0))
    }

    fun excerptAt(loader: ChapterLoader, location: TextLocation, maxLen: Int = 240): String {
        val item = runCatching { loader.load(location.chapterId) }.getOrNull() ?: return ""
        val text = item.blocks.joinToString("\n") { it.text }
        val start = location.start.coerceIn(0, text.length)
        val end = (location.end.coerceAtLeast(start)).coerceAtMost(text.length)
        return text.substring(start, end).take(maxLen)
    }

    fun cfiForFrame(loader: ChapterLoader, frame: ReaderFrame): String? {
        val descriptors = loader.descriptors()
        val order = descriptors.indexOfFirst { it.id == frame.chapterId }
        if (order < 0) return null
        val spineIndex = order + 1
        val loc = frame.location
        val block = frame.layout.blocks.firstOrNull { block ->
            loc.start in block.globalStart..(block.globalStart + block.charCount)
        }
        val elementIndex = block?.elementIndex ?: 2
        val charOffset = (loc.start - (block?.globalStart ?: 0)).coerceAtLeast(0)
        return CfiAddressing.toCfi(LayoutPosition(spineIndex, elementIndex, charOffset))
    }

    fun chapterBarLabel(loader: ChapterLoader, chapterId: String): String {
        val descriptors = loader.descriptors()
        val index = descriptors.indexOfFirst { it.id == chapterId }
        if (index < 0) return ""
        val title = descriptors[index].title?.takeIf { it.isNotBlank() } ?: chapterId
        return "c ${index + 1}/${descriptors.size} · $title"
    }

    fun progressFraction(loader: ChapterLoader, chapterId: String): Double =
        progressFraction(loader, TextLocation(chapterId, 0))

    /** Chapter index plus the offset inside that chapter, over the chapter count. */
    fun progressFraction(loader: ChapterLoader, location: TextLocation): Double {
        val descriptors = loader.descriptors()
        if (descriptors.isEmpty()) return 0.0
        val index = descriptors.indexOfFirst { it.id == location.chapterId }
        if (index < 0) return 0.0
        val length = runCatching {
            loader.load(location.chapterId).blocks.sumOf { it.text.length }
        }.getOrDefault(0).coerceAtLeast(1)
        val within = location.start.coerceIn(0, length).toDouble() / length.toDouble()
        return (index + within) / descriptors.size.toDouble()
    }

    fun pageLabel(frame: ReaderFrame): String {
        val pages = frame.layout.pages
        if (pages.isEmpty()) return ""
        val index = pages.indices.lastOrNull { pageIndex ->
            val lines = pages[pageIndex].lines
            if (lines.isEmpty()) return@lastOrNull false
            val last = lines.last()
            val block = frame.layout.blocks.getOrNull(last.blockIndex) ?: return@lastOrNull false
            frame.location.start < block.globalStart + last.end
        } ?: pages.lastIndex
        return "p ${index + 1}/${pages.size}"
    }

    private fun chapterIdForSpineIndex(loader: ChapterLoader, spineIndex: Int): String? {
        val descriptors = loader.descriptors()
        descriptors.getOrNull(spineIndex - 1)?.let { return it.id }
        return descriptors.getOrNull(spineIndex)?.id
    }
}
