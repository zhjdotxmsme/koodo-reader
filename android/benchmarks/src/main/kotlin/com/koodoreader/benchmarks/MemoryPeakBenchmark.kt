package com.koodoreader.benchmarks

import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * AC-4: memory peak < 350 MB while reading a 100k-character EPUB.
 *
 * Deliberately NOT a `MacrobenchmarkRule` test: Macrobenchmark's public metrics
 * (startup / frame / trace section / power) have no "peak PSS" metric, and a
 * Perfetto `memory` query would drag in the trace processor for one number. So
 * this test drives a full read-through and samples `dumpsys meminfo <pkg>`
 * (`TOTAL PSS`), which is exactly the recipe docs/android-baseline.json records
 * for this metric — now automated.
 *
 * Output: JSON under the app's external files dir
 * (`Android/data/com.koodoreader.reader/files/benchmark/memory-peak.json`) plus a
 * logcat line, both of which `scripts/ci-macro-benchmark.sh` picks up.
 *
 * Budget enforcement is opt-in (`-e benchmarkFailureOnBudget=true`) because the
 * number is device-class dependent; the default run only records it.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class MemoryPeakBenchmark {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)

    @Test
    fun readingThroughKeepsPeakPssUnderBudget() {
        val context = instrumentation.targetContext
        val launch = requireNotNull(context.packageManager.getLaunchIntentForPackage(BenchmarkTargets.PACKAGE)) {
            "no launcher intent for ${BenchmarkTargets.PACKAGE}"
        }.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        context.startActivity(launch)
        device.waitForIdle()

        Ui.openFixtureBook(device)
        Ui.awaitReader(device)

        var peakKb = 0L
        var samples = 0
        var firstKb: Long? = null
        repeat(BenchmarkTargets.MEMORY_READ_PAGES) { index ->
            Ui.turnPage(device)
            if (index % SAMPLE_EVERY == 0) {
                MemoryProbe.totalPssKb(device)?.let { kb ->
                    if (firstKb == null) firstKb = kb
                    peakKb = maxOf(peakKb, kb)
                    samples++
                }
            }
        }

        val peakMb = peakKb / 1024.0
        val growthMb = (peakKb - (firstKb ?: peakKb)) / 1024.0
        val report = JSONObject()
            .put("metric", "memoryPeakMb")
            .put("packageName", BenchmarkTargets.PACKAGE)
            .put("fixture", BenchmarkTargets.fixtureTitle())
            .put("pagesRead", BenchmarkTargets.MEMORY_READ_PAGES)
            .put("samples", samples)
            .put("firstPssMb", (firstKb ?: 0L) / 1024.0)
            .put("peakPssMb", peakMb)
            .put("growthMb", growthMb)
            .put("targetMb", BenchmarkTargets.MEMORY_PEAK_MB)
            .put("measuredAt", System.currentTimeMillis())

        val out = File(context.getExternalFilesDir(null) ?: context.filesDir, "benchmark")
            .apply { mkdirs() }
            .resolve("memory-peak.json")
        out.writeText(report.toString(2), Charsets.UTF_8)
        Log.i(TAG, "MEMORY_PEAK_MB=$peakMb samples=$samples report=${out.absolutePath}")

        assertTrue("no meminfo sample could be parsed", samples > 0)
        if (BenchmarkTargets.failOnBudget() && peakMb > BenchmarkTargets.MEMORY_PEAK_MB) {
            fail("memory peak ${"%.1f".format(peakMb)} MB exceeds budget ${BenchmarkTargets.MEMORY_PEAK_MB} MB")
        }
    }

    private companion object {
        const val TAG = "KoodoBench"
        const val SAMPLE_EVERY = 10
    }
}
