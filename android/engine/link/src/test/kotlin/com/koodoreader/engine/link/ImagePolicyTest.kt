package com.koodoreader.engine.link

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Coverage of the image open policy, aligned 1:1 with
 * `NativeEventDispatcher.openImage`:
 *
 *  - non-`data:` source            → REJECTED_SOURCE ("Nothing can open this"),
 *  - `data:` longer than 15 MiB    → TOO_LARGE ("Image too large…"),
 *  - otherwise (incl. == 15 MiB)   → OPEN_NATIVE (the bound is inclusive).
 */
class ImagePolicyTest {

    private fun spec(src: String): ImageSpec = ImageSpec(ImageSource.of(src))

    @Test
    @DisplayName("a data: URI within the bound is openable natively")
    fun withinLimit() {
        val s = spec("data:image/png;base64,iVBORw0KGgo=")
        assertEquals(ImageDecision.OPEN_NATIVE, s.decision())
        assertTrue(s.isOpenable())
        assertTrue(s.isDataUri)
    }

    @Test
    @DisplayName("a non-data: source is rejected regardless of size")
    fun rejectedSource() {
        assertEquals(
            ImageDecision.REJECTED_SOURCE,
            spec("images/cover.png").decision(),
        )
        assertEquals(
            ImageDecision.REJECTED_SOURCE,
            spec("blob:https://localhost/uuid").decision(),
        )
        assertEquals(
            ImageDecision.REJECTED_SOURCE,
            spec("content://media/external/images/1").decision(),
        )
        assertFalse(spec("images/cover.png").isOpenable())
    }

    @Test
    @DisplayName("a data: URI strictly over the limit is TOO_LARGE")
    fun overLimit() {
        val limit = LinkProtocol.IMAGE_SIZE_LIMIT
        // 'a'.repeat(limit + 1) → text length strictly greater than the bound.
        val src = "data:" + "a".repeat((limit + 1L).toInt())
        assertEquals(ImageDecision.TOO_LARGE, spec(src).decision())
        assertFalse(spec(src).isOpenable())
    }

    @Test
    @DisplayName("the bound is INCLUSIVE: length == limit still opens")
    fun exactlyAtLimit() {
        val limit = LinkProtocol.IMAGE_SIZE_LIMIT
        val prefix = "data:"
        val src = prefix + "a".repeat((limit - prefix.length).toInt())
        assertEquals(limit, src.length.toLong())
        assertEquals(ImageDecision.OPEN_NATIVE, spec(src).decision())
    }

    @Test
    @DisplayName("ImagePolicy.isWithinLimit is an inclusive <= comparison")
    fun policyBoundSemantics() {
        val policy = ImagePolicy(dataUriTextLengthLimit = 10L)
        assertTrue(policy.isWithinLimit(10L))
        assertTrue(policy.isWithinLimit(9L))
        assertFalse(policy.isWithinLimit(11L))
    }

    @Test
    @DisplayName("a tighter custom policy rejects a URI the default policy accepts")
    fun customPolicy() {
        val src = "data:" + "a".repeat(100)
        assertEquals(ImageDecision.OPEN_NATIVE, spec(src).decision(ImagePolicy.DEFAULT))
        assertEquals(
            ImageDecision.TOO_LARGE,
            spec(src).decision(ImagePolicy(dataUriTextLengthLimit = 50L)),
        )
    }

    @Test
    @DisplayName("ImageSpec decodes a ViewImageEvent through the same classification")
    fun fromEvent() {
        val openable = ImageSpec.of(ViewImageEvent("data:image/gif;base64,R0lGOD"))
        assertEquals(ImageDecision.OPEN_NATIVE, openable.decision())

        val rejected = ImageSpec.of(ViewImageEvent("x.png"))
        assertEquals(ImageDecision.REJECTED_SOURCE, rejected.decision())
    }
}
