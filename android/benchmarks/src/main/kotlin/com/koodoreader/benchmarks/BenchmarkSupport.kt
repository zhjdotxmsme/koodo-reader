package com.koodoreader.benchmarks

import android.os.Bundle
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

/**
 * Budgets and fixtures shared by the benchmark suite.
 *
 * Targets are the acceptance metrics of docs/android-native-migration.md §8 and
 * are mirrored in docs/android-baseline.json / docs/android-baseline-after.json;
 * `scripts/ci-macro-benchmark.sh` reads the same numbers from there, so a change
 * must be made in the docs first.
 */
object BenchmarkTargets {
    const val PACKAGE = "com.koodoreader.reader"

    const val COLD_START_P90_MS = 1_500L
    const val OPEN_EPUB_P90_MS = 1_200L
    const val PAGE_TURN_P90_MS = 50L
    const val MEMORY_PEAK_MB = 350L

    /** Launcher alias resolved by the `nativeLauncher` manifest placeholder. */
    const val LAUNCHER_ALIAS = "$PACKAGE.LauncherAlias"

    /** `-e benchmarkBookTitle "<title>"` — the fixture as shown on the bookshelf. */
    const val FIXTURE_TITLE_ARG = "benchmarkBookTitle"

    /** 1 MB / 300-chapter EPUB per the migration doc; the title is a fallback. */
    const val FALLBACK_FIXTURE_TITLE = "benchmark-300ch.epub"

    /** `-e benchmarkFailureOnBudget=true` turns soft reports into hard failures. */
    const val FAIL_ON_BUDGET_ARG = "benchmarkFailureOnBudget"

    fun arguments(): Bundle = InstrumentationRegistry.getArguments()

    fun fixtureTitle(): String =
        arguments().getString(FIXTURE_TITLE_ARG)?.takeIf { it.isNotBlank() } ?: FALLBACK_FIXTURE_TITLE

    fun failOnBudget(): Boolean = arguments().getString(FAIL_ON_BUDGET_ARG).toBoolean()

    /** How many page turns one iteration performs (frame metrics aggregate them). */
    const val PAGE_TURNS_PER_ITERATION = 20

    /** How many pages [MemoryPeakBenchmark] reads through before sampling stops. */
    const val MEMORY_READ_PAGES = 120
}

/**
 * UiAutomator helpers. Every wait is bounded: a benchmark that hangs is worse
 * than one that fails, because a hung device poisons the rest of the run.
 */
object Ui {
    const val SHORT_WAIT_MS = 5_000L
    const val OPEN_WAIT_MS = 20_000L

    fun device(): UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    /**
     * Opens the fixture book from the bookshelf by its visible title.
     *
     * Contract with the fixture: the title passed via
     * `-e benchmarkBookTitle` must be visible on the bookshelf without scrolling
     * (push/import the fixture first; see docs/p8-rollout-checklist.md §5.1).
     */
    fun openFixtureBook(device: UiDevice, title: String = BenchmarkTargets.fixtureTitle()) {
        val card = device.wait(Until.findObject(By.textContains(title)), OPEN_WAIT_MS)
            ?: error("fixture book \"$title\" not found on the bookshelf — push it and pass -e benchmarkBookTitle")
        card.click()
        device.waitForIdle()
    }

    /** Waits until the reader chrome (any text node) is on screen. */
    fun awaitReader(device: UiDevice) {
        device.wait(Until.hasObject(By.pkg(BenchmarkTargets.PACKAGE)), SHORT_WAIT_MS)
        device.waitForIdle()
    }

    fun turnPage(device: UiDevice) {
        val (width, height) = device.displayWidth to device.displayHeight
        // Horizontal swipe from the right edge to the left edge = "next page".
        device.swipe(
            (width * 0.85f).toInt(),
            (height * 0.5f).toInt(),
            (width * 0.15f).toInt(),
            (height * 0.5f).toInt(),
            8,
        )
        device.waitForIdle()
    }
}

/** `dumpsys meminfo` probe — the only memory number available without Perfetto. */
object MemoryProbe {
    private val TOTAL_PSS = Regex("TOTAL PSS:\\s*([\\d,]+)")
    private val TOTAL_FALLBACK = Regex("\\bTOTAL:\\s*([\\d,]+)")

    /** @return total PSS in KB, or `null` when the dump could not be parsed. */
    fun totalPssKb(device: UiDevice, packageName: String = BenchmarkTargets.PACKAGE): Long? {
        val dump = device.executeShellCommand("dumpsys meminfo $packageName")
        val match = TOTAL_PSS.find(dump) ?: TOTAL_FALLBACK.find(dump)
        return match?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull()
    }
}

/** Shared scope helper: turn a [MacrobenchmarkScope] into the fixture reader. */
fun MacrobenchmarkScope.openFixtureBook() {
    Ui.openFixtureBook(device)
    Ui.awaitReader(device)
}
