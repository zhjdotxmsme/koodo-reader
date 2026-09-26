package com.koodoreader.reader.shell

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.koodoreader.reader.epubhost.NativeEpubScreen

object ShellRoutes {
    const val LIBRARY = "library"
    const val READER = "reader/{bookKey}"
    const val BACKUP = "backup"
    const val TRASH = "trash"
    const val STATS = "stats"
    const val DICTIONARY = "dictionary"

    fun reader(bookKey: String): String = "reader/${Uri.encode(bookKey)}"
}

@Composable
fun ShellNavHost(assets: ReaderAssetHost = ReaderAssetHost.NONE) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = ShellRoutes.LIBRARY) {
        composable(ShellRoutes.LIBRARY) {
            LibraryScreen(
                onOpenBook = { key -> navController.navigate(ShellRoutes.reader(key)) },
                onOpenBackup = { navController.navigate(ShellRoutes.BACKUP) },
                onOpenTrash = { navController.navigate(ShellRoutes.TRASH) },
                onOpenStats = { navController.navigate(ShellRoutes.STATS) },
                onOpenDictionary = { navController.navigate(ShellRoutes.DICTIONARY) },
            )
        }
        composable(ShellRoutes.BACKUP) {
            BackupScreen(onBack = { navController.popBackStack() })
        }
        composable(ShellRoutes.TRASH) {
            TrashScreen(onBack = { navController.popBackStack() })
        }
        // P6 reading stats (desktop /stats): the module shipped long before this
        // route existed, which is exactly the "delivered but unreachable" gap this
        // nav entry closes.
        composable(ShellRoutes.STATS) {
            StatsRoute(onBack = { navController.popBackStack() })
        }
        // P6 dictionary manager: local .mdx/.mdd import + enable/order/default.
        // The cloud catalogue is intentionally not mounted (see DictionaryRoute).
        composable(ShellRoutes.DICTIONARY) {
            DictionaryRoute(onBack = { navController.popBackStack() })
        }
        composable(
            route = ShellRoutes.READER,
            arguments = listOf(navArgument("bookKey") { type = NavType.StringType }),
        ) { entry ->
            // P3/P5 routing dispatch — the book format decides which native
            // reader composable mounts. PDF → NativePdfScreen (loopback pdf.js);
            // EPUB / TXT / MD / MOBI(AZW/AZW3) → NativeEpubScreen + ReaderSession
            // （engine/layout 分页 + CFI，四格式共用一屏，各一个 session 实现）;
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
                "EPUB", "TXT", "MD", "MARKDOWN", "MOBI", "AZW", "AZW3" -> NativeEpubScreen(
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