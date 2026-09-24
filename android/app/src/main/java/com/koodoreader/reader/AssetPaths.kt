package com.koodoreader.reader

/**
 * Pure path helpers for [LocalAssetServer].
 *
 * Extracted from the server so `:app`'s JVM unit tests can pin them without an
 * `AssetManager`/`Context` (the server itself is Android-only).
 *
 * Why the multi-root part exists: the APK ships two independent asset trees —
 * `assets/webapp` (the React island) and `assets/pdfengine` (the pdf.js host for
 * the native PDF reader, P3). A server rooted at `webapp` used to 404 every
 * `/pdfengine/...` request, so the native reader could never load its engine.
 */
object AssetPaths {

    const val INDEX_FILE = "index.html"

    /** Asset roots served, in order. First match wins. */
    val DEFAULT_ROOTS: List<String> = listOf("webapp", "pdfengine")

    /**
     * Strip the query string, normalise separators and drop traversal segments.
     * An empty result (i.e. `/`) maps to [INDEX_FILE].
     */
    fun normalize(raw: String): String {
        val withoutQuery = raw.substringBefore('?')
        val parts = withoutQuery
            .replace('\\', '/')
            .split('/')
            .filter { it.isNotEmpty() && it != "." && it != ".." }
        if (parts.isEmpty()) return INDEX_FILE
        return parts.joinToString("/")
    }

    /**
     * Asset-manager paths to try for a normalised request [path], in order.
     * Roots are relative to the APK `assets/` directory; a blank root means the
     * request path is used as-is.
     */
    fun candidates(path: String, roots: List<String> = DEFAULT_ROOTS): List<String> =
        roots.map { root -> if (root.isEmpty()) path else "$root/$path" }

    /**
     * Whether [path] may fall back to [INDEX_FILE]: a client-side route has no
     * file extension in its last segment. A request for a missing *file* must
     * 404 instead of silently returning the SPA shell.
     */
    fun isClientRoute(path: String): Boolean = !path.substringAfterLast('/', "").contains('.')
}
