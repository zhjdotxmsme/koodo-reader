package com.koodoreader.engine.link

/**
 * Image event data model + size policy.
 *
 * Semantics are 1:1 aligned with `NativeEventDispatcher.openImage`
 * (`android/app`), the single source of truth for the WebView track:
 *
 *  | openImage behaviour                                   | this model                     |
 *  |-------------------------------------------------------|--------------------------------|
 *  | `imgSrc` not starting with `data:` → "nothing opens" | [ImageDecision.REJECTED_SOURCE]|
 *  | `imgSrc.length > 15MiB` → "image too large"           | [ImageDecision.TOO_LARGE]      |
 *  | otherwise → full-screen read-only WebView dialog      | [ImageDecision.OPEN_NATIVE]    |
 *
 * The heavy part (full-screen dialog, pinch zoom, close button, toast copy)
 * is Compose/View UI code and deliberately stays in the UI layer; everything
 * this module decides is DATA-LEVEL: which source kinds are openable at all
 * and whether the size bound is respected.
 *
 * The UI layer should map the [ImageDecision] to the same UX as the WebView
 * track: `OPEN_NATIVE` → full-screen viewer; `TOO_LARGE` → toast equivalent
 * of "Image too large to open in the app"; `REJECTED_SOURCE` → "Nothing can
 * open this".
 */

/**
 * Where the image bytes live, from the `view-image` payload's `imgSrc`.
 *
 * `NativeEventDispatcher.openImage` accepts ONLY `data:` URIs (everything
 * else → "Nothing can open this"). [ImageSource.of] reproduces that split.
 */
sealed class ImageSource {
    /** A self-contained `data:` URI (the only kind the app can render today). */
    data class DataUri(val uri: String) : ImageSource()

    /**
     * Any non-`data:` reference (relative path, `blob:`, `content:`, …).
     * Carried here so the model is closed; the decision for it is always
     * [ImageDecision.REJECTED_SOURCE], mirroring the dispatcher.
     */
    data class Uri(val uri: String) : ImageSource()

    companion object {
        /** Mirror of `openImage`'s `imgSrc.startsWith("data:")` check. */
        fun of(src: String): ImageSource =
            if (src.startsWith("data:")) DataUri(src) else Uri(src)
    }
}

/** Which side of the policy line a given image falls on. */
enum class ImageDecision {
    /** Openable natively (`data:` URI within the size bound). */
    OPEN_NATIVE,

    /** `data:` URI strictly larger than the bound ("Image too large…"). */
    TOO_LARGE,

    /** Not a `data:` URI — nothing native to open it with ("Nothing can…" ). */
    REJECTED_SOURCE,
}

/**
 * The data-level view of one `view-image` event.
 *
 * @property source the classified source of the image.
 * @property byteLength known decoded byte size of the image payload, when the
 *   producer knows it (diagnostics / future decoders); `0` = unknown. Note
 *   the dispatch decision uses the data-URI TEXT length (see
 *   [decision]) to match `openImage` byte-for-byte.
 */
data class ImageSpec(
    val source: ImageSource,
    val byteLength: Long = 0L,
) {
    val isDataUri: Boolean get() = source is ImageSource.DataUri

    /**
     * Dispatch decision. Faithful port of `openImage`'s two guards:
     *
     * ```
     * if (!imgSrc.startsWith("data:")) → REJECTED_SOURCE
     * if (imgSrc.length.toLong() > IMAGE_SIZE_LIMIT) → TOO_LARGE
     * ```
     *
     * i.e. the bound is INCLUSIVE: `length == limit` still opens.
     */
    fun decision(policy: ImagePolicy = ImagePolicy.DEFAULT): ImageDecision = when {
        source !is ImageSource.DataUri -> ImageDecision.REJECTED_SOURCE
        policy.isWithinLimit(dataUriTextLength()) -> ImageDecision.OPEN_NATIVE
        else -> ImageDecision.TOO_LARGE
    }

    /** Convenience: the dispatcher either opens it or it does not. */
    fun isOpenable(policy: ImagePolicy = ImagePolicy.DEFAULT): Boolean =
        decision(policy) == ImageDecision.OPEN_NATIVE

    /** `openImage` measures `imgSrc.length` — the raw URI text. */
    private fun dataUriTextLength(): Long =
        (source as? ImageSource.DataUri)?.uri?.length?.toLong() ?: 0L

    companion object {
        /** Decode a `view-image` event (app layer already read `imgSrc`). */
        fun of(event: ViewImageEvent): ImageSpec = ImageSpec(ImageSource.of(event.imgSrc))
    }
}

/**
 * Size policy for in-app image opening.
 *
 * Default limit: [LinkProtocol.IMAGE_SIZE_LIMIT] = 15 MiB of data-URI text —
 * exactly `NativeEventDispatcher.IMAGE_SIZE_LIMIT`. Tune only when the
 * dispatcher is tuned the same way.
 */
data class ImagePolicy(
    val dataUriTextLengthLimit: Long = LinkProtocol.IMAGE_SIZE_LIMIT,
) {
    /**
     * Inclusive bound, mirroring `openImage`'s `> LIMIT` rejection:
     * `length ≤ limit` → openable, `length > limit` → too large.
     */
    fun isWithinLimit(dataUriTextLength: Long): Boolean =
        dataUriTextLength <= dataUriTextLengthLimit

    companion object {
        /** The dispatcher-aligned default: 15 MiB data-URI text bound. */
        val DEFAULT: ImagePolicy = ImagePolicy()
    }
}
