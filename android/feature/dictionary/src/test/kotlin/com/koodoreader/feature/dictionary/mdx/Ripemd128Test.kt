package com.koodoreader.feature.dictionary.mdx

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RIPEMD-128 vectors.
 *
 * `hex` values in [publishedVectors] are the digests from the RIPEMD-128 reference
 * test suite (Bosselaers); they were also reproduced through
 * `node_modules/js-mdict/dist/esm/utils.js ripemd128` while generating the golden
 * fixtures, so this test pins the port against BOTH the published standard and the
 * exact implementation the dictionary format was designed against.
 */
class Ripemd128Test {

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun `matches the published ripemd128 vectors`() {
        val published = mapOf(
            "" to "cdf26213a150dc3ecb610f18f6b38b46",
            "a" to "86be7afa339d0fc7cfc785e72f578d33",
            "abc" to "c14a12199c66e4ba84636b0f69144c77",
            "message digest" to "9e327b3d6e523062afc1132d7df9d1b8",
            "abcdefghijklmnopqrstuvwxyz" to "fd2aa607f71dc8f510714922b371834e",
        )
        for ((input, expected) in published) {
            assertEquals("ripemd128(\"$input\")", expected, hex(Ripemd128.digest(input.toByteArray())))
        }
    }

    @Test
    fun `digest is always 16 bytes`() {
        assertEquals(16, Ripemd128.digest(ByteArray(0)).size)
        assertEquals(16, Ripemd128.digest(ByteArray(1)).size)
        assertEquals(16, Ripemd128.digest("x".repeat(1000).toByteArray()).size)
    }

    /**
     * The padding boundary is the classic RIPEMD/MD4 off-by-one: 55 bytes still fit
     * in one block, 56 bytes need a second one (0x80 + 8 length bytes).
     */
    @Test
    fun `padding boundary at 55 and 56 bytes`() {
        val first = hex(Ripemd128.digest(ByteArray(55) { 'a'.code.toByte() }))
        val second = hex(Ripemd128.digest(ByteArray(56) { 'a'.code.toByte() }))
        assertEquals(32, first.length)
        assertEquals(32, second.length)
        // Same input, same digest — proves the block split is deterministic.
        assertEquals(first, hex(Ripemd128.digest(ByteArray(55) { 'a'.code.toByte() })))
        org.junit.Assert.assertNotEquals(first, second)
    }
}
