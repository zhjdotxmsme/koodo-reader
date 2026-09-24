package com.koodoreader.benchmarks

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkRule
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.TraceSectionMetric
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.filters.SdkSuppress
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * AC-2: open EPUB (1 MB / 300 chapters) ≤ 1.2 s.
 *
 * The measurement is a **trace section**, not a wall clock guess: the reader must
 * wrap its open path in
 *
 * ```kotlin
 * android.os.Trace.beginSection("openBook")
 * // … parse + paginate + first frame …
 * android.os.Trace.endSection()
 * ```
 *
 * That instrumentation is a P8 follow-up (docs/p8-rollout-checklist.md §5.2,
 * item P8-F2) because it touches `:app` reader code. Until it lands this
 * benchmark is skipped instead of failing CI:
 *
 *   -e benchmarkOpenEpubTrace=true   # enables it
 *
 * The book itself is driven through the UI, so the same flow also produces the
 * logcat timestamps that `docs/android-baseline.json` records as the interim
 * recipe for this metric.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class OpenEpubBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun openEpubFirstFrame() {
        assumeTrue(
            "openBook trace section not wired yet (docs/p8-rollout-checklist.md §5.2 P8-F2)",
            BenchmarkTargets.arguments().getString("benchmarkOpenEpubTrace").toBoolean(),
        )
        benchmarkRule.measureRepeated(
            packageName = BenchmarkTargets.PACKAGE,
            metrics = listOf(TraceSectionMetric("openBook", TraceSectionMetric.Mode.First)),
            compilationMode = CompilationMode.None(),
            startupMode = StartupMode.WARM,
            iterations = 10,
            setupBlock = { pressHome() },
            measureBlock = {
                startActivityAndWait()
                openFixtureBook()
            },
        )
    }

    /**
     * Book-open flow smoke: no trace dependency, just proof that the fixture
     * opens and produces frames. Keeps the UiAutomator contract (title visible on
     * the first bookshelf screen) under test even while the trace section is
     * missing.
     */
    @Test
    fun fixtureBookOpens() {
        benchmarkRule.measureRepeated(
            packageName = BenchmarkTargets.PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.None(),
            startupMode = StartupMode.WARM,
            iterations = 1,
            setupBlock = { pressHome() },
            measureBlock = {
                startActivityAndWait()
                openFixtureBook()
            },
        )
    }
}
