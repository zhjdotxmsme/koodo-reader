package com.koodoreader.reader.shell

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.koodoreader.reader.epubhost.NativeEpubScreen
// TranslateSettingsRoute 定义在 reader.translate 包（其余 settings 叶子路由都在
// shell 包），必须显式导入——同包可见性规则对跨包组件不生效，漏了就是
// Unresolved reference。
import com.koodoreader.reader.translate.TranslateSettingsRoute

/**
 * Route table of the native shell.
 *
 * The route strings and the two route predicates live in [ShellNav] as pure,
 * unit-tested functions; this file only mounts composables. Compose routes are
 * keyed by [ShellNav]/[ShellTab] constants so a rename cannot desynchronise the
 * bar from the graph.
 *
 * The [NavHostController] is supplied by [ShellScaffold], which owns the bottom
 * bar and therefore needs the current route.
 */
@Composable
fun ShellNavHost(
    navController: NavHostController,
    assets: ReaderAssetHost = ReaderAssetHost.NONE,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = ShellTab.START.route,
        modifier = modifier,
    ) {
        composable(ShellNav.LIBRARY) {
            // The library's top bar now carries only title + view options +
            // import (W5a). Trash / Backup / Dictionary are reached through the
            // Settings tab and Stats has its own tab, so this screen no longer
            // takes callbacks for them.
            LibraryScreen(
                onOpenBook = { key -> navController.navigate(ShellNav.reader(key)) },
            )
        }

        // P6 reading stats, now a top-level tab: no close affordance, because
        // the bottom bar is the way out of a tab.
        composable(ShellNav.STATS) {
            StatsRoute(onBack = { navController.popBackStack() }, showClose = false)
        }

        composable(ShellNav.NOTES) {
            NotesScreen(
                onJump = { bookKey, cfi ->
                    val route = if (cfi.isNullOrBlank()) {
                        ShellNav.reader(bookKey)
                    } else {
                        ShellNav.readerWithCfi(bookKey, cfi)
                    }
                    navController.navigate(route)
                },
            )
        }

        composable(ShellNav.SETTINGS) {
            SettingsScreen(
                onOpenBackup = { navController.navigate(ShellNav.BACKUP) },
                onOpenTrash = { navController.navigate(ShellNav.TRASH) },
                onOpenDictionary = { navController.navigate(ShellNav.DICTIONARY) },
                onOpenAppearance = { navController.navigate(ShellNav.APPEARANCE) },
                onOpenReading = { navController.navigate(ShellNav.READING) },
                onOpenTranslate = { navController.navigate(ShellNav.TRANSLATE) },
                onOpenTts = { navController.navigate(ShellNav.TTS) },
                onOpenAbout = { navController.navigate(ShellNav.ABOUT) },
            )
        }

        // ── Settings leaves: keep the bottom bar (they are drills, not tabs) ──
        composable(ShellNav.BACKUP) {
            BackupScreen(onBack = { navController.popBackStack() })
        }
        composable(ShellNav.TRASH) {
            TrashScreen(onBack = { navController.popBackStack() })
        }
        composable(ShellNav.DICTIONARY) {
            DictionaryRoute(onBack = { navController.popBackStack() })
        }
        composable(ShellNav.APPEARANCE) {
            AppearanceRoute(onBack = { navController.popBackStack() })
        }
        composable(ShellNav.READING) {
            ReadingSettingsRoute(onBack = { navController.popBackStack() })
        }
        composable(ShellNav.TRANSLATE) {
            TranslateSettingsRoute(onBack = { navController.popBackStack() })
        }
        composable(ShellNav.TTS) {
            TtsSettingsRoute(onBack = { navController.popBackStack() })
        }
        composable(ShellNav.ABOUT) {
            AboutRoute(onBack = { navController.popBackStack() })
        }

        composable(
            route = ShellNav.READER_PATTERN,
            arguments = listOf(navArgument("bookKey") { type = NavType.StringType }),
        ) { entry ->
            // P3/P5 routing dispatch — the book format decides which native
            // reader composable mounts. PDF → NativePdfScreen (loopback pdf.js);
            // EPUB / TXT / MD / MOBI(AZW/AZW3) / FB2 / DOCX / HTML family →
            // NativeEpubScreen + ReaderSession（engine/layout 分页 + CFI，共用一屏）;
            // 其余格式 → P1 placeholder（CBZ/CBT/CB7 由 :app ComicViewerActivity
            // 承接，见 P5-CBZ-5 / P8-F1）。
            val key = Uri.decode(entry.arguments?.getString("bookKey").orEmpty())
            val viewModel: LibraryViewModel = viewModel()
            val book by viewModel.book(key).collectAsStateWithLifecycle(initialValue = null)
            val format = book?.format?.uppercase()
            when (format) {
                "PDF" -> NativePdfScreen(
                    bookKey = key,
                    onBack = { navController.popBackStack() },
                    assets = assets,
                    viewModel = viewModel,
                )
                "EPUB", "TXT", "MD", "MARKDOWN", "MOBI", "AZW", "AZW3",
                "HTML", "HTM", "XHTML", "XML", "MHTML", "MHT", "FB2", "DOCX",
                -> NativeEpubScreen(
                    bookKey = key,
                    onBack = { navController.popBackStack() },
                    viewModel = viewModel,
                    onOpenTranslateSettings = { navController.navigate(ShellNav.TRANSLATE) },
                )
                "CBZ", "CBT", "CB7" -> ComicBookRoute(
                    bookKey = key,
                    book = book,
                    onBack = { navController.popBackStack() },
                )
                else -> ReaderPlaceholderScreen(
                    bookKey = key,
                    onBack = { navController.popBackStack() },
                    viewModel = viewModel,
                )
            }
        }

        // Reader with an optional CFI target (jump to annotation from Notes).
        composable(
            route = ShellNav.READER_WITH_CFI_PATTERN,
            arguments = listOf(
                navArgument("bookKey") { type = NavType.StringType },
                navArgument("cfi") {
                    type = NavType.StringType
                    // 可选查询参数的正确写法：非空 StringType + 空串默认值。
                    // 用 defaultValue = null 会在 NavArgument 的 require() 上抛
                    // IllegalArgumentException；而 NavHost 在【启动构图为每条路由建图】
                    // 时就会触发这条校验 → 库屏渲染前 App 就崩（"一打开就闪退"根因）。
                    // 空串在读者屏经 `initialCfi?.takeIf{isNotBlank()}` 被当作"无 CFI"。
                    defaultValue = ""
                },
            ),
        ) { entry ->
            val key = Uri.decode(entry.arguments?.getString("bookKey").orEmpty())
            val cfi = entry.arguments?.getString("cfi")
            val viewModel: LibraryViewModel = viewModel()
            val book by viewModel.book(key).collectAsStateWithLifecycle(initialValue = null)
            val format = book?.format?.uppercase()
            when (format) {
                "PDF" -> NativePdfScreen(
                    bookKey = key,
                    onBack = { navController.popBackStack() },
                    assets = assets,
                    viewModel = viewModel,
                )
                "EPUB", "TXT", "MD", "MARKDOWN", "MOBI", "AZW", "AZW3",
                "HTML", "HTM", "XHTML", "XML", "MHTML", "MHT", "FB2", "DOCX",
                -> NativeEpubScreen(
                    bookKey = key,
                    onBack = { navController.popBackStack() },
                    viewModel = viewModel,
                    initialCfi = cfi,
                    onOpenTranslateSettings = { navController.navigate(ShellNav.TRANSLATE) },
                )
                "CBZ", "CBT", "CB7" -> ComicBookRoute(
                    bookKey = key,
                    book = book,
                    onBack = { navController.popBackStack() },
                )
                else -> ReaderPlaceholderScreen(
                    bookKey = key,
                    onBack = { navController.popBackStack() },
                    viewModel = viewModel,
                )
            }
        }
    }
}

/**
 * 漫画轨在 App 内的入口（P5-CBZ-5 补全）：文件在导入时已落到 filesDir/books，
 * 直接以 EXTRA_FILE 拉起 [ComicViewerActivity]——与外部 VIEW intent 同一条路
 * （MainActivity → IntentRoutePolicy.NATIVE_COMIC）。此前书架点击落
 * ReaderPlaceholderScreen，「外部能开、App 内点开却是占位屏」的缺口。
 *
 * 屏在 Activity 下方：启动动画完成后被盖住；返回键回到书架（back stack 保留）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComicBookRoute(
    bookKey: String,
    book: com.koodoreader.core.data.entity.BookEntity?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var launched by remember { mutableStateOf(false) }

    // `book` 是 Flow 收集，可能晚于构图到达——以 (book, bookKey) 为 key，
    // 到齐后重跑效果；launched 守卫防重复启动。解析不到文件则留在提示屏。
    LaunchedEffect(book, bookKey) {
        if (launched) return@LaunchedEffect
        val b = book ?: return@LaunchedEffect
        val file = ReaderFiles.resolveBookFile(
            booksDir = java.io.File(context.filesDir, "books"),
            bookKey = b.key,
            format = b.format,
            recordedPath = b.path,
        )
        if (file != null && file.isFile) {
            launched = true
            context.startActivity(
                com.koodoreader.reader.imagehost.ComicViewerActivity.intent(context, file),
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(book?.name.orEmpty()) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            Text("正在打开发票…")
        }
    }
}
