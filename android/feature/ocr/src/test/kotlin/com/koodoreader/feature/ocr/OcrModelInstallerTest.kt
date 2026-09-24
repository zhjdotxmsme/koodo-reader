package com.koodoreader.feature.ocr

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [OcrModelInstaller] — the part of the OCR download layer that used to be
 * untestable because it lived in the Android adapter (`MlKitModelDownloader`),
 * which was excluded from the build entirely (see its file header: ML Kit's
 * options are not `OptionalModuleApi`s, so the ModuleInstall path could not
 * compile).
 *
 * The probe above it is a one-call seam, which is what makes these assertions
 * possible without a device or Google Play services.
 */
class OcrModelInstallerTest {

    private val latin = OcrModelCatalog.packFor(OcrScript.LATIN)

    /** Fails the first [failures] probes, then succeeds. Records every attempt. */
    private class CountingProbe(private var failures: Int) : ModelProbe {
        var attempts = 0
            private set
        var lastScript: OcrScript? = null
            private set

        override suspend fun probe(script: OcrScript) {
            attempts++
            lastScript = script
            if (failures > 0) {
                failures--
                throw IllegalStateException("model not downloaded")
            }
        }
    }

    private fun installer(probe: ModelProbe, delays: List<Long> = listOf(1L, 1L)): OcrModelInstaller {
        val slept = mutableListOf<Long>()
        return OcrModelInstaller(probe = probe, retryDelaysMs = delays, sleep = { slept.add(it) })
    }

    @Test
    fun `state is cheap and starts as not installed`() {
        val probe = CountingProbe(failures = 0)
        val installer = installer(probe)
        assertEquals(DownloadState.NotInstalled, installer.state(latin))
        // Contract rule 1: querying state must not run the probe.
        assertEquals(0, probe.attempts)
    }

    @Test
    fun `a model that is already usable reports AlreadyInstalled`() = runBlocking {
        val probe = CountingProbe(failures = 0)
        val installer = installer(probe)
        val result = installer.ensureInstalled(latin)
        assertEquals(DownloadResult.AlreadyInstalled, result)
        assertEquals(1, probe.attempts)
        assertEquals(DownloadState.Installed, installer.state(latin))
    }

    @Test
    fun `a model that appears after a retry reports Downloaded`() = runBlocking {
        // First probe misses (Play services is still fetching), second succeeds:
        // that ordering is the only observable proof a fetch happened.
        val probe = CountingProbe(failures = 1)
        val installer = installer(probe, delays = listOf(1L, 1L))
        val result = installer.ensureInstalled(latin)
        assertTrue(result is DownloadResult.Downloaded)
        assertEquals(latin.approxBytes, (result as DownloadResult.Downloaded).bytes)
        assertEquals(2, probe.attempts)
        assertEquals(DownloadState.Installed, installer.state(latin))
    }

    @Test
    fun `a permanently missing model fails with the first error`() = runBlocking {
        val probe = CountingProbe(failures = Int.MAX_VALUE)
        val installer = installer(probe, delays = listOf(1L, 1L))
        val result = installer.ensureInstalled(latin)
        assertTrue(result is DownloadResult.Failed)
        assertEquals("model not downloaded", (result as DownloadResult.Failed).reason)
        assertTrue(result.retryable)
        // delays.size + 1 attempts: the first check plus one per backoff.
        assertEquals(3, probe.attempts)
        assertTrue(installer.state(latin) is DownloadState.Failed)
    }

    @Test
    fun `no retry delays means exactly one attempt`() = runBlocking {
        val probe = CountingProbe(failures = Int.MAX_VALUE)
        val installer = installer(probe, delays = emptyList())
        assertTrue(installer.ensureInstalled(latin) is DownloadResult.Failed)
        assertEquals(1, probe.attempts)
    }

    @Test
    fun `progress stays indeterminate because ML Kit exposes no byte counts`() = runBlocking {
        val probe = CountingProbe(failures = 0)
        val installer = installer(probe)
        val reported = mutableListOf<Float>()
        installer.ensureInstalled(latin) { reported.add(it) }
        // Documented consequence: the UI must show an indeterminate spinner; a
        // fabricated 0..1 ramp here would be a lie the host could not act on.
        assertTrue(reported.isEmpty())
    }

    @Test
    fun `a pack without a script is unsupported`() = runBlocking {
        val probe = CountingProbe(failures = 0)
        val installer = installer(probe)
        val pack = ModelPack(id = "custom", displayName = "Custom", approxBytes = 1L, script = null)
        assertEquals(DownloadResult.Unsupported, installer.ensureInstalled(pack))
        assertEquals(0, probe.attempts)
    }

    @Test
    fun `release never claims to free space`() = runBlocking {
        val probe = CountingProbe(failures = 0)
        val installer = installer(probe)
        installer.ensureInstalled(latin)
        // Contract rule 6: the packs are Google Play services modules.
        assertFalse(installer.release(latin))
        // …but the cached observation is dropped so the UI re-checks.
        assertEquals(DownloadState.NotInstalled, installer.state(latin))
    }

    @Test
    fun `refresh caches a probe result without retrying`() = runBlocking {
        val probe = CountingProbe(failures = Int.MAX_VALUE)
        val installer = installer(probe, delays = listOf(1L, 1L))
        assertEquals(DownloadState.NotInstalled, installer.refresh(latin))
        assertEquals(1, probe.attempts)
    }

    @Test
    fun `catalogue exposes one pack per script with its manifest token`() {
        assertEquals(OcrScript.entries.size, OcrModelCatalog.packs.size)
        for (script in OcrScript.entries) {
            val pack = OcrModelCatalog.packFor(script)
            assertEquals(script, pack.script)
            assertEquals(script.manifestValue, pack.manifestValue)
            // The manifest value is what the app's DEPENDENCIES meta-data lists.
            assertEquals(script.unbundledBytes, pack.approxBytes)
        }
    }
}
