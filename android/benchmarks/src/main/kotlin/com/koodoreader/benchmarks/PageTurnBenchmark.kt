package com.koodoreader.benchmarks

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkRule
import androidx.benchmark.macro.StartupMode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.filters.SdkSuppress
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * AC-3: page turn P90 < 50 ms.
 *
 * `FrameTimingMetric` publishes `frameDurationCpuMs` with P50/P90/P95/P99, which
 * maps 1:1 onto the target, so no extra parsing is needed: the P90 the script
 * reads out of the benchmark JSON *is* this metric's P90.
 *
 * Opening the book lives in `setupBlock` (not measured) — only the page turns are
 * timed, otherwise the first-frame cost of the reader would dominate the sample.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class PageTurnBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun pageTurnFrames() = benchmarkRule.measureRepeated(
        packageName = BenchmarkTargets.PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.WARM,
        iterations = 10,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            openFixtureBook()
        },
        measureBlock = {
            repeat(BenchmarkTargets.PAGE_TURNS_PER_ITERATION) {
                Ui.turnPage(device)
            }
        },
    )
}
