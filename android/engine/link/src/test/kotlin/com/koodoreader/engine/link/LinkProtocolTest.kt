package com.koodoreader.engine.link

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Pins the protocol constants to the EXACT literals used by
 * `NativeEventDispatcher.kt` (single source of truth) and `nativeBridge.js`.
 * If these start failing, the two tracks have drifted — align them before
 * anything else, because JS→Kotlin event names are matched by string.
 */
class LinkProtocolTest {

    @Test
    @DisplayName("event names match NativeEventDispatcher.EVENT_*")
    fun eventNamesMatchDispatcher() {
        assertEquals("view-image", LinkProtocol.EVENT_VIEW_IMAGE)
        assertEquals("link-clicked", LinkProtocol.EVENT_LINK_CLICKED)
    }

    @Test
    @DisplayName("payload field names match the dispatcher's optString keys")
    fun fieldNamesMatchDispatcher() {
        assertEquals("imgSrc", LinkProtocol.FIELD_IMG_SRC)
        assertEquals("href", LinkProtocol.FIELD_HREF)
        assertEquals("footnote", LinkProtocol.FIELD_FOOTNOTE)
    }

    @Test
    @DisplayName("image size limit == NativeEventDispatcher.IMAGE_SIZE_LIMIT (15 MiB)")
    fun imageSizeLimitMatchesDispatcher() {
        assertEquals(15L * 1024L * 1024L, LinkProtocol.IMAGE_SIZE_LIMIT)
    }
}
