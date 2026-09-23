package com.koodoreader.reader.shell

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

object ShellRoutes {
    const val LIBRARY = "library"
    const val READER = "reader/{bookKey}"

    fun reader(bookKey: String): String = "reader/${Uri.encode(bookKey)}"
}

@Composable
fun ShellNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = ShellRoutes.LIBRARY) {
        composable(ShellRoutes.LIBRARY) {
            LibraryScreen(onOpenBook = { key -> navController.navigate(ShellRoutes.reader(key)) })
        }
        composable(
            route = ShellRoutes.READER,
            arguments = listOf(navArgument("bookKey") { type = NavType.StringType }),
        ) { entry ->
            ReaderPlaceholderScreen(
                bookKey = Uri.decode(entry.arguments?.getString("bookKey").orEmpty()),
                onBack = { navController.popBackStack() },
            )
        }
    }
}
