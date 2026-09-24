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

object ShellRoutes {
    const val LIBRARY = "library"
    const val READER = "reader/{bookKey}"
    const val BACKUP = "backup"
    const val TRASH = "trash"
    const val STATS = "stats"

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
        composable(
            route = ShellRoutes.READER,
            arguments = listOf(navArgument("bookKey") { type = NavType.StringType }),
        ) { entry ->
            // P3 routing dispatch — the book format decides which reader
            // composable mounts. PDF lands on NativePdfScreen (which renders
            // through the loopback-hosted pdf.js engine); everything else falls
            // through to the P1 placeholder for now (P2 native EPUB lands later;
            // see docs/android-native-migration.md).
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
                else -> ReaderPlaceholderScreen(
                    bookKey = key,
                    onBack = { navController.popBackStack() },
                    viewModel = viewModel,
                )
            }
        }
    }
}