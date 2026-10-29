package com.koodoreader.reader.epubhost

import android.content.Intent
import android.net.Uri
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.koodoreader.core.data.KoodoDatabase
import com.koodoreader.core.data.KoodoDatabaseProvider
import com.koodoreader.core.data.entity.BookmarkEntity
import com.koodoreader.core.data.entity.NoteEntity
import com.koodoreader.core.designsystem.CssColor
import com.koodoreader.core.ui.theme.ThemeSpecBridge
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.koodoreader.engine.feature.BionicConfig
import com.koodoreader.engine.feature.BionicReading
import com.koodoreader.engine.feature.ParagraphMode
import com.koodoreader.engine.feature.ReadingRuler
import com.koodoreader.engine.feature.ReadingRulerConfig
import com.koodoreader.engine.feature.SpeedReading
import com.koodoreader.engine.layout.CfiAddressing
import com.koodoreader.engine.layout.LayoutLine
import com.koodoreader.engine.layout.LayoutPosition
import com.koodoreader.engine.layout.PaginatorOptions
import com.koodoreader.reader.shell.ReaderFeatureCodec
import com.koodoreader.reader.shell.ReaderFeaturePrefs
import com.koodoreader.reader.shell.ReaderFeatureState
import com.koodoreader.reader.shell.ReaderPurifiedText
import com.koodoreader.reader.shell.ReaderTextPipeline
import com.koodoreader.engine.link.LinkAction
import com.koodoreader.engine.link.LinkRouter
import com.koodoreader.engine.toc.ChapterText
import com.koodoreader.engine.toc.SearchHit
import com.koodoreader.engine.toc.SearchIndex
import com.koodoreader.engine.toc.SearchQuery
import com.koodoreader.feature.dictionary.DictRepository
import com.koodoreader.feature.translate.AiAssistant
import com.koodoreader.feature.translate.AiOutcome
import com.koodoreader.feature.translate.TranslationPopup
import com.koodoreader.feature.translate.TranslationPopupLabels
import com.koodoreader.feature.tts.ForegroundTtsService
// ic_tts_notification 是 feature/tts 模块的资源（非转置 R：app 模块的 R 不含它），
// 这里用 alias 引用该模块的 R。
import com.koodoreader.feature.tts.R as TtsR
import com.koodoreader.feature.tts.TtsControlUiState
import com.koodoreader.feature.tts.TtsControlSheet
import com.koodoreader.feature.tts.TtsMediaCommand
import com.koodoreader.feature.tts.TtsConfig
import com.koodoreader.feature.tts.TtsPlaybackSnapshot
import com.koodoreader.feature.tts.TtsPlaybackState
import com.koodoreader.reader.shell.AiModelPrefs
import com.koodoreader.reader.shell.FontManager
import com.koodoreader.reader.shell.LibraryViewModel
import com.koodoreader.engine.docx.DocxDocument
import com.koodoreader.reader.AppLog
import com.koodoreader.reader.BuildConfig
import com.koodoreader.reader.R
import com.koodoreader.reader.shell.LocalI18n
import com.koodoreader.reader.shell.ReaderAssetHost
import com.koodoreader.reader.shell.ReaderChromeAction
import com.koodoreader.reader.shell.ReaderChromeRevealStrip
import com.koodoreader.reader.shell.ReaderFiles
import com.koodoreader.reader.shell.ReaderImmersiveBars
import com.koodoreader.reader.shell.ReaderHrefJump
import com.koodoreader.reader.shell.ReaderPageGesture
import com.koodoreader.reader.shell.ReaderGestureCodec
import com.koodoreader.reader.shell.ReaderGestureRuntime
import com.koodoreader.reader.shell.ReaderPageGestures
import com.koodoreader.reader.shell.ReaderPageTurn
import com.koodoreader.reader.shell.ReaderProgressMath
import com.koodoreader.reader.shell.ReaderProgressPrefs
import com.koodoreader.reader.shell.ReaderTapKind
import com.koodoreader.reader.shell.ReaderTapTarget
import com.koodoreader.reader.shell.RememberReadingSession
import com.koodoreader.reader.shell.ShellAppearancePrefs
import com.koodoreader.reader.translate.AppHttpTransport
import com.koodoreader.reader.shell.builtInPreset
import com.koodoreader.reader.shell.t
import com.koodoreader.reader.translate.rememberTranslationPopupController
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * EPUB 原生阅读屏（步骤③）：Canvas 绘制 [EpubBookSession] 的分页行，
 * 点区翻页（左 1/3 上页 / 右 1/3 下页 / 中 1/3 呼出进度条），进度 = 章 +
 * 百分比，位置 CFI 经 [EpubBookSession.cfiForPage] 读取（翻页即得，后续
 * 标注/书签卡挂持久化）。
 *
 * 分页会话随「文件 + 视口尺寸」重建（旋转/改字号时重新分页）；退出时
 * close 释放归档句柄。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NativeEpubScreen(
    bookKey: String,
    onBack: () -> Unit,
    viewModel: LibraryViewModel = viewModel(),
    /**
     * Optional CFI target to jump to directly (from Notes tab annotation).
     * Takes priority over the stored reading position. Null → resume last-read.
     */
    initialCfi: String? = null,
    /**
     * Navigation to the translation credentials form (设置 → 翻译与 AI).
     * Shown by the popup when no API key is configured; null → hide the link.
     */
    onOpenTranslateSettings: (() -> Unit)? = null,
    assets: ReaderAssetHost = ReaderAssetHost.NONE,
) {
    val context = LocalContext.current
    val book by viewModel.book(bookKey).collectAsStateWithLifecycle(initialValue = null)
    // 阅读进度（bookKey → 位置 CFI）：非 Room 键值存储，schema.lock 五表保持干净。
    val progressPrefs = remember(context) { ReaderProgressPrefs(context) }
    RememberReadingSession(bookKey)
    val file = remember(book?.key, book?.path, book?.format) {
        val b = book ?: return@remember null
        ReaderFiles.resolveBookFile(
            booksDir = File(context.filesDir, "books"),
            bookKey = b.key,
            format = b.format,
            recordedPath = b.path,
        )
    }

    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val featurePrefs = remember(context) { ReaderFeaturePrefs(context) }
    var features by remember { mutableStateOf(featurePrefs.load()) }
    fun commitFeatures(next: ReaderFeatureState) {
        features = next
        featurePrefs.save(next)
        ReaderTextPipeline.setRulesJson(next.textRulesJson)
    }
    fun setFontScale(v: Float) {
        commitFeatures(features.copy(fontScale = v.coerceIn(ReaderFeatureCodec.FONT_SCALE_MIN, ReaderFeatureCodec.FONT_SCALE_MAX)))
    }
    val fontScale = features.fontScale
    DisposableEffect(featurePrefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            features = featurePrefs.load()
            ReaderTextPipeline.setRulesJson(features.textRulesJson)
        }
        featurePrefs.register(listener)
        onDispose { featurePrefs.unregister(listener) }
    }
    val format = book?.format?.lowercase()
    val useFoliate = ReaderEnginePolicy.useFoliate(format, BuildConfig.READER_ENGINE)
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var foliateCfi by remember { mutableStateOf("") }
    var foliateFraction by remember { mutableStateOf(0.0) }
    var foliateLabel by remember { mutableStateOf("") }
    var foliateToc by remember { mutableStateOf<List<FoliateHostBridge.TocItem>>(emptyList()) }
    var foliateReady by remember { mutableStateOf(false) }
    var foliateSearchHits by remember { mutableStateOf<List<FoliateHostBridge.SearchHit>>(emptyList()) }
    var selectionExcerpt by remember { mutableStateOf("") }
    var selectionCfi by remember { mutableStateOf("") }
    var showSelectionMenu by remember { mutableStateOf(false) }
    var footnoteText by remember { mutableStateOf<String?>(null) }
    var pendingFootnoteHref by remember { mutableStateOf<String?>(null) }
    val showChromeState = remember { mutableStateOf(ReaderPageGestures.CHROME_VISIBLE_ON_OPEN) }
    var showChrome by showChromeState
    val foliateBridge = remember(useFoliate, assets.baseUrl, file) {
        if (!useFoliate || file == null || !assets.available) null
        else FoliateHostBridge(
            context = context,
            baseUrl = assets.baseUrl,
            eventSink = object : FoliateHostBridge.EventSink {
                override fun onEvent(name: String, payload: JSONObject) {
                    when (name) {
                        "relocate" -> {
                            val cfi = payload.optString("cfi", "")
                            val frac = payload.optDouble("fraction", 0.0)
                            val label = payload.optString("label", "")
                            mainHandler.post {
                                foliateCfi = cfi
                                foliateFraction = frac
                                if (label.isNotBlank()) foliateLabel = label
                            }
                        }
                        "selection" -> {
                            val excerpt = payload.optString("excerpt", "")
                            val cfi = payload.optString("cfi", "")
                            if (!FoliateSelectionMenu.visible(excerpt)) return
                            mainHandler.post {
                                selectionExcerpt = excerpt
                                selectionCfi = cfi
                                showSelectionMenu = true
                            }
                        }
                        "footnote" -> {
                            val excerpt = payload.optString("excerpt", "")
                            val href = payload.optString("href", "")
                            mainHandler.post {
                                when (val decision = FoliateFootnote.decide(excerpt, href)) {
                                    is FoliateFootnote.Decision.Show -> footnoteText = decision.text
                                    is FoliateFootnote.Decision.Navigate -> {
                                        footnoteText = null
                                        pendingFootnoteHref = decision.href
                                    }
                                    FoliateFootnote.Decision.Ignore -> Unit
                                }
                            }
                        }
                        "toggleChrome" -> mainHandler.post {
                            showChromeState.value = !showChromeState.value
                        }
                    }
                }
            },
        )
    }
    DisposableEffect(foliateBridge) {
        val view = foliateBridge?.view
        if (view == null) {
            onDispose { }
        } else {
            var downX = 0f
            var downY = 0f
            view.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.x
                        downY = event.y
                    }
                    MotionEvent.ACTION_UP -> {
                        val gestureCfg = ReaderGestureRuntime.config
                        val edge = ReaderGestureCodec.clampedTapEdge(gestureCfg)
                        val tap = ReaderPageGestures.tapIfNotDrag(
                            downX,
                            downY,
                            event.x,
                            event.y,
                            v.width.toFloat(),
                            edgeFraction = edge,
                        )
                        val swipe = if (tap == null) {
                            ReaderPageGestures.onHorizontalSwipe(
                                event.x - downX,
                                event.y - downY,
                                enabled = gestureCfg.swipePageTurn,
                            )
                        } else {
                            null
                        }
                        when (tap ?: swipe) {
                            ReaderPageGesture.TOGGLE_CHROME ->
                                v.post { showChromeState.value = !showChromeState.value }
                            ReaderPageGesture.PREV_PAGE ->
                                v.post { foliateBridge?.prevPage() }
                            ReaderPageGesture.NEXT_PAGE ->
                                v.post { foliateBridge?.nextPage() }
                            else -> Unit
                        }
                    }
                }
                false
            }
            onDispose { view.setOnTouchListener(null) }
        }
    }
    // 会话按 (文件, 格式, 视口, 字号) 重建：尺寸/字号变化时重新分页。
    //
    // P9-anr (round-2 真机根因): 分页 open 不能跑在主 UI 线程。原实现在
    // remember {} 里内联执行 ReaderSessionFactory.open(...) —— 整本 EPUB
    // 解包 + 逐页分页 + 逐字符 Paint 测量都压在 Compose 的 UI 线程上。JVM
    // 单测用 10 章小 fixture + 确定性 measurer 且 JVM 已热，毫秒级全绿；真机
    // (OPPO PKT110)上冷启动 JIT + 几百页的书要数十秒 → 系统在 5s
    // "Input dispatching timed out" 后强杀进程 (ANR logcat: "MotionEvent
    // action=DOWN ... Waited 5000ms")，即「点开书就 ANR 闪退」。
    // 修复：open 挪进 LaunchedEffect 的 withContext(Dispatchers.IO)。
    // Paint.measureText 走 TextSizeCache (synchronized) — 纯读跨线程安全；
    // UI 线程保持响应，分页期间显示进度条，session 落地后首帧渲染页面。
    // 旧 session 在新 session 替换时关闭，避免归档句柄泄漏。
    var session by remember { mutableStateOf<ReaderSession?>(null) }
    var opening by remember { mutableStateOf(true) }
    var openError by remember { mutableStateOf<String?>(null) }
    var moreMenu by remember { mutableStateOf(false) }
    var showAppearance by remember { mutableStateOf(false) }
    // openKey 变化（翻页不触发；旋转/字号/换文件触发）→ 重新分页。
    val openKey = Triple(
        file,
        viewport,
        listOf(
            format,
            features.fontScale,
            features.lineHeightRatio,
            features.marginHorizontalPx,
            features.textRulesJson,
            features.columnsPerPage,
            features.fontKey,
        ),
    )
    LaunchedEffect(openKey) {
        if (useFoliate) return@LaunchedEffect
        val (f, vp, rest) = openKey
        val fmt = rest[0] as String?
        if (f == null || vp.width == 0 || vp.height == 0 || fmt == null) {
            session = null
            opening = false
            return@LaunchedEffect
        }
        opening = true
        openError = null
        ReaderTextPipeline.setRulesJson(features.textRulesJson)
        val prev = session
        val density = context.resources.displayMetrics.density
        val margin = ReaderFeatureCodec.clampedMargin(features)
        val opened = withContext(Dispatchers.IO) {
            runCatching {
                ReaderSessionFactory.open(
                    format = fmt,
                    file = f,
                    viewportWidthPx = vp.width.toFloat(),
                    viewportHeightPx = vp.height.toFloat(),
                    measurer = AndroidTextMeasurer(
                        density,
                        FontManager.typefaceFor(context, features.fontKey.ifBlank { null }),
                    ),
                    options = PaginatorOptions(
                        columnsPerPage = ReaderFeatureCodec.clampedColumns(features),
                        fontSizePx = ReaderFeatureCodec.fontSizePx(features),
                        lineHeightMultiple = ReaderFeatureCodec.clampedLineHeight(features),
                        marginHorizontalPx = margin,
                        marginVerticalPx = margin,
                    ),
                )
            }
        }
        opened.exceptionOrNull()?.let { err ->
            AppLog.e("NativeEpub", "paginate failed: ${f.name}", err)
            openError = err.message ?: err.javaClass.simpleName
        }
        val s = opened.getOrNull()
        if (s != null) {
            session = s
            prev?.close()
        } else {
            session = null
        }
        opening = false
    }

    LaunchedEffect(foliateBridge, file, format, initialCfi, useFoliate) {
        if (!useFoliate) return@LaunchedEffect
        if (foliateBridge == null || file == null || format == null) {
            if (file != null && !assets.available) {
                openError = "engine unavailable"
            }
            opening = false
            return@LaunchedEffect
        }
        opening = true
        openError = null
        foliateReady = false
        val opened = withContext(Dispatchers.IO) {
            runCatching {
                val published = if (format == "docx") {
                    val html = DocxDocument.htmlFromFile(file)
                    val tmp = File(context.cacheDir, "$bookKey.html")
                    tmp.writeText(html)
                    assets.publish(tmp) ?: error("publish failed")
                } else {
                    assets.publish(file) ?: error("publish failed")
                }
                foliateBridge.bootstrap()
                foliateBridge.open(published, format)
                val items = foliateBridge.toc()
                val resume = initialCfi?.takeIf { it.isNotBlank() }
                    ?: progressPrefs.cfiOf(bookKey)
                if (!resume.isNullOrBlank()) foliateBridge.goTo(resume)
                items
            }
        }
        opened.exceptionOrNull()?.let { err ->
            AppLog.e("NativeEpub", "foliate open failed: ${file.name}", err)
            openError = err.message ?: err.javaClass.simpleName
            foliateToc = emptyList()
            foliateReady = false
        }
        opened.getOrNull()?.let {
            foliateToc = it
            foliateReady = true
        }
        opening = false
    }
    DisposableEffect(foliateBridge, file) {
        onDispose {
            foliateBridge?.close()
            file?.let { assets.release(it) }
        }
    }
    LaunchedEffect(foliateCfi, foliateFraction, useFoliate, book?.key) {
        if (!useFoliate) return@LaunchedEffect
        val key = book?.key ?: return@LaunchedEffect
        if (foliateCfi.isNotBlank()) {
            progressPrefs.save(key, foliateCfi, foliateFraction)
        }
    }

    val currentPageState = remember { mutableIntStateOf(0) }
    var currentPage by currentPageState
    // 打开恢复：优先用 initialCfi（笔记跳转），否则按上次位置 CFI 落页（无进度 → 第 0 页）。
    // P9-anr (round-2): resumePage 是**扫描全书找目标 CFI → 页**，几百页的书
    // 在主线程跑会再次 ANR — 与 session.open 同理，放 IO。
    LaunchedEffect(session) {
        val target = initialCfi?.takeIf { it.isNotBlank() }
            ?: book?.key?.let { progressPrefs.cfiOf(it) }
        val pg = withContext(Dispatchers.IO) { session?.resumePage(target) } ?: 0
        currentPage = pg
    }
    // 翻页写回进度（位置 CFI；与桌面 recordLocation 同一寻址格式）。
    LaunchedEffect(session, currentPage) {
        val key = book?.key ?: return@LaunchedEffect
        val s = session ?: return@LaunchedEffect
        if (s.pageCount > 0) {
            s.cfiForPage(currentPage)?.let { cfi ->
                progressPrefs.save(key, cfi, ReaderProgressMath.fraction(currentPage, s.pageCount))
            }
        }
    }

    val pageCount = session?.pageCount ?: 0
    DisposableEffect(foliateBridge, useFoliate, pageCount) {
        ReaderPageTurn.bind { gesture ->
            when (gesture) {
                ReaderPageGesture.PREV_PAGE -> {
                    if (useFoliate) foliateBridge?.prevPage()
                    else if (currentPageState.intValue > 0) currentPageState.intValue--
                    true
                }
                ReaderPageGesture.NEXT_PAGE -> {
                    if (useFoliate) foliateBridge?.nextPage()
                    else if (currentPageState.intValue < pageCount - 1) currentPageState.intValue++
                    true
                }
                else -> false
            }
        }
        onDispose { ReaderPageTurn.unbind() }
    }
    val lines = session?.pageLines(currentPage) ?: emptyList()
    val cfi = if (pageCount > 0) session?.cfiForPage(currentPage) else null

    // ── 书签 ──────────────────────────────────────────────────────────────
    val db: KoodoDatabase = KoodoDatabaseProvider.get(context)
    var bookmarkFlash by remember { mutableStateOf(false) }

    // P9-r3-T-mv0hf34m-3j1k07: 替换 9 处 MainScope().launch → readerScope.launch.
    // readerScope 由 Composition 持有，离开 Composition (Activity finish 或导航离开)
    // 时自动取消 —— 避免 Activity 泄漏与配置变更 (旋转/字号) 时协程错过取消时机。
    val readerScope = rememberCoroutineScope()
    LaunchedEffect(pendingFootnoteHref) {
        val href = pendingFootnoteHref ?: return@LaunchedEffect
        if (href.isBlank()) return@LaunchedEffect
        withContext(Dispatchers.IO) { runCatching { foliateBridge?.goTo(href) } }
        pendingFootnoteHref = null
    }

    fun addBookmark() {
        if (useFoliate) {
            val cfi = foliateCfi.ifBlank { return }
            val entity = BookmarkEntity(
                key = System.currentTimeMillis().toString(),
                bookKey = bookKey,
                cfi = cfi,
                percentage = foliateFraction.toFloat().toString(),
                chapter = foliateToc.firstOrNull()?.label.orEmpty(),
            )
            readerScope.launch {
                db.bookmarkDao().upsert(entity)
                bookmarkFlash = true
                delay(1500)
                bookmarkFlash = false
            }
            return
        }
        val cfi = session?.cfiForPage(currentPage) ?: return
        val s = session ?: return
        if (s.pageCount <= 0) return
        // P9-anr (round-3): 用 chapterOf 避免 O(n) 扫描。
        val chapterIdx = if (s is PagedDocumentSession) s.chapterOf(currentPage)
                         else (0 until s.chapterCount)
                             .lastOrNull { c -> s.pageOfChapter(c) <= currentPage } ?: 0
        val entity = BookmarkEntity(
            key = System.currentTimeMillis().toString(),
            bookKey = bookKey,
            cfi = cfi,
            percentage = ((currentPage + 1).toFloat() / s.pageCount).toString(),
            chapter = s.chapterLabel(chapterIdx),
        )
        readerScope.launch {
            db.bookmarkDao().upsert(entity)
            bookmarkFlash = true
            delay(1500)
            bookmarkFlash = false
        }
    }

    // ── 全页高亮集合（blockIndex → 是否需要画背景色） ─────────────────────────
    // 监听 noteDao：新增/删除笔记都自动触发重渲染
    val bookNotes by db.noteDao().observeForBook(bookKey)
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val highlightBlockColors: Map<Int, Long> = remember(bookNotes, session, currentPage) {
        val s = session ?: return@remember emptyMap()
        val layout = s.layoutResult() ?: return@remember emptyMap()
        bookNotes.mapNotNull { note ->
            val cfi = note.cfi ?: return@mapNotNull null
            val pos = CfiAddressing.fromCfi(cfi, layout) ?: return@mapNotNull null
            val line = layout.lineAt(pos) ?: return@mapNotNull null
            if (line.page == currentPage) line.blockIndex to (note.color ?: 0x99FFFFL) else null
        }.toMap()
    }

    // ── 全文搜索 ──────────────────────────────────────────────────────────────
    var showSearch by remember { mutableStateOf(false) }
    // P9-anr (round-3): 全书文本拼接 + SearchIndex 构建在**首次组合**时跑在主
    // 线程 —— 几百章的书上可能 1s+ 卡顿；挪 IO + IO-dispatched LaunchedEffect
    // 让构建期间 searchIndex 暂为空（搜索按钮在 ready 后自动可用，
    // remember key = bookKey/session 不变则不重复构建）。
    var searchIndex by remember { mutableStateOf<SearchIndex?>(null) }
    LaunchedEffect(bookKey, session) {
        val s = session ?: run { searchIndex = null; return@LaunchedEffect }
        if (s.chapterCount == 0) { searchIndex = null; return@LaunchedEffect }
        searchIndex = withContext(Dispatchers.IO) {
            val chapters = (0 until s.chapterCount).map { i ->
                ChapterText(
                    spineIndex = i,
                    title = s.chapterLabel(i),
                    text = s.chapterText(i),
                    cfiStart = "epubcfi(/6/${i + 1})",
                )
            }
            SearchIndex.build(bookKey, chapters)
        }
    }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<SearchHit>>(emptyList()) }

    fun doSearch() {
        val q = searchQuery.trim()
        if (q.isEmpty()) return
        if (useFoliate) {
            readerScope.launch {
                foliateSearchHits = withContext(Dispatchers.IO) {
                    runCatching { foliateBridge?.search(q).orEmpty() }.getOrDefault(emptyList())
                }
            }
            return
        }
        searchIndex
            ?.search(SearchQuery(bookKey, q))
            ?.take(30)
            ?.let { searchResults = it }
    }

    fun jumpToHit(hit: SearchHit) {
        val s = session ?: return
        currentPage = s.pageOfChapter(hit.spineIndex)
        showSearch = false
    }

    fun jumpToFoliateHit(hit: FoliateHostBridge.SearchHit) {
        val target = hit.cfi.ifBlank { hit.href }
        if (target.isBlank()) return
        readerScope.launch(Dispatchers.IO) { runCatching { foliateBridge?.goTo(target) } }
        showSearch = false
    }

    // ── 双击选词 + 高亮/笔记 ────────────────────────────────────────────────
    var selectedLine: LayoutLine? by remember { mutableStateOf(null) }
    var imagePreview by remember { mutableStateOf<LayoutLine?>(null) }
    var footnoteBody by remember { mutableStateOf<String?>(null) }
    var showAi by remember { mutableStateOf(false) }
    var aiQuestion by remember { mutableStateOf("") }
    var aiAnswer by remember { mutableStateOf("") }
    var aiBusy by remember { mutableStateOf(false) }
    var showNoteDialog by remember { mutableStateOf(false) }
    var noteText by remember { mutableStateOf("") }
    // 字典
    val dictRepo = remember(context) {
        DictRepository(context.applicationContext.filesDir)
    }
    var showDictDialog by remember { mutableStateOf(false) }
    var dictResultText by remember { mutableStateOf("") }
    // 翻译弹窗（P6 feature/translate）：弹窗本身 stateless，这里是选词源宿主。
    // 控制器持有加密凭据库 + 历史库，选词菜单一键触发翻译。
    val translateController = rememberTranslationPopupController(context)
    val translateState by translateController.state.collectAsStateWithLifecycle()
    // 目录
    var showToc by remember { mutableStateOf(false) }
    // 高亮颜色（0 = 黄色 default）
    // 0=yellow 1=green 2=pink 3=blue
    var selectedHighlightColor by remember { mutableStateOf(0) }

    // ── TTS 朗读 ─────────────────────────────────────────────────────────────
    var showTtsControl by remember { mutableStateOf(false) }
    var connectedTtsSvc by remember { mutableStateOf<ForegroundTtsService?>(null) }

    // TTS 长任务用了 ServiceConnection 30 次重试循环，明显长于 readerScope 的平均寿命，
    // 单独的 ttsScope 拿 cancellation 语义专门驱动 (含 setHostCommandListener 嵌套回调 line 313)。
    val ttsScope = rememberCoroutineScope()

    val ttsServiceConn = remember(context) {
        object : android.content.ServiceConnection {
            override fun onServiceConnected(
                name: android.content.ComponentName,
                service: android.os.IBinder,
            ) {
                connectedTtsSvc = (service as ForegroundTtsService.LocalBinder).service()
            }
            override fun onServiceDisconnected(name: android.content.ComponentName) {
                connectedTtsSvc = null
            }
        }
    }

    fun startTts() {
        val s = session ?: return
        val chapterIdx = (0 until s.chapterCount)
            .lastOrNull { c -> s.pageOfChapter(c) <= currentPage } ?: 0
        val chapterText = ReaderPurifiedText.plain(s.chapterText(chapterIdx), features.textRulesJson)
        if (chapterText.isBlank()) return
        if (connectedTtsSvc == null) {
            context.bindService(
                android.content.Intent(context, ForegroundTtsService::class.java),
                ttsServiceConn,
                android.content.Context.BIND_AUTO_CREATE,
            )
        }
        ttsScope.launch {
            for (i in 0 until 30) { if (connectedTtsSvc != null) break; delay(100) }
            val svc = connectedTtsSvc ?: return@launch
            svc.setHostCommandListener { cmd ->
                if (cmd == TtsMediaCommand.NEXT) {
                    readerScope.launch {
                        delay(300)
                        if (currentPage < s.pageCount - 1) {
                            currentPage++
                            val nextIdx = (0 until s.chapterCount)
                                .lastOrNull { c -> s.pageOfChapter(c) <= currentPage }
                                ?: s.chapterCount - 1
                            val nextText = ReaderPurifiedText.plain(
                                s.chapterText(nextIdx),
                                features.textRulesJson,
                            )
                            if (nextText.isNotBlank()) {
                                svc.loadChapter(bookKey, book?.name ?: "", s.chapterLabel(nextIdx), nextText)
                                svc.play()
                            }
                        }
                    }
                }
            }
            svc.loadChapter(bookKey, book?.name ?: "", s.chapterLabel(chapterIdx), chapterText)
            svc.play()
            showTtsControl = true
        }
    }

    fun stopTts() {
        connectedTtsSvc?.stop()
        showTtsControl = false
    }

    DisposableEffect(ttsServiceConn) {
        onDispose { runCatching { context.unbindService(ttsServiceConn) } }
    }

    fun isAlreadyHighlighted(line: LayoutLine): Boolean =
        line.blockIndex in highlightBlockColors

    fun currentSelectionCfi(): String {
        if (selectionCfi.isNotBlank()) return selectionCfi
        val line = selectedLine ?: return if (useFoliate) foliateCfi else ""
        return CfiAddressing.toCfi(LayoutPosition(
            line.position.spineIndex,
            line.position.elementIndex,
            line.start,
        ))
    }

    fun currentSelectionText(): String =
        selectionExcerpt.ifBlank { selectedLine?.text.orEmpty() }

    fun highlightCss(color: Long): String = when (color) {
        0x9988FF88L -> "#88ff88"
        0x99FFAAFFL -> "#ffaaff"
        0x99AACCFFL -> "#aaccff"
        else -> "#ffe066"
    }

    fun saveHighlight(colorIdx: Int = 0) {
        val excerpt = currentSelectionText().trim()
        if (excerpt.isEmpty()) return
        val colors = listOf(0x99FFFFL, 0x9988FF88L, 0x99FFAAFFL, 0x99AACCFFL)
        val color = if (colorIdx < colors.size) colors[colorIdx] else 0x99FFFFL
        val cfi = currentSelectionCfi()
        val s = session
        val chapterIdx = if (s != null) {
            (0 until s.chapterCount).lastOrNull { c -> s.pageOfChapter(c) <= currentPage } ?: 0
        } else 0
        val entity = NoteEntity(
            key = System.currentTimeMillis().toString(),
            bookKey = bookKey,
            chapter = s?.chapterLabel(chapterIdx) ?: foliateLabel,
            chapterIndex = chapterIdx.toLong(),
            text = excerpt,
            cfi = cfi,
            notes = "",  // empty = plain highlight
            percentage = if (useFoliate) {
                foliateFraction.toString()
            } else {
                val pages = s?.pageCount ?: 1
                ((currentPage + 1).toFloat() / (pages + 0.1f)).toString()
            },
            color = color,
        )
        readerScope.launch {
            db.noteDao().upsert(entity)
            if (useFoliate && cfi.isNotBlank()) {
                withContext(Dispatchers.IO) {
                    runCatching { foliateBridge?.addHighlight(cfi, highlightCss(color)) }
                }
            }
        }
        showSelectionMenu = false
    }

    fun deleteHighlight() {
        val cfi = currentSelectionCfi()
        if (cfi.isBlank()) {
            showSelectionMenu = false
            return
        }
        readerScope.launch {
            val notes = db.noteDao().observeForBook(bookKey).first()
            val target = notes.firstOrNull { it.cfi == cfi }
            target?.let { db.noteDao().deleteByKey(it.key) }
            if (useFoliate) {
                withContext(Dispatchers.IO) {
                    runCatching { foliateBridge?.removeHighlight(cfi) }
                }
            }
            showSelectionMenu = false
        }
    }

    val dictionaryLabel = t("Dictionary")
    fun lookupDictionary() {
        val word = currentSelectionText().trim().takeIf { it.isNotEmpty() } ?: return
        readerScope.launch {
            val result = withContext(Dispatchers.IO) { dictRepo.lookup(word) }
            val htmlSafe = result.html
            dictResultText = if (htmlSafe != null) {
                "【${result.entry}】\n" +
                    htmlSafe.replace(Regex("<[^>]+>"), "").replace(Regex("&[a-z]+;"), "·").trim()
            } else {
                "$dictionaryLabel: $word"
            }
            showDictDialog = true
            showSelectionMenu = false
        }
    }

    fun startTranslation() {
        val raw = currentSelectionText().trim().takeIf { it.isNotEmpty() } ?: return
        val text = ReaderPurifiedText.plain(raw, features.textRulesJson)
        val cfi = currentSelectionCfi()
        translateController.show(text, bookKey = bookKey, cfi = cfi)
        showSelectionMenu = false
        readerScope.launch { translateController.translate() }
    }

    fun saveNote() {
        val excerpt = currentSelectionText().trim()
        if (excerpt.isEmpty()) return
        val s = session
        val chapterIdx = when {
            s is PagedDocumentSession -> s.chapterOf(currentPage)
            s != null -> (0 until s.chapterCount)
                .lastOrNull { c -> s.pageOfChapter(c) <= currentPage } ?: 0
            else -> 0
        }
        val cfi = currentSelectionCfi()
        val entity = NoteEntity(
            key = System.currentTimeMillis().toString(),
            bookKey = bookKey,
            chapter = s?.chapterLabel(chapterIdx) ?: foliateLabel,
            chapterIndex = chapterIdx.toLong(),
            text = excerpt,
            cfi = cfi,
            notes = noteText,
            percentage = if (useFoliate) {
                foliateFraction.toString()
            } else {
                val pages = s?.pageCount ?: 1
                ((currentPage + 1).toFloat() / (pages + 0.1f)).toString()
            },
        )
        readerScope.launch { db.noteDao().upsert(entity) }
        showNoteDialog = false
        noteText = ""
    }

    fun openUri(uri: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
        }
    }

    fun handleLink(href: String) {
        when (val action = LinkRouter.DEFAULT.route(href)) {
            is LinkAction.OpenBrowser -> openUri(action.uri)
            is LinkAction.OpenBrowserIntent -> openUri(action.uri)
            is LinkAction.InternalJump -> {
                val s = session ?: return
                currentPage = s.resumePage(action.sourceHref)
            }
            is LinkAction.Footnote -> footnoteBody = action.text
            LinkAction.None -> {
                val s = session ?: return
                val hrefs = (0 until s.chapterCount).map { s.chapterHref(it) }
                val idx = ReaderHrefJump.chapterIndex(href, hrefs)
                if (idx != null) currentPage = s.pageOfChapter(idx) else openUri(href)
            }
        }
    }

    fun lookupFootnote(number: Int): String {
        val s = session ?: return "[$number]"
        val idx = if (s is PagedDocumentSession) s.chapterOf(currentPage)
        else (0 until s.chapterCount).lastOrNull { c -> s.pageOfChapter(c) <= currentPage } ?: 0
        val text = s.chapterText(idx)
        val match = Regex("\\[$number\\]\\s*([^\\[]{8,800})").find(text)
        return match?.groupValues?.getOrNull(1)?.trim() ?: "[$number]"
    }

    fun runAi(summarize: Boolean) {
        val s = session ?: return
        val idx = if (s is PagedDocumentSession) s.chapterOf(currentPage)
        else (0 until s.chapterCount).lastOrNull { c -> s.pageOfChapter(c) <= currentPage } ?: 0
        val excerpt = s.chapterText(idx).take(6_000)
        val assistant = AiAssistant(AiModelPrefs(context).load(), AppHttpTransport())
        readerScope.launch {
            aiBusy = true
            val out = if (summarize) {
                assistant.summarize(excerpt)
            } else {
                assistant.ask(aiQuestion, excerpt)
            }
            aiAnswer = when (out) {
                is AiOutcome.Success -> out.text
                AiOutcome.NotConfigured -> "Configure an AI service"
                is AiOutcome.Failure -> out.detail
            }
            aiBusy = false
        }
    }

    fun selectAt(offset: androidx.compose.ui.geometry.Offset) {
        val s = session ?: return
        val layout = s.layoutResult() ?: return
        val pos = layout.positionAt(offset.x, offset.y) ?: return
        val line = s.pageLines(currentPage)
            .firstOrNull { it.containsY(offset.y) && offset.x in it.x..it.rightPx }
            ?: layout.lineAt(pos)
            ?: return
        if (line.imageSrc != null) return  // 不选图片行
        selectedLine = line
        selectionExcerpt = line.text
        selectionCfi = CfiAddressing.toCfi(LayoutPosition(
            line.position.spineIndex,
            line.position.elementIndex,
            line.start,
        ))
        showSelectionMenu = true
    }

    // 当前页所有图片位图（spineIndex:"src" → Bitmap），一次性解码避免每帧 IO。
    // P9-anr (round-2): 大图 PNG/JPEG 解码在 remember{} 主线程跑会再卡 5s+，
    // 挪 Dispatchers.IO；页码切换时新 effect 取消旧 effect（LaunchedEffect
    // key 含 currentPage 自动取消），避免累积解码。
    var pageImages by remember { mutableStateOf<Map<String, android.graphics.Bitmap?>>(emptyMap()) }
    LaunchedEffect(session, currentPage) {
        val s = session ?: run { pageImages = emptyMap(); return@LaunchedEffect }
        val lines = s.pageLines(currentPage).filter { it.imageSrc != null }
        if (lines.isEmpty()) { pageImages = emptyMap(); return@LaunchedEffect }
        pageImages = withContext(Dispatchers.IO) {
            lines.associate { line ->
                "${line.position.spineIndex}:${line.imageSrc}" to loadBitmap(s, line)
            }
        }
    }

    // 在 composable 上下文捕获颜色：阅读页配色独立于 App 主题
    // （settings-page spec §8.3）——取「外观」设置选定的 ThemeKind 预设，
    // 未设置的默认预设 = 白底黑字（与桌面 themeUtil 默认一致）。
    // Canvas 的 DrawScope lambda 不是 composable 上下文，不能直接读 MaterialTheme。
    val appearancePrefs = remember(context) { ShellAppearancePrefs(context) }
    DisposableEffect(appearancePrefs) { onDispose { appearancePrefs.close() } }
    val readerThemeKind by appearancePrefs.readerThemeKindFlow.collectAsStateWithLifecycle()
    val readerPreset = remember(readerThemeKind) { readerThemeKind.builtInPreset() }
    val bgColor = ThemeSpecBridge.background(readerPreset)
    val fgColor = ThemeSpecBridge.foreground(readerPreset)
    val viewSettings = remember(
        readerThemeKind, features.fontScale, features.fontKey,
        features.lineHeightRatio, features.marginHorizontalPx,
        features.columnsPerPage, features.scrolled, features.doublePage,
        features.textRulesJson, features.customBookCss, features.customBookCssEnabled,
        features.perfTier,
    ) {
        ReadestViewSettings.from(readerThemeKind, features)
    }
    LaunchedEffect(useFoliate, foliateReady, viewSettings) {
        if (!useFoliate || !foliateReady) return@LaunchedEffect
        val host = foliateBridge ?: return@LaunchedEffect
        val bgArgb = CssColor.parse(viewSettings.bg).toInt()
        withContext(Dispatchers.IO) {
            runCatching { host.setViewSettings(viewSettings, bgArgb) }
        }
    }
    LaunchedEffect(useFoliate, foliateReady, bookNotes) {
        if (!useFoliate || !foliateReady) return@LaunchedEffect
        val host = foliateBridge ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            bookNotes.forEach { note ->
                val cfi = note.cfi?.takeIf { it.isNotBlank() } ?: return@forEach
                runCatching { host.addHighlight(cfi, highlightCss(note.color ?: 0x99FFFFL)) }
            }
        }
    }
    val chapterInfo = remember(session, currentPage) {
        session?.let { s ->
            // P9-anr (round-3): 原实现 O(chapters×log pages)（每次 currentPage
            // 变更都 lastOrNull 扫描）；现用 PagedDocumentSession.chapterOf
            // 一次 O(log chapters) binary search。
            val idx = if (s is PagedDocumentSession) s.chapterOf(currentPage)
                      else (0 until s.chapterCount)
                          .lastOrNull { c -> s.pageOfChapter(c) <= currentPage } ?: 0
            "c ${idx + 1}/${s.chapterCount} · ${s.chapterLabel(idx)}"
        } ?: ""
    }

    MaterialTheme(colorScheme = ThemeSpecBridge.readerScheme(readerPreset)) {
    Box(modifier = Modifier.fillMaxSize()) {
    ReaderImmersiveBars(showChrome)
    Scaffold(
        containerColor = bgColor,
        contentColor = fgColor,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (showChrome) {
                TopAppBar(
                    title = {
                        if (bookmarkFlash) {
                            Text("✓ ${t("Bookmark")}", color = MaterialTheme.colorScheme.primary, maxLines = 1)
                        } else {
                            Text(
                                book?.name ?: "",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(painterResource(R.drawable.ic_back), contentDescription = "Back")
                        }
                    },
                    actions = {
                        // Primary chrome stays on-screen on a phone TopAppBar.
                        // Font / bookmark / TTS used to sit here too and pushed
                        // TOC off the right edge — the packaged native APK then
                        // looked like it had no contents list.
                        if (ReaderChromeAction.TOC in ReaderChromeAction.PRIMARY) {
                            IconButton(onClick = { showToc = true }) {
                                Icon(
                                    painterResource(R.drawable.ic_list_view),
                                    contentDescription = t("Content"),
                                )
                            }
                        }
                        if (ReaderChromeAction.SEARCH in ReaderChromeAction.PRIMARY) {
                            IconButton(onClick = { showSearch = true }) {
                                Icon(
                                    painterResource(R.drawable.ic_search),
                                    contentDescription = t("Search"),
                                )
                            }
                        }
                        Box {
                            IconButton(onClick = { moreMenu = true }) {
                                Icon(
                                    painterResource(R.drawable.ic_more),
                                    contentDescription = t("More"),
                                )
                            }
                            DropdownMenu(
                                expanded = moreMenu,
                                onDismissRequest = { moreMenu = false },
                            ) {
                                if (FoliateAppearance.showInReaderSheet(useFoliate)) {
                                    DropdownMenuItem(
                                        text = { Text(t("Appearance")) },
                                        onClick = {
                                            moreMenu = false
                                            showAppearance = true
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("A−  ${(fontScale * 100).toInt()}%") },
                                    onClick = { setFontScale(fontScale - 0.1f) },
                                )
                                DropdownMenuItem(
                                    text = { Text("A+") },
                                    onClick = { setFontScale(fontScale + 0.1f) },
                                )
                                DropdownMenuItem(
                                    text = { Text(t("Bookmark")) },
                                    onClick = {
                                        moreMenu = false
                                        addBookmark()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(t("Speak the text")) },
                                    onClick = {
                                        moreMenu = false
                                        if (showTtsControl) stopTts() else startTts()
                                    },
                                )
                                if (!useFoliate) {
                                    DropdownMenuItem(
                                        text = { Text(t("Fast reading mode (make the first half of the word bold)")) },
                                        onClick = {
                                            moreMenu = false
                                            commitFeatures(features.copy(bionic = !features.bionic))
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(t("Enable reading ruler")) },
                                        onClick = {
                                            moreMenu = false
                                            commitFeatures(features.copy(ruler = !features.ruler))
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(t("Enable paragraph mode")) },
                                        onClick = {
                                            moreMenu = false
                                            commitFeatures(features.copy(paragraphMode = !features.paragraphMode))
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(t("Enable speed reading")) },
                                        onClick = {
                                            moreMenu = false
                                            commitFeatures(features.copy(speedReading = !features.speedReading))
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(t("Two-column layout")) },
                                        onClick = {
                                            moreMenu = false
                                            val next = if (ReaderFeatureCodec.clampedColumns(features) >= 2) 1 else 2
                                            commitFeatures(features.copy(columnsPerPage = next))
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(t("Ask AI")) },
                                    onClick = {
                                        moreMenu = false
                                        showAi = true
                                    },
                                )
                            }
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (showChrome && (pageCount > 0 || (useFoliate && foliateReady))) {
                Surface(tonalElevation = 2.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showToc = true }
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        val bar = if (useFoliate) {
                            val pct = (foliateFraction * 100).toInt().coerceIn(0, 100)
                            val label = foliateToc.firstOrNull { it.href.isNotBlank() }?.label.orEmpty()
                            listOf(label, "$pct%").filter { it.isNotBlank() }.joinToString(" · ")
                        } else {
                            "$chapterInfo · p ${currentPage + 1}/$pageCount"
                        }
                        Text(
                            bar,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
                // 视口尺寸必须在这里捕获：session 的创建依赖 viewport 非零，而
                // Canvas（原来唯一设置 viewport 的地方）只在 session 非空时才
                // 组合——挂在 Canvas 上是鸡生蛋死锁，首帧后永远空白。挂在
                // 常驻的 Box 上，任何分支都能拿到尺寸并触发重组。
                .onSizeChanged { viewport = it },
        ) {
            when {
                useFoliate && book == null -> Unit
                useFoliate && file == null -> Text(
                    "Book file unavailable on device.",
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                )
                useFoliate && !assets.available -> Text(
                    "The local reflow engine is unavailable for this book.\n" +
                        "(book key: $bookKey)\n\n" +
                        "The loopback asset server must be running.",
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                )
                useFoliate && openError != null -> Text(
                    "Could not open this book.\n(${openError})\nImport it again or use the web viewer.",
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                )
                FoliateReaderLayout.attachWebView(
                    useFoliate = useFoliate,
                    hasFile = file != null,
                    assetsAvailable = assets.available,
                ) -> Box(Modifier.fillMaxSize()) {
                    AndroidView(
                        factory = { foliateBridge?.view ?: android.view.View(it) },
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (FoliateReaderLayout.showLoadingOverlay(foliateReady, openError)) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(Modifier.height(16.dp))
                                Text(t("Loading"), style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
                session == null && viewport == IntSize.Zero -> Unit // 首帧：等尺寸
                session == null && opening && file != null -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    // P9-anr (round-2): 分页期间显示进度条，UI 线程保持响应
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text(
                            t("Loading"),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                session == null -> Text(
                    if (file == null) {
                        "Book file unavailable on device."
                    } else {
                        val detail = openError?.let { "\n($it)" } ?: ""
                        "Could not paginate this EPUB.$detail\nImport it again or use the web viewer."
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                )
                else -> Box(Modifier.fillMaxSize()) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(pageCount, currentPage, features.paragraphMode, session) {
                                detectTapGestures(
                                    onLongPress = { offset ->
                                        if (ReaderPageGestures.onLongPress() == ReaderPageGesture.SELECT) {
                                            selectAt(offset)
                                        }
                                    },
                                    onDoubleTap = { offset ->
                                        if (ReaderPageGestures.onDoubleTap() == ReaderPageGesture.SELECT) {
                                            selectAt(offset)
                                        }
                                    },
                                ) { offset ->
                                    if (showSelectionMenu) {
                                        showSelectionMenu = false
                                        return@detectTapGestures
                                    }
                                    val hit = session?.pageLines(currentPage)?.firstOrNull {
                                        it.containsY(offset.y) && offset.x in it.x..it.rightPx
                                    }
                                    if (hit != null) {
                                        when (
                                            val kind = ReaderTapTarget.classify(
                                                hit.imageSrc,
                                                hit.text,
                                                hit.linkHref,
                                            )
                                        ) {
                                            is ReaderTapKind.Image -> {
                                                imagePreview = hit
                                                return@detectTapGestures
                                            }
                                            is ReaderTapKind.Link -> {
                                                handleLink(kind.href)
                                                return@detectTapGestures
                                            }
                                            is ReaderTapKind.Footnote -> {
                                                footnoteBody = lookupFootnote(kind.number)
                                                return@detectTapGestures
                                            }
                                            ReaderTapKind.None -> Unit
                                        }
                                    }
                                    val edge = ReaderGestureCodec.clampedTapEdge(ReaderGestureRuntime.config)
                                    when (ReaderPageGestures.onTap(offset.x, size.width.toFloat(), edge)) {
                                        ReaderPageGesture.PREV_PAGE ->
                                            if (currentPage > 0) currentPage--
                                        ReaderPageGesture.NEXT_PAGE ->
                                            if (currentPage < pageCount - 1) currentPage++
                                        ReaderPageGesture.TOGGLE_CHROME ->
                                            showChrome = !showChrome
                                        ReaderPageGesture.SELECT -> Unit
                                    }
                                }
                            }
                            .pointerInput(pageCount, currentPage) {
                                var dx = 0f
                                var dy = 0f
                                detectDragGestures(
                                    onDragStart = { dx = 0f; dy = 0f },
                                    onDragEnd = {
                                        when (
                                            ReaderPageGestures.onHorizontalSwipe(
                                                dx,
                                                dy,
                                                enabled = ReaderGestureRuntime.config.swipePageTurn,
                                            )
                                        ) {
                                            ReaderPageGesture.PREV_PAGE ->
                                                if (currentPage > 0) currentPage--
                                            ReaderPageGesture.NEXT_PAGE ->
                                                if (currentPage < pageCount - 1) currentPage++
                                            else -> Unit
                                        }
                                    },
                                    onDrag = { _, amount ->
                                        dx += amount.x
                                        dy += amount.y
                                    },
                                )
                            },
                    ) {
                        drawRect(color = bgColor)
                        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                        paint.color = fgColor.toArgb()
                        paint.typeface = FontManager.typefaceFor(context, features.fontKey.ifBlank { null })
                        for (line in lines) {
                            drawLayoutLine(
                                line,
                                paint,
                                pageImages,
                                highlightBlockColors,
                                bionic = features.bionic,
                            )
                        }
                        if (features.ruler) {
                            val area = ReadingRuler.resolve(
                                fingerY = size.height / 2f,
                                viewportHeight = size.height,
                                config = ReadingRulerConfig(
                                    lineHeight = features.rulerLineHeight,
                                    lineHeightPx = lines.firstOrNull()?.height ?: 24f,
                                    followFinger = false,
                                ),
                            )
                            drawRect(
                                color = fgColor.copy(alpha = 0.12f),
                                topLeft = Offset(0f, area.top),
                                size = Size(size.width, area.height),
                            )
                        }
                    }
                    if (features.paragraphMode && session != null) {
                        ParagraphModeLayer(
                            session = session!!,
                            currentPage = currentPage,
                            onPrevPage = { if (currentPage > 0) currentPage-- },
                            onNextPage = { if (currentPage < pageCount - 1) currentPage++ },
                            onToggleChrome = { showChrome = !showChrome },
                        )
                    }
                    if (features.speedReading && lines.isNotEmpty()) {
                        SpeedReadingLayer(
                            text = lines.joinToString(" ") { it.text },
                            wpm = ReaderFeatureCodec.clampedWpm(features),
                            onTap = { showChrome = !showChrome },
                        )
                    }
                }
            }
        }
    }

    ReaderChromeRevealStrip(
        chromeVisible = showChrome,
        modifier = Modifier.align(Alignment.TopCenter),
        onReveal = { showChrome = true },
    )

    // ── 选中行菜单（叠在阅读页底部，不能作为 Scaffold 的兄弟节点
    // 被 NavHost 顶到屏幕外） ───────────────────────────────────────────────
    if (showSelectionMenu && FoliateSelectionMenu.visible(currentSelectionText())) {
        val excerpt = currentSelectionText()
        val highlighted = selectedLine?.let { isAlreadyHighlighted(it) }
            ?: FoliateSelectionMenu.highlighted(
                currentSelectionCfi(),
                bookNotes.mapNotNull { it.cfi },
            )

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            tonalElevation = 8.dp,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                if (highlighted) {
                    // 已高亮：显示删除 + 字典
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "${t("Highlight")} 「${excerpt.take(24)}」",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                        TextButton(onClick = { deleteHighlight() }) { Text(t("Delete")) }
                        TextButton(onClick = { lookupDictionary() }) { Text(t("Dictionary")) }
                        TextButton(onClick = { startTranslation() }) { Text(t("Translate")) }
                        TextButton(onClick = { showSelectionMenu = false }) { Text(t("Close")) }
                    }
                } else {
                    // 未高亮：高亮(4色) + 笔记 + 字典
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "「${excerpt.take(16)}」",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                        TextButton(onClick = { saveHighlight(0) }) { Text(t("Yellow")) }
                        TextButton(onClick = { saveHighlight(1) }) { Text(t("Green")) }
                        TextButton(onClick = { saveHighlight(2) }) { Text(t("Pink")) }
                        TextButton(onClick = { saveHighlight(3) }) { Text(t("Blue")) }
                    }
                    Row(modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { noteText = ""; showNoteDialog = true }) { Text(t("Note")) }
                        TextButton(onClick = { lookupDictionary() }) { Text(t("Dictionary")) }
                        TextButton(onClick = { startTranslation() }) { Text(t("Translate")) }
                        TextButton(onClick = { showSelectionMenu = false }) { Text(t("Close")) }
                    }
                }
            }
        }
    }
    } // Box wrapping Scaffold + selection overlay

    // ── 翻译弹窗（选词翻译，P6 feature/translate 接线点） ──────────────────────
    if (translateState.visible) {
        val i18n = LocalI18n.current
        val clipboard = LocalClipboardManager.current
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            TranslationPopup(
                state = translateState,
                sources = translateController.sources(),
                onSourceSelected = { id ->
                    readerScope.launch { translateController.switchProviderAndTranslate(id) }
                },
                onRetry = { readerScope.launch { translateController.translate() } },
                onCopy = { payload -> clipboard.setText(AnnotatedString(payload)) },
                onDismiss = { translateController.dismiss() },
                onOpenSettings = onOpenTranslateSettings,
                labels = TranslationPopupLabels.from { key -> i18n.localization.t(key) },
            )
        }
    }

    // ── 字典对话框 ─────────────────────────────────────────────────────────────
    if (showDictDialog) {
        AlertDialog(
            onDismissRequest = { showDictDialog = false },
            title = { Text(t("Dictionary")) },
            text = {
                Text(
                    dictResultText,
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = { showDictDialog = false }) { Text(t("Close")) }
            },
        )
    }

    // ── TTS 控制栏（底部） ────────────────────────────────────────────────────
    if (showTtsControl) {
        val svc = connectedTtsSvc
        if (svc != null) {
            val uiState = try { svc.controlState() } catch (_: Exception) { null }
            if (uiState != null) {
                TtsControlSheet(
                    state = uiState,
                    onCommand = { cmd ->
                        when (cmd) {
                            TtsMediaCommand.PLAY        -> svc.play()
                            TtsMediaCommand.PAUSE       -> svc.pause()
                            TtsMediaCommand.STOP        -> stopTts()
                            TtsMediaCommand.NEXT        -> svc.next()
                            TtsMediaCommand.PREVIOUS    -> svc.previous()
                            TtsMediaCommand.PLAY_PAUSE  -> if (svc.snapshot().state == com.koodoreader.feature.tts.TtsPlaybackState.PLAYING) svc.pause() else svc.play()
                            TtsMediaCommand.FAST_FORWARD -> svc.next()
                            TtsMediaCommand.REWIND      -> svc.previous()
                            else -> {}
                        }
                    },
                    onDismiss = { stopTts() },
                )
            }
        }
    }

    if (FoliateFootnote.showSheet(footnoteText)) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { footnoteText = null },
            sheetState = sheetState,
        ) {
            Text(
                text = t("Notes"),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Text(
                text = footnoteText.orEmpty(),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .verticalScroll(rememberScrollState()),
            )
            TextButton(onClick = { footnoteText = null }) { Text(t("Close")) }
        }
    }

    // ── 目录（底部表，和 PDF outline 同一套交互，避免 AlertDialog+TextButton 像超链接）
    if (showToc) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { showToc = false },
            sheetState = sheetState,
        ) {
            Text(
                text = t("Content"),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.8f),
            ) {
                if (useFoliate) {
                    items(foliateToc) { item ->
                        val label = item.label.ifEmpty { item.href }.ifEmpty { "·" }
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Normal,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val target = item.href.ifBlank { item.cfi }
                                    readerScope.launch(Dispatchers.IO) {
                                        runCatching { foliateBridge?.goTo(target) }
                                    }
                                    showToc = false
                                }
                                .padding(
                                    start = (16 + item.depth * 12).dp,
                                    end = 16.dp,
                                    top = 12.dp,
                                    bottom = 12.dp,
                                ),
                        )
                    }
                } else {
                    items(session?.chapterCount ?: 0) { i ->
                        val label = (session?.chapterLabel(i) ?: "").ifEmpty { "${i + 1}" }
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Normal,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    session?.let { s -> currentPage = s.pageOfChapter(i) }
                                    showToc = false
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }

    // ── 笔记输入对话框 ─────────────────────────────────────────────────────────
    if (showNoteDialog) {
        AlertDialog(
            onDismissRequest = { showNoteDialog = false },
            title = { Text(t("Note")) },
            text = {
                Column {
                    Text(selectedLine?.text.orEmpty().take(80), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = noteText,
                        onValueChange = { noteText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(t("Note")) },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { saveNote() }) { Text(t("Save")) }
            },
            dismissButton = {
                TextButton(onClick = { showNoteDialog = false }) { Text(t("Cancel")) }
            },
        )
    }

    imagePreview?.let { preview ->
        val bmp = pageImages["${preview.position.spineIndex}:${preview.imageSrc}"]
        AlertDialog(
            onDismissRequest = { imagePreview = null },
            title = { Text(t("Image")) },
            text = {
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = preview.text,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(preview.imageSrc.orEmpty())
                }
            },
            confirmButton = {
                TextButton(onClick = { imagePreview = null }) { Text(t("Close")) }
            },
        )
    }
    footnoteBody?.let { body ->
        AlertDialog(
            onDismissRequest = { footnoteBody = null },
            title = { Text(t("Notes")) },
            text = { Text(body) },
            confirmButton = {
                TextButton(onClick = { footnoteBody = null }) { Text(t("Close")) }
            },
        )
    }
    if (showAi) {
        AlertDialog(
            onDismissRequest = { showAi = false },
            title = { Text(t("Ask AI")) },
            text = {
                Column {
                    OutlinedTextField(
                        value = aiQuestion,
                        onValueChange = { aiQuestion = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(t("Ask a question")) },
                    )
                    Spacer(Modifier.height(8.dp))
                    if (aiBusy) CircularProgressIndicator()
                    if (aiAnswer.isNotBlank()) {
                        Text(
                            if (aiAnswer == "Configure an AI service") t("Configure an AI service") else aiAnswer,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { runAi(summarize = false) }, enabled = !aiBusy) {
                    Text(t("Ask AI"))
                }
            },
            dismissButton = {
                TextButton(onClick = { runAi(summarize = true) }, enabled = !aiBusy) {
                    Text(t("Summarize this chapter"))
                }
            },
        )
    }

    // ── 搜索对话框 ──────────────────────────────────────────────────────────────
    if (showSearch) {
        AlertDialog(
            onDismissRequest = { showSearch = false },
            title = { Text(t("Search in book")) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(t("Search")) },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(12.dp))
                    if (useFoliate && foliateSearchHits.isNotEmpty()) {
                        Text("${foliateSearchHits.size}", style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.height(4.dp))
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(280.dp),
                        ) {
                            items(foliateSearchHits) { hit ->
                                Text(
                                    hit.excerpt.ifBlank { hit.href },
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { jumpToFoliateHit(hit) }
                                        .padding(vertical = 6.dp),
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    } else if (searchResults.isEmpty()) {
                        Text(
                            t("Search in book"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text("${searchResults.size}", style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.height(4.dp))
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(280.dp),
                        ) {
                            items(searchResults) { hit ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { jumpToHit(hit) }
                                        .padding(vertical = 6.dp),
                                ) {
                                    Row {
                                        Text(
                                            hit.contextBefore,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        Text(
                                            hit.matchedText,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        Text(
                                            hit.contextAfter,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    Text(
                                        "${hit.spineIndex + 1}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { doSearch() }) { Text(t("Search")) }
            },
            dismissButton = {
                TextButton(onClick = { showSearch = false }) { Text(t("Close")) }
            },
        )
    }

    if (showAppearance) {
        ReaderAppearanceSheet(
            selected = readerThemeKind,
            fontScale = fontScale,
            lineHeight = ReaderFeatureCodec.clampedLineHeight(features),
            marginPx = ReaderFeatureCodec.clampedMargin(features),
            scrolled = features.scrolled,
            onTheme = { appearancePrefs.readerThemeKind = it },
            onFontScale = { setFontScale(it) },
            onLineHeight = { commitFeatures(features.copy(lineHeightRatio = it)) },
            onMargin = { commitFeatures(features.copy(marginHorizontalPx = it)) },
            onScrolled = { commitFeatures(features.copy(scrolled = it)) },
            onDismiss = { showAppearance = false },
        )
    }
    }
}

/**
 * 绘制一行：纯文字走 Canvas drawText；图片行 (imageSrc != null, text 空白)
 * 从 [pageImages] 取 Bitmap，缩放到页宽以内居中绘制。
 */
private fun DrawScope.drawLayoutLine(
    line: LayoutLine,
    paint: android.graphics.Paint,
    pageImages: Map<String, android.graphics.Bitmap?> = emptyMap(),
    highlightColors: Map<Int, Long> = emptyMap(),
    bionic: Boolean = false,
) {
    // 高亮背景色（使用笔记存储的颜色，默认半透明黄）
    if (line.blockIndex in highlightColors && line.imageSrc == null) {
        val color = highlightColors[line.blockIndex] ?: 0x99FFFFL
        val canvas = drawContext.canvas.nativeCanvas
        val bg = android.graphics.Paint()
        bg.color = color.toInt()
        bg.style = android.graphics.Paint.Style.FILL
        canvas.drawRect(
            line.x - 2f,
            line.y,
            line.rightPx + 2f,
            line.y + line.height,
            bg,
        )
    }
    if (line.imageSrc != null && line.text.isEmpty()) {
        val bmp = pageImages["${line.position.spineIndex}:${line.imageSrc}"]
        if (bmp != null) {
            val canvas = drawContext.canvas.nativeCanvas
            val maxW = size.width.toInt()
            val scale = kotlin.math.min(1f, maxW.toFloat() / bmp.width)
            val drawW = (bmp.width * scale).toInt()
            val drawH = (bmp.height * scale).toInt()
            val left = ((size.width - drawW) / 2f).toInt()
            val top = line.y.toInt()
            val src = android.graphics.Rect(0, 0, bmp.width, bmp.height)
            val dst = android.graphics.Rect(left, top, left + drawW, top + drawH)
            canvas.drawBitmap(bmp, src, dst, null)
        }
        return
    }
    paint.textSize = line.fontSizePx
    val canvas = drawContext.canvas.nativeCanvas
    if (bionic && line.text.isNotEmpty()) {
        var x = line.x
        for (run in BionicReading.split(line.text, BionicConfig.DESKTOP_PARITY)) {
            paint.isFakeBoldText = run.emphasized
            canvas.drawText(run.text, x, line.baselineY, paint)
            x += paint.measureText(run.text)
        }
        paint.isFakeBoldText = false
    } else {
        canvas.drawText(line.text, line.x, line.baselineY, paint)
    }
}

/** Decode an image line's bitmap from the session (EPUB zip / file). Returns null if missing. */
private fun loadBitmap(session: ReaderSession, line: LayoutLine): android.graphics.Bitmap? {
    val src = line.imageSrc?.takeIf { it.isNotBlank() } ?: return null
    val baseHref = session.chapterHref(line.position.spineIndex)
    val bytes = session.readImage(baseHref, src) ?: return null
    return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}

/** 绘制一行：字体按行字号，位置用引擎产出的 x/baseline（同一测量口径）。 */
// private (removed — merged into the drawLine above)

@Composable
private fun ParagraphModeLayer(
    session: ReaderSession,
    currentPage: Int,
    onPrevPage: () -> Unit,
    onNextPage: () -> Unit,
    onToggleChrome: () -> Unit,
) {
    val chapter = remember(session, currentPage) {
        (0 until session.chapterCount)
            .lastOrNull { c -> session.pageOfChapter(c) <= currentPage } ?: 0
    }
    val paragraphs = remember(session, chapter) {
        ParagraphMode.split(session.chapterText(chapter))
    }
    var startAtEnd by remember { mutableStateOf(false) }
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(chapter, paragraphs.size) {
        index = if (startAtEnd) (paragraphs.size - 1).coerceAtLeast(0) else 0
        startAtEnd = false
    }
    val text = paragraphs.getOrElse(index) { "" }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .pointerInput(index, paragraphs.size) {
                detectTapGestures { offset ->
                    when (ReaderPageGestures.onTap(offset.x, size.width.toFloat())) {
                        ReaderPageGesture.PREV_PAGE -> {
                            if (index > 0) {
                                index--
                            } else if (currentPage > 0) {
                                startAtEnd = true
                                onPrevPage()
                            }
                        }
                        ReaderPageGesture.NEXT_PAGE -> {
                            if (index < paragraphs.lastIndex) {
                                index++
                            } else {
                                startAtEnd = false
                                onNextPage()
                            }
                        }
                        ReaderPageGesture.TOGGLE_CHROME -> onToggleChrome()
                        ReaderPageGesture.SELECT -> Unit
                    }
                }
            }
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text.ifBlank { t("Empty") },
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.verticalScroll(rememberScrollState()),
        )
    }
}

@Composable
private fun SpeedReadingLayer(
    text: String,
    wpm: Int,
    onTap: () -> Unit,
) {
    val engine = remember(wpm) { SpeedReading(wpm) }
    val chunks = remember(text, engine) { engine.chunks(text) }
    var index by remember(chunks) { mutableIntStateOf(0) }
    LaunchedEffect(chunks) {
        index = 0
        while (index < chunks.size) {
            delay(chunks[index].durationMs.toLong().coerceAtLeast(1L))
            if (index >= chunks.lastIndex) break
            index++
        }
    }
    val chunk = chunks.getOrNull(index)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .pointerInput(Unit) {
                detectTapGestures { onTap() }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (chunk == null) {
            Text(t("Empty"), style = MaterialTheme.typography.headlineSmall)
        } else {
            val before = chunk.text.take(chunk.orpIndex)
            val pivot = chunk.text.getOrNull(chunk.orpIndex)?.toString().orEmpty()
            val after = chunk.text.drop(chunk.orpIndex + 1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(before, style = MaterialTheme.typography.headlineMedium)
                Text(
                    pivot,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(after, style = MaterialTheme.typography.headlineMedium)
            }
        }
    }
}
