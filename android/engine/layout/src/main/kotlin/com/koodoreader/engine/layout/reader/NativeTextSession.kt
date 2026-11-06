package com.koodoreader.engine.layout.reader

import com.koodoreader.engine.layout.EpubDocument
import com.koodoreader.engine.layout.LayoutEngine
import com.koodoreader.engine.layout.LayoutResult
import com.koodoreader.engine.layout.LayoutTokens
import com.koodoreader.engine.layout.PaginatorOptions
import com.koodoreader.engine.layout.TextMeasurer

class NativeTextSession(
    private val loader: ChapterLoader,
    private val measurer: TextMeasurer,
    private val options: PaginatorOptions,
) : AutoCloseable {
    lateinit var current: ReaderFrame
        private set

    private var pageIndex = 0
    private var viewportWidthPx = 0f
    private var viewportHeightPx = 0f

    fun openAt(location: TextLocation, width: Float, height: Float) {
        require(location.start >= 0) { "Negative offset: ${location.start}" }
        require(location.end >= location.start) {
            "Inverted range: ${location.start}..${location.end}"
        }
        val descriptor = loader.descriptors().firstOrNull { it.id == location.chapterId }
            ?: throw IllegalArgumentException("Unknown chapter: ${location.chapterId}")
        val start = location.start.coerceAtMost(descriptor.charCount)
        val chapter = loader.load(location.chapterId)
        val document = EpubDocument(listOf(chapter))
        val tokens = LayoutTokens(
            viewportWidthPx = width,
            viewportHeightPx = height,
            marginHorizontalPx = options.marginHorizontalPx,
            marginVerticalPx = options.marginVerticalPx,
            fontSizePx = options.fontSizePx,
            lineHeightMultiple = options.lineHeightMultiple,
        )
        val layout = LayoutEngine(measurer, options).layout(document, tokens)
        viewportWidthPx = width
        viewportHeightPx = height
        current = ReaderFrame(location.chapterId, layout, location.copy(start = start))
        pageIndex = pageIndexAt(current, start)
        current = current.copy(location = locationAt(current, pageIndex))
    }

    private fun pageIndexAt(frame: ReaderFrame, start: Int): Int {
        if (frame.layout.pages.isEmpty()) return 0
        for (index in frame.layout.pages.indices) {
            val lines = frame.layout.pages[index].lines
            if (lines.isEmpty()) continue
            val last = lines.last()
            val pageEnd = frame.layout.blocks[last.blockIndex].globalStart + last.end
            if (start < pageEnd) return index
        }
        return frame.layout.pages.lastIndex
    }

    fun next(): ReaderFrame {
        val frame = current
        if (pageIndex + 1 < frame.layout.pages.size) {
            pageIndex++
            current = frame.copy(location = locationAt(frame, pageIndex))
        } else {
            val descriptors = loader.descriptors()
            val chapterIndex = descriptors.indexOfFirst { it.id == frame.chapterId }
            if (chapterIndex >= 0 && chapterIndex + 1 < descriptors.size) {
                openAt(TextLocation(descriptors[chapterIndex + 1].id, 0), viewportWidthPx, viewportHeightPx)
                current = current.copy(location = locationAt(current, 0))
            }
        }
        return current
    }

    fun previous(): ReaderFrame {
        val frame = current
        if (pageIndex > 0) {
            pageIndex--
            current = frame.copy(location = locationAt(frame, pageIndex))
        } else {
            val descriptors = loader.descriptors()
            val chapterIndex = descriptors.indexOfFirst { it.id == frame.chapterId }
            if (chapterIndex > 0) {
                openAt(TextLocation(descriptors[chapterIndex - 1].id, 0), viewportWidthPx, viewportHeightPx)
                val last = current.layout.pages.lastIndex
                pageIndex = last.coerceAtLeast(0)
                current = current.copy(location = locationAt(current, pageIndex))
            }
        }
        return current
    }

    private fun locationAt(frame: ReaderFrame, index: Int): TextLocation {
        val lines = frame.layout.pages[index].lines
        if (lines.isEmpty()) return TextLocation(frame.chapterId, 0)
        val first = lines.first()
        val last = lines.last()
        val start = frame.layout.blocks[first.blockIndex].globalStart + first.start
        val end = frame.layout.blocks[last.blockIndex].globalStart + last.end
        return TextLocation(frame.chapterId, start, end)
    }

    /** Clears session state only; [ChapterLoader] is owned by the host screen. */
    override fun close() = Unit
}
