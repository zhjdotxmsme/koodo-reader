package com.koodoreader.feature.dictionary

import com.koodoreader.feature.dictionary.mdx.MdictHeaderParser

/**
 * Turns a raw MDX definition into HTML the Android reader can show in the popup.
 *
 * REFERENCE BEHAVIOUR — the desktop hands the raw definition to the popup and then
 * re-runs `audio.audio-player` loading on the rendered nodes
 * (src/components/popups/popupDict/component.tsx:224-242), i.e. definitions are
 * trusted HTML fragments that may reference `.mdd` resources (`<img>`, `<audio>`,
 * `style.css`). This renderer keeps that shape but adds the three things a native
 * client needs and a browser got for free:
 *
 *  1. **`@@@LINK=` redirects** — an entry whose body is `@@@LINK=otherword` is an
 *     alias; resolve it (bounded depth) instead of showing the raw marker.
 *  2. **Resource rewriting** — `src=".../foo.png"` cannot be loaded by a native
 *     view, so container references are rewritten to `dict-res://<dictId>/<key>`
 *     and served from [MddParser] by the app (see `MddResourceScheme`).
 *  3. **Sanitising** — dictionary files are untrusted input; `<script>`, inline
 *     event handlers and `javascript:` URLs are removed.
 *
 * Also applies the header `StyleSheet` markers (`` `1` `` → begin/end tags) exactly
 * like `utils.js:333 substituteStylesheet`.
 */
object HtmlDefinitionRenderer {

    /** `dict-res://` — the virtual scheme the Android layer resolves from the `.mdd`. */
    const val RESOURCE_SCHEME = "dict-res"

    /** A definition longer than this is truncated before hitting the UI. */
    const val DEFAULT_MAX_LENGTH = 1_000_000

    /** Cycle guard for `@@@LINK=` chains. */
    const val MAX_LINK_DEPTH = 8

    data class Options(
        /** Dictionary id, used to build `dict-res://` URLs. */
        val dictId: String = "",
        /** Rewrite container-relative resource URLs to [RESOURCE_SCHEME] URLs. */
        val rewriteResources: Boolean = true,
        /** Keep `<style>` blocks (TextView ignores most of them anyway). */
        val keepStyle: Boolean = true,
        /** Wrap the fragment so the popup can theme it. */
        val wrap: Boolean = true,
        val maxLength: Int = DEFAULT_MAX_LENGTH,
    )

    /**
     * @param rawDefinition the HTML body stored in the `.mdx` record.
     * @param styleSheet the dictionary header stylesheet (`MdictHeader.styleSheet`).
     * @param lookup resolves `@@@LINK=` targets; return `null` to stop the chain.
     */
    fun render(
        rawDefinition: String,
        styleSheet: Map<String, List<String>> = emptyMap(),
        options: Options = Options(),
        lookup: ((String) -> String?)? = null,
    ): String {
        if (rawDefinition.isEmpty()) return ""
        val resolved = resolveLinks(rawDefinition, lookup)
        val styled = MdictHeaderParser.substituteStyleSheet(styleSheet, resolved)
        var html = sanitize(styled, options)
        if (options.rewriteResources) html = rewriteResourceUrls(html, options.dictId)
        if (html.length > options.maxLength) html = html.substring(0, options.maxLength)
        return if (options.wrap) wrap(html) else html
    }

    /** MDict stores `@@@LINK=word` (sometimes `@@@LINK= word`) as an alias body. */
    private const val LINK_PREFIX = "@@@LINK="

    fun resolveLinks(body: String, lookup: ((String) -> String?)?): String {
        var current = body
        var depth = 0
        while (depth < MAX_LINK_DEPTH) {
            val trimmed = current.trim()
            if (!trimmed.startsWith(LINK_PREFIX)) return current
            val target = trimmed.removePrefix(LINK_PREFIX).trim()
            if (target.isEmpty() || lookup == null) return current
            val next = lookup(target) ?: return current
            if (next.trim() == trimmed) return current
            current = next
            depth++
        }
        return current
    }

    /** Remove active content. Dictionary files come from the internet; treat as hostile. */
    fun sanitize(html: String, options: Options = Options()): String {
        var out = html
        out = REMOVE_SCRIPT.replace(out, "")
        if (!options.keepStyle) out = REMOVE_STYLE.replace(out, "")
        out = REMOVE_IFRAME.replace(out, "")
        out = REMOVE_OBJECT.replace(out, "")
        // `onclick="…"`, `onerror=…` and friends.
        out = REMOVE_EVENT_HANDLER.replace(out, "")
        out = REMOVE_JS_URL.replace(out, "")
        return out
    }

    /**
     * `src="logo.png"`, `src="/img/logo.png"`, `src="file:///img/logo.png"` all
     * address the `.mdd` container. Absolute `http(s)://`, `data:` and already
     * rewritten `dict-res://` URLs are left alone.
     */
    fun rewriteResourceUrls(html: String, dictId: String): String {
        if (dictId.isEmpty()) return html
        return RESOURCE_ATTR.replace(html) { match ->
            val attr = match.groupValues[1]
            val quote = match.groupValues[2]
            val url = match.groupValues[3]
            if (!isContainerUrl(url)) {
                match.value
            } else {
                "$attr=$quote${resourceUrl(dictId, url)}$quote"
            }
        }
    }

    /** Build the virtual URL the app resolves through `MddParser.locateFlexible`. */
    fun resourceUrl(dictId: String, reference: String): String {
        val key = reference
            .substringBefore('#')
            .substringBefore('?')
            .replace("%20", " ")
            .replace('/', '\\')
            .trimStart('\\')
        return "$RESOURCE_SCHEME://$dictId/" + key.replace(" ", "%20").replace("\\", "/")
    }

    /** The inverse of [resourceUrl] — what the app hands to `MddParser`. */
    fun resourceKeyFromUrl(url: String): Pair<String, String>? {
        if (!url.startsWith("$RESOURCE_SCHEME://")) return null
        val rest = url.removePrefix("$RESOURCE_SCHEME://")
        val slash = rest.indexOf('/')
        if (slash <= 0) return null
        val dictId = rest.substring(0, slash)
        val key = "\\" + rest.substring(slash + 1).replace("/", "\\").replace("%20", " ")
        return dictId to key
    }

    private fun isContainerUrl(url: String): Boolean {
        val lower = url.trim().lowercase()
        return !(
            lower.startsWith("http://") || lower.startsWith("https://") ||
                lower.startsWith("data:") || lower.startsWith("$RESOURCE_SCHEME://") ||
                lower.startsWith("#") || lower.startsWith("mailto:")
            )
    }

    /** Wrap so the popup can style/scroll one node (and so the length cap is visible). */
    fun wrap(html: String): String =
        "<div class=\"koodo-dict-entry\">$html</div>"

    /** Strip tags for clipboard / TTS: `<b>apple</b> n. 苹果` → `apple n. 苹果`. */
    fun toPlainText(html: String): String = html
        .let { REMOVE_SCRIPT.replace(it, "") }
        .let { REMOVE_STYLE.replace(it, "") }
        .let { BLOCK_TAGS.replace(it, "\n") }
        .let { TAG.replace(it, "") }
        .let { MdictHeaderParser.unescapeEntities(it) }
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\n{2,}"), "\n")
        .trim()

    private val REMOVE_SCRIPT = Regex("<script\\b[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE)
    private val REMOVE_STYLE = Regex("<style\\b[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE)
    private val REMOVE_IFRAME = Regex("<iframe\\b[^>]*>[\\s\\S]*?</iframe>", RegexOption.IGNORE_CASE)
    private val REMOVE_OBJECT = Regex("<object\\b[^>]*>[\\s\\S]*?</object>", RegexOption.IGNORE_CASE)
    private val REMOVE_EVENT_HANDLER = Regex("\\son[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)", RegexOption.IGNORE_CASE)
    private val REMOVE_JS_URL = Regex("(href|src)\\s*=\\s*([\"'])\\s*javascript:[^\"']*\\2", RegexOption.IGNORE_CASE)
    private val RESOURCE_ATTR = Regex("\\b(src|href|data-src)\\s*=\\s*([\"'])([^\"']*)\\2", RegexOption.IGNORE_CASE)
    private val BLOCK_TAGS = Regex("</?(div|p|br|li|tr|h[1-6])\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val TAG = Regex("<[^>]+>")
}
