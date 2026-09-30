package com.koodoreader.reader.shell

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.koodoreader.reader.R

/**
 * The shell: a bottom navigation bar over the shared [ShellNavHost].
 *
 * ALL show/hide and ownership decisions come from [ShellNav]'s pure functions —
 * this file contains no route-string comparisons, so "is the bar visible on the
 * reader?" is a unit-tested fact rather than a detail of the layout code.
 *
 * The bar is hidden only on the reader ([ShellNav.hidesBottomBar]); the settings
 * leaves keep it, so drilling into Backup or Trash does not cost the user a
 * back-press to change tabs.
 */
@Composable
fun ShellScaffold(assets: ReaderAssetHost = ReaderAssetHost.NONE) {
    val navController = rememberNavController()
    ShellScaffold(navController = navController, assets = assets)
}

@Composable
fun ShellScaffold(
    navController: NavHostController,
    assets: ReaderAssetHost = ReaderAssetHost.NONE,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    // `destination.route` is the NavHost PATTERN, so the reader reports
    // "reader/{bookKey}". ShellNav.isReader accepts both the pattern and a
    // concrete "reader/..." route, which is what makes the unit test meaningful.
    val route = backStackEntry?.destination?.route
    val selectedTab = ShellNav.topLevelFor(route)
    val i18n = LocalI18n.current

    Scaffold(
        bottomBar = {
            if (!ShellNav.hidesBottomBar(route)) {
                NavigationBar {
                    for (tab in ShellTab.barOrder()) {
                        NavigationBarItem(
                            selected = tab == selectedTab,
                            onClick = { navController.switchToTab(tab) },
                            icon = { Icon(tab.icon(), contentDescription = null) },
                            // Label keys reuse the desktop catalogs where they
                            // exist ("Reading Stats", "Settings"); the remaining
                            // keys are added to src/assets/locales/en.json in W6a.
                            label = { Text(i18n.localization.t(tab.labelKey)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        ShellNavHost(
            navController = navController,
            assets = assets,
            modifier = Modifier.padding(padding),
        )
    }
}

/**
 * Switch tabs without growing the back stack: pop to the graph start, keep each
 * tab's own state, and never stack duplicates of the same tab.
 */
private fun NavHostController.switchToTab(tab: ShellTab) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Bar icons come from the Readest-style vector drawable set
 * (R.drawable.ic_*, rounded monochrome flat). Stats uses ic_stats (bar chart),
 * which replaces the previous DateRange approximation from material-icons-core.
 */
@Composable
private fun ShellTab.icon() = when (this) {
    ShellTab.LIBRARY -> painterResource(R.drawable.ic_library)
    ShellTab.NOTES -> painterResource(R.drawable.ic_note)
    ShellTab.STATS -> painterResource(R.drawable.ic_stats)
    ShellTab.SETTINGS -> painterResource(R.drawable.ic_settings)
}
