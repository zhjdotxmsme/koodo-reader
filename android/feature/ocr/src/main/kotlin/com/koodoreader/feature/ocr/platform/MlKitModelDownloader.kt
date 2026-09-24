// feature/ocr — ML Kit adapter for the on-demand download contract.
//
// This is the ANDROID half of the OCR download layer: it turns
// [OcrModelInstaller]'s probe into a real ML Kit call. The state machine,
// coalescing and retry policy live in the pure module
// (`ocr/OcrModelInstaller.kt`, covered by OcrModelInstallerTest).
//
// ---------------------------------------------------------------------------
// Why there is no ModuleInstallClient here (was: BLOCKED, 2026-09-24)
//
// The first revision used the Play-services module-install API
// (`ModuleInstall.getClient(...).areModulesAvailable/installModules`). That API
// needs an `OptionalModuleApi` per module, and ML Kit's text-recognition options
// objects are not one:
//
//   $ javap -classpath play-services-mlkit-text-recognition-19.0.1/classes.jar \
//       com.google.mlkit.vision.text.latin.TextRecognizerOptions
//   public class ...TextRecognizerOptions
//       implements com.google.mlkit.vision.text.TextRecognizerOptionsInterface
//   $ javap ... com.google.mlkit.vision.text.TextRecognizerOptionsInterface
//   public interface ...TextRecognizerOptionsInterface {   // no OptionalModuleApi
//     getModuleId() is declared; getOptionalFeatures() is not
//   }
//
// 19.0.1 is the newest published version, and the bundled `com.google.mlkit:
// text-recognition*` artifacts do not put that class on the classpath at all, so
// no import change, version bump or adapter makes it type-correct. This file
// therefore implements the documented fallback: the manifest `DEPENDENCIES`
// meta-data is the install-time pre-download mechanism, and "is the model usable
// yet?" is answered by probing the recognizer — which also triggers ML Kit's own
// on-demand fetch for a missing model.
//
// Android-only file; the contract it implements is JVM-tested through
// OcrModelInstaller.
// ---------------------------------------------------------------------------
package com.koodoreader.feature.ocr.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognizer
import com.koodoreader.feature.ocr.DownloadResult
import com.koodoreader.feature.ocr.DownloadState
import com.koodoreader.feature.ocr.ModelPack
import com.koodoreader.feature.ocr.ModelProbe
import com.koodoreader.feature.ocr.OcrModelInstaller
import com.koodoreader.feature.ocr.OcrScript
import com.koodoreader.feature.ocr.OnDemandDownloader
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [OnDemandDownloader] for ML Kit script models.
 *
 * See the file header for why a probe replaces the ModuleInstall API, and
 * `ocr/OcrModelInstaller.kt` for the retry/coalescing policy.
 */
class MlKitModelDownloader(
    retryDelaysMs: List<Long> = OcrModelInstaller.DEFAULT_RETRY_DELAYS_MS,
    probe: ModelProbe = MlKitModelProbe(),
) : OnDemandDownloader {

    private val installer = OcrModelInstaller(probe = probe, retryDelaysMs = retryDelaysMs)

    /**
     * Kept for call sites that already hold a `Context` (see [OcrWiring]); ML Kit
     * resolves its own `MlKitContext` from the manifest-registered init provider,
     * so nothing here needs the context.
     */
    constructor(
        context: Context,
        retryDelaysMs: List<Long> = OcrModelInstaller.DEFAULT_RETRY_DELAYS_MS,
        probe: ModelProbe = MlKitModelProbe(),
    ) : this(retryDelaysMs, probe)

    override fun state(pack: ModelPack): DownloadState = installer.state(pack)

    override suspend fun ensureInstalled(
        pack: ModelPack,
        onProgress: ((Float) -> Unit)?,
    ): DownloadResult = installer.ensureInstalled(pack, onProgress)

    override suspend fun release(pack: ModelPack): Boolean = installer.release(pack)

    /** Convenience for the settings screen: install without progress reporting. */
    suspend fun install(pack: ModelPack): DownloadResult = installer.install(pack)

    /** Probe once and cache: "is this script usable on this device?" */
    suspend fun refresh(pack: ModelPack): DownloadState = installer.refresh(pack)

    /**
     * The real probe: create the script's recognizer and run it against a blank
     * image.
     *
     * Creating the recognizer already resolves the model; running it once is what
     * makes ML Kit fetch a model that is not on the device yet, and it is also
     * what surfaces "no Play services" / "no network" as a failure instead of a
     * silent empty result. The blank image keeps the cost to a model load plus an
     * empty-page pass.
     */
    internal class MlKitModelProbe(
        private val recognizerFactory: (OcrScript) -> TextRecognizer =
            MlKitOcrProvider.Companion::defaultRecognizer,
    ) : ModelProbe {

        override suspend fun probe(script: OcrScript) {
            val recognizer = recognizerFactory(script)
            try {
                val bitmap = Bitmap.createBitmap(PROBE_SIZE, PROBE_SIZE, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.WHITE)
                    val image = InputImage.fromBitmap(bitmap, 0)
                    suspendCancellableCoroutine { continuation ->
                        recognizer.process(image)
                            .addOnSuccessListener { if (continuation.isActive) continuation.resume(Unit) }
                            .addOnFailureListener { error ->
                                if (continuation.isActive) continuation.resumeWithException(error)
                            }
                    }
                } finally {
                    bitmap.recycle()
                }
            } finally {
                runCatching { recognizer.close() }
            }
        }

        private companion object {
            /** Small enough to be free, large enough for ML Kit to accept it. */
            const val PROBE_SIZE = 8
        }
    }
}
