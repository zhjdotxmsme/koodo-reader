package com.koodoreader.engine.pdf

import org.json.JSONArray
import org.json.JSONObject

/**
 * Outline resolver for native PDF reading (P3).
 *
 * Source: pdf.js's [getOutline()](https://mozilla.github.io/pdf.js/api/draft/
 * .pdf.PDFDocumentProxy.html) emits a tree of:
 * ```
 * {
 *   title: String,
 *   bold: Boolean,
 *   italic: Boolean,
 *   color: [Number],     // RGB
 *   dest: Array | String | null,  // legacy named-dest passthrough
 *   url: String,
 *   items: Array<OutlineItem>,
 * }
 * ```
 * The native reader needs two flat things: a recursive [OutlineEntry] tree
 * for the sidebar, and an O(1) `page -> nearest outline entry` map so the
 * chapter label updates as the user scrolls.
 *
 * Why not just walk pdf.js's tree verbatim? Two reasons:
 *   1. pdf.js leaves `dest` as an Array of the form `[pageRef, "Json" /]
 *      "XYZ", x, y, zoom]` — a raw [pageRef] is a [PdfRef] object, not a
 *      page index. Resolving it requires the loaded document proxy, which
 *      lives in the WebView, so this resolver takes the **already-resolved**
 *      `pageNumber` (1-based) and the optional `rect` instead. The WebView
 *      bridge is responsible for the resolve; this resolver just consumes.
 *   2. We want to keep the JSON shape neutral: the WebView serializes the
 *      tree as `{title, pageNumber, rect?, children: [...]}` and the Kotlin
 *      side turns it into [OutlineEntry].
 *
 * ## Why `pageNumber == 0` is legal
 *
 * Some outlines point at bookmarks inside an attachment or a non-primary
 * document (the legacy /Dest may resolve to null). The WebView bridge
 * SHOULD drop them before sending; if it doesn't, [OutlineResolver.resolve]
 * keeps them but [pageNumber] is 0, so consumers can render them greyed
 * out or skip them in the nearest-entry lookup.
 */
object OutlineResolver {

    /**
     * Parse the WebView-side resolved outline JSON into a [Tree].
     *
     * @param json the WebView payload (see [resolveItem] for the schema)
     * @return a [Tree] with the root entries; an empty tree if `json` is
     *   null/blank (a PDF without an outline)
     */
    fun resolve(json: String?): Tree {
        if (json.isNullOrBlank()) return Tree(emptyList())
        return try {
            val root = JSONArray(json)
            resolveChildren(root)
        } catch (e: Exception) {
            // Malformed payload: degrade to no outline rather than crashing
            // the reader. The WebView side has its own logging.
            Tree(emptyList())
        }
    }

    private fun resolveChildren(arr: JSONArray): List<OutlineEntry> {
        val out = ArrayList<OutlineEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += resolveItem(o)
        }
        return out
    }

    private fun resolveItem(o: JSONObject): OutlineEntry {
        val title = o.optString("title", "").trim()
        val pageNumber = o.optInt("pageNumber", 0)
        val rect = o.optJSONObject("rect")?.let(::rectOf)
        val children = o.optJSONArray("children").let { children ->
            if (children == null || children.length() == 0) emptyList() else resolveChildren(children)
        }
        return OutlineEntry(
            title = title.ifEmpty { "(untitled)" },
            pageNumber = pageNumber,
            rect = rect,
            children = children,
        )
    }

    private fun rectOf(o: JSONObject): OutlineRect = OutlineRect(
        x = o.optDouble("x", 0.0).toFloat(),
        y = o.optDouble("y", 0.0).toFloat(),
        width = o.optDouble("width", 0.0).toFloat(),
        height = o.optDouble("height", 0.0).toFloat(),
    )

    /**
     * Flat lookup: given the currently visible page (1-based), return the
     * deepest outline entry whose [OutlineEntry.pageNumber] <= [page] (ties
     * broken by tree order). Used to label the progress panel.
     */
    fun nearestEntry(tree: Tree, page: Int): OutlineEntry? {
        // Pre-order DFS — outline entries are stored in the JSON in document
        // order, so pre-order visits them in display order too. The first
        // entry with `pageNumber <= page` (and > 0) is the right answer.
        var best: OutlineEntry? = null
        for (e in preorder(tree.entries)) {
            if (e.pageNumber in 1..page) best = e
            else if (e.pageNumber > page) break // rest is later in document
        }
        return best
    }

    private fun preorder(entries: List<OutlineEntry>): Sequence<OutlineEntry> = sequence {
        for (e in entries) {
            yield(e)
            yieldAll(preorder(e.children))
        }
    }

    /** An outline tree. [entries] is the root level. */
    data class Tree(val entries: List<OutlineEntry>) {
        val isEmpty: Boolean get() = entries.isEmpty()
        val totalCount: Int by lazy {
            var n = 0
            for (e in preorder(entries)) n++
            n
        }
    }

    /**
     * One outline entry. [pageNumber] is 1-based; 0 means "unresolved —
     * caller may have included a deprecated /Dest". [rect] is in the
     * unrotated page-coordinate system (PDF user space, origin = bottom-left,
     * units = points), only set when the WebView could resolve the zoom/XYZ
     * sub-array.
     */
    data class OutlineEntry(
        val title: String,
        val pageNumber: Int,
        val rect: OutlineRect?,
        val children: List<OutlineEntry>,
    )

    /** Page-relative rectangle in PDF user space (points). */
    data class OutlineRect(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
    )
}