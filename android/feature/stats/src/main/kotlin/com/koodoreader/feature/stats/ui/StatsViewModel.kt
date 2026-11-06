// feature/stats — state holder of the /stats screen.
package com.koodoreader.feature.stats.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.koodoreader.feature.stats.AnnotationStatsStore
import com.koodoreader.feature.stats.BookReadingRank
import com.koodoreader.feature.stats.FormatShare
import com.koodoreader.feature.stats.IsoDay
import com.koodoreader.feature.stats.ReadingSession
import com.koodoreader.feature.stats.ReadingSessionRepository
import com.koodoreader.feature.stats.StatsAggregator
import com.koodoreader.feature.stats.StatsBookInfo
import com.koodoreader.feature.stats.StatsDomain
import com.koodoreader.feature.stats.StatsRange
import com.koodoreader.feature.stats.StatsSnapshot
import com.koodoreader.feature.stats.StatsSummary
import kotlinx.coroutines.launch

/** Desktop `chartTab: "line" | "bar"`. */
enum class ChartTab { BAR, LINE }

/** Desktop `StatsState` + loading/error, in Compose-observable form. */
data class StatsUiState(
    val isLoading: Boolean = true,
    val snapshot: StatsSnapshot = StatsSnapshot.EMPTY,
    val chartTab: ChartTab = ChartTab.BAR,
    val error: String? = null,
    /** Today’s annotation counts ([StatsDomain.daily]). */
    val todayNotesAdded: Int = 0,
    val todayHighlightsAdded: Int = 0,
    /** Range for the distribution/ranking sections (summary cards are fixed). */
    val range: StatsRange = StatsRange.SEVEN_DAYS,
    val summary: StatsSummary = StatsSummary(0, 0, 0),
    val formatShares: List<FormatShare> = emptyList(),
    val hourBuckets: List<Long> = List(24) { 0L },
    val ranking: List<BookReadingRank> = emptyList(),
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
    private val annotationStats: AnnotationStatsStore = AnnotationStatsStore { emptyList() },
    private val bookInfoProvider: suspend () -> Map<String, StatsBookInfo> = { emptyMap() },
    private val zoneOffsetProvider: () -> Int = { 0 },
) : ViewModel() {

    var state by mutableStateOf(StatsUiState())
        private set

    private var cachedSessions: List<ReadingSession> = emptyList()
    private var cachedBooks: Map<String, StatsBookInfo> = emptyMap()
    private var cachedToday: IsoDay = IsoDay(1970, 1, 1)

    private data class Derived(
        val formatShares: List<FormatShare>,
        val hourBuckets: List<Long>,
        val ranking: List<BookReadingRank>,
    )

    private fun derive(range: StatsRange): Derived {
        val scoped = StatsDomain.inRange(cachedSessions, range, cachedToday)
        return Derived(
            formatShares = StatsDomain.formatDistribution(scoped, cachedBooks),
            hourBuckets = StatsDomain.hourDistribution(scoped, zoneOffsetProvider()),
            ranking = StatsAggregator().rankBooks(scoped, cachedBooks.mapValues { it.value.title }),
        )
    }

    fun selectRange(range: StatsRange) {
        val derived = derive(range)
        state = state.copy(
            range = range,
            formatShares = derived.formatShares,
            hourBuckets = derived.hourBuckets,
            ranking = derived.ranking,
        )
    }

    fun load() {
        state = state.copy(isLoading = true, error = null)
        viewModelScope.launch {
            state = try {
                val today = todayProvider()
                val sessions = repository.loadSessionsForStats()
                val events = annotationStats.events()
                val books = bookInfoProvider()
                cachedSessions = sessions
                cachedBooks = books
                cachedToday = today
                val derived = derive(state.range)
                val daily = StatsDomain.daily(today, sessions, events)
                val snapshot = repository.snapshot(
                    booksRead = booksReadProvider(),
                    progressByBook = progressProvider(),
                    today = today,
                )
                state.copy(
                    isLoading = false,
                    snapshot = snapshot,
                    error = null,
                    todayNotesAdded = daily.notesAddedCount,
                    todayHighlightsAdded = daily.highlightsAddedCount,
                    summary = StatsDomain.summary(sessions, today),
                    formatShares = derived.formatShares,
                    hourBuckets = derived.hourBuckets,
                    ranking = derived.ranking,
                )
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
