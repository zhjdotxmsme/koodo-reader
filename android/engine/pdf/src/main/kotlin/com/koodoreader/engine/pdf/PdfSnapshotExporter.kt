package com.koodoreader.engine.pdf

/**
 * PDF snapshot / print contract (P3).
 *
 * P0 POC selected pdf.js as the in-app renderer; PdfRenderer (the
 * framework `android.graphics.pdf.PdfRenderer`) is reserved for the
 * "export current page as image / print" bypass because it has zero
 * dependency cost and renders faster than pdf.js for a one-shot raster
 * (docs/android-pdf-poc.md §3, R4 caveat).
 *
 * Why this is a contract (interface) and not an implementation:
 *   - framework PdfRenderer lives in `android.graphics.pdf.*` — not
 *     available in a pure JVM module;
 *   - the JVM tests exercise the contract with a fake so the algorithm
 *     (page-fit dimensions, output-format selection) is testable without
 *     the Android SDK;
 *   - the Android host module wires a `PdfRenderer`-backed implementation
 *     at runtime (see `:app/src/main/java/com/koodoreader/reader/
 *     PdfRendererSnapshot.kt` — companion to [PdfCoverExtractor]).
 *
 * Consumers: the native reader's "share current page" / "print" buttons.
 */
interface PdfSnapshotExporter {

    /**
     * Render [pageNumber] (1-based) of [pdfPath] to a PNG/JPEG byte stream.
     *
     * @param pdfPath absolute path of the PDF on the device filesystem.
     * @param pageNumber 1-based; must be in `1..pageCount`.
     * @param spec how big / which format to render at.
     * @return the encoded bytes (PNG or JPEG per [Spec.format]) plus a
     *   [Meta] record describing what was actually emitted.
     */
    fun export(pdfPath: String, pageNumber: Int, spec: Spec): Result

    data class Spec(
        /** Target width in pixels (clamped to [MIN_WIDTH]..[MAX_WIDTH]). */
        val targetWidthPx: Int = DEFAULT_WIDTH_PX,
        /** Output format. */
        val format: Format = Format.PNG,
        /** JPEG quality 0..100 (ignored for PNG). */
        val jpegQuality: Int = 90,
        /** Whether to keep the original page background (true) or force white. */
        val keepBackground: Boolean = true,
    ) {
        init {
            require(targetWidthPx in MIN_WIDTH..MAX_WIDTH) { "targetWidthPx out of range (was $targetWidthPx)" }
            require(jpegQuality in 1..100) { "jpegQuality must be 1..100 (was $jpegQuality)" }
        }

        enum class Format { PNG, JPEG }

        companion object {
            const val MIN_WIDTH = 64
            const val MAX_WIDTH = 4096
            const val DEFAULT_WIDTH_PX = 1024
        }
    }

    data class Meta(
        val pageWidthPx: Int,
        val pageHeightPx: Int,
        val format: Spec.Format,
        val bytes: Int,
    )

    data class Result(
        val data: ByteArray,
        val meta: Meta,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Result) return false
            return data.contentEquals(other.data) && meta == other.meta
        }
        override fun hashCode(): Int = data.contentHashCode() * 31 + meta.hashCode()
    }

    companion object {
        /**
         * Compute the snapshot target size given a PDF's natural page size.
         *
         * The algorithm: scale the page so its longest side is exactly
         * [Spec.targetWidthPx], never enlarge (PDFs are vectorial but a
         * snapshot for sharing should not bloat the file).
         *
         * The unit tests pin this so a future PdfRenderer re-implementation
         * has to match the bytes-to-bytes output dimensions.
         */
        fun computeSize(naturalWidthPt: Float, naturalHeightPt: Float, spec: Spec): Pair<Int, Int> {
            val longest = maxOf(naturalWidthPt, naturalHeightPt)
            if (longest <= 0f) return 1 to 1
            val scale = spec.targetWidthPx.toFloat() / longest
            val w = (naturalWidthPt * scale).toInt().coerceAtLeast(1)
            val h = (naturalHeightPt * scale).toInt().coerceAtLeast(1)
            return w to h
        }

        /** MIME type for the [Spec.Format]. */
        fun mimeType(format: Spec.Format): String = when (format) {
            Spec.Format.PNG -> "image/png"
            Spec.Format.JPEG -> "image/jpeg"
        }

        /** File extension for the [Spec.Format] (without the dot). */
        fun extension(format: Spec.Format): String = when (format) {
            Spec.Format.PNG -> "png"
            Spec.Format.JPEG -> "jpg"
        }
    }
}