package com.koodoreader.engine.link

/**
 * Wire protocol for the image / link / footnote events.
 *
 * PROTOCOL MIRROR — these constants MUST stay in sync with:
 *
 *  1. `android/app/src/main/java/com/koodoreader/reader/NativeEventDispatcher.kt`
 *     (single source of truth on the Android side): event names
 *     [EVENT_VIEW_IMAGE] / [EVENT_LINK_CLICKED] and payload fields
 *     `imgSrc` / `href` / `footnote`.
 *  2. `src/utils/android/nativeBridge.js` (EVENTS), which pins the same names
 *     on the JS side (its Jest tests assert the literals).
 *
 * | event          | field      | semantics (mirrors NativeEventDispatcher)                              |
 * |----------------|------------|------------------------------------------------------------------------|
 * | `view-image`   | `imgSrc`   | the clicked image's source; a `data:` URI is viewable in-app          |
 * | `link-clicked` | `href`     | the raw link href                                                      |
 * | `link-clicked` | `footnote` | footnote text; when non-blank it OVERRIDES [FIELD_HREF] (dialog wins) |
 *
 * Do not rename these without updating both sides — the bridge passes raw
 * strings, so a drift here silently stops the events reaching the UI.
 */
object LinkProtocol {
    /** `NativeEventDispatcher.EVENT_VIEW_IMAGE`. */
    const val EVENT_VIEW_IMAGE: String = "view-image"

    /** `NativeEventDispatcher.EVENT_LINK_CLICKED`. */
    const val EVENT_LINK_CLICKED: String = "link-clicked"

    /** Payload key of the image source in a `view-image` event. */
    const val FIELD_IMG_SRC: String = "imgSrc"

    /** Payload key of the link href in a `link-clicked` event. */
    const val FIELD_HREF: String = "href"

    /** Payload key of the footnote text in a `link-clicked` event. */
    const val FIELD_FOOTNOTE: String = "footnote"

    /**
     * Maximum size of a natively viewable image, expressed in data-URI TEXT
     * characters. Mirror of `NativeEventDispatcher.IMAGE_SIZE_LIMIT`
     * (`15L * 1024 * 1024`, "bytes (data URI text length bound)").
     *
     * Exact boundary semantics of `openImage`: `imgSrc.length > LIMIT` →
     * rejected ("Image too large…"); `imgSrc.length == LIMIT` is still opened.
     * See [ImagePolicy.isWithinLimit].
     */
    const val IMAGE_SIZE_LIMIT: Long = 15L * 1024 * 1024
}

/**
 * Decoded `view-image` event: what the app layer hands to the engine after
 * reading `rawJson.optString("imgSrc")`.
 *
 * @property imgSrc the raw `imgSrc` payload value (a `data:` URI in every
 *   case the WebView dispatcher is willing to open; anything else is an
 *   unsupported source, see [ImageSpec.decision]).
 */
data class ViewImageEvent(
    val imgSrc: String,
)

/**
 * Decoded `link-clicked` event: what the app layer hands to the engine after
 * reading `href` / `footnote`. Both fields coexist in the same payload; the
 * footnote, when non-blank, takes precedence over the href — exactly the
 * `NativeEventDispatcher.handleLinkClick` contract, mirrored by
 * [LinkRouter.routeEvent].
 *
 * @property href the raw `href` payload value (may be empty).
 * @property footnote the raw `footnote` payload value; non-blank ⇒ footnote.
 */
data class LinkClickEvent(
    val href: String,
    val footnote: String,
)
