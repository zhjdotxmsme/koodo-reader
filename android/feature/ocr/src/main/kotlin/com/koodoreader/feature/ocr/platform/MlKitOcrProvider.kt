// feature/ocr — Android implementation of the OCR engine (ML Kit Text Recognition v2).
//
// API surface: `com.google.mlkit.vision.text.*` — the SAME classes for the
// bundled artifact (`com.google.mlkit:text-recognition[-chinese|...]`) and for
// the Play-services artifact
// (`com.google.android.gms:play-services-mlkit-text-recognition[-chinese|...]`).
// This module depends on the Play-services (ON-DEMAND) variant on purpose:
//
//   * the model is downloaded on first use / install time instead of adding
//     ~4 MB per script per ABI to the APK (unbundled ≈ 260 KB per script per
//     architecture);
//   * no ML Kit `.so` is packaged into our APK at all, which removes the
//     16 KB page-size risk at the source (Google maintains the libraries inside
//     the Play services APK). See docs/p6-stats-ocr-design.md §5.
//
// Android-only file: it is excluded from the JVM test harness by the `platform/`
// package rule (the pure loop it plugs into is covered by
// OcrSearchRepositoryTest with a fake engine).
package com.koodoreader.feature.ocr.platform

import android.graphics.Bitmap
import android.os.SystemClock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.koodoreader.feature.ocr.OcrBox
import com.koodoreader.feature.ocr.OcrEngine
import com.koodoreader.feature.ocr.OcrImage
import com.koodoreader.feature.ocr.OcrLine
import com.koodoreader.feature.ocr.OcrPageResult
import com.koodoreader.feature.ocr.OcrRequest
import com.koodoreader.feature.ocr.OcrScript
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** [OcrImage] backed by an Android [Bitmap] (a rendered PDF page). */
class MlKitPageImage(
    val bitmap: Bitmap,
    val rotationDegrees: Int = 0,
) : OcrImage

/**
 * ML Kit text recognition behind the pure [OcrEngine] seam.
 *
 * One recognizer per script, created lazily and reused: ML Kit clients are
 * expensive to build and are documented as safe to reuse. [close] releases all
 * of them; the reader calls it when leaving the scanned document.
 */
class MlKitOcrProvider(
    private val recognizerFactory: (OcrScript) -> TextRecognizer = ::defaultRecognizer,
) : OcrEngine {

    private val recognizers = LinkedHashMap<OcrScript, TextRecognizer>()

    override suspend fun recognize(request: OcrRequest, image: OcrImage): OcrPageResult {
        val page = image as? MlKitPageImage
            ?: throw IllegalArgumentException("MlKitOcrProvider needs an MlKitPageImage, got ${image::class.java.name}")
        require(page.bitmap.width > 0 && page.bitmap.height > 0) { "empty page bitmap" }

        val recognizer = recognizers.getOrPut(request.script) { recognizerFactory(request.script) }
        val input = InputImage.fromBitmap(page.bitmap, page.rotationDegrees)
        val started = SystemClock.elapsedRealtime()
        val text = recognizer.processSuspend(input)
        return OcrPageResult(
            bookKey = request.bookKey,
            pageIndex = request.pageIndex,
            script = request.script,
            lines = text.toOcrLines(),
            durationMillis = SystemClock.elapsedRealtime() - started,
        )
    }

    override fun close() {
        recognizers.values.forEach { runCatching { it.close() } }
        recognizers.clear()
    }

    companion object {
        /**
         * ML Kit options per script. The class names are identical in the
         * bundled and the Play-services artifacts; only the artifact in
         * build.gradle decides where the model comes from.
         */
        fun defaultRecognizer(script: OcrScript): TextRecognizer = when (script) {
            OcrScript.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            OcrScript.CHINESE ->
                TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            OcrScript.DEVANAGARI ->
                TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
            OcrScript.JAPANESE ->
                TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            OcrScript.KOREAN ->
                TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        }
    }
}

/**
 * Maps an ML Kit `Text` result onto the pure model. Blocks are flattened in
 * reading order, exactly as ML Kit reports them
 * (`textBlocks` → `lines` → `elements`).
 */
internal fun Text.toOcrLines(): List<OcrLine> {
    val lines = ArrayList<OcrLine>(textBlocks.size * 4)
    textBlocks.forEach { block ->
        block.lines.forEach { line ->
            lines.add(
                OcrLine(
                    text = line.text,
                    box = line.boundingBox?.let { OcrBox(it.left, it.top, it.right, it.bottom) },
                    confidence = line.confidence?.toFloat(),
                    languageTag = line.recognizedLanguage,
                ),
            )
        }
    }
    return lines
}

/**
 * `TextRecognizer.process` returns a `Task`; awaiting it here with
 * `suspendCancellableCoroutine` avoids pulling in
 * `kotlinx-coroutines-play-services` just for one call.
 */
private suspend fun TextRecognizer.processSuspend(image: InputImage): Text =
    suspendCancellableCoroutine { continuation ->
        process(image)
            .addOnSuccessListener { text -> if (continuation.isActive) continuation.resume(text) }
            .addOnFailureListener { error ->
                if (continuation.isActive) continuation.resumeWithException(error)
            }
    }
