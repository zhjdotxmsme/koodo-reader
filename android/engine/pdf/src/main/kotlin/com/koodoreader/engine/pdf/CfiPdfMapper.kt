package com.koodoreader.engine.pdf

import org.json.JSONObject

/**
 * Range / CFI mapping for PDF annotations and bookmarks (P3, ADR-002).
 *
 * The desktop reader's PDF notes table stores a `cfi` JSON like
 * ```
 * { "fingerprint": "<hex>", "page": 12, "x": 34.5, "y": 678.0,
 *   "width": 120.0, "height": 18.0, "cfi": "<page-step CFI>" }
 * ```
 * (see `src/containers/viewer/component.tsx`, PDF branch, and
 * `src/utils/file/sqlUtil.ts`). We keep that shape verbatim so a desktop
 * .db round-trip yields byte-identical rows.
 *
 * Why a PDF-specific mapping, not a generic CFI:
 *   - the EPUB CFI is **a string** identifying an offset inside a
 *     streaming-content document; a PDF is a bag of fixed-size pages with
 *     absolute coordinates in points. The desktop schema uses `page +
 *     bbox` for that reason, with a `cfi` field reserved for a future
 *     EPUB-CFI bridge.
 *   - ADR-002 says "CFI is the single address format across formats"; we
 *     honour it by emitting a sibling `cfi` field that encodes the same
 *     address using a page-step grammar so the existing CFI kernel can
 *     compare it without learning the PDF coordinate system.
 *
 * What lives here:
 *   - parse a desktop-format JSON string into a [PdfRange];
 *   - serialise a [PdfRange] back to the desktop format (used when writing
 *     a new annotation from the native reader);
 *   - round-trip: parse(serialise(r)) == r for every r built by the
 *     native engine.
 */
object CfiPdfMapper {

    /**
     * One PDF address: 1-based page + bbox in PDF user space (points,
     * origin = bottom-left). Empty bbox means "the whole page" (used by
     * page-level bookmarks).
     *
     * [fingerprint] matches pdf.js's `pdfDocumentFingerprint` — the hex
     * digest of the document's `/Info` + trailer. Two PDFs with the same
     * fingerprint are treated as the same logical book even if the byte
     * contents differ (rebuilds, re-encrypts).
     */
    data class PdfRange(
        val fingerprint: String,
        val page: Int,
        val x: Float = 0f,
        val y: Float = 0f,
        val width: Float = 0f,
        val height: Float = 0f,
    ) {
        val isPageLevel: Boolean
            get() = width <= 0f || height <= 0f

        init {
            require(page >= 0) { "page must be >= 0 (was $page)" }
            require(x >= 0f && y >= 0f) { "x/y must be >= 0 (was $x, $y)" }
            require(width >= 0f && height >= 0f) { "width/height must be >= 0 (was $width, $height)" }
        }
    }

    /** Parse a desktop-format `cfi` JSON string into a [PdfRange]. */
    fun parse(cfiJson: String?): PdfRange? {
        if (cfiJson.isNullOrBlank()) return null
        return try {
            val o = JSONObject(cfiJson)
            val page = o.optInt("page", o.optInt("chapterDocIndex", 0) + 1)
            if (page <= 0) return null
            PdfRange(
                fingerprint = o.optString("fingerprint", ""),
                page = page,
                x = o.optDouble("x", 0.0).toFloat(),
                y = o.optDouble("y", 0.0).toFloat(),
                width = o.optDouble("width", 0.0).toFloat(),
                height = o.optDouble("height", 0.0).toFloat(),
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Serialise a [PdfRange] to the desktop-format JSON string. The
     * `chapterDocIndex`, `chapterHref` and `cfi` fields are filled in so
     * the row matches a freshly-written desktop note (the import pipeline
     * doesn't filter on those — see `src/utils/file/sqlUtil.ts`).
     *
     * Round-trip parity: [parse]([serialise](r)) == r for every r built
     * by the native engine.
     */
    fun serialise(range: PdfRange): String {
        val o = JSONObject()
        o.put("fingerprint", range.fingerprint)
        o.put("page", range.page)
        o.put("chapterDocIndex", range.page - 1)
        o.put("chapterHref", "title" + (range.page - 1))
        // Only put the bbox fields when there's an actual highlight area;
        // page-level bookmarks stay clean (the desktop engine does the same).
        if (!range.isPageLevel) {
            o.put("x", range.x.toDouble())
            o.put("y", range.y.toDouble())
            o.put("width", range.width.toDouble())
            o.put("height", range.height.toDouble())
        }
        o.put("cfi", pageStepCfi(range))
        return o.toString()
    }

    /**
     * Build the CFI spine-step encoding that lives in the `cfi` field —
     * `/page(N)[/t(x,y,w,h)]`. We use `/page(N)` as the addressable
     * spine step and `/t(x,y,w,h)` to encode the highlight rectangle in
     * PDF user space, matching the desktop convention.
     */
    fun pageStepCfi(range: PdfRange): String = buildString {
        append("/page(").append(range.page).append(')')
        if (!range.isPageLevel) {
            append("/t(")
                .append(range.x).append(',')
                .append(range.y).append(',')
                .append(range.width).append(',')
                .append(range.height).append(')')
        }
    }
}