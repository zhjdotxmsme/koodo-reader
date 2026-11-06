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

    fun progressFraction(loader: ChapterLoader, chapterId: String): Double {
        val descriptors = loader.descriptors()
        val index = descriptors.indexOfFirst { it.id == chapterId }
        if (index < 0 || descriptors.isEmpty()) return 0.0
        return (index + 1).toDouble() / descriptors.size.toDouble()
    }

    private fun chapterIdForSpineIndex(loader: ChapterLoader, spineIndex: Int): String? {
        val descriptors = loader.descriptors()
        descriptors.getOrNull(spineIndex - 1)?.let { return it.id }
        return descriptors.getOrNull(spineIndex)?.id
    }
}
