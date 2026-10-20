package com.koodoreader.reader.epubhost

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.koodoreader.core.data.KoodoDatabase
import com.koodoreader.core.data.KoodoDatabaseProvider
import com.koodoreader.core.data.entity.BookmarkEntity
import com.koodoreader.core.data.entity.NoteEntity
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
import com.koodoreader.reader.shell.ReaderTextPipeline
import com.koodoreader.engine.toc.ChapterText
import com.koodoreader.engine.toc.SearchHit
import com.koodoreader.engine.toc.SearchIndex
import com.koodoreader.engine.toc.SearchQuery
import com.koodoreader.feature.dictionary.DictRepository
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
import com.koodoreader.reader.shell.LibraryViewModel
import com.koodoreader.reader.AppLog
import com.koodoreader.reader.R
import com.koodoreader.reader.shell.LocalI18n
import com.koodoreader.reader.shell.ReaderChromeAction
import com.koodoreader.reader.shell.ReaderFiles
import com.koodoreader.reader.shell.ReaderPageGesture
import com.koodoreader.reader.shell.ReaderPageGestures
import com.koodoreader.reader.shell.ReaderProgressPrefs
import com.koodoreader.reader.shell.ShellAppearancePrefs
import com.koodoreader.reader.shell.builtInPreset
import com.koodoreader.reader.shell.t
import com.koodoreader.reader.translate.rememberTranslationPopupController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
) {
    val context = LocalContext.current
    val book by viewModel.book(bookKey).collectAsStateWithLifecycle(initialValue = null)
    // 阅读进度（bookKey → 位置 CFI）：非 Room 键值存储，schema.lock 五表保持干净。
    val progressPrefs = remember(context) { ReaderProgressPrefs(context) }
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
        ),
    )
    LaunchedEffect(openKey) {
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
                    measurer = AndroidTextMeasurer(density),
                    options = PaginatorOptions(
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

    var currentPage by remember { mutableIntStateOf(0) }
    var showChrome by remember { mutableStateOf(true) }
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
        if (s.pageCount > 0) s.cfiForPage(currentPage)?.let { progressPrefs.save(key, it) }
    }

    val pageCount = session?.pageCount ?: 0
    val lines = session?.pageLines(currentPage) ?: emptyList()
    val cfi = if (pageCount > 0) session?.cfiForPage(currentPage) else null

    // ── 书签 ──────────────────────────────────────────────────────────────
    val db: KoodoDatabase = KoodoDatabaseProvider.get(context)
    var bookmarkFlash by remember { mutableStateOf(false) }

    // P9-r3-T-mv0hf34m-3j1k07: 替换 9 处 MainScope().launch → readerScope.launch.
    // readerScope 由 Composition 持有，离开 Composition (Activity finish 或导航离开)
    // 时自动取消 —— 避免 Activity 泄漏与配置变更 (旋转/字号) 时协程错过取消时机。
    val readerScope = rememberCoroutineScope()

    fun addBookmark() {
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
        val layout = s.layoutResult()
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

    // ── 双击选词 + 高亮/笔记 ────────────────────────────────────────────────
    var selectedLine: LayoutLine? by remember { mutableStateOf(null) }
    var showSelectionMenu by remember { mutableStateOf(false) }
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
        val chapterText = s.chapterText(chapterIdx)
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
                            val nextText = s.chapterText(nextIdx)
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

    fun saveHighlight(colorIdx: Int = 0) {
        val line = selectedLine ?: return
        val s = session ?: return
        val chapterIdx = (0 until s.chapterCount)
            .lastOrNull { c -> s.pageOfChapter(c) <= currentPage } ?: 0
        val cfi = CfiAddressing.toCfi(LayoutPosition(
            line.position.spineIndex,
            line.position.elementIndex,
            line.start,
        ))
        val colors = listOf(0x99FFFFL, 0x9988FF88L, 0x99FFAAFFL, 0x99AACCFFL)
        val color = if (colorIdx < colors.size) colors[colorIdx] else 0x99FFFFL
        val entity = NoteEntity(
            key = System.currentTimeMillis().toString(),
            bookKey = bookKey,
            chapter = s.chapterLabel(chapterIdx),
            chapterIndex = chapterIdx.toLong(),
            text = line.text,
            cfi = cfi,
            notes = "",  // empty = plain highlight
            percentage = ((currentPage + 1).toFloat() / (s.pageCount + 0.1f)).toString(),
            color = color,
        )
        readerScope.launch { db.noteDao().upsert(entity) }
        showSelectionMenu = false
    }

    fun deleteHighlight() {
        val line = selectedLine ?: return
        val cfi = CfiAddressing.toCfi(LayoutPosition(
            line.position.spineIndex,
            line.position.elementIndex,
            line.start,
        ))
        readerScope.launch {
            val notes = db.noteDao().observeForBook(bookKey).first()
            val target = notes.firstOrNull { it.cfi == cfi }
            target?.let { db.noteDao().deleteByKey(it.key) }
            showSelectionMenu = false
        }
    }

    fun lookupDictionary() {
        val line = selectedLine ?: return
        val word = line.text.trim().takeIf { it.isNotEmpty() } ?: return
        readerScope.launch {
            val result = withContext(Dispatchers.IO) { dictRepo.lookup(word) }
            val htmlSafe = result.html
            dictResultText = if (htmlSafe != null) {
                "【${result.entry}】\n" +
                    htmlSafe.replace(Regex("<[^>]+>"), "").replace(Regex("&[a-z]+;"), "·").trim()
            } else {
                "未找到「$word」的释义\n（请确认已导入字典文件）"
            }
            showDictDialog = true
            showSelectionMenu = false
        }
    }

    fun startTranslation() {
        val line = selectedLine ?: return
        val text = line.text.trim().takeIf { it.isNotEmpty() } ?: return
        val cfi = CfiAddressing.toCfi(LayoutPosition(
            line.position.spineIndex,
            line.position.elementIndex,
            line.start,
        ))
        translateController.show(text, bookKey = bookKey, cfi = cfi)
        showSelectionMenu = false
        readerScope.launch { translateController.translate() }
    }

    fun saveNote() {
        val line = selectedLine ?: return
        val s = session ?: return
        // P9-anr (round-3): 用 chapterOf 避免 O(n) 扫描。
        val chapterIdx = if (s is PagedDocumentSession) s.chapterOf(currentPage)
                         else (0 until s.chapterCount)
                             .lastOrNull { c -> s.pageOfChapter(c) <= currentPage } ?: 0
        val cfi = CfiAddressing.toCfi(LayoutPosition(
            line.position.spineIndex,
            line.position.elementIndex,
            line.start,
        ))
        val entity = NoteEntity(
            key = System.currentTimeMillis().toString(),
            bookKey = bookKey,
            chapter = s.chapterLabel(chapterIdx),
            chapterIndex = chapterIdx.toLong(),
            text = line.text,
            cfi = cfi,
            notes = noteText,
            percentage = ((currentPage + 1).toFloat() / (s.pageCount + 0.1f)).toString(),
        )
        readerScope.launch { db.noteDao().upsert(entity) }
        showNoteDialog = false
        noteText = ""
    }

    fun selectAt(offset: androidx.compose.ui.geometry.Offset) {
        val s = session ?: return
        val layout = s.layoutResult()
        val pos = layout.positionAt(offset.x, offset.y) ?: return
        val line = s.pageLines(currentPage)
            .firstOrNull { it.containsY(offset.y) && offset.x in it.x..it.rightPx }
            ?: layout.lineAt(pos)
            ?: return
        if (line.imageSrc != null) return  // 不选图片行
        selectedLine = line
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

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            if (showChrome) {
                TopAppBar(
                    title = {
                        if (bookmarkFlash) {
                            Text("✓ 已添加书签", color = MaterialTheme.colorScheme.primary)
                        } else {
                            Text(book?.name ?: "")
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
                            }
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (showChrome && pageCount > 0) {
                Surface(tonalElevation = 2.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showToc = true }
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        Text("$chapterInfo · p ${currentPage + 1}/$pageCount", style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.weight(1f))
                        Text(
                            (cfi ?: "").take(48),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
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
                            "正在打开…",
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
                            .pointerInput(pageCount, currentPage, features.paragraphMode) {
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
                                    when (ReaderPageGestures.onTap(offset.x, size.width.toFloat())) {
                                        ReaderPageGesture.PREV_PAGE ->
                                            if (currentPage > 0) currentPage--
                                        ReaderPageGesture.NEXT_PAGE ->
                                            if (currentPage < pageCount - 1) currentPage++
                                        ReaderPageGesture.TOGGLE_CHROME ->
                                            showChrome = !showChrome
                                        ReaderPageGesture.SELECT -> Unit
                                    }
                                }
                            },
                    ) {
                        drawRect(color = bgColor)
                        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                        paint.color = fgColor.toArgb()
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

    // ── 选中行菜单（叠在阅读页底部，不能作为 Scaffold 的兄弟节点
    // 被 NavHost 顶到屏幕外） ───────────────────────────────────────────────
    if (showSelectionMenu && selectedLine != null) {
        val line = selectedLine!!
        val highlighted = isAlreadyHighlighted(line)

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
                            "已高亮「${line.text.take(24)}」",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                        TextButton(onClick = { deleteHighlight() }) { Text("删除") }
                        TextButton(onClick = { lookupDictionary() }) { Text("字典") }
                        TextButton(onClick = { startTranslation() }) { Text("翻译") }
                        TextButton(onClick = { showSelectionMenu = false }) { Text("关闭") }
                    }
                } else {
                    // 未高亮：高亮(4色) + 笔记 + 字典
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "「${line.text.take(16)}」",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                        TextButton(onClick = { saveHighlight(0) }) { Text("黄") }
                        TextButton(onClick = { saveHighlight(1) }) { Text("绿") }
                        TextButton(onClick = { saveHighlight(2) }) { Text("粉") }
                        TextButton(onClick = { saveHighlight(3) }) { Text("蓝") }
                    }
                    Row(modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { noteText = ""; showNoteDialog = true }) { Text("笔记") }
                        TextButton(onClick = { lookupDictionary() }) { Text("字典") }
                        TextButton(onClick = { startTranslation() }) { Text("翻译") }
                        TextButton(onClick = { showSelectionMenu = false }) { Text("关闭") }
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
            title = { Text("字典") },
            text = {
                Text(
                    dictResultText,
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = { showDictDialog = false }) { Text("关闭") }
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

    // ── 目录对话框 ─────────────────────────────────────────────────────────────
    if (showToc) {
        AlertDialog(
            onDismissRequest = { showToc = false },
            title = { Text("目录") },
            text = {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp),
                ) {
                    items((session?.chapterCount ?: 0)) { i ->
                        TextButton(onClick = {
                            session?.let { s -> currentPage = s.pageOfChapter(i) }
                            showToc = false
                        }) {
                            // 标签优先取书自带目录（EpubToc：NAV/NCX 真章节名）；
                            // 没有 TOC 的降级 spine 文件名；两者皆空时兜底序号。
                            Text(
                                (session?.chapterLabel(i) ?: "").ifEmpty { "第 ${i + 1} 章" },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showToc = false }) { Text("关闭") }
            },
        )
    }

    // ── 笔记输入对话框 ─────────────────────────────────────────────────────────
    if (showNoteDialog) {
        AlertDialog(
            onDismissRequest = { showNoteDialog = false },
            title = { Text("添加笔记") },
            text = {
                Column {
                    Text(selectedLine?.text.orEmpty().take(80), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = noteText,
                        onValueChange = { noteText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("笔记内容") },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { saveNote() }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showNoteDialog = false }) { Text("取消") }
            },
        )
    }

    // ── 搜索对话框 ──────────────────────────────────────────────────────────────
    if (showSearch) {
        AlertDialog(
            onDismissRequest = { showSearch = false },
            title = { Text("全文搜索") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("输入关键词") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(12.dp))
                    if (searchResults.isEmpty()) {
                        Text(
                            "输入关键词后点「搜索」按钮",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text("${searchResults.size} 条结果", style = MaterialTheme.typography.labelSmall)
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
                                        "第 ${hit.spineIndex + 1} 页",
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
                TextButton(onClick = { doSearch() }) { Text("搜索") }
            },
            dismissButton = {
                TextButton(onClick = { showSearch = false }) { Text("关闭") }
            },
        )
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
