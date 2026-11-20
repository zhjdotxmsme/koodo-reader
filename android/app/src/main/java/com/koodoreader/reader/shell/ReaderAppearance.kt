package com.koodoreader.reader.shell

/**
 * Timing and scripting for live typography / theme updates in the WebView
 * readers.
 *
 * ## Why a debounce
 *
 * Every appearance change (font size, line height, margins, theme, custom CSS)
 * re-lays-out the whole book inside the WebView. A `Slider` emits dozens of
 * values while the finger is down, so applying each one means dozens of full
 * re-paginations per second: the text jitters, the scroll position jumps, and
 * on a long chapter the UI thread saturates.
 *
 * KOReader applies layout settings only when the panel is dismissed; Readest
 * pushes a single stylesheet per change. Both amount to the same rule:
 * **coalesce, then apply once.**
 *
 * `LaunchedEffect(key = settings)` gives this for free — a new value cancels
 * the pending coroutine before it wakes, so only the last value of a drag is
 * ever applied. The delay is what makes the coalescing happen in time.
 *
 * 120ms is below the ~150ms threshold where a "live" preview starts to feel
 * laggy, and well above a slider's per-frame interval (~16ms), so a drag
 * collapses to exactly one application.
 */
object ReaderAppearance {

    /** Trailing-edge delay before pushing appearance to a WebView. */
    const val SETTLE_MS = 120L

    /** Id of the injected `<style>` element (WebView readers). */
    const val STYLE_ELEMENT_ID = "koodo-reader-typography"

    /**
     * JavaScript that swaps the single typography stylesheet **and preserves
     * the reader's place**.
     *
     * Scroll is restored by *ratio*, not by pixel offset: after a font-size
     * change the same pixel offset points at a different sentence, so a pixel
     * restore would silently move the reader. The ratio keeps the paragraph the
     * reader was looking at at the top of the viewport.
     *
     * The stylesheet is swapped rather than reloaded, so the DOM (and therefore
     * highlights, footnotes and images) survives — the same reason Readest keeps
     * one `<style>` element and rewrites its contents.
     */
    fun swapStyleJs(css: String, elementId: String = STYLE_ELEMENT_ID): String {
        val id = jsString(elementId)
        val style = jsString(css)
        return "(function(){" +
            "var d=document;var se=d.scrollingElement||d.documentElement;" +
            "var maxY=(se.scrollHeight-se.clientHeight);" +
            "var ratio=maxY>0?(se.scrollTop||d.body.scrollTop||0)/maxY:0;" +
            "var old=d.getElementById($id);if(old)old.remove();" +
            "var s=d.createElement('style');s.id=$id;s.textContent=$style;" +
            "(d.head||d.documentElement).appendChild(s);" +
            "var maxY2=(se.scrollHeight-se.clientHeight);" +
            "if(maxY2>0){se.scrollTop=ratio*maxY2;}" +
            "})();"
    }

    /**
     * Quotes a string as a JavaScript literal.
     *
     * Hand-rolled instead of `org.json.JSONObject.quote` on purpose: this file
     * is exercised by plain JVM unit tests, where `org.json` is an unimplemented
     * Android stub and every call throws.
     *
     * `\u2028` / `\u2029` are escaped because they terminate a line in
     * JavaScript before ES2019 and a stylesheet legitimately contains Chinese
     * punctuation next to them.
     */
    fun jsString(value: String): String {
        val sb = StringBuilder(value.length + 16)
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\u2028' -> sb.append("\\u2028")
                '\u2029' -> sb.append("\\u2029")
                else -> sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
