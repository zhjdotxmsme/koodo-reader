package com.koodoreader.reader.shell

import android.content.Context
import android.content.Intent
import android.webkit.WebView
import android.os.Handler
import android.os.Looper
import android.view.ActionMode
import android.view.MotionEvent
import android.webkit.WebViewClient
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.koodoreader.core.data.KoodoDatabaseProvider
import com.koodoreader.core.data.entity.NoteEntity
import com.koodoreader.engine.annotate.HighlightColor
import com.koodoreader.engine.annotate.HighlightPalette
import com.koodoreader.engine.pdf.CfiPdfMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import com.koodoreader.reader.reader.BookAnnotationsScreen
import com.koodoreader.reader.reader.BookAnnotationsViewModel
import com.koodoreader.reader.reader.ReaderWindowController
import com.koodoreader.reader.reader.ReaderControlCenterSheet
import com.koodoreader.reader.reader.ReaderNativeSurfaceObscurer
import com.koodoreader.reader.reader.ReaderFormatKind
import com.koodoreader.reader.reader.ReaderMoreIconWithOverflow
import com.koodoreader.reader.reader.ReaderOverflowItems
import com.koodoreader.reader.reader.ReaderTocPage
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.koodoreader.engine.pdf.OutlineResolver
import com.koodoreader.engine.pdf.PdfSearchEngine
import com.koodoreader.feature.ocr.OcrScript
import com.koodoreader.reader.R
import com.koodoreader.reader.pdfhost.PdfJsHostBridge
import com.koodoreader.reader.pdfhost.PdfRendererSnapshot
import java.io.File
import androidx.activity.compose.BackHandler
import com.koodoreader.core.designsystem.MotionTokens
import com.koodoreader.core.designsystem.SpaceTokens
import androidx.compose.runtime.mutableIntStateOf
import com.koodoreader.core.ui.component.ErrorState
import com.koodoreader.core.ui.component.koodoPressScale
import org.json.JSONObject

/**
 * Native PDF reader screen (P3).
 *
 * Joins the pieces that already existed but were never connected:
 *  - [PdfJsHostBridge] hosts pdf.js in a `WebView` over the loopback asset
 *    server and rasterises one page per call (see `assets/pdfengine/engine.mjs`,
 *    tested by `scripts/test-pdfengine.js`);
 *  - Compose draws the returned bitmap, so paging and zoom are host-side;
 *  - the outline drawer, the search dialog and "share current page" are the
 *    three toolbar actions P3 owed.
 *
 * The engine `WebView` is attached at 1×1 and never shown. It must be attached
 * for pdf.js's worker and canvas rendering to run, but nothing of it is visible
 * — pages arrive as bitmaps. A consequence, stated rather than hidden: this
 * design has no DOM text layer, so PDF *annotation* capture is not reachable
 * (`engine.mjs` documents `selectedRect`/`paintHighlights` as boundaries).
 *
 * Host prerequisites: the activity must have started its `LocalAssetServer` and
 * published the book (`ReaderAssetHost.publish`). Without it the screen shows an
 * explicit unavailable state — never a blank page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NativePdfScreen(
    bookKey: String,
    onBack: () -> Unit,
    assets: ReaderAssetHost = ReaderAssetHost.NONE,
    viewModel: LibraryViewModel = viewModel(),
) {
    val context = LocalContext.current
    val chromeViewModel: ReaderChromeViewModel = viewModel(
        factory = ReaderChromeViewModel.Factory(context),
    )
    val chromeState by chromeViewModel.state.collectAsStateWithLifecycle()
    val contentInsets = rememberReaderContentInsets()
    val density = LocalDensity.current
    val book by viewModel.book(bookKey).collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val progressPrefs = remember(context) { ReaderProgressPrefs(context) }
    val featurePrefs = remember(context) { ReaderFeaturePrefs(context) }
    var features by remember { mutableStateOf(featurePrefs.load()) }
    val appearancePrefs = remember(context) { ShellAppearancePrefs(context) }
    DisposableEffect(appearancePrefs) { onDispose { appearancePrefs.close() } }
    val readerThemeKind by appearancePrefs.readerThemeKindFlow.collectAsStateWithLifecycle()
    val readerScheme = rememberReaderColorScheme(readerThemeKind, features.customBackgroundArgb)
    // Whether the reader PAGE is dark — selects the highlight tint table so a
    // 30%-alpha band stays visible on a night page.
    val readerPageIsDark = readerScheme.background.luminance() < 0.5f

    val readerActivity = context as? android.app.Activity
    DisposableEffect(readerActivity) {
        onDispose { readerActivity?.let(ReaderWindowController::reset) }
    }
    LaunchedEffect(features.brightness, features.keepScreenOn, features.orientation) {
        readerActivity?.let { ReaderWindowController.apply(it, features) }
    }

    // Books live in filesDir/books/<key>.<ext> (ReaderFiles.resolveBookFile);
    // Room's recorded path is authoritative when present.
    val pdfFile = remember(book?.key, book?.path) {
        val b = book ?: return@remember null
        ReaderFiles.resolveBookFile(
            booksDir = File(context.filesDir, "books"),
            bookKey = b.key,
            format = b.format,
            recordedPath = b.path,
        )
    }
    val pdfUrl = remember(pdfFile, assets.available) {
        pdfFile?.let { if (assets.available) assets.publish(it) else null }
    }

    var showOutline by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showOcr by remember { mutableStateOf(false) }
    var showPageMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var showChrome by remember { mutableStateOf(ReaderPageGestures.CHROME_VISIBLE_ON_OPEN) }
    var reflowSelectionText by remember { mutableStateOf("") }
    var reflowSelectionX by remember { mutableStateOf(0f) }
    var reflowSelectionY by remember { mutableStateOf(0f) }
    var reflowSelectionW by remember { mutableStateOf(0f) }
    var reflowSelectionH by remember { mutableStateOf(0f) }
    var showReflowSelection by remember { mutableStateOf(false) }
    var showControlCenter by remember { mutableStateOf(false) }
    var showReflowNoteInput by remember { mutableStateOf(false) }
    var reflowNoteDraft by remember { mutableStateOf("") }
    var showBookAnnotations by remember { mutableStateOf(false) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val clipboard = LocalClipboardManager.current
    val annotationDb = remember(context) { KoodoDatabaseProvider.get(context) }
    val bookAnnotations = remember(bookKey, annotationDb) {
        BookAnnotationsViewModel(
            annotationDb.noteDao(),
            annotationDb.bookmarkDao(),
            bookKey,
            scope,
        )
    }
    val annotationNotes by bookAnnotations.notes.collectAsStateWithLifecycle()
    val annotationBookmarks by bookAnnotations.bookmarks.collectAsStateWithLifecycle()
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    // 打开失败重试：ErrorState 的 onRetry 递增，重跑下方 open LaunchedEffect。
    var retryKey by remember { mutableIntStateOf(0) }

    // ── 返回键链（后注册且 enabled 优先）：选区/弹层 > 目录 > chrome > 直接退出 ──
    // 置于 bridge 早退（PdfUnavailableScreen）之前，未打开状态下返回也直接退出。
    BackHandler { onBack() }
    BackHandler(enabled = showChrome) { showChrome = false }
    BackHandler(enabled = showOutline) { showOutline = false }
    // 底部弹层（reflow 选区 / 页面菜单 / 批注）自身已注册更晚的 back 处理，这里兜底。
    BackHandler(enabled = showReflowSelection || showPageMenu || showBookAnnotations) {
        when {
            showReflowSelection -> showReflowSelection = false
            showPageMenu -> showPageMenu = false
            else -> showBookAnnotations = false
        }
    }

    // One engine WebView per book URL; the controller tears the document down on
    // dispose. Created during composition, which is the main thread.
    val bridge = remember(pdfUrl) {
        pdfUrl?.let { PdfJsHostBridge(context, assets.baseUrl) }
    }

    if (bridge == null || pdfUrl == null || pdfFile == null) {
        PdfUnavailableScreen(title = book?.name.orEmpty(), bookKey = bookKey, onBack = onBack)
        return
    }
    RememberReadingSession(bookKey)

    val controller = remember(bridge, pdfFile, pdfUrl) {
        PdfReaderController(
            bridge = bridge,
            exporter = PdfRendererSnapshot(pdfFile),
            pdfFile = pdfFile,
            pdfUrl = pdfUrl,
            cacheDir = context.cacheDir,
            scope = scope,
            bootstrap = { bridge.bootstrap() },
        )
    }
    val state = controller.state
    LaunchedEffect(features.perfTier) {
        controller.pdfRenderScale = ReaderPerformancePolicy.pdfRenderScale(features)
        controller.requestRender(force = true)
    }
    LaunchedEffect(
        features.pdfCropMarginPx,
        features.pdfReflow,
        features.fontScale,
    ) {
        controller.cropMarginPx = ReaderFeatureCodec.clampedPdfCrop(features).toInt()
        controller.reflow = features.pdfReflow
        controller.reflowFontScale = features.fontScale
        controller.requestRender(force = true)
    }
    val reflowPaintSink = remember { ReflowPaintSink() }
    val reflowHighlightsJson = remember(annotationNotes, state.page) {
        PdfReflowHighlights.jsonForPage(annotationNotes, state.page, readerPageIsDark)
    }
    val reflowCss = ReaderFeatureCodec.userStylesheet(
        features,
        firstLineIndent = chromeState.firstLineIndent,
        density = LocalDensity.current.density,
    ) + "\nbody{line-height:${ReaderFeatureCodec.clampedLineHeight(features)};font-size:${ReaderFeatureCodec.fontSizePx(features)}px;margin:0;}"
    val currentReflowCss by rememberUpdatedState(reflowCss)
    val reflowWeb = remember(features.pdfReflow) {
        if (!features.pdfReflow) return@remember null
        ReflowWebView(context).apply {
            // JS is needed for the selection relay (the system ActionMode is
            // suppressed by ReflowWebView so the app menu can take over).
            settings.javaScriptEnabled = true
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.evaluateJavascript(
                        ReaderAppearance.swapStyleJs(currentReflowCss),
                        null,
                    )
                    view?.evaluateJavascript(REFLOW_SELECTION_RELAY_JS, null)
                    view?.evaluateJavascript(
                        PdfReflowHighlights.paintJavascript(reflowPaintSink.highlightsJson),
                        null,
                    )
                    reflowPaintSink.pendingScrollY?.let { y ->
                        view?.evaluateJavascript("window.scrollTo(0,${y.toInt()});", null)
                        reflowPaintSink.pendingScrollY = null
                    }
                }
            }
            addJavascriptInterface(object {
                @android.webkit.JavascriptInterface
                fun onSelection(text: String, x: Float, y: Float, width: Float, height: Float) {
                    if (text.isBlank()) return
                    mainHandler.post {
                        reflowSelectionText = text
                        reflowSelectionX = x
                        reflowSelectionY = y
                        reflowSelectionW = width
                        reflowSelectionH = height
                        showReflowSelection = true
                    }
                }
            }, "__koodoReflow")
        }
    }
    LaunchedEffect(reflowHighlightsJson, features.pdfReflow, state.reflowHtml, reflowWeb) {
        if (!features.pdfReflow || state.reflowHtml.isNullOrBlank() || reflowWeb == null) return@LaunchedEffect
        reflowPaintSink.highlightsJson = reflowHighlightsJson
        reflowWeb.evaluateJavascript(
            PdfReflowHighlights.paintJavascript(reflowHighlightsJson),
            null,
        )
    }
    // WebView eats Compose taps (same problem as foliate): host-side touch
    // handling restores center-tap chrome and edge page-turns in reflow mode.
    // Long-press selects the word/paragraph at the touch point via JS instead
    // of the system ActionMode (suppressed by ReflowWebView).
    DisposableEffect(reflowWeb) {
        var downX = 0f
        var downY = 0f
        var longPress: Runnable? = null
        fun cancelLongPress(v: android.view.View) {
            longPress?.let(v::removeCallbacks)
            longPress = null
        }
        // Edge-back would otherwise swallow the left page-turn band.
        val reflowExclusion = reflowWeb?.let { com.koodoreader.reader.shell.ReaderGestureExclusion.install(it) }
        reflowWeb?.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    cancelLongPress(v)
                    val x = downX
                    val y = downY
                    longPress = Runnable {
                        reflowWeb?.evaluateJavascript(reflowSelectAtPointJs(x, y), null)
                    }.also { v.postDelayed(it, ReaderGestureMetrics.LONG_PRESS_MS) }
                }
                MotionEvent.ACTION_MOVE -> {
                    val slop = ReaderGestureMetrics.tapSlopPx(v.resources.displayMetrics.density)
                    if (kotlin.math.abs(event.x - downX) > slop ||
                        kotlin.math.abs(event.y - downY) > slop
                    ) {
                        cancelLongPress(v)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    cancelLongPress(v)
                    // A long-press is text selection, not a tap: without the
                    // timeout check the ACTION_UP would page-turn and reload
                    // the reflow HTML, destroying the fresh selection.
                    if (event.eventTime - event.downTime >= ReaderGestureMetrics.LONG_PRESS_MS) {
                        return@setOnTouchListener false
                    }
                    val cfg = ReaderGestureRuntime.config
                    val tap = ReaderPageGestures.tapAtIfNotDrag(
                        downX = downX,
                        downY = downY,
                        upX = event.x,
                        upY = event.y,
                        widthPx = v.width.toFloat(),
                        heightPx = v.height.toFloat(),
                        slopPx = ReaderGestureMetrics.tapSlopPx(v.resources.displayMetrics.density),
                        layout = cfg.tapZoneLayout,
                        forwardRatio = ReaderGestureCodec.clampedTapEdge(cfg),
                        backwardRatio = ReaderGestureCodec.clampedTapBack(cfg),
                        mirrored = v.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL,
                    )
                    when (tap) {
                        ReaderPageGesture.TOGGLE_CHROME -> v.post { showChrome = !showChrome }
                        ReaderPageGesture.PREV_PAGE -> v.post { controller.previousPage() }
                        ReaderPageGesture.NEXT_PAGE -> v.post { controller.nextPage() }
                        else -> Unit
                    }
                }
                MotionEvent.ACTION_CANCEL -> cancelLongPress(v)
            }
            false
        }
        onDispose {
            reflowWeb?.let { web ->
                web.setOnTouchListener(null)
                reflowExclusion?.let(web::removeOnLayoutChangeListener)
                com.koodoreader.reader.shell.ReaderGestureExclusion.clear(web)
            }
        }
    }
    DisposableEffect(reflowWeb) {
        onDispose {
            reflowWeb?.let { webView ->
                webView.stopLoading()
                (webView.parent as? android.view.ViewGroup)?.removeView(webView)
                webView.destroy()
            }
        }
    }
    // Live typography for PDF reflow.
    //
    // Before this, the typography stylesheet was injected only from
    // `onPageFinished`, so dragging the font-size / line-height slider in
    // reflow mode did nothing visible until the document reloaded. Swapping the
    // single <style> element re-lays-out in place and keeps the reader's place
    // by ratio (see ReaderAppearance.swapStyleJs), which is what makes the
    // control panel's "live preview" actually live.
    LaunchedEffect(reflowCss, features.pdfReflow) {
        val web = reflowWeb ?: return@LaunchedEffect
        if (!features.pdfReflow || state.reflowHtml.isNullOrBlank()) return@LaunchedEffect
        delay(ReaderAppearance.SETTLE_MS)
        web.evaluateJavascript(ReaderAppearance.swapStyleJs(reflowCss), null)
    }
    LaunchedEffect(features.pdfReflow, state.reflowHtml, features.fontKey) {
        val raw = state.reflowHtml
        if (features.pdfReflow && !raw.isNullOrBlank()) {
            val family = features.fontKey.replace("'", "").trim()
            val html = if (family.isEmpty()) raw else raw.replace(
                "font-family:Georgia,serif",
                "font-family:'$family',sans-serif",
            )
            reflowWeb?.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        }
    }
    DisposableEffect(controller) {
        ReaderPageTurn.bind { gesture ->
            when (gesture) {
                ReaderPageGesture.PREV_PAGE -> {
                    controller.previousPage()
                    true
                }
                ReaderPageGesture.NEXT_PAGE -> {
                    controller.nextPage()
                    true
                }
                else -> false
            }
        }
        onDispose { ReaderPageTurn.unbind() }
    }

    // OCR (P6): the scanned-page index. Script comes from the UI language because
    // `books` has no language column (documented in the completeness report §16).
    val i18n = LocalI18n.current
    val i18nLanguage by i18n.language.collectAsState()
    val ocrController = remember(bridge, pdfFile, i18nLanguage) {
        PdfOcrController(
            context = context.applicationContext,
            bookKey = pdfFile.nameWithoutExtension,
            script = OcrScript.forLanguageTag(i18nLanguage),
            raster = { page ->
                controller.rasterForOcr(page)?.let(PdfOcrController::pageImage)
            },
            scope = scope,
        )
    }

    LaunchedEffect(controller, retryKey) {
        controller.spread = features.doublePage
        controller.open()
    }
    var progressRestored by remember { mutableStateOf(false) }
    LaunchedEffect(state.pageCount) {
        if (progressRestored || state.pageCount <= 0) return@LaunchedEffect
        progressRestored = true
        progressPrefs.cfiOf(bookKey)
            ?.let(ReaderProgressMath::pageFromPdfCfi)
            ?.let { controller.goTo(it) }
    }
    LaunchedEffect(state.page, state.pageCount, progressRestored) {
        if (!progressRestored || state.pageCount <= 0) return@LaunchedEffect
        progressPrefs.save(
            bookKey,
            ReaderProgressMath.pdfCfi(state.page),
            ReaderProgressMath.fraction(state.page - 1, state.pageCount),
        )
    }
    LaunchedEffect(features.doublePage) {
        controller.spread = features.doublePage
        controller.requestRender(force = true)
    }
    DisposableEffect(controller) {
        onDispose {
            controller.close()
            assets.release(pdfFile)
        }
    }
    DisposableEffect(ocrController) {
        onDispose { ocrController.close() }
    }

    // 冷启动打开失败由内容区的 ErrorState + 重试承载（见下方 when 分支）；
    // 这里只处理已有页面后的中途错误，仍用轻提示。
    state.error?.let { message ->
        if (state.pageCount > 0) {
            LaunchedEffect(message) {
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                controller.dismissError()
            }
        }
    }

    MaterialTheme(colorScheme = readerScheme) {
    Box(modifier = Modifier.fillMaxSize()) {
    ReaderSystemBars(
        fullScreen = chromeState.fullScreen,
        chromeVisible = showChrome || showOutline,
        readerBackground = readerScheme.background,
    )
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            ReaderTopBar(
                visible = showChrome,
                title = {
                    Column {
                        Text(book?.name.orEmpty(), maxLines = 1)
                        if (state.pageCount > 0) {
                            Text(
                                "${state.page} / ${state.pageCount} · ${state.zoomPercent}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                onBack = onBack,
                onClose = { showChrome = false },
                actions = {
                    IconButton(
                        onClick = {
                            controller.loadOutline()
                            showOutline = true
                        },
                        enabled = state.pageCount > 0,
                        modifier = Modifier.koodoPressScale(),
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_list_view),
                            contentDescription = t("PDF outline"),
                        )
                    }
                    IconButton(
                        onClick = { showSearch = true },
                        enabled = state.pageCount > 0,
                        modifier = Modifier.koodoPressScale(),
                    ) {
                        Icon(painterResource(R.drawable.ic_search), contentDescription = t("Search in book"))
                    }
                    ReaderMoreIconWithOverflow(
                        menuExpanded = moreMenu,
                        onMenuExpandedChange = { moreMenu = it },
                        enabled = state.pageCount > 0,
                        items = ReaderOverflowItems.forPdf(
                            exporting = state.exporting,
                            onZoomOut = { controller.zoomOut() },
                            onZoomIn = { controller.zoomIn() },
                            onOcr = {
                                ocrController.refresh()
                                showOcr = true
                            },
                            onShare = {
                                controller.exportCurrentPage { file -> shareSnapshot(context, file) }
                            },
                            onReadingOptions = { showControlCenter = true },
                            onNotes = { showBookAnnotations = true },
                        ),
                    )
                },
            )
        },
    ) { _ ->
        val readerLayoutDirection = LocalLayoutDirection.current
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Chrome overlays the content; Scaffold padding would resize
                // the page on every toggle and leave large blank bands.
                .background(MaterialTheme.colorScheme.background)
                // Keep the page-turn bands reachable on gesture-navigation
                // devices; the chrome band stays usable for the back gesture.
                .then(
                    rememberReaderGestureExclusion(
                        edgeFraction = ReaderGestureCodec.clampedTapBack(
                            ReaderGestureRuntime.config,
                        ),
                    ),
                )
                // Tap zones follow the user's layout choice (edges / vertical
                // bands / desktop grid); the chrome band toggles the toolbar.
                .pointerInput(state.pageCount) {
                    detectTapGestures(
                        onLongPress = { showPageMenu = true },
                    ) { offset ->
                        val cfg = ReaderGestureRuntime.config
                        when (
                            ReaderPageGestures.onTapAt(
                                x = offset.x,
                                y = offset.y,
                                widthPx = size.width.toFloat(),
                                heightPx = size.height.toFloat(),
                                config = cfg,
                                mirrored = readerLayoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl,
                            )
                        ) {
                            ReaderPageGesture.PREV_PAGE -> controller.previousPage()
                            ReaderPageGesture.NEXT_PAGE -> controller.nextPage()
                            ReaderPageGesture.TOGGLE_CHROME -> showChrome = !showChrome
                            ReaderPageGesture.SELECT -> Unit
                        }
                    }
                }
                .pointerInput(state.pageCount) {
                    var dx = 0f
                    var dy = 0f
                    detectDragGestures(
                        onDragStart = { dx = 0f; dy = 0f },
                        onDragEnd = {
                            when (
                                ReaderPageGestures.onHorizontalSwipe(
                                    dx,
                                    dy,
                                    minDx = ReaderGestureMetrics.swipeMinPx(density.density),
                                    enabled = ReaderGestureRuntime.config.swipePageTurn,
                                )
                            ) {
                                ReaderPageGesture.PREV_PAGE -> controller.previousPage()
                                ReaderPageGesture.NEXT_PAGE -> controller.nextPage()
                                else -> Unit
                            }
                        },
                        onDrag = { _, amount ->
                            dx += amount.x
                            dy += amount.y
                        },
                    )
                }
                .onSizeChanged {
                    containerSize = it
                    controller.onViewportWidth(it.width)
                },
        ) {
            // The engine WebView: attached (pdf.js needs a live view) but invisible.
            AndroidView(factory = { bridge.view }, modifier = Modifier.size(1.dp))

            when {
                state.opening -> Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Text(t("Loading"), modifier = Modifier.padding(top = 12.dp))
                }

                state.passwordRequired -> PasswordPrompt(
                    error = state.passwordError,
                    onSubmit = { password -> controller.open(password) },
                )

                // 冷启动失败：没有任何页面可显示时给全屏重试入口。
                state.error != null && state.pageCount == 0 -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    ErrorState(
                        message = state.error.orEmpty(),
                        onRetry = {
                            controller.dismissError()
                            retryKey++
                        },
                        retryText = t("Retry"),
                    )
                }

                else -> {
                    if (state.rendering && state.pageImage == null && state.reflowHtml == null) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    if (features.pdfReflow) {
                        AndroidView(
                            factory = { checkNotNull(reflowWeb) },
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(
                                    start = contentInsets.left,
                                    end = contentInsets.right,
                                    top = contentInsets.top,
                                    bottom = contentInsets.bottom,
                                )
                                .padding(
                                    start = chromeState.horizontalMarginDp.dp,
                                    end = chromeState.horizontalMarginDp.dp,
                                )
                                .verticalScroll(rememberScrollState()),
                        )
                    } else state.pageImage?.let { image ->
                        val spread = features.doublePage && state.companionImage != null
                        val drawnWidth = with(density) {
                            val base = if (spread) containerSize.width / 2f else containerSize.width.toFloat()
                            (base * state.zoom).coerceAtLeast(1f).toDp()
                        }
                        val drawnWidthPx = with(density) { drawnWidth.toPx() }
                        val drawnHeightPx = drawnWidthPx * (image.height.toFloat() / image.width.coerceAtLeast(1))
                        val pdfHighlights = remember(
                            annotationNotes,
                            state.page,
                            state.pageWidthPt,
                            state.pageHeightPt,
                            drawnWidthPx,
                            drawnHeightPx,
                        ) {
                            annotationNotes.mapNotNull { note ->
                                val range = note.cfi?.let(CfiPdfMapper::parse)
                                    ?.takeIf { it.page == state.page }
                                    ?: return@mapNotNull null
                                val rect = PdfHighlightOverlay.toOverlayRect(
                                    range,
                                    state.pageWidthPt,
                                    state.pageHeightPt,
                                    drawnWidthPx,
                                    drawnHeightPx,
                                ) ?: return@mapNotNull null
                                rect to HighlightPalette.paintArgb(note.color, readerPageIsDark)
                            }
                        }
                        LaunchedEffect(pdfHighlights, features.pdfReflow) {
                            if (features.pdfReflow) return@LaunchedEffect
                            val ranges = annotationNotes.mapNotNull { note ->
                                note.cfi?.let(CfiPdfMapper::parse)?.takeIf { it.page == state.page }
                            }
                            withContext(Dispatchers.IO) {
                                runCatching { bridge.paintHighlights(ranges) }
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(
                                    start = contentInsets.left,
                                    end = contentInsets.right,
                                    top = contentInsets.top,
                                    bottom = contentInsets.bottom,
                                )
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState()),
                        ) {
                            if (spread) {
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    Image(
                                        bitmap = image,
                                        contentDescription = null,
                                        modifier = Modifier.width(drawnWidth),
                                    )
                                    state.companionImage?.let { right ->
                                        Image(
                                            bitmap = right,
                                            contentDescription = null,
                                            modifier = Modifier.width(drawnWidth),
                                        )
                                    }
                                }
                            } else {
                                Image(
                                    bitmap = image,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .width(drawnWidth)
                                        .align(Alignment.TopCenter),
                                )
                                if (pdfHighlights.isNotEmpty()) {
                                    Canvas(
                                        modifier = Modifier
                                            .width(drawnWidth)
                                            .height(with(density) { drawnHeightPx.toDp() })
                                            .align(Alignment.TopCenter),
                                    ) {
                                        pdfHighlights.forEach { (rect, argb) ->
                                            drawRect(
                                                color = androidx.compose.ui.graphics.Color(argb.toInt()),
                                                topLeft = androidx.compose.ui.geometry.Offset(rect.left, rect.top),
                                                size = androidx.compose.ui.geometry.Size(rect.width, rect.height),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    ReaderBottomProgress(
                        visible = showChrome,
                        fraction = if (state.pageCount > 0) state.page.toFloat() / state.pageCount else 0f,
                        label = "${state.page} / ${state.pageCount}",
                        onClick = { controller.loadOutline(); showOutline = true },
                        modifier = Modifier.align(Alignment.BottomCenter),
                        actions = {
                            TextButton(
                                onClick = controller::previousPage,
                                enabled = state.page > 1,
                                modifier = Modifier.koodoPressScale(),
                            ) {
                                Text("‹ ${t("Previous page")}")
                            }
                            TextButton(
                                onClick = controller::nextPage,
                                enabled = state.page < state.pageCount,
                                modifier = Modifier.koodoPressScale(),
                            ) {
                                Text("${t("Next page")} ›")
                            }
                        },
                    )
                }
            }
        }
    }

    ReaderChromeRevealStrip(
        chromeVisible = showChrome,
        modifier = Modifier.align(Alignment.TopCenter),
        onReveal = { showChrome = true },
    )

    // 首次进入阅读页引导：一次性、点任意处可跳过。
    val (guideVisible, dismissGuide) = rememberReaderFirstRunGuide()
    ReaderFirstRunGuide(
        visible = guideVisible,
        onDismiss = dismissGuide,
        modifier = Modifier.fillMaxSize(),
    )
    }

    fun jumpToAnnotationCfi(cfi: String) {
        ReaderProgressMath.pageFromStoredCfi(cfi)?.let { controller.goTo(it) }
        CfiPdfMapper.parse(cfi)?.takeIf { it.reflowDocument && !it.isPageLevel }?.let { range ->
            reflowPaintSink.pendingScrollY = range.y
            reflowWeb?.evaluateJavascript("window.scrollTo(0,${range.y.toInt()});", null)
        }
    }

    fun saveReflowAnnotation(noteContent: String) {
        val range = CfiPdfMapper.PdfRange(
            fingerprint = book?.md5.orEmpty(),
            page = state.page,
            x = reflowSelectionX,
            y = reflowSelectionY,
            width = reflowSelectionW,
            height = reflowSelectionH,
            reflowDocument = true,
        )
        val entity = NoteEntity(
            key = System.currentTimeMillis().toString(),
            bookKey = bookKey,
            chapter = "PDF · ${state.page}",
            text = reflowSelectionText,
            cfi = CfiPdfMapper.serialise(range),
            notes = noteContent,
            color = if (noteContent.isEmpty()) HighlightColor.YELLOW.code.toLong() else null,
        )
        scope.launch(Dispatchers.IO) { annotationDb.noteDao().upsert(entity) }
    }

    // The reflow selection sheet must not obscure the WebView: the reader is
    // choosing what to do with words they have to see, and the browser paints
    // that selection inside the WebView. Hiding it freezes the last frame and
    // the selection highlight is never drawn.
    val pdfModalObscured =
        showControlCenter || showBookAnnotations
    ReaderNativeSurfaceObscurer(pdfModalObscured, bridge.view, reflowWeb)

    if (showControlCenter) {
        ReaderControlCenterSheet(
            format = ReaderFormatKind.PDF,
            useFoliate = false,
            initialTab = null,
            readerThemeKind = readerThemeKind,
            onTheme = { appearancePrefs.readerThemeKind = it },
            fontScale = features.fontScale,
            onFontScale = {
                val next = features.copy(fontScale = ReaderFeatureCodec.snappedFontScale(it))
                features = next
                featurePrefs.save(next)
            },
            state = features,
            onFeatureChange = {
                features = it
                featurePrefs.save(it)
            },
            chromeState = chromeState,
            onChromeState = chromeViewModel::update,
            marginDp = chromeState.horizontalMarginDp,
            onMarginDp = chromeViewModel::setHorizontalMarginDp,
            onDismiss = { showControlCenter = false },
        )
    }

    if (showReflowSelection) {
        ModalBottomSheet(onDismissRequest = { showReflowSelection = false }) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                Text(
                    reflowSelectionText,
                    maxLines = 2,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(reflowSelectionText))
                        showReflowSelection = false
                    },
                    modifier = Modifier.fillMaxWidth().koodoPressScale(),
                ) { Text(t("Copy")) }
                TextButton(
                    onClick = {
                        saveReflowAnnotation("")
                        showReflowSelection = false
                    },
                    modifier = Modifier.fillMaxWidth().koodoPressScale(),
                ) { Text(t("Highlight")) }
                TextButton(
                    onClick = {
                        showReflowSelection = false
                        showReflowNoteInput = true
                    },
                    modifier = Modifier.fillMaxWidth().koodoPressScale(),
                ) { Text(t("Note")) }
                TextButton(
                    onClick = {
                        controller.search(reflowSelectionText)
                        showSearch = true
                        showReflowSelection = false
                    },
                    modifier = Modifier.fillMaxWidth().koodoPressScale(),
                ) { Text(t("Search in book")) }
            }
        }
    }
    if (showReflowNoteInput) {
        AlertDialog(
            onDismissRequest = { showReflowNoteInput = false },
            title = { Text(t("Note")) },
            text = {
                Column {
                    Text(reflowSelectionText, maxLines = 3, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = reflowNoteDraft,
                        onValueChange = { reflowNoteDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    saveReflowAnnotation(reflowNoteDraft)
                    reflowNoteDraft = ""
                    showReflowNoteInput = false
                }, modifier = Modifier.koodoPressScale()) { Text(t("Confirm")) }
            },
            dismissButton = {
                TextButton(onClick = { showReflowNoteInput = false }, modifier = Modifier.koodoPressScale()) { Text(t("Cancel")) }
            },
        )
    }

    if (showPageMenu) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { showPageMenu = false },
            sheetState = sheetState,
        ) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                TextButton(
                    onClick = {
                        showPageMenu = false
                        controller.loadOutline()
                        showOutline = true
                    },
                    modifier = Modifier.fillMaxWidth().koodoPressScale(),
                ) { Text(t("PDF outline")) }
                TextButton(
                    onClick = {
                        showPageMenu = false
                        showSearch = true
                    },
                    modifier = Modifier.fillMaxWidth().koodoPressScale(),
                ) { Text(t("Search in book")) }
                TextButton(
                    onClick = {
                        showPageMenu = false
                        controller.exportCurrentPage { file -> shareSnapshot(context, file) }
                    },
                    enabled = state.pageCount > 0 && !state.exporting,
                    modifier = Modifier.fillMaxWidth().koodoPressScale(),
                ) { Text(t("Share")) }
            }
        }
    }

    if (showOutline) {
        ReaderTocPage(
            title = t("PDF outline"),
            onBack = { showOutline = false },
        ) { padding ->
            OutlineList(
                rows = state.outline,
                loading = state.outlineLoading,
                onPick = { row ->
                    controller.openOutlineRow(row)
                    showOutline = false
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        }
    }

    if (showSearch) {
        SearchDialog(
            initialQuery = state.searchQuery,
            hits = state.hits,
            hitIndex = state.hitIndex,
            searching = state.searching,
            onSearch = controller::search,
            onCycle = controller::cycleHit,
            onJump = controller::jumpToHit,
            onDismiss = { showSearch = false },
        )
    }

    if (showOcr) {
        PdfOcrDialog(
            state = ocrController.state,
            currentPage = state.page,
            pageCount = state.pageCount,
            onIndexPage = { page -> ocrController.index(page..page) },
            onIndexAll = { ocrController.index(1..state.pageCount) },
            onCancel = ocrController::cancel,
            onSearch = ocrController::search,
            onForget = ocrController::forget,
            onJump = { page ->
                controller.goTo(page)
                showOcr = false
            },
            onDismiss = { showOcr = false },
        )
    }

    if (showBookAnnotations) {
        ModalBottomSheet(onDismissRequest = { showBookAnnotations = false }) {
            BookAnnotationsScreen(
                notes = annotationNotes,
                bookmarks = annotationBookmarks,
                onJump = { cfi ->
                    showBookAnnotations = false
                    jumpToAnnotationCfi(cfi)
                },
                onRecolor = { note, color ->
                    scope.launch { bookAnnotations.changeHighlightColor(note.key, color) }
                },
                onAddNote = { content ->
                    scope.launch { bookAnnotations.addNote(content) }
                },
                onEditNote = { note, content ->
                    scope.launch { bookAnnotations.editNote(note.key, content) }
                },
                onDeleteNote = { note ->
                    scope.launch { bookAnnotations.deleteNote(note.key) }
                },
                onDeleteBookmark = { bookmark ->
                    scope.launch { bookAnnotations.deleteBookmark(bookmark.key) }
                },
            )
        }
    }
    }
}

/**
 * Shown when the book file or the loopback origin is missing.
 *
 * The message is English-only on purpose: the Android locale catalogs are
 * generated from the desktop ones by `scripts/sync-locales-android.js --check`,
 * which CI enforces, so a native-only key cannot be added to just one side.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PdfUnavailableScreen(title: String, bookKey: String, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (title.isEmpty()) bookKey else title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.koodoPressScale()) {
                        Icon(painterResource(R.drawable.ic_back), contentDescription = t("Back"))
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            // 引擎/资源不可用不是瞬时错误：重试无法恢复，故不提供重试按钮。
            ErrorState(
                message = t("The local PDF engine is unavailable for this book.") +
                    "\n(book key: $bookKey)\n\n" +
                    t("The loopback asset server must be running and the book file must exist under files/books/."),
            )
        }
    }
}

@Composable
private fun PasswordPrompt(error: Boolean, onSubmit: (String) -> Unit) {
    var field by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(t("Password")) },
        text = {
            Column {
                Text(t("Enter password"))
                OutlinedTextField(
                    value = field,
                    onValueChange = { field = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSubmit(field) }),
                    isError = error,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (error) {
                    Text(
                        t("Wrong password"),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(field) }, modifier = Modifier.koodoPressScale()) { Text(t("Enter password")) }
        },
    )
}

@Composable
private fun OutlineList(
    rows: List<OutlineResolver.Row>,
    loading: Boolean,
    onPick: (OutlineResolver.Row) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        if (loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (rows.isEmpty()) {
            Text(
                text = t("PDF empty outline"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            return@Column
        }
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            itemsIndexed(rows) { _, row ->
                val resolved = row.entry.pageNumber > 0
                TextButton(
                    onClick = { onPick(row) },
                    enabled = resolved,
                    modifier = Modifier.fillMaxWidth().koodoPressScale(),
                ) {
                    Text(
                        text = if (resolved) {
                            "${row.entry.title}  ·  ${row.entry.pageNumber}"
                        } else {
                            row.entry.title
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = if (row.depth == 0) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = (row.depth * 12).dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchDialog(
    initialQuery: String,
    hits: List<PdfSearchEngine.Hit>,
    hitIndex: Int,
    searching: Boolean,
    onSearch: (String) -> Unit,
    onCycle: (Int) -> Unit,
    onJump: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf(initialQuery) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("Search in book")) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(t("Search")) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                when {
                    searching -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

                    hits.isEmpty() && query.isNotBlank() -> Text(
                        t("No result found"),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )

                    hits.isEmpty() -> Unit

                    else -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${hitIndex + 1} / ${hits.size}",
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Row {
                                TextButton(onClick = { onCycle(-1) }, modifier = Modifier.koodoPressScale()) { Text("‹") }
                                TextButton(onClick = { onCycle(1) }, modifier = Modifier.koodoPressScale()) { Text("›") }
                            }
                        }
                        LazyColumn(modifier = Modifier.fillMaxHeight(0.5f)) {
                            itemsIndexed(hits) { index, hit ->
                                TextButton(
                                    onClick = { onJump(index) },
                                    modifier = Modifier.fillMaxWidth().koodoPressScale(),
                                    contentPadding = PaddingValues(
                                        horizontal = SpaceTokens.LIST_ITEM_HORIZONTAL.dp,
                                        vertical = SpaceTokens.LIST_ITEM_VERTICAL.dp,
                                    ),
                                ) {
                                    Text(
                                        text = "[${hit.pageNumber}] ${hit.snippet}",
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        fontWeight = if (index == hitIndex) {
                                            FontWeight.Bold
                                        } else {
                                            FontWeight.Normal
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSearch(query) }, modifier = Modifier.koodoPressScale()) { Text(t("Search")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.koodoPressScale()) { Text(t("Close")) }
        },
    )
}

/** Hand the exported snapshot to the share sheet through the FileProvider. */
private fun shareSnapshot(context: Context, file: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, null))
    }.onFailure {
        Toast.makeText(context, it.message ?: "share failed", Toast.LENGTH_SHORT).show()
    }
}


/** Reflow WebView with the system selection ActionMode suppressed — the app
 *  shows its own selection sheet via the JS relay instead. */
private class ReflowWebView(context: Context) : WebView(context) {
    override fun startActionMode(callback: ActionMode.Callback?): ActionMode? = null
    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? = null
}

private class ReflowPaintSink {
    @Volatile var highlightsJson: String = "[]"
    @Volatile var pendingScrollY: Float? = null
}

/** Debounced selection relay; injected on every reflow page load. */
private const val REFLOW_SELECTION_RELAY_JS = """
(function(){
  if (window.__koodoReflowBound) return;
  window.__koodoReflowBound = true;
  var timer = null;
  document.addEventListener('selectionchange', function(){
    clearTimeout(timer);
    timer = setTimeout(function(){
      var s = String(window.getSelection() || '');
      if (s.trim().length === 0) return;
      var rect = { left: 0, top: 0, width: 0, height: 0 };
      try {
        var sel = window.getSelection();
        if (sel && sel.rangeCount > 0) {
          var b = sel.getRangeAt(0).getBoundingClientRect();
          var sx = window.scrollX || 0, sy = window.scrollY || 0;
          rect = { left: b.left + sx, top: b.top + sy, width: b.width, height: b.height };
        }
      } catch (e) {}
      window.__koodoReflow.onSelection(s, rect.left, rect.top, rect.width, rect.height);
    }, 500);
  });
})();
"""


/** Selects the text run at viewport point (x, y) and relays it to the app.
 *  Expands to whitespace/punctuation boundaries (CJK runs select the sentence). */
private fun reflowSelectAtPointJs(x: Float, y: Float): String = """
(function(){
  var range = document.caretRangeFromPoint($x, $y);
  if (!range) return;
  var node = range.startContainer;
  if (!node || node.nodeType !== 3) return;
  var text = node.textContent || '';
  var offset = range.startOffset;
  var start = offset, end = offset;
  var boundary = /[\s，。；、：！？,.;:!?（）()《》<>「」"\n\r]/;
  while (start > 0 && !boundary.test(text[start-1])) start--;
  while (end < text.length && !boundary.test(text[end])) end++;
  if (start === end) return;
  var sel = window.getSelection();
  var r = document.createRange();
  r.setStart(node, start);
  r.setEnd(node, end);
  sel.removeAllRanges();
  sel.addRange(r);
  var s = sel.toString();
  if (s.trim().length === 0) return;
  var b = r.getBoundingClientRect();
  var sx = window.scrollX || 0, sy = window.scrollY || 0;
  window.__koodoReflow.onSelection(s, b.left + sx, b.top + sy, b.width, b.height);
})();
"""
