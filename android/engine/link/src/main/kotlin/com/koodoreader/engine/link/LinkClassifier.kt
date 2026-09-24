package com.koodoreader.engine.link

import com.koodoreader.engine.cfi.isCfi
import com.koodoreader.engine.cfi.parseOrNull
import com.koodoreader.engine.cfi.unwrapCfi

/**
 * Classifies a raw `link-clicked` href into a [LinkTarget] (see [LinkKind]
 * for the taxonomy and the alignment notes vs [LinkRouter]).
 *
 * Behaviour that is FIXED (do not change without updating
 * `NativeEventDispatcher` + `nativeBridge.js` regressions):
 *
 *  - scheme matching is CASE-INSENSITIVE (`HTTP://` / `Mailto:` count as
 *    `http` / `mailto`) — a deliberate superset of the dispatcher's
 *    case-sensitive `startsWith`, because real-world hrefs come through the
 *    engine in mixed case and the dispatcher's system-browser branch
 *    (`Intent(ACTION_VIEW)`) accepts both;
 *  - the `footnote` field of the event is NOT handled here — that dispatch
 *    happens one level up, in [LinkRouter.routeEvent];
 *  - EPUBCFI bodies are VALIDATED with `:engine:cfi` (`parseOrNull`); a
 *    malformed body yields a [LinkTarget] with [LinkTarget.cfiValid] =
 *    `false` rather than an exception, so classification never throws.
 *
 * Accepts two EPUBCFI spellings, both seen in the wild and in this codebase:
 *  - the URI form `epubcfi:` with an optional version:
 *    `epubcfi:0?/6/4[chap01ref]!/4` (EPUB 3 internal link);
 *  - this codebase's wrapped form `epubcfi(/6/4[chap01ref]!/4)` (the
 *    `epubcfi(...)` wrapper that `engine/cfi`'s `isCfi`/`unwrapCfi` define).
 */
object LinkClassifier {

    /**
     * Pure function: same href → same [LinkTarget]. Never throws (an invalid
     * href classifies as [LinkKind.OTHER], an invalid EPUBCFI keeps
     * [LinkKind.EPUBCFI] but with [LinkTarget.cfiValid] = `false`).
     */
    fun classify(href: String): LinkTarget {
        val raw = href
        val h = href.trim()

        if (h.isEmpty()) return LinkTarget(raw, LinkKind.OTHER)

        // 1) in-page anchor (checked before schemes: `#` is never a scheme)
        if (h.startsWith("#")) {
            return LinkTarget(raw, LinkKind.ANCHOR, fragment = h.substring(1))
        }

        // 2) this codebase's wrapped CFI form: epubcfi(<inner>)
        if (isCfi(h)) {
            return epubCfiTarget(raw, unwrapCfi(h))
        }

        val scheme = schemeOf(h)

        // 3) EPUB URI form: epubcfi:[version?]<inner>
        if (scheme == "epubcfi") {
            val body = h.substringAfter(":", "")
            return epubCfiTarget(raw, stripEpubCfiVersion(body))
        }

        // 4) the dispatcher's "openable in the system browser" set
        return when (scheme) {
            "http" -> LinkTarget(raw, LinkKind.EXTERNAL_HTTP, uri = h)
            "https" -> LinkTarget(raw, LinkKind.EXTERNAL_HTTP, uri = h)

            "mailto" -> {
                val address = h.substringAfter(":", "").substringBefore("?").trim()
                LinkTarget(raw, LinkKind.MAILTO, uri = h, emailAddress = address)
            }

            // 5) storage / file URIs — whitelist-gated by the router
            "content", "file" -> LinkTarget(raw, LinkKind.SAF, uri = h)

            // 6) all other schemes + relative paths
            else -> LinkTarget(raw, LinkKind.OTHER)
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /**
     * Classify an EPUBCFI target, validating [inner] with `:engine:cfi`.
     * Keeps the [LinkKind.EPUBCFI] kind on validation failure so the router
     * (and tests) can tell "an epubcfi link that was broken" apart from
     * "not an epubcfi link at all".
     */
    private fun epubCfiTarget(raw: String, inner: String): LinkTarget {
        val body = inner.trim()
        // EPUB CFI syntax requires the path to start with "/" (e.g.
        // "/6/4[chap01ref]!/4/2"). `:engine:cfi` mirrors upstream epubcfi.js, whose
        // tokenizer tolerates a body without it and returns an empty point, so the
        // leading slash is checked here: a body like "2" must not become a
        // navigable InternalJump.
        if (body.isEmpty() || !body.startsWith("/")) {
            return LinkTarget(raw, LinkKind.EPUBCFI, cfiValid = false)
        }
        val parsed = parseOrNull(body) // CfiException → null, never throws
        return LinkTarget(raw, LinkKind.EPUBCFI, cfi = parsed, cfiValid = parsed != null)
    }

    /**
     * Strip the optional EPUB 3 version marker of an `epubcfi:` body:
     * a single optional digit followed by `?` (e.g. `0?`, `99?`), or a lone
     * leading `?`. Anything else is left untouched — CFI syntax has no top
     * level `?`, so a stray one will fail validation downstream.
     */
    private fun stripEpubCfiVersion(body: String): String = when {
        body.startsWith("?") -> body.substring(1)
        body.length >= 2 && body[0].isDigit() && body[1] == '?' -> body.substring(2)
        else -> body
    }

    /**
     * URI *scheme* of [h], lowercased, or `null`.
     *
     * A scheme is `ALPHA *( ALPHA / DIGIT / "+" / "-" / "." )` — this is what
     * tells `mailto:x` (scheme `mailto`) apart from a RELATIVE path that merely
     * contains a colon later on (`chap03.xhtml#c:1` → no scheme).
     */
    private fun schemeOf(h: String): String? {
        val colon = h.indexOf(':')
        if (colon <= 0) return null
        val s = h.substring(0, colon)
        if (!s.first().isLetter()) return null
        if (!s.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) return null
        return s.lowercase()
    }
}
