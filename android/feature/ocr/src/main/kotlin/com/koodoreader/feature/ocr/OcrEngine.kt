// feature/ocr — pure-Kotlin OCR core (P6).
//
// Desktop reference: `public/lib/esearch-ocr` + `onnxruntime-web` (web) and the
// native Windows/macOS OCR paths in `src/utils/main/ocr-util.js`, wired through
// `getOcrResult` / `parseWithSystemOCR` in `src/utils/request/*`. The native
// Android port uses ML Kit's on-device Text Recognition v2 (`text-recognition`
// API surface, see platform/MlKitOcrProvider.kt) with the ON-DEMAND model pack
// instead of shipping the 4 MB-per-script-per-ABI bundled model + .so files
// (16 KB page-size risk, see docs/p6-stats-ocr-design.md §5).
//
// This file has zero Android imports: script→model mapping, the OCR text
// normaliser (desktop `cleanWindowsOcrText` parity for CJK) and the engine
// seam are all unit-testable on the JVM.
package com.koodoreader.feature.ocr

/**
 * ML Kit text recognition scripts. `manifestValue` is the manifest meta-data
 * token (`com.google.mlkit.vision.DEPENDENCIES`) that requests the install-time
 * download; the artifact coordinates are the ones from the ML Kit text
 * recognition v2 guide (unbundled = model downloaded through Google Play
 * services, bundled = model linked into the APK).
 */
enum class OcrScript(
    val manifestValue: String,
    val unbundledArtifact: String,
    val bundledArtifact: String,
    /** ML Kit guide: ~260 KB per script per architecture, unbundled. */
    val unbundledBytes: Long = 260L * 1024L,
    /** ML Kit guide: ~4 MB per script per architecture, bundled. */
    val bundledBytes: Long = 4L * 1024L * 1024L,
) {
    LATIN(
        manifestValue = "ocr",
        unbundledArtifact = "com.google.android.gms:play-services-mlkit-text-recognition:19.0.1",
        bundledArtifact = "com.google.mlkit:text-recognition:16.0.1",
    ),
    CHINESE(
        manifestValue = "ocr_chinese",
        unbundledArtifact = "com.google.android.gms:play-services-mlkit-text-recognition-chinese:16.0.1",
        bundledArtifact = "com.google.mlkit:text-recognition-chinese:16.0.1",
    ),
    DEVANAGARI(
        manifestValue = "ocr_devanagari",
        unbundledArtifact = "com.google.android.gms:play-services-mlkit-text-recognition-devanagari:16.0.1",
        bundledArtifact = "com.google.mlkit:text-recognition-devanagari:16.0.1",
    ),
    JAPANESE(
        manifestValue = "ocr_japanese",
        unbundledArtifact = "com.google.android.gms:play-services-mlkit-text-recognition-japanese:16.0.1",
        bundledArtifact = "com.google.mlkit:text-recognition-japanese:16.0.1",
    ),
    KOREAN(
        manifestValue = "ocr_korean",
        unbundledArtifact = "com.google.android.gms:play-services-mlkit-text-recognition-korean:16.0.1",
        bundledArtifact = "com.google.mlkit:text-recognition-korean:16.0.1",
    ),
    ;

    companion object {
        /**
         * Best script for an ISO language tag, mirroring the desktop engine's
         * language lists (`getOcrLangList` → tesseract/paddle codes): Chinese,
         * Japanese, Korean and Devanagari get their dedicated model, everything
         * else the Latin one.
         */
        fun forLanguageTag(tag: String?): OcrScript {
            val language = tag?.lowercase()?.replace('_', '-')?.substringBefore('-').orEmpty()
            return when (language) {
                "zh", "zh-cn", "zh-tw", "zh-hk", "zh-mo", "zh-hans", "zh-hant" -> CHINESE
                "ja" -> JAPANESE
                "ko" -> KOREAN
                "hi", "mr", "ne", "sa" -> DEVANAGARI
                else -> LATIN
            }
        }

        /** `android:value="ocr,ocr_chinese"` for the manifest meta-data. */
        fun manifestValue(scripts: Collection<OcrScript>): String =
            scripts.distinct().joinToString(",") { it.manifestValue }
    }
}

/** Opaque page image; the Android layer wraps a `Bitmap` (+ rotation). */
interface OcrImage

/** Bounding box in page pixels, `null` when the engine reports geometry only per block. */
data class OcrBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** One recognised line (ML Kit `Text.Line`). */
data class OcrLine(
    val text: String,
    val box: OcrBox? = null,
    val confidence: Float? = null,
    val languageTag: String? = null,
)

/**
 * A recognised scanned page. `lines` keeps the reading order reported by the
 * engine, so the normaliser can rebuild paragraphs without re-sorting.
 */
data class OcrPageResult(
    val bookKey: String,
    val pageIndex: Int,
    val script: OcrScript,
    val lines: List<OcrLine>,
    val durationMillis: Long = 0L,
) {
    val rawText: String get() = lines.joinToString("\n") { it.text }
}

/** One page to recognise. */
data class OcrRequest(
    val bookKey: String,
    val pageIndex: Int,
    val script: OcrScript,
)

/** Engine seam: `MlKitOcrProvider` is the only production implementation. */
interface OcrEngine {
    suspend fun recognize(request: OcrRequest, image: OcrImage): OcrPageResult
    fun close() {}
}

/**
 * Deshapes OCR output into indexable text.
 *
 * Desktop parity: `cleanWindowsOcrText` (src/utils/main/ocr-util.js) exists
 * because Windows.Media.Ocr joins CJK runs with spaces — the same fix is needed
 * for ML Kit output, which also breaks lines. Rules:
 *  1. a hyphen at a line end between two latin words is removed
 *     (`inter-` + `national` → `international`);
 *  2. a line break between two CJK characters becomes NOTHING, anywhere else it
 *     becomes a single space;
 *  3. runs of whitespace collapse to one space and the result is trimmed.
 */
object OcrTextNormalizer {

    private val hyphenBreak = Regex("([A-Za-z])-\\s*\\n\\s*([a-z])")
    private val whitespace = Regex("[ \\t\\u00A0]+")

    fun normalize(lines: List<String>): String = normalize(lines.joinToString("\n"))

    fun normalize(raw: String): String {
        if (raw.isEmpty()) return ""
        val dehyphenated = hyphenBreak.replace(raw) { match -> "${match.groupValues[1]}${match.groupValues[2]}" }
        val builder = StringBuilder(dehyphenated.length)
        var index = 0
        while (index < dehyphenated.length) {
            val current = dehyphenated[index]
            if (current == '\n' || current == '\r') {
                val previous = builder.lastOrNull()
                // Look ahead to the next non-newline character.
                var next = index + 1
                while (next < dehyphenated.length && (dehyphenated[next] == '\n' || dehyphenated[next] == '\r')) next++
                val following = dehyphenated.getOrNull(next)
                val joiner = if (previous != null && following != null &&
                    isCjk(previous) && isCjk(following)
                ) {
                    ""
                } else {
                    " "
                }
                if (builder.isNotEmpty() && builder.last() != ' ') builder.append(joiner)
                index = next
                continue
            }
            builder.append(current)
            index++
        }
        return whitespace.replace(builder.toString(), " ").trim()
    }

    /**
     * Index tokens: lowercased latin words plus CJK unigrams AND bigrams (the
     * classic n-gram trick that makes 1-2 character queries searchable without a
     * Chinese word segmenter).
     */
    fun tokens(normalizedText: String): List<String> {
        val tokens = ArrayList<String>()
        val latin = StringBuilder()
        fun flushLatin() {
            if (latin.isNotEmpty()) {
                tokens.add(latin.toString().lowercase())
                latin.setLength(0)
            }
        }
        var index = 0
        while (index < normalizedText.length) {
            val ch = normalizedText[index]
            when {
                isCjk(ch) -> {
                    flushLatin()
                    tokens.add(ch.toString())
                    val next = normalizedText.getOrNull(index + 1)
                    if (next != null && isCjk(next)) tokens.add("$ch$next")
                }
                ch.isLetterOrDigit() || ch == '\'' || ch == '\u2019' -> latin.append(ch)
                else -> flushLatin()
            }
            index++
        }
        flushLatin()
        return tokens
    }

    /** CJK ideographs (BMP), kana and hangul — same ranges as the stats module. */
    fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return code in 0x3040..0x30FF ||
            code in 0x3400..0x4DBF ||
            code in 0x4E00..0x9FFF ||
            code in 0xAC00..0xD7AF ||
            code in 0xF900..0xFAFF
    }
}
