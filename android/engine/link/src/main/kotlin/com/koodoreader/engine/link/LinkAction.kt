package com.koodoreader.engine.link

import com.koodoreader.engine.cfi.Cfi

/**
 * The DECISION of "who should handle this link" — the vocabulary the UI
 * layer switches on. Each action names its handler explicitly:
 *
 *  | action                        | handler                                   |
 *  |-------------------------------|-------------------------------------------|
 *  | [OpenBrowser]                 | the SYSTEM (implicit `ACTION_VIEW`)       |
 *  | [OpenBrowserIntent]           | the SYSTEM (explicit browser intent)      |
 *  | [InternalJump]                | the READER rendition (CFI navigation)     |
 *  | [Footnote]                    | the NOTE UI (text dialog)                 |
 *  | [None]                        | NOBODY — safe no-op                       |
 *
 * The two `OPEN_*` variants encode a policy choice rather than a capability
 * difference: [OpenBrowser] is the implicit "open with default app" intent
 * (the exact mechanism `NativeEventDispatcher.handleLinkClick` uses —
 * `Intent(Intent.ACTION_VIEW, Uri.parse(href))`), while [OpenBrowserIntent]
 * is emitted when [LinkPolicy.explicitBrowserIntent] is set, letting the
 * shell attach a chooser / preferred browser.
 *
 * The heavy lifting (launching the intent, chooser dialogs, toasts on
 * failure) lives in the Android/Compose layer — this sealed hierarchy is the
 * boundary it programs against.
 */
sealed class LinkAction {
    /**
     * Hand [uri] to the system's default app for it: `http(s)` → the default
     * browser, `mailto:` → the mail app, whitelisted `content:`/`file:` →
     * the app that registered the URI. The shell implements this as
     * `Intent(Intent.ACTION_VIEW, Uri.parse(uri))` — identical to the
     * WebView-track dispatcher — and shows "Nothing can open this" on
     * `ActivityNotFoundException`.
     *
     * @property viaSafWhitelist `true` when the open was permitted by
     *   [LinkPolicy.safWhitelist] (a book-authored storage URI); `false` for
     *   ordinary http(s)/mailto opens. Kept on the action (not only on the
     *   policy) so the shell's log/UX can tell a policy hit from a default.
     */
    data class OpenBrowser(
        val uri: String,
        val viaSafWhitelist: Boolean = false,
    ) : LinkAction()

    /**
     * Same target as [OpenBrowser] but the shell builds an EXPLICIT browser
     * intent (policy [LinkPolicy.explicitBrowserIntent] = `true`) — e.g. a
     * resolved browser component or a chooser.
     */
    data class OpenBrowserIntent(val uri: String) : LinkAction()

    /**
     * The reader jumps TO THE BOOK POSITION [cfi] (EPUBCFI target). The
     * rendition does the navigation (the `engine/epub` / composition layer
     * resolves [cfi] and scrolls); the raw [sourceHref] is preserved for logs.
     */
    data class InternalJump(
        val cfi: Cfi,
        val sourceHref: String,
    ) : LinkAction()

    /**
     * Show the footnote text (the `link-clicked` payload's `footnote` field,
     * exactly the contract of `NativeEventDispatcher.showFootnoteDialog` —
     * a read-only text card with a copy button; the Compose layer reuses the
     * same i18n labels).
     */
    data class Footnote(val text: String) : LinkAction()

    /**
     * Nothing opens — the safe no-op. Reached for: empty/`OTHER` hrefs,
     * `javascript:`-style schemes, SAF URIs NOT on the whitelist, invalid
     * EPUBCFI bodies, and `#anchor` links the page handles itself (the
     * WebView track likewise does nothing native for in-page anchors).
     */
    object None : LinkAction()
}

/**
 * Whitelist / policy flags for [LinkRouter].
 *
 * DEFAULT policy == the WebView dispatch track:
 *  - `http` / `https` / `mailto` → OPEN (system browser / mail app);
 *  - SAF storage URIs → CLOSED (whitelist is empty);
 *  - EPUBCFI → internal jump ALLOWED (a capability upgrade over the WebView
 *    track, which does nothing native for such links — strictly additive);
 *  - everything else → NONE.
 *
 * Flip flags per-install to be stricter (e.g. block `mailto`, or open ONLY
 * through an explicit browser intent).
 */
data class LinkPolicy(
    val allowHttp: Boolean = true,
    val allowHttps: Boolean = true,
    val allowMailto: Boolean = true,
    val explicitBrowserIntent: Boolean = false,
    val allowEpubCfiJump: Boolean = true,
    val allowFootnote: Boolean = true,
    /**
     * Exact-match whitelist of SAF URIs a book may open (normalized [LinkTarget.uri]
     * strings, e.g. `content://koodo.local/chapters/1`). Comparison is strict
     * on purpose: this gate keeps a book from reaching arbitrary local storage.
     */
    val safWhitelist: Set<String> = emptySet(),
) {
    companion object {
        /** Dispatcher-parity defaults (see class KDoc). */
        val DEFAULT: LinkPolicy = LinkPolicy()
    }
}
