package com.koodoreader.reader.reader

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import com.koodoreader.core.designsystem.MotionTokens
import com.koodoreader.reader.shell.ReaderContentInsets
import com.koodoreader.core.ui.theme.KoodoMotion

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
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
    /**
     * Whether the reader page is in dark mode. Drives the highlight tint:
     * a 30%-alpha yellow over a near-black page is effectively invisible, so
     * dark mode uses a darker, more saturated tint table instead.
     */
    readerPageIsDark: Boolean = false,
    /**
     * The immersive insets to subtract from the viewport before paginating.
     *
     * The engine derives the page height from the viewport it is given, so the
     * status-bar and navigation-bar clearance must be removed here — otherwise
     * the last line of every page is laid out under the gesture bar.
     */
    contentInsets: ReaderContentInsets = ReaderContentInsets(0.dp, 0.dp),
) {
    val density = LocalDensity.current.density
    val measurer = remember(density) { AndroidTextMeasurer(density) }
    val viewModel = remember(loader, measurer) {
        TextReaderViewModel(loader, annotationStore, measurer)
    }
    var readerView by remember { mutableStateOf<ReaderView?>(null) }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            // The text is drawn inside the immersive area the engine
            // paginated for. `start`/`end` only ever clear a landscape
            // navigation bar or a display cutout — the portrait page margin
            // still belongs to the engine (marginHorizontalPx).
            .padding(
                start = contentInsets.left,
                end = contentInsets.right,
                top = contentInsets.top,
                bottom = contentInsets.bottom,
            ),
    ) {
        // The viewport the engine paginates for.
        //
        // `padding` above already removed the insets from these constraints —
        // BoxWithConstraints reports the constraints it was *measured* with,
        // after the padding modifier. Subtracting topPx/bottomPx a second time
        // (as this used to) made the engine paginate for a page ~2× the inset
        // shorter than the box it draws into, which showed up as a large blank
        // band under the last line.
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
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
                // 翻页过渡：250ms 轻微位移+淡入淡出，方向跟随前进/后退。
                // 自绘分页排版属引擎层，不引入 Pager；targetState 直接取
                // ReaderView，出场帧仍渲染旧页内容。
                var turnForward by remember { mutableStateOf(true) }
                AnimatedContent(
                    targetState = view,
                    transitionSpec = {
                        val dir = if (turnForward) 1 else -1
                        (slideInHorizontally(tween(MotionTokens.PAGE, easing = KoodoMotion.Decelerate)) { it / 4 * dir } +
                            fadeIn(tween(MotionTokens.PAGE, easing = KoodoMotion.Decelerate)))
                            .togetherWith(
                                slideOutHorizontally(tween(MotionTokens.PAGE, easing = KoodoMotion.Decelerate)) { -it / 4 * dir } +
                                    fadeOut(tween(MotionTokens.PAGE, easing = KoodoMotion.Decelerate)),
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
                            readerPageIsDark,
                        ),
                        sidemarkColors = NativeNoteHighlights.noteMarkColorsForPage(
                            notesForHighlights,
                            pageView.layout,
                            pageView.page,
                            readerPageIsDark,
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
                    readerPageIsDark,
                )
                TextScrollView(
                    view = view,
                    highlightBlockColors = scrollHighlights,
                    sidemarkColors = NativeNoteHighlights.noteMarkColorsForLayout(
                        notesForHighlights,
                        view.layout,
                        readerPageIsDark,
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
                )
            }
        }
        }
    }
}
