package com.koodoreader.reader.reader

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import com.koodoreader.core.designsystem.MotionTokens

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.koodoreader.core.data.entity.NoteEntity
import com.koodoreader.engine.layout.PaginatorOptions
import com.koodoreader.engine.layout.reader.ChapterLoader
import com.koodoreader.engine.layout.reader.ReaderFrame
import com.koodoreader.engine.layout.reader.PageRenderMode
import com.koodoreader.engine.layout.reader.ReaderView
import com.koodoreader.engine.layout.reader.TextLocation
import com.koodoreader.reader.epubhost.AndroidTextMeasurer

/**
 * Compose host for [TextReaderViewModel]: paged or scrolled native text from a [ChapterLoader].
 */
@Composable
fun NativeTextReaderScreen(
    loader: ChapterLoader,
    initialLocation: TextLocation,
    scrolled: Boolean,
    modifier: Modifier = Modifier,
    fontScale: Float = 1f,
    layoutOptions: PaginatorOptions = PaginatorOptions(),
    annotationStore: AnnotationStore = NoOpAnnotationStore,
    notesForHighlights: List<NoteEntity> = emptyList(),
    control: TextReaderScreenControl? = null,
    onLocationChanged: (TextLocation, ReaderFrame?) -> Unit = { _, _ -> },
    onLineSelected: () -> Unit = {},
    onToggleChrome: () -> Unit = {},
) {
    val density = LocalDensity.current.density
    val measurer = remember(density) { AndroidTextMeasurer(density) }
    val viewModel = remember(loader, measurer) {
        TextReaderViewModel(loader, annotationStore, measurer)
    }
    var readerView by remember { mutableStateOf<ReaderView?>(null) }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        LaunchedEffect(widthPx, heightPx, initialLocation, scrolled, fontScale, layoutOptions) {
            if (widthPx <= 0f || heightPx <= 0f) return@LaunchedEffect
            viewModel.setViewport(widthPx, heightPx)
            viewModel.setLayoutOptions(layoutOptions)
            viewModel.setRenderMode(if (scrolled) PageRenderMode.SCROLL else PageRenderMode.PAGED)
            viewModel.open(initialLocation)
            if (fontScale != 1f) viewModel.setFontScale(fontScale)
            readerView = viewModel.readerView()
            onLocationChanged(viewModel.currentLocation, viewModel.currentFrame)
        }

        androidx.compose.runtime.DisposableEffect(viewModel) {
            onDispose { viewModel.close() }
        }

        androidx.compose.runtime.DisposableEffect(viewModel, control, loader) {
            fun syncAfterNav() {
                readerView = viewModel.readerView()
                onLocationChanged(viewModel.currentLocation, viewModel.currentFrame)
            }
            control?.goPrevious = { viewModel.goPrevious(); syncAfterNav() }
            control?.goNext = { viewModel.goNext(); syncAfterNav() }
            control?.jumpTo = { loc ->
                viewModel.open(loc)
                syncAfterNav()
            }
            control?.chapterLabel = {
                ChapterLoaderProgress.chapterBarLabel(loader, viewModel.currentLocation.chapterId)
            }
            control?.currentChapterId = { viewModel.currentLocation.chapterId }
            onDispose {
                control?.goPrevious = {}
                control?.goNext = {}
                control?.jumpTo = {}
                control?.chapterLabel = { "" }
                control?.currentChapterId = { "" }
                control?.selectionLocation = null
                control?.selectionText = ""
            }
        }

        val view = readerView
        var pageImages by remember { mutableStateOf<Map<String, android.graphics.Bitmap?>>(emptyMap()) }
        LaunchedEffect(view, loader) {
            val v = view ?: run {
                pageImages = emptyMap()
                return@LaunchedEffect
            }
            val imageLines = when (v.mode) {
                PageRenderMode.PAGED -> v.layout.lines.filter { it.page == v.page && it.imageSrc != null }
                PageRenderMode.SCROLL -> v.layout.lines.filter { it.imageSrc != null }
            }
            if (imageLines.isEmpty()) {
                pageImages = emptyMap()
                return@LaunchedEffect
            }
            pageImages = withContext(Dispatchers.IO) { loader.loadInlineImages(imageLines) }
        }
        val widthDp = (constraints.maxWidth / density).toInt()
        val tocViewModel = remember(loader) { TocViewModel(loader) }
        // EXPANDED 侧栏改为可收起：状态提升到这里（旋转等配置变化后保持）。
        var sidebarOpen by rememberSaveable { mutableStateOf(true) }
        AdaptiveReaderLayout(
            widthDp = widthDp,
            sidebarOpen = sidebarOpen,
            sidebar = {
                TocSidebar(
                    viewModel = tocViewModel,
                    currentChapterId = viewModel.currentLocation.chapterId,
                    onJump = { id ->
                        viewModel.open(TextLocation(id, 0))
                        readerView = viewModel.readerView()
                        onLocationChanged(viewModel.currentLocation, viewModel.currentFrame)
                    },
                    onClose = { sidebarOpen = false },
                )
            },
        ) {
        if (view == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else when (view.mode) {
            PageRenderMode.PAGED -> {
                // 翻页过渡：200ms 轻微位移+淡入淡出，方向跟随前进/后退。
                // 自绘分页排版属引擎层，不引入 Pager；targetState 直接取
                // ReaderView，出场帧仍渲染旧页内容。
                var turnForward by remember { mutableStateOf(true) }
                AnimatedContent(
                    targetState = view,
                    transitionSpec = {
                        val dir = if (turnForward) 1 else -1
                        (slideInHorizontally(tween(MotionTokens.NORMAL)) { it / 4 * dir } +
                            fadeIn(tween(MotionTokens.NORMAL)))
                            .togetherWith(
                                slideOutHorizontally(tween(MotionTokens.NORMAL)) { -it / 4 * dir } +
                                    fadeOut(tween(MotionTokens.NORMAL)),
                            )
                    },
                    label = "pageTurn",
                ) { pageView ->
                    TextPageView(
                        view = pageView,
                        canGoNext = view.hasNextPage || viewModel.hasNextChapter(),
                        canGoPrevious = view.hasPrevPage || viewModel.hasPreviousChapter(),
                        highlightBlockColors = NativeNoteHighlights.blockColorsForPage(
                            notesForHighlights,
                            pageView.layout,
                            pageView.page,
                        ),
                        pageImages = pageImages,
                        onLongPressLine = { line ->
                            control?.selectionText = line.text
                            control?.selectionLocation = TextLocation(
                                viewModel.currentLocation.chapterId,
                                line.start,
                                line.end,
                            )
                            onLineSelected()
                        },
                        onPageTurn = { forward ->
                            turnForward = forward
                            if (forward) viewModel.goNext() else viewModel.goPrevious()
                            readerView = viewModel.readerView()
                            onLocationChanged(viewModel.currentLocation, viewModel.currentFrame)
                        },
                        onToggleChrome = onToggleChrome,
                    )
                }
            }
            PageRenderMode.SCROLL -> {
                val scrollHighlights = NativeNoteHighlights.blockColorsForLayout(
                    notesForHighlights,
                    view.layout,
                )
                TextScrollView(
                    view = view,
                    highlightBlockColors = scrollHighlights,
                    pageImages = pageImages,
                    onLongPressLine = { line ->
                        control?.selectionText = line.text
                        control?.selectionLocation = TextLocation(
                            viewModel.currentLocation.chapterId,
                            line.start,
                            line.end,
                        )
                        onLineSelected()
                    },
                )
            }
        }
        }
    }
}
