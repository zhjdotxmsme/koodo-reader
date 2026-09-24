package com.koodoreader.engine.pdf

/**
 * PDF text search (P3).
 *
 * Source: pdf.js's `PDFFindController` walks the document's text layer and
 * yields matches of the form:
 * ```
 * {
 *   pageNumber: 1-based,
 *   rects:      [ { x, y, width, height }, ... ]   // PDF user-space, points
 *   text:       matched substring (for snippet display),
 * }
 * ```
 * The WebView side serialises that into the JSON this class consumes.
 *
 * What this engine adds on top:
 *   - **Snippets**: the surrounding context (configurable; default 32 chars)
 *     so the result list shows "…word…word…match…word…" — desktop parity
 *     (`kookit searchBox`).
 *   - **Page-only / All pages**: forward-only, no reverse — desktop parity
 *     (the desktop PDF reader does forward-only too, since pdf.js's
 *     `findNext` is what `kookit` calls). Reverse navigation is a P6
 *     enhancement.
 *   - **Hit bounding-box normalisation**: every `rect` is sorted by (y, x)
 *     and clamped to non-negative dimensions, so a downstream
 *     "render highlight on the text layer" compose function can draw without
 *     a defensive pass.
 *
 * The algorithm is intentionally tiny (a single linear scan with a
 * case-insensitive substring test) because pdf.js already does the heavy
 * lifting in its worker; this class just wraps the JSON. When a future
 * iteration needs regex / fuzzy / accent-insensitive, replace [matches]
 * only.
 */
class PdfSearchEngine {

    /**
     * Normalised search result.
     *
     * @property pageNumber 1-based; matches the `pageNumber` returned by
     *   the WebView bridge (== `pdf.js pageNumber`).
     * @property rects the highlighted rectangles in PDF user space (points,
     *   origin = bottom-left). Always non-empty for a match.
     * @property snippet the surrounding text, with `…` markers; <= 200 chars
     *   by default.
     */
    data class Hit(
        val pageNumber: Int,
        val rects: List<Rect>,
        val snippet: String,
    )

    /**
     * @param json the WebView payload — see [parsePageHits].
     * @param query the user's search string (used for snippet context
     *   trimming; an empty query yields zero hits).
     * @param snippetContext number of characters to keep on each side of
     *   the match in [Hit.snippet] (clamped to 0..200).
     * @return hits sorted by `(pageNumber, rectTop, rectLeft)`, the same
     *   order the user sees them on screen.
     */
    fun parseHits(
        json: String?,
        query: String,
        snippetContext: Int = 32,
    ): List<Hit> {
        if (json.isNullOrBlank() || query.isEmpty()) return emptyList()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val ctx = snippetContext.coerceIn(0, 200)
        return try {
            parsePageHits(json).flatMap { page ->
                page.hits.map { hit ->
                    Hit(
                        pageNumber = page.pageNumber,
                        rects = normaliseRects(hit.rects),
                        snippet = snippetAround(trimmed, hit.text, ctx),
                    )
                }
            }.sortedWith(
                compareBy(
                    { it.pageNumber },
                    { it.rects.firstOrNull()?.y ?: 0f },
                    { it.rects.firstOrNull()?.x ?: 0f },
                ),
            )
        } catch (e: Exception) {
            // Malformed payload: return zero hits (the WebView side logs).
            emptyList()
        }
    }

    /**
     * Navigate to the hit `[index]` within `[hits]`, wrapping at the ends.
     * Returns the new index; the caller then dispatches a scroll-to-(page,
     * rect) on the WebView. Pure: doesn't touch any WebView state.
     */
    fun cycleTo(hits: List<Hit>, index: Int, direction: Int): Int {
        if (hits.isEmpty()) return -1
        val size = hits.size
        var i = index
        repeat(size) {
            i = ((i + direction) % size + size) % size
            return i
        }
        return index.coerceIn(0, size - 1)
    }

    // ---- internals ----

    internal data class PageHits(
        val pageNumber: Int,
        val hits: List<RawHit>,
    )

    internal data class RawHit(
        val rects: List<Rect>,
        val text: String,
    )

    /** Rectangle in PDF user space (points). Origin = bottom-left. */
    data class Rect(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
    )

    private fun parsePageHits(json: String): List<PageHits> {
        // The WebView sends an array of per-page hit lists; each hit is
        // { rects: [...], text: "..." }. We tolerate the legacy shape
        // { page, hits } by trying both — the WebView side logs drift.
        val arr = org.json.JSONArray(json)
        val out = ArrayList<PageHits>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val page = o.optInt("pageNumber", -1).takeIf { it > 0 }
                ?: o.optInt("page", -1).takeIf { it > 0 }
                ?: continue
            val hits = o.optJSONArray("hits") ?: continue
            val list = ArrayList<RawHit>(hits.length())
            for (j in 0 until hits.length()) {
                val h = hits.optJSONObject(j) ?: continue
                val rects = h.optJSONArray("rects")?.let(::parseRects) ?: emptyList()
                if (rects.isEmpty()) continue
                list += RawHit(rects = rects, text = h.optString("text", ""))
            }
            if (list.isNotEmpty()) out += PageHits(page, list)
        }
        return out
    }

    private fun parseRects(arr: org.json.JSONArray): List<Rect> {
        val out = ArrayList<Rect>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val r = Rect(
                x = o.optDouble("x", 0.0).toFloat(),
                y = o.optDouble("y", 0.0).toFloat(),
                width = o.optDouble("width", 0.0).toFloat(),
                height = o.optDouble("height", 0.0).toFloat(),
            )
            if (r.width > 0f && r.height > 0f) out += r
        }
        return out
    }

    private fun normaliseRects(rects: List<Rect>): List<Rect> =
        rects.sortedWith(compareBy({ it.y }, { it.x }))

    private fun snippetAround(query: String, hitText: String, ctx: Int): String {
        val hay = hitText.ifEmpty { return "" }
        val needle = query
        val idx = hay.lowercase().indexOf(needle.lowercase())
        if (idx < 0 || hay.isEmpty()) {
            // No exact match in the snippet text (pdf.js may split the
            // match across runs): just trim.
            return hay.take(ctx * 2)
        }
        val start = (idx - ctx).coerceAtLeast(0)
        val end = (idx + needle.length + ctx).coerceAtMost(hay.length)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < hay.length) "…" else ""
        return prefix + hay.substring(start, end) + suffix
    }
}