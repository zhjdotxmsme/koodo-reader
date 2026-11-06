package com.koodoreader.reader.reader

import com.koodoreader.engine.layout.PaginatorOptions
import com.koodoreader.engine.layout.TextMeasurer
import com.koodoreader.engine.layout.reader.ChapterLoader
import com.koodoreader.engine.layout.reader.NativeTextSession
import com.koodoreader.engine.layout.reader.PageRenderMode
import com.koodoreader.engine.layout.reader.PageRenderer
import com.koodoreader.engine.layout.reader.ReaderFrame
import com.koodoreader.engine.layout.reader.ReaderView
import com.koodoreader.engine.layout.reader.TextLocation

/**
 * Native text reading coordinator: one chapter per layout pass via [NativeTextSession].
 */
class TextReaderViewModel(
    val loader: ChapterLoader,
    @Suppress("unused")
    private val annotations: AnnotationStore,
    private val measurer: TextMeasurer,
    private var viewportWidthPx: Float = 400f,
    private var viewportHeightPx: Float = 700f,
) {
    private var session: NativeTextSession? = null
    private var fontScaleInternal: Float = 1f
    private var layoutOptions: PaginatorOptions = PaginatorOptions()
    var renderMode: PageRenderMode = PageRenderMode.PAGED
        private set

    val fontScale: Float get() = fontScaleInternal

    val currentFrame: ReaderFrame?
        get() = session?.current

    var currentLocation: TextLocation = TextLocation("", 0)
        private set

    fun setLayoutOptions(options: PaginatorOptions) {
        layoutOptions = options
    }

    fun open(location: TextLocation) {
        closeSession()
        val options = layoutOptions.copy(
            fontSizePx = layoutOptions.fontSizePx * fontScaleInternal,
        )
        session = NativeTextSession(loader, measurer, options).also { s ->
            s.openAt(location, viewportWidthPx, viewportHeightPx)
            currentLocation = s.current.location
        }
    }

    fun setViewport(width: Float, height: Float) {
        if (width <= 0f || height <= 0f) return
        if (viewportWidthPx == width && viewportHeightPx == height) return
        viewportWidthPx = width
        viewportHeightPx = height
        if (currentLocation.chapterId.isNotEmpty()) {
            open(currentLocation)
        }
    }

    fun setRenderMode(mode: PageRenderMode) {
        renderMode = mode
    }

    fun readerView(): ReaderView? {
        val frame = currentFrame ?: return null
        return PageRenderer.render(frame, renderMode)
    }

    fun setFontScale(scale: Float) {
        val anchor = currentLocation
        fontScaleInternal = scale
        if (anchor.chapterId.isEmpty()) return
        open(TextLocation(anchor.chapterId, anchor.start))
    }

    fun goNext() {
        session?.next()?.let { currentLocation = it.location }
    }

    fun goPrevious() {
        session?.previous()?.let { currentLocation = it.location }
    }

    fun hasNextChapter(): Boolean {
        val chapterId = currentFrame?.chapterId ?: return false
        val descriptors = loader.descriptors()
        val index = descriptors.indexOfFirst { it.id == chapterId }
        return index >= 0 && index + 1 < descriptors.size
    }

    fun hasPreviousChapter(): Boolean {
        val chapterId = currentFrame?.chapterId ?: return false
        val descriptors = loader.descriptors()
        val index = descriptors.indexOfFirst { it.id == chapterId }
        return index > 0
    }

    fun close() {
        closeSession()
    }

    private fun closeSession() {
        session?.close()
        session = null
    }
}
