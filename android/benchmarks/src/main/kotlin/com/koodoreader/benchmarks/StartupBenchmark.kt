package com.koodoreader.benchmarks

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.MacrobenchmarkRule
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.filters.SdkSuppress
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * AC-1: cold start P90 ≤ 1.5 s (docs/android-native-migration.md §8).
 *
 * `StartupTimingMetric` aggregates with `timeToInitialDisplay` /
 * `timeToFullDisplay`; the metric it publishes carries one sample per iteration
 * and reports median/min/max. The **P90** number is derived by
 * `scripts/ci-macro-benchmark.sh`: it reads the per-iteration samples from the
 * benchmark JSON (`metrics.*.runs`, falling back to `median` when a benchmark
 * version omits the raw samples) and cross-checks it against
 * `scripts/measure-cold-start.js`, which computes a true P90 from
 * `am start -W` samples and does not need the profileable flag.
 *
 * `CompilationMode.None()` matches the shipped release shape (no AOT profile):
 * the app is measured the way a fresh install runs it.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class StartupBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartup() = benchmarkRule.measureRepeated(
        packageName = BenchmarkTargets.PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.COLD,
        iterations = 10,
    ) {
        pressHome()
        startActivityAndWait()
    }

    /**
     * Warm start, kept as a second data point: the delta between cold and warm is
     * what tells us whether class loading or I/O dominates the budget.
     */
    @Test
    fun warmStartup() = benchmarkRule.measureRepeated(
        packageName = BenchmarkTargets.PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.WARM,
        iterations = 10,
    ) {
        pressHome()
        startActivityAndWait()
    }
}
