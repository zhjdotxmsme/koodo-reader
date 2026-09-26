package com.koodoreader.reader.epubhost

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.koodoreader.engine.layout.LayoutLine
import com.koodoreader.reader.shell.LibraryViewModel
import com.koodoreader.reader.shell.ReaderFiles
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
) {
    val context = LocalContext.current
    val book by viewModel.book(bookKey).collectAsStateWithLifecycle(initialValue = null)
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
    // 会话按 (文件, 视口) 重建：尺寸未知时 null（首帧后 onSizeChanged 触发）。
    val session = remember(file, viewport) {
        val f = file ?: return@remember null
        if (viewport.width == 0 || viewport.height == 0) return@remember null
        runCatching {
            EpubBookSession.open(
                file = f,
                viewportWidthPx = viewport.width.toFloat(),
                viewportHeightPx = viewport.height.toFloat(),
                measurer = AndroidTextMeasurer(context.resources.displayMetrics.density),
            )
        }.getOrNull()
    }
    DisposableEffect(session) {
        onDispose { session?.close() }
    }

    var currentPage by remember { mutableIntStateOf(0) }
    var showChrome by remember { mutableStateOf(true) }
    LaunchedEffect(session) { currentPage = 0 }

    val pageCount = session?.pageCount ?: 0
    val lines = session?.pageLines(currentPage) ?: emptyList()
    val cfi = if (pageCount > 0) session?.cfiForPage(currentPage) else null

    // 在 composable 上下文捕获颜色（Canvas 的 DrawScope lambda 不是
    // composable 上下文，不能直接读 MaterialTheme.colorScheme）。
    val bgColor = MaterialTheme.colorScheme.background
    val fgColor = MaterialTheme.colorScheme.onSurface
    val chapterInfo = remember(session, currentPage) {
        session?.let { s ->
            val idx = (0 until s.spine.chapters.size)
                .lastOrNull { c -> s.pageOfChapter(c) <= currentPage } ?: 0
            "c ${idx + 1}/${s.spine.chapters.size} · ${s.spine.chapters[idx].href.substringAfterLast('/')}"
        } ?: ""
    }

    Scaffold(
        topBar = {
            if (showChrome) {
                TopAppBar(
                    title = { Text(book?.name ?: "") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                            detectTapGestures { offset ->
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
                    for (line in lines) drawLine(line, paint)
                }
            }
        }
    }
}

/** 绘制一行：字体按行字号，位置用引擎产出的 x/baseline（同一测量口径）。 */
private fun DrawScope.drawLine(line: LayoutLine, paint: android.graphics.Paint) {
    paint.textSize = line.fontSizePx
    drawContext.canvas.nativeCanvas.drawText(line.text, line.x, line.baselineY, paint)
}
