package com.koodoreader.reader.shell

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.koodoreader.reader.epubhost.NativeEpubScreen

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
            // No book-jump wiring yet: the route cannot carry a CFI, and a tap
            // that opened the book at its last-read page instead of the
            // annotation would be a lie. See the P0-1 note on this card.
            NotesScreen()
        }

        composable(ShellNav.SETTINGS) {
            SettingsScreen(
                onOpenBackup = { navController.navigate(ShellNav.BACKUP) },
                onOpenTrash = { navController.navigate(ShellNav.TRASH) },
                onOpenDictionary = { navController.navigate(ShellNav.DICTIONARY) },
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
