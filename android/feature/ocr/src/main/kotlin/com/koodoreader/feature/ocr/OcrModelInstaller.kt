// feature/ocr — model-install state machine (P6, pure JVM).
//
// Split out of platform/MlKitModelDownloader.kt so the logic that decides
// "installed / downloading / failed" is unit-testable without Android: the
// Android side can only *probe* (create a recognizer and run it), and a probe is
// just `suspend (OcrScript) -> Unit` that throws when the model is unusable.
//
// Why a probe at all instead of the Play-services ModuleInstall API? Because
// ML Kit's text-recognition options objects are NOT `OptionalModuleApi`s, which
// makes `ModuleInstallRequest.addApi(...)` impossible for them (javap evidence
// in feature/ocr/build.gradle). The models still live in Google Play services and
// are still fetched on demand — the app simply does not get to drive that
// download explicitly:
//   1. the app manifest declares
//      `<meta-data android:name="com.google.mlkit.vision.DEPENDENCIES" .../>`
//      so Play services pre-fetches the listed models at install time;
//   2. whatever is still missing is fetched by ML Kit on first use, which is
//      exactly what the probe triggers.
//
// Consequences that the UI must live with (contract in
// OnDemandModelDownloader.kt):
//   * **No byte progress**: ML Kit exposes no download progress for these
//     models, so [DownloadState.Downloading] carries `progress = null` and
//     `onProgress` is never called. The UI shows an indeterminate spinner.
//   * **No uninstall**: the packs are Play-services modules; [release] returns
//     false (contract rule 6).
//   * "downloaded" vs "already there" is only observable through the probe
//     order: a probe that succeeds on the first attempt reports
//     [DownloadResult.AlreadyInstalled]; one that only succeeds after a retry
//     proves a fetch happened in between and reports [DownloadResult.Downloaded].
package com.koodoreader.feature.ocr

import kotlinx.coroutines.delay

/**
 * Runs a recognizer once against a tiny blank image.
 *
 * Success = the script model is usable on this device. Failure = missing model,
 * no Play services, no network, no disk space, … — the caller retries, because
 * ML Kit fetches a missing model on first use.
 */
fun interface ModelProbe {
    suspend fun probe(script: OcrScript)
}

class OcrModelInstaller(
    private val probe: ModelProbe,
    /** Backoff between probe attempts; its size decides the attempt count. */
    private val retryDelaysMs: List<Long> = DEFAULT_RETRY_DELAYS_MS,
    /** Injectable for tests; real callers want the default. */
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : OnDemandDownloader {

    private val lock = Any()
    private val observed = mutableMapOf<String, DownloadState>()
    private val inFlight = mutableSetOf<String>()

    /** Last observed state; never performs IO (contract rule 1). */
    override fun state(pack: ModelPack): DownloadState = synchronized(lock) {
        observed[pack.id] ?: DownloadState.NotInstalled
    }

    /** Probe once and cache the answer (the settings screen's "check" action). */
    suspend fun refresh(pack: ModelPack): DownloadState {
        val script = pack.script ?: return DownloadState.NotInstalled
        val state = runCatching { probe.probe(script) }.fold(
            onSuccess = { DownloadState.Installed },
            onFailure = { DownloadState.NotInstalled },
        )
        synchronized(lock) { observed[pack.id] = state }
        return state
    }

    override suspend fun ensureInstalled(
        pack: ModelPack,
        onProgress: ((Float) -> Unit)?,
    ): DownloadResult {
        val script = pack.script ?: return DownloadResult.Unsupported

        // One install per pack at a time; late callers observe Downloading and
        // are told so rather than starting a second probe (contract rule 5).
        val started = synchronized(lock) {
            if (!inFlight.add(pack.id)) {
                false
            } else {
                observed[pack.id] = DownloadState.Downloading(null)
                true
            }
        }
        if (!started) return DownloadResult.Failed("already downloading", retryable = true)

        try {
            var firstError: String? = null
            // attempts = retries + 1: the first probe is the "is it there?" check,
            // every later one can only succeed because a fetch completed.
            val attempts = retryDelaysMs.size + 1
            for (attempt in 0 until attempts) {
                val failure = runCatching { probe.probe(script) }.exceptionOrNull()
                if (failure == null) {
                    val outcome = if (attempt == 0) {
                        DownloadResult.AlreadyInstalled
                    } else {
                        DownloadResult.Downloaded(pack.approxBytes)
                    }
                    synchronized(lock) { observed[pack.id] = DownloadState.Installed }
                    return outcome
                }
                if (firstError == null) {
                    firstError = failure.message ?: failure::class.java.simpleName
                }
                // ML Kit reports no progress for these models, so nothing is
                // reported through `onProgress`; the state stays indeterminate.
                retryDelaysMs.getOrNull(attempt)?.let { sleep(it) }
            }
            val reason = firstError ?: "model unavailable"
            synchronized(lock) { observed[pack.id] = DownloadState.Failed(reason) }
            return DownloadResult.Failed(reason)
        } finally {
            synchronized(lock) { inFlight.remove(pack.id) }
        }
    }

    /** The packs live in Google Play services: the app cannot delete them. */
    override suspend fun release(pack: ModelPack): Boolean {
        synchronized(lock) { observed.remove(pack.id) }
        return false
    }

    /** Convenience for the host: install without progress reporting. */
    suspend fun install(pack: ModelPack): DownloadResult = ensureInstalled(pack)

    companion object {
        /**
         * ~1 s, 3 s, 8 s: long enough for Play services to land a small model on
         * a normal connection, short enough that a user staring at the spinner
         * still gets a failure it can act on.
         */
        val DEFAULT_RETRY_DELAYS_MS: List<Long> = listOf(1_000L, 3_000L, 8_000L)
    }
}
