package com.koodoreader.engine.link

import com.koodoreader.engine.cfi.Cfi

/**
 * EPUB link taxonomy — the classification every `link-clicked` href gets.
 *
 * The split follows what the READER needs to decide: who opens it (system,
 * book, footnote dialog, or nobody).
 *
 * Alignment note vs the WebView track: `NativeEventDispatcher.handleLinkClick`
 * only distinguishes `http(s)://` / `mailto:` (open in system browser) from
 * "everything else → nothing native". This taxonomy refines that into
 * explicit kinds so the native track can ADD capabilities (epubcfi jumps,
 * whitelisted storage URIs) without regressing the baseline: with the
 * [LinkPolicy.DEFAULT] policy, the router's verdict for every kind below
 * agrees with the dispatcher.
 */
enum class LinkKind {
    /** `#fragment` — an in-page anchor; the page itself can handle it. */
    ANCHOR,

    /** `epubcfi:` URI (optional `N?` version) or `epubcfi(...)` wrapper form. */
    EPUBCFI,

    /** Absolute `http://` / `https://` URL. */
    EXTERNAL_HTTP,

    /** `mailto:` link (email address with optional `?params`). */
    MAILTO,

    /**
     * Storage-access URIs: `content://…` (SAF) and `file://…` — links that
     * point at local storage rather than the web. Handling is whitelist-gated
     * (a book must never be able to point the app at arbitrary local files).
     */
    SAF,

    /**
     * Everything else: empty href, unknown schemes (`javascript:`,
     * `vnd.***:`, `about:`), relative document paths. Never opened by default.
     */
    OTHER,
}

/**
 * A link href plus everything the [LinkRouter] needs to decide on it.
 *
 * Construct via [LinkClassifier.classify] — the individual fields are derived
 * (and validated, for [EPUBCFI][LinkKind.EPUBCFI] targets, against
 * `:engine:cfi`).
 *
 * @property href the ORIGINAL raw href, as delivered in `link-clicked`.
 * @property kind the [LinkKind] classification.
 * @property uri the usable absolute URI, when the kind has one
 *   ([EXTERNAL_HTTP]/[MAILTO]/[SAF]); empty for [ANCHOR]/[OTHER].
 * @property fragment the fragment for [ANCHOR] targets (`href` minus `#`).
 * @property cfi the PARSED CFI when [kind] == [LinkKind.EPUBCFI] and the body
 *   validates via `engine/cfi`; `null` otherwise (incl. invalid EPUBCFI).
 * @property cfiValid `true`/`false` only for EPUBCFI targets (whether [cfi]
 *   parsed); `null` for every other kind (= "not applicable").
 * @property emailAddress the address for [MAILTO] targets (`?params` stripped).
 */
data class LinkTarget(
    val href: String,
    val kind: LinkKind,
    val uri: String = "",
    val fragment: String = "",
    val cfi: Cfi? = null,
    val cfiValid: Boolean? = null,
    val emailAddress: String = "",
)
