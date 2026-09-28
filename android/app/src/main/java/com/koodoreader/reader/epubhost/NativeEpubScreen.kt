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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.koodoreader.core.data.KoodoDatabase
import com.koodoreader.core.data.KoodoDatabaseProvider
import com.koodoreader.core.data.entity.BookmarkEntity
import com.koodoreader.core.data.entity.NoteEntity
import com.koodoreader.engine.layout.CfiAddressing
import com.koodoreader.engine.layout.LayoutLine
import com.koodoreader.engine.layout.LayoutPosition
import com.koodoreader.engine.layout.PaginatorOptions
import com.koodoreader.engine.toc.ChapterText
import com.koodoreader.engine.toc.SearchHit
import com.koodoreader.engine.toc.SearchIndex
import com.koodoreader.engine.toc.SearchQuery
import com.koodoreader.feature.dictionary.DictRepository
import com.koodoreader.reader.shell.LibraryViewModel
import com.koodoreader.reader.shell.ReaderFiles
import com.koodoreader.reader.shell.ReaderProgressPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
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
    // 字号缩放：1.0 = 默认（DesktopReaderConfig.FONT_SIZE_DEFAULT = 17f）
    // 持久化到 SharedPreferences（与 ReaderProgressPrefs 同一 prefs 文件）。
    val fontPrefs = remember(context) { context.getSharedPreferences("reader", 0) }
    var fontScale by remember { mutableStateOf(fontPrefs.getFloat("fontScale", 1.0f)) }
    fun setFontScale(v: Float) {
        val clamped = v.coerceIn(0.7f, 2.5f)
        fontScale = clamped
        fontPrefs.edit().putFloat("fontScale", clamped).apply()
    }
    val format = book?.format?.lowercase()
    // 会话按 (文件, 格式, 视口, 字号) 重建：尺寸/字号变化时重新分页。
    val session: ReaderSession? = remember(file, format, viewport, fontScale) {
        val f = file ?: return@remember null
        if (viewport.width == 0 || viewport.height == 0) return@remember null
        val density = context.resources.displayMetrics.density
        ReaderSessionFactory.open(
            format = format,
            file = f,
            viewportWidthPx = viewport.width.toFloat(),
            viewportHeightPx = viewport.height.toFloat(),
            measurer = AndroidTextMeasurer(density),
            options = PaginatorOptions(fontSizePx = 17f * fontScale),
        )
    }
    DisposableEffect(session) {
        onDispose { session?.close() }
    }

    var currentPage by remember { mutableIntStateOf(0) }
    var showChrome by remember { mutableStateOf(true) }
    // 打开恢复：优先用 initialCfi（笔记跳转），否则按上次位置 CFI 落页（无进度 → 第 0 页）。
    LaunchedEffect(session) {
        val target = initialCfi?.takeIf { it.isNotBlank() }
            ?: book?.key?.let { progressPrefs.cfiOf(it) }
        currentPage = session?.resumePage(target) ?: 0
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

    fun addBookmark() {
        val cfi = session?.cfiForPage(currentPage) ?: return
        val s = session ?: return
        if (s.pageCount <= 0) return
        val chapterIdx = (0 until s.chapterCount)
            .lastOrNull { c -> s.pageOfChapter(c) <= currentPage } ?: 0
        val entity = BookmarkEntity(
            key = System.currentTimeMillis().toString(),
            bookKey = bookKey,
            cfi = cfi,
            percentage = ((currentPage + 1).toFloat() / s.pageCount).toString(),
            chapter = s.chapterLabel(chapterIdx),
        )
        kotlinx.coroutines.MainScope().launch {
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

    val highlightBlockIndices: Set<Int> = remember(bookNotes, session, currentPage) {
        val s = session ?: return@remember emptySet()
        val layout = s.layoutResult()
        bookNotes.mapNotNull { note ->
            val cfi = note.cfi ?: return@mapNotNull null
            val pos = CfiAddressing.fromCfi(cfi, layout) ?: return@mapNotNull null
            val line = layout.lineAt(pos) ?: return@mapNotNull null
            if (line.page == currentPage) line.blockIndex else null
        }.toSet()
    }

    // ── 全文搜索 ──────────────────────────────────────────────────────────────
    var showSearch by remember { mutableStateOf(false) }
    val searchIndex = remember(bookKey, session) {
        val s = session ?: return@remember null
        if (s.chapterCount == 0) return@remember null
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
    // 目录
    var showToc by remember { mutableStateOf(false) }
    // 高亮颜色（0 = 黄色 default）
    // 0=yellow 1=green 2=pink 3=blue
    var selectedHighlightColor by remember { mutableStateOf(0) }

    fun isAlreadyHighlighted(line: LayoutLine): Boolean =
        line.blockIndex in highlightBlockIndices

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
        val colors = listOf(0x99FFFFL, 0x99AAAAFL, 0x99FFAAFFL, 0x99AACCFFL)
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
        MainScope().launch { db.noteDao().upsert(entity) }
        showSelectionMenu = false
    }

    fun deleteHighlight() {
        val line = selectedLine ?: return
        val cfi = CfiAddressing.toCfi(LayoutPosition(
            line.position.spineIndex,
            line.position.elementIndex,
            line.start,
        ))
        MainScope().launch {
            val notes = db.noteDao().observeForBook(bookKey).first()
            val target = notes.firstOrNull { it.cfi == cfi }
            target?.let { db.noteDao().deleteByKey(it.key) }
            showSelectionMenu = false
        }
    }

    fun lookupDictionary() {
        val line = selectedLine ?: return
        val word = line.text.trim().takeIf { it.isNotEmpty() } ?: return
        MainScope().launch {
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

    fun saveNote() {
        val line = selectedLine ?: return
        val s = session ?: return
        val chapterIdx = (0 until s.chapterCount)
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
        MainScope().launch { db.noteDao().upsert(entity) }
        showNoteDialog = false
        noteText = ""
    }

    fun onDoubleTap(offset: androidx.compose.ui.geometry.Offset) {
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
    val pageImages: Map<String, android.graphics.Bitmap?> = remember(session, currentPage) {
        val s = session ?: return@remember emptyMap()
        s.pageLines(currentPage)
            .filter { it.imageSrc != null }
            .associate { line ->
                val key = "${line.position.spineIndex}:${line.imageSrc}"
                key to loadBitmap(s, line)
            }
    }

    // 在 composable 上下文捕获颜色（Canvas 的 DrawScope lambda 不是
    // composable 上下文，不能直接读 MaterialTheme.colorScheme）。
    val bgColor = MaterialTheme.colorScheme.background
    val fgColor = MaterialTheme.colorScheme.onSurface
    val chapterInfo = remember(session, currentPage) {
        session?.let { s ->
            val idx = (0 until s.chapterCount)
                .lastOrNull { c -> s.pageOfChapter(c) <= currentPage } ?: 0
            "c ${idx + 1}/${s.chapterCount} · ${s.chapterLabel(idx)}"
        } ?: ""
    }

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
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        // 字号缩小（A-）
                        TextButton(onClick = { setFontScale(fontScale - 0.1f) }) {
                            Text("A−", style = MaterialTheme.typography.labelLarge)
                        }
                        Text(
                            text = "${(fontScale * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                        // 字号放大（A+）
                        TextButton(onClick = { setFontScale(fontScale + 0.1f) }) {
                            Text("A+", style = MaterialTheme.typography.labelLarge)
                        }
                        // 书签（文字按钮，避免图标版本依赖）
                        TextButton(onClick = { addBookmark() }) {
                            Text("🔖", style = MaterialTheme.typography.labelLarge)
                        }
                        // 搜索（文字按钮）
                        TextButton(onClick = { showSearch = true }) {
                            Text("🔍", style = MaterialTheme.typography.labelLarge)
                        }
                        // 目录
                        TextButton(onClick = { showToc = true }) {
                            Text("☰", style = MaterialTheme.typography.labelLarge)
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
                .background(MaterialTheme.colorScheme.background),
        ) {
            when {
                session == null && viewport == IntSize.Zero -> Unit // 首帧：等尺寸
                session == null -> Text(
                    if (file == null) {
                        "Book file unavailable on device."
                    } else {
                        "Could not paginate this EPUB.\nImport it again or use the web viewer."
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                )
                else -> Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { viewport = it }
                        .pointerInput(pageCount) {
                            detectTapGestures(
                                onDoubleTap = { offset -> onDoubleTap(offset) },
                            ) { offset ->
                                if (showSelectionMenu) {
                                    showSelectionMenu = false
                                    return@detectTapGestures
                                }
                                val w = size.width
                                when {
                                    offset.x < w / 3f -> if (currentPage > 0) currentPage--
                                    offset.x > 2f * w / 3f -> if (currentPage < pageCount - 1) currentPage++
                                    else -> showChrome = !showChrome
                                }
                            }
                        },
                ) {
                    drawRect(color = bgColor)
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                    paint.color = fgColor.toArgb()
                    for (line in lines) drawLayoutLine(line, paint, pageImages, highlightBlockIndices)
                }
            }
        }
    }

    // ── 选中行菜单（底部浮动条） ─────────────────────────────────────────────
    if (showSelectionMenu && selectedLine != null) {
        val line = selectedLine!!
        val highlighted = line.blockIndex in highlightBlockIndices

        Surface(
            modifier = Modifier.fillMaxWidth(),
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
                        TextButton(onClick = { showSelectionMenu = false }) { Text("关闭") }
                    }
                }
            }
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
                            Text(
                                "第 ${i + 1} 章  ${session?.chapterLabel(i) ?: ""}",
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
    highlightBlocks: Set<Int> = emptySet(),
) {
    // 高亮背景色（半透明黄）
    if (line.blockIndex in highlightBlocks && line.imageSrc == null) {
        val canvas = drawContext.canvas.nativeCanvas
        val bg = android.graphics.Paint()
        bg.color = (0x99 shl 24) or (0xFF shl 16) or (0xFF shl 8) or 0x00  // semi-transparent yellow
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
    drawContext.canvas.nativeCanvas.drawText(line.text, line.x, line.baselineY, paint)
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
