package com.koodoreader.engine.pdf

import org.json.JSONObject

/**
 * Persistent PDF annotation model (P3).
 *
 * Mirrors the desktop `notes` row (`src/utils/file/sqlUtil.ts`,
 * `BookHelper.handleRenderHighlighters`) column-for-column so a desktop
 * backup round-trips:
 *   - `bookKey`       matches `:core:importer` `BookRecord.key`
 *   - `cfi`           the JSON serialised by [CfiPdfMapper]
 *   - `text`          the highlighted substring ("" for bookmarks)
 *   - `note`          the user's note body ("" for highlights)
 *   - `color`         "#RRGGBB" or "#RRGGBBAA"
 *   - `style`         one of the desktop kookit highlight styles (see
 *                     [Style]). Keeping the desktop enum names verbatim
 *                     so saved values migrate without a mapping table.
 *
 * Persistence is a host concern: this module ships the data type, the
 * serialiser and the deserialiser; the `:core:data` room layer wires the
 * DAO.
 */
data class PdfAnnotation(
    val bookKey: String,
    val range: CfiPdfMapper.PdfRange,
    val text: String,
    val note: String,
    val color: String,
    val style: Style,
    val chapterIndex: Int = range.page - 1,
) {
    enum class Style(val desktopValue: String) {
        HIGHLIGHT("highlight"),
        UNDERLINE("underline"),
        SQUIGGLY("squiggly"),
        STRIKEOUT("strikeout"),
        BOOKMARK("bookmark"),
        ;

        companion object {
            fun fromDesktop(raw: String?): Style =
                entries.firstOrNull { it.desktopValue.equals(raw, ignoreCase = true) } ?: HIGHLIGHT
        }
    }

    /** Desktop-format JSON for the `cfi` column. */
    fun toCfiJson(): String = CfiPdfMapper.serialise(range)

    companion object {
        /**
         * Build a [PdfAnnotation] from a desktop-style `notes` row.
         *
         * @param row `Map<String, Any?>` with at least `bookKey`, `cfi`,
         *   plus optional `text`, `note`, `color`, `style`. Missing fields
         *   degrade to defaults; an unparseable `cfi` returns null.
         */
        fun fromRow(row: Map<String, Any?>): PdfAnnotation? {
            val key = row["bookKey"] as? String ?: return null
            val cfi = row["cfi"]?.toString() ?: return null
            val range = CfiPdfMapper.parse(cfi) ?: return null
            return PdfAnnotation(
                bookKey = key,
                range = range,
                text = (row["text"] as? String).orEmpty(),
                note = (row["note"] as? String).orEmpty(),
                color = (row["color"] as? String)?.takeIf { it.isNotEmpty() } ?: DEFAULT_COLOR,
                style = Style.fromDesktop(row["style"] as? String),
                chapterIndex = (row["chapterIndex"] as? Number)?.toInt() ?: (range.page - 1),
            )
        }

        /**
         * Companion for callers that have the `cfi` JSON string and the
         * rest as raw values. Useful for tests where the row shape is
         * controlled.
         */
        fun build(
            bookKey: String,
            cfiJson: String,
            text: String = "",
            note: String = "",
            color: String = DEFAULT_COLOR,
            style: Style = Style.HIGHLIGHT,
        ): PdfAnnotation? {
            val range = CfiPdfMapper.parse(cfiJson) ?: return null
            return PdfAnnotation(
                bookKey = bookKey,
                range = range,
                text = text,
                note = note,
                color = color,
                style = style,
            )
        }

        const val DEFAULT_COLOR = "#FFFF00"
    }
}

/**
 * Container payload shape for the WebView side — pdf.js's annotation layer
 * is a tree of `Annotation` objects; we only consume the rendering subset
 * (rect + colour) and ignore the rest. Keeping this separate from
 * [PdfAnnotation] (the persistent user-side annotation) is deliberate —
 * they overlap in coordinates and that's where the confusion can end.
 */
object PdfLayerAnnotation {
    fun parseRects(json: String?): List<CfiPdfMapper.PdfRange> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            val out = ArrayList<CfiPdfMapper.PdfRange>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val page = o.optInt("page", -1).takeIf { it > 0 } ?: continue
                val rect = o.optJSONObject("rect") ?: continue
                out += CfiPdfMapper.PdfRange(
                    fingerprint = o.optString("fingerprint", ""),
                    page = page,
                    x = rect.optDouble("x", 0.0).toFloat(),
                    y = rect.optDouble("y", 0.0).toFloat(),
                    width = rect.optDouble("width", 0.0).toFloat(),
                    height = rect.optDouble("height", 0.0).toFloat(),
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Pack a list of [CfiPdfMapper.PdfRange] into the JSON the WebView
     * consumes when re-painting the highlight layer.
     */
    fun serialiseRects(ranges: List<CfiPdfMapper.PdfRange>): String {
        val arr = org.json.JSONArray()
        for (r in ranges) {
            val rect = JSONObject()
                .put("x", r.x.toDouble())
                .put("y", r.y.toDouble())
                .put("width", r.width.toDouble())
                .put("height", r.height.toDouble())
            arr.put(
                JSONObject()
                    .put("page", r.page)
                    .put("fingerprint", r.fingerprint)
                    .put("rect", rect)
            )
        }
        return arr.toString()
    }
}