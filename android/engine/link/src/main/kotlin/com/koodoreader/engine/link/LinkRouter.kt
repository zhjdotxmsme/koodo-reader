package com.koodoreader.engine.link

/**
 * Turns a classified [LinkTarget] (plus a [LinkPolicy]) into a [LinkAction] —
 * the "该由谁处理" (who-handles-it) decision — and the event-level entry
 * [routeEvent] which reproduces the `link-clicked` payload contract
 * EXACTLY as `NativeEventDispatcher.handleLinkClick` implements it:
 *
 * ```
 * // NativeEventDispatcher.handleLinkClick, distilled:
 * if (footnote.isNotBlank()) { showFootnoteDialog(footnote); return }
 * if (href startsWith "http://" | "https://" | "mailto:") { ACTION_VIEW; return }
 * // in-app / unknown scheme: nothing native to do
 * ```
 *
 * i.e. with [LinkPolicy.DEFAULT]:
 *  - non-blank [LinkClickEvent.footnote] → [LinkAction.Footnote] (WINS over href),
 *  - `http(s)` / `mailto` → [LinkAction.OpenBrowser],
 *  - every other kind → [LinkAction.None].
 *
 * The native track ADDS (never removes) capabilities on top of that:
 * valid [LinkKind.EPUBCFI] → [LinkAction.InternalJump], and whitelisted
 * [LinkKind.SAF] → [LinkAction.OpenBrowser] with [LinkAction.OpenBrowser.viaSafWhitelist]
 * — both new links the WebView track silently ignored, so no regressions.
 *
 * Pure decisions only: no Android imports, no side effects — the shell maps
 * the [LinkAction] to intents/CFI navigation/dialogs and toasts.
 */
class LinkRouter(
    val policy: LinkPolicy = LinkPolicy.DEFAULT,
) {

    /** Classify + route a raw href in one step (event-free convenience). */
    fun route(href: String): LinkAction = route(LinkClassifier.classify(href))

    /**
     * Event-level entry point — drop-in semantic equivalent of
     * `handleLinkClick(payload)` for the fields of `link-clicked`.
     *
     * The `footnote` field takes PRECEDENCE over the `href` field, exactly
     * like the dispatcher (its `if (footnote.isNotBlank())` runs first).
     */
    fun routeEvent(event: LinkClickEvent): LinkAction =
        routeEvent(event.href, event.footnote)

    /** Two-arg form (what the app layer passes after reading the two JSON fields). */
    fun routeEvent(href: String, footnote: String): LinkAction {
        if (footnote.isNotBlank()) {
            if (policy.allowFootnote) return LinkAction.Footnote(footnote)
            // Policy disabled footnote UI → fall through to the href. (The
            // dispatcher has no such flag; strict superset, same default.)
        }
        return route(href)
    }

    /** The core decision table. See [LinkAction] for who handles each. */
    fun route(target: LinkTarget): LinkAction = when (target.kind) {
        LinkKind.ANCHOR ->
            // In-page anchor: the page/reader positions itself; the dispatcher
            // likewise opens nothing native for same-document fragments.
            LinkAction.None

        LinkKind.EPUBCFI -> when {
            target.cfiValid != true -> LinkAction.None // invalid/absent CFI
            !policy.allowEpubCfiJump -> LinkAction.None
            else -> target.cfi?.let { LinkAction.InternalJump(it, target.href) }
                ?: LinkAction.None
        }

        LinkKind.EXTERNAL_HTTP -> {
            val isHttps = target.uri.startsWith("https://", ignoreCase = true)
            val allowed = if (isHttps) policy.allowHttps else policy.allowHttp
            if (allowed) externalOpen(target.uri) else LinkAction.None
        }

        LinkKind.MAILTO ->
            if (policy.allowMailto) externalOpen(target.uri) else LinkAction.None

        LinkKind.SAF -> {
            // Whitelist-gated: a book may only open the storage URIs it was
            // explicitly allowed to. Exact match on the normalized URI.
            if (target.uri.isNotBlank() && target.uri in policy.safWhitelist) {
                LinkAction.OpenBrowser(target.uri, viaSafWhitelist = true)
            } else {
                LinkAction.None
            }
        }

        LinkKind.OTHER ->
            // Unknown scheme (`javascript:`, `vnd.***:`, `about:`), relative
            // paths, empty href: never opened by default — mirrors the
            // dispatcher's fallthrough ("In-app / unknown scheme: nothing
            // native to do").
            LinkAction.None
    }

    /** Policy-selected external-open flavor (see [LinkPolicy.explicitBrowserIntent]). */
    private fun externalOpen(uri: String): LinkAction =
        if (policy.explicitBrowserIntent) LinkAction.OpenBrowserIntent(uri)
        else LinkAction.OpenBrowser(uri)

    companion object {
        /** Preconfigured router at the dispatcher-parity defaults. */
        val DEFAULT: LinkRouter = LinkRouter()
    }
}
