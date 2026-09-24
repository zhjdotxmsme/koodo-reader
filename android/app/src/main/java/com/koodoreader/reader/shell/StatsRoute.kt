package com.koodoreader.reader.shell

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.koodoreader.core.data.KoodoDatabaseProvider
import com.koodoreader.feature.stats.platform.ReadingSessionWiring
import com.koodoreader.feature.stats.ui.StatsScreen
import com.koodoreader.feature.stats.ui.StatsViewModel

/**
 * Host of the P6 reading-stats screen (desktop `/stats`).
 *
 * Why this file exists: `feature:stats` shipped the aggregate, the Compose
 * screen and the state holder, but nothing in `:app` ever mounted them — the
 * module was "delivered, unreachable" (docs/android-completeness-2026-09-24.md
 * §4/§12). This is the missing glue and nothing else:
 *  - the repository comes from the module's own [ReadingSessionWiring] (its
 *    private Room database, see platform/StatsDatabase.kt);
 *  - `booksRead` is the library row count from `:core:data`;
 *  - the i18n lookup is the desktop-parity [LocalI18n].
 *
 * Known gap, deliberately not faked: `progressProvider` is empty because the
 * native readers do not persist reading progress yet (P2 / gap 1). `:core:data`'s
 * `books` table has no progress column, so there is nothing truthful to pass;
 * the screen renders the same zeroes the desktop shows for a fresh database.
 */
@Composable
fun StatsRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    // `I18nState.t` is a plain lookup; the composable wrapper cannot be called
    // from the screen's non-composable `t:` parameter.
    val i18n = LocalI18n.current
    val language by i18n.language.collectAsState()
    @Suppress("UNUSED_EXPRESSION")
    language

    val factory = remember(app) {
        viewModelFactory {
            initializer {
                val db = KoodoDatabaseProvider.get(app)
                StatsViewModel(
                    repository = ReadingSessionWiring.repository(app),
                    booksReadProvider = { db.bookDao().count().toInt() },
                    progressProvider = { emptyMap() },
                    todayProvider = {
                        ReadingSessionWiring.localToday(System.currentTimeMillis())
                    },
                )
            }
        }
    }
    val viewModel: StatsViewModel = viewModel(factory = factory)
    LaunchedEffect(viewModel) { viewModel.load() }

    StatsScreen(
        state = viewModel.state,
        darkTheme = isSystemInDarkTheme(),
        t = { key -> i18n.localization.t(key) },
        onClose = onBack,
        onChartTabSelected = viewModel::selectChartTab,
    )
}
