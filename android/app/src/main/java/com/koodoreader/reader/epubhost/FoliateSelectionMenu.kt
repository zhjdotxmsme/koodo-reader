package com.koodoreader.reader.epubhost

import androidx.compose.ui.geometry.Offset
import org.json.JSONObject

/**
 * When the foliate WebView reports a non-blank excerpt, NativeEpubScreen
 * shows the same highlight / dictionary / translate / note chrome as Canvas.
 */
object FoliateSelectionMenu {

    fun visible(excerpt: String?): Boolean = !excerpt.isNullOrBlank()

    fun highlighted(cfi: String, noteCfis: List<String>): Boolean =
        cfi.isNotBlank() && noteCfis.any { it == cfi }

    /**
     * The WebView-local press point (CSS px, origin at the WebView's top-left)
     * carried by the `selection` payload, or null when the event was not
     * gesture-originated (e.g. a `selectionchange` replay without a point) —
     * the host then reuses its previous anchor.
     */
    fun payloadPoint(payload: JSONObject): Offset? {
        if (!payload.has("px") || !payload.has("py")) return null
        val px = payload.optDouble("px", Double.NaN)
        val py = payload.optDouble("py", Double.NaN)
        if (px.isNaN() || py.isNaN()) return null
        return Offset(px.toFloat(), py.toFloat())
    }

    /** WebView CSS px → root-box px via the WebView's current bounds. */
    fun anchorPx(point: Offset, boundsLeft: Float, boundsTop: Float, density: Float): Offset =
        Offset(boundsLeft + point.x * density, boundsTop + point.y * density)
}
