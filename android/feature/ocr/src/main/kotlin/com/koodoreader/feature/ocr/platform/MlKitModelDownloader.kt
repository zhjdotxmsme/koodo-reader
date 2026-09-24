// feature/ocr — ML Kit model packs through the Play services module-install API.
//
// ML Kit's unbundled models ARE on-demand Google Play services modules, so the
// download is driven by `ModuleInstallClient`
// (https://developers.google.cn/android/guides/module-install-apis):
//   areModulesAvailable → check, installModules → urgent install with progress,
//   deferredInstall → background install, InstallStatusListener → progress.
//
// Two things the app must also do (main-thread wiring, NOT done by this module):
//   1. request the install-time download in the app manifest:
//        <meta-data android:name="com.google.mlkit.vision.DEPENDENCIES"
//                   android:value="ocr,ocr_chinese" />
//      (`OcrScript.manifestValue(listOf(...))` builds that value);
//   2. add `com.google.android.gms:play-services-base` (ModuleInstall lives
//      there) — already implied by the play-services-mlkit-* artifacts.
//
// Android-only file (excluded from the JVM harness); the contract it implements
// is covered by OnDemandModelDownloaderTest against the in-memory double.
package com.koodoreader.feature.ocr.platform

import android.content.Context
import com.google.android.gms.common.api.OptionalModuleApi
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallClient
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.koodoreader.feature.ocr.DownloadResult
import com.koodoreader.feature.ocr.DownloadState
import com.koodoreader.feature.ocr.ModelPack
import com.koodoreader.feature.ocr.OcrScript
import com.koodoreader.feature.ocr.OnDemandDownloader
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The `OptionalModuleApi` handle of an ML Kit script model.
 *
 * ML Kit's text-recognition options objects are the module handles for the
 * Play-services artifacts — the very same objects are passed to
 * `TextRecognition.getClient(...)`. The declared return type is nullable on
 * purpose: if a future ML Kit version stops exposing them here, this file stops
 * COMPILING at these five lines instead of silently reporting "not installed".
 * (Integration-time check, design doc §5.3.)
 */
object MlKitOptionalModuleApis {
    fun of(script: OcrScript): OptionalModuleApi? = when (script) {
        OcrScript.LATIN -> TextRecognizerOptions.DEFAULT_OPTIONS
        OcrScript.CHINESE -> ChineseTextRecognizerOptions.Builder().build()
        OcrScript.DEVANAGARI -> DevanagariTextRecognizerOptions.Builder().build()
        OcrScript.JAPANESE -> JapaneseTextRecognizerOptions.Builder().build()
        OcrScript.KOREAN -> KoreanTextRecognizerOptions.Builder().build()
    }
}

/**
 * [OnDemandDownloader] for ML Kit model packs.
 *
 * `state` never performs IO (contract rule 1): it returns the last observed
 * availability, refreshed by [refresh] / [ensureInstalled].
 * `release` always returns false — models live in Google Play services and
 * cannot be removed by the app (contract rule 6).
 */
class MlKitModelDownloader(
    private val client: ModuleInstallClient,
    private val apiFor: (OcrScript) -> OptionalModuleApi? = MlKitOptionalModuleApis::of,
) : OnDemandDownloader {

    private val observed = ConcurrentHashMap<String, DownloadState>()

    constructor(context: Context) : this(ModuleInstall.getClient(context.applicationContext))

    override fun state(pack: ModelPack): DownloadState =
        observed[pack.id] ?: DownloadState.NotInstalled

    /** Queries Google Play services once and caches the answer. */
    suspend fun refresh(pack: ModelPack): DownloadState {
        val api = pack.script?.let(apiFor) ?: return DownloadState.NotInstalled
        val available = suspendCancellableCoroutine { continuation ->
            client.areModulesAvailable(api)
                .addOnSuccessListener { response ->
                    if (continuation.isActive) continuation.resume(response.areModulesAvailable())
                }
                .addOnFailureListener { error ->
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
        }
        val state = if (available) DownloadState.Installed else DownloadState.NotInstalled
        observed[pack.id] = state
        return state
    }

    override suspend fun ensureInstalled(
        pack: ModelPack,
        onProgress: ((Float) -> Unit)?,
    ): DownloadResult {
        val api = pack.script?.let(apiFor)
            ?: return DownloadResult.Unsupported

        runCatching { refresh(pack) }
        if (observed[pack.id] == DownloadState.Installed) return DownloadResult.AlreadyInstalled

        observed[pack.id] = DownloadState.Downloading(null)
        val outcome = runCatching {
            suspendCancellableCoroutine { continuation ->
                val listener = object : InstallStatusListener {
                    override fun onInstallStatusUpdated(update: ModuleInstallStatusUpdate) {
                        update.progressInfo?.let { info ->
                            val total = info.totalBytesToDownload
                            val progress =
                                if (total > 0L) (info.bytesDownloaded.toFloat() / total.toFloat()) else null
                            observed[pack.id] = DownloadState.Downloading(progress)
                            if (progress != null && continuation.isActive) onProgress?.invoke(progress)
                        }
                        when (update.installState) {
                            ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED -> {
                                client.unregisterListener(this)
                                if (continuation.isActive) continuation.resume(DownloadResult.Downloaded(pack.approxBytes))
                            }

                            ModuleInstallStatusUpdate.InstallState.STATE_CANCELED -> {
                                client.unregisterListener(this)
                                if (continuation.isActive) {
                                    continuation.resume(DownloadResult.Failed("download canceled"))
                                }
                            }

                            ModuleInstallStatusUpdate.InstallState.STATE_FAILED -> {
                                client.unregisterListener(this)
                                if (continuation.isActive) {
                                    continuation.resume(
                                        DownloadResult.Failed(
                                            update.errorCode.takeIf { it != 0 }
                                                ?.let { "module install failed (error $it)" }
                                                ?: "module install failed",
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
                val request = ModuleInstallRequest.newBuilder()
                    .addApi(api)
                    .setListener(listener)
                    .build()
                client.installModules(request)
                    .addOnSuccessListener { response ->
                        // "already installed" short-circuits the listener path.
                        if (response.areModulesAlreadyInstalled() && continuation.isActive) {
                            client.unregisterListener(listener)
                            continuation.resume(DownloadResult.AlreadyInstalled)
                        }
                    }
                    .addOnFailureListener { error ->
                        client.unregisterListener(listener)
                        if (continuation.isActive) {
                            continuation.resume(DownloadResult.Failed(error.message ?: "module install request failed"))
                        }
                    }
                continuation.invokeOnCancellation { client.unregisterListener(listener) }
            }
        }

        return outcome.fold(
            onSuccess = { result ->
                observed[pack.id] = when (result) {
                    DownloadResult.AlreadyInstalled, is DownloadResult.Downloaded -> DownloadState.Installed
                    is DownloadResult.Failed -> DownloadState.Failed(result.reason, result.retryable)
                    DownloadResult.Unsupported -> DownloadState.Failed("unsupported", retryable = false)
                }
                result
            },
            onFailure = { error ->
                val reason = error.message ?: error::class.java.simpleName
                observed[pack.id] = DownloadState.Failed(reason)
                DownloadResult.Failed(reason)
            },
        )
    }

    /** ML Kit models are owned by Google Play services — the app cannot delete them. */
    override suspend fun release(pack: ModelPack): Boolean {
        observed.remove(pack.id)
        return false
    }

    /** Convenience for the settings screen: install without progress reporting. */
    suspend fun install(pack: ModelPack): DownloadResult = ensureInstalled(pack)
}
