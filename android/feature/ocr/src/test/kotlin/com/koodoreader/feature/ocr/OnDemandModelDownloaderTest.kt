package com.koodoreader.feature.ocr

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the shared on-demand download interface (the dictionary
 * module implements the same contract — see the file header of
 * OnDemandModelDownloader.kt).
 */
class OnDemandModelDownloaderTest {

    private val chinese = OcrModelCatalog.packFor(OcrScript.CHINESE)

    @Test
    fun `catalogue has one unbundled pack per script`() {
        assertEquals(OcrScript.entries.size, OcrModelCatalog.packs.size)
        assertEquals("mlkit-text-recognition-chinese", chinese.id)
        assertEquals("ocr_chinese", chinese.manifestValue)
        assertEquals(OcrScript.CHINESE.unbundledBytes, chinese.approxBytes)
        // Bundled models are ~4 MB per script per ABI — the reason for the
        // on-demand download (design doc §5).
        assertEquals(4L * 1024L * 1024L * OcrScript.entries.size, OcrModelCatalog.bundledBytes(OcrScript.entries))
    }

    @Test
    fun `state is NotInstalled before the first download`() {
        val downloader = InMemoryOnDemandDownloader()
        assertEquals(DownloadState.NotInstalled, downloader.state(chinese))
    }

    @Test
    fun `ensureInstalled reports progress and ends Installed`() = runBlocking {
        val downloader = InMemoryOnDemandDownloader(steps = 4)
        val progress = mutableListOf<Float>()
        val result = downloader.ensureInstalled(chinese) { progress.add(it) }

        assertEquals(DownloadResult.Downloaded(chinese.approxBytes), result)
        assertEquals(listOf(0.25f, 0.5f, 0.75f, 1.0f), progress)
        assertTrue(progress.zipWithNext().all { (a, b) -> b >= a }) // monotonic
        assertTrue(progress.all { it in 0f..1f })
        assertEquals(DownloadState.Installed, downloader.state(chinese))
        assertEquals(listOf(chinese.id), downloader.downloadAttempts)
    }

    @Test
    fun `an installed pack is never downloaded twice`() = runBlocking {
        val downloader = InMemoryOnDemandDownloader(installed = setOf(chinese.id))
        var progressCalls = 0
        val result = downloader.ensureInstalled(chinese) { progressCalls++ }

        assertEquals(DownloadResult.AlreadyInstalled, result)
        assertEquals(0, progressCalls)
        assertTrue(downloader.downloadAttempts.isEmpty())
    }

    @Test
    fun `a failing download has no partial state`() = runBlocking {
        val downloader = InMemoryOnDemandDownloader(failWith = "no network")
        val result = downloader.ensureInstalled(chinese)

        assertTrue(result is DownloadResult.Failed)
        assertEquals("no network", (result as DownloadResult.Failed).reason)
        assertTrue(result.retryable)
        assertTrue(downloader.state(chinese) is DownloadState.Failed)
        assertTrue(downloader.downloadAttempts.isEmpty())
    }

    @Test
    fun `an unsupported pack fails non-retryably`() = runBlocking {
        val downloader = InMemoryOnDemandDownloader(installable = { false })
        val result = downloader.ensureInstalled(chinese)

        assertTrue(result is DownloadResult.Failed)
        assertFalse((result as DownloadResult.Failed).retryable)
        assertFalse((downloader.state(chinese) as DownloadState.Failed).retryable)
    }

    @Test
    fun `release removes the pack only when it was installed`() = runBlocking {
        val downloader = InMemoryOnDemandDownloader(steps = 1)
        assertFalse(downloader.release(chinese)) // never installed
        downloader.ensureInstalled(chinese)
        assertTrue(downloader.release(chinese))
        assertEquals(DownloadState.NotInstalled, downloader.state(chinese))
    }
}
