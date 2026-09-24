// feature/ocr — on-demand model download contract (P6).
//
// SHARED CONTRACT WITH THE P6 DICTIONARY MODULE
// ---------------------------------------------
// `feature:ocr` and `feature:dictionary` (P6 词典, native MDX/MDD) both need
// "download a model pack on first use, remember it, expose progress". Both
// sub-agents define an interface with the name [OnDemandDownloader] and the
// behaviour below; when both modules are registered in android/settings.gradle
// the MAIN THREAD decides where the single shared declaration lives
// (`:core:common`, or `:feature:dictionary` owning it with `:feature:ocr`
// depending on it). Until then each module stays self-contained — no module
// depends on a file that only exists in the other one.
//
// Contract (verified by OnDemandModelDownloaderTest for the in-memory double,
// and by platform/MlKitModelDownloader.kt for ML Kit):
//   1. `state` is cheap and non-blocking — safe to call from the main thread /
//      on every recomposition; it never performs IO.
//   2. `ensureInstalled` is idempotent: an already installed pack resolves to
//      [DownloadResult.AlreadyInstalled] without touching the network.
//   3. Progress callbacks are monotonically non-decreasing and inside 0f..1f;
//      an instant install may skip the callback entirely.
//   4. There is no partially installed state: the result is either
//      [DownloadResult.Downloaded]/[DownloadResult.AlreadyInstalled] or
//      [DownloadResult.Failed].
//   5. Concurrent calls for the same pack are coalesced by the implementation
//      (one in-flight download, late callers observe [DownloadState.Downloading]).
//   6. `release` may be unsupported: ML Kit downloads live in Google Play
//      services and CANNOT be removed by the app, so it returns `false` there
//      and the UI must not promise a "free up space" action for those packs.
package com.koodoreader.feature.ocr

/** One downloadable pack. OCR packs are ML Kit script models. */
data class ModelPack(
    val id: String,
    val displayName: String,
    val approxBytes: Long,
    val script: OcrScript? = null,
    /** `android:value` token used by the ML Kit manifest meta-data. */
    val manifestValue: String = script?.manifestValue ?: id,
)

/** Observable-ish state of a pack, cheap to query. */
sealed interface DownloadState {
    data object NotInstalled : DownloadState
    data class Downloading(val progress: Float?) : DownloadState
    data object Installed : DownloadState
    data class Failed(val reason: String, val retryable: Boolean = true) : DownloadState
}

sealed interface DownloadResult {
    data object AlreadyInstalled : DownloadResult
    data class Downloaded(val bytes: Long) : DownloadResult
    data class Failed(val reason: String, val retryable: Boolean = true) : DownloadResult
    data object Unsupported : DownloadResult
}

/**
 * The seam both P6 modules implement. See the contract list above.
 */
interface OnDemandDownloader {
    fun state(pack: ModelPack): DownloadState
    suspend fun ensureInstalled(pack: ModelPack, onProgress: ((Float) -> Unit)? = null): DownloadResult

    /** @return true when the pack was actually removed. */
    suspend fun release(pack: ModelPack): Boolean
}

/** OCR side of the pack catalogue (the dictionary module has its own). */
object OcrModelCatalog {

    val packs: List<ModelPack> = OcrScript.entries.map { script ->
        ModelPack(
            id = "mlkit-text-recognition-${script.name.lowercase()}",
            displayName = "ML Kit OCR (${script.name.lowercase()})",
            approxBytes = script.unbundledBytes,
            script = script,
        )
    }

    fun packFor(script: OcrScript): ModelPack =
        packs.first { it.script == script }

    /** Bundled alternative: ~4 MB per script per ABI — see the design doc §5. */
    fun bundledBytes(scripts: Collection<OcrScript>): Long =
        scripts.distinct().sumOf { it.bundledBytes }
}

/**
 * Deterministic double used by unit tests, Compose previews and the
 * "no Play services" fallback path. [steps] simulates a multi-step download so
 * progress reporting can be asserted.
 */
class InMemoryOnDemandDownloader(
    installed: Set<String> = emptySet(),
    private val steps: Int = 3,
    private val failWith: String? = null,
    private val installable: (ModelPack) -> Boolean = { true },
) : OnDemandDownloader {

    private val installedIds = installed.toMutableSet()
    private val states = mutableMapOf<String, DownloadState>()
    private val inFlight = mutableSetOf<String>()

    /** Records the packs whose download was actually attempted. */
    val downloadAttempts = mutableListOf<String>()

    override fun state(pack: ModelPack): DownloadState =
        states[pack.id] ?: if (pack.id in installedIds) DownloadState.Installed else DownloadState.NotInstalled

    override suspend fun ensureInstalled(
        pack: ModelPack,
        onProgress: ((Float) -> Unit)?,
    ): DownloadResult {
        if (pack.id in installedIds) return DownloadResult.AlreadyInstalled
        if (!installable(pack)) {
            states[pack.id] = DownloadState.Failed("not supported on this device", retryable = false)
            return DownloadResult.Failed("not supported on this device", retryable = false)
        }
        failWith?.let { reason ->
            states[pack.id] = DownloadState.Failed(reason)
            return DownloadResult.Failed(reason)
        }
        synchronized(inFlight) {
            if (!inFlight.add(pack.id)) return DownloadResult.Failed("already downloading", retryable = true)
        }
        try {
            val effectiveSteps = steps.coerceAtLeast(1)
            for (step in 1..effectiveSteps) {
                states[pack.id] = DownloadState.Downloading(step.toFloat() / effectiveSteps)
                onProgress?.invoke(step.toFloat() / effectiveSteps)
            }
            installedIds.add(pack.id)
            states[pack.id] = DownloadState.Installed
            downloadAttempts.add(pack.id)
            return DownloadResult.Downloaded(pack.approxBytes)
        } finally {
            synchronized(inFlight) { inFlight.remove(pack.id) }
        }
    }

    override suspend fun release(pack: ModelPack): Boolean {
        states.remove(pack.id)
        return installedIds.remove(pack.id)
    }
}
