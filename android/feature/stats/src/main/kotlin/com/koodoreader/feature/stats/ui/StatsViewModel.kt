// feature/stats — state holder of the /stats screen.
package com.koodoreader.feature.stats.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.koodoreader.feature.stats.IsoDay
import com.koodoreader.feature.stats.ReadingSessionRepository
import com.koodoreader.feature.stats.StatsSnapshot
import kotlinx.coroutines.launch

/** Desktop `chartTab: "line" | "bar"`. */
enum class ChartTab { BAR, LINE }

/** Desktop `StatsState` + loading/error, in Compose-observable form. */
data class StatsUiState(
    val isLoading: Boolean = true,
    val snapshot: StatsSnapshot = StatsSnapshot.EMPTY,
    val chartTab: ChartTab = ChartTab.BAR,
    val error: String? = null,
)

/**
 * Loads the snapshot from [ReadingSessionRepository].
 *
 * `booksRead` and `progressByBook` come from the library/Room layer (desktop
 * reads them from `recordLocation`), so they are injected as suspending
 * providers instead of hard-wiring the module to :core:data.
 */
class StatsViewModel(
    private val repository: ReadingSessionRepository,
    private val booksReadProvider: suspend () -> Int = { 0 },
    private val progressProvider: suspend () -> Map<String, Double> = { emptyMap() },
    private val todayProvider: () -> IsoDay,
) : ViewModel() {

    var state by mutableStateOf(StatsUiState())
        private set

    fun load() {
        state = state.copy(isLoading = true, error = null)
        viewModelScope.launch {
            state = try {
                val snapshot = repository.snapshot(
                    booksRead = booksReadProvider(),
                    progressByBook = progressProvider(),
                    today = todayProvider(),
                )
                state.copy(isLoading = false, snapshot = snapshot, error = null)
            } catch (failure: Exception) {
                // Desktop `loadStats` swallows database errors and shows zeroes;
                // the native screen keeps the reason for the log instead.
                state.copy(isLoading = false, snapshot = StatsSnapshot.EMPTY, error = failure.message)
            }
        }
    }

    fun selectChartTab(tab: ChartTab) {
        state = state.copy(chartTab = tab)
    }
}
