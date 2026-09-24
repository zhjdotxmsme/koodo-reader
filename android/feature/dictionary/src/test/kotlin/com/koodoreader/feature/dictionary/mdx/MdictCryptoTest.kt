package com.koodoreader.feature.dictionary.mdx

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Block decryption, pinned against vectors produced by js-mdict 6.0.8 itself
 * (`node_modules/js-mdict/dist/esm/utils.js` loaded from Node, see
 * `.scratch/mkfixture.mjs` in the verification notes of
 * `docs/p6-dictionary-architecture.md` §7).
 *
 * VECTOR PROVENANCE: `MdictCryptoTest.vectors.txt` equivalent —
 * ```
 * fast_decrypt("The quick brown fox jumps", key="0123456789abcdef")
 *   -> 43e20e570716d36fe5596d2ceb71fa0566b0c85aa61d834a67
 * mdxDecrypt(1122334455667788 0102030405060708090a)
 *   -> 1122334455667788 d0249733e080ceee715c     (envelope preserved)
 * ```
 * The `mdxDecrypt` vector additionally proves the RIPEMD-128 key derivation: the
 * first 8 bytes must survive untouched, and the payload must match byte for byte.
 */
class MdictCryptoTest {

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun unhex(text: String): ByteArray =
        ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun `fastDecrypt matches the js-mdict vector`() {
        val input = "The quick brown fox jumps".toByteArray()
        val key = "0123456789abcdef".toByteArray()
        assertEquals(
            "43e20e570716d36fe5596d2ceb71fa0566b0c85aa61d834a67",
            hex(MdictCrypto.fastDecrypt(input, key)),
        )
    }

    @Test
    fun `fastDecrypt leaves its input untouched`() {
        val input = "The quick brown fox jumps".toByteArray()
        val copy = input.copyOf()
        MdictCrypto.fastDecrypt(input, "0123456789abcdef".toByteArray())
        assertArrayEquals(copy, input)
    }

    @Test
    fun `mdxDecrypt matches the js-mdict vector and keeps the envelope`() {
        val input = unhex("11223344556677880102030405060708090a")
        val output = MdictCrypto.mdxDecrypt(input)
        assertEquals("1122334455667788d0249733e080ceee715c", hex(output))
        // The compression type + adler32 prefix is not encrypted.
        assertArrayEquals(input.copyOfRange(0, 8), output.copyOfRange(0, 8))
    }

    /**
     * `mdict-analysis` defines `_fast_encrypt` as the exact inverse of
     * `_fast_decrypt`; [MdictFixtureWriter.fastEncrypt] implements the writing side.
     * This is what makes the `Encrypted="Yes"` fixture in `MdxParserTest` meaningful.
     */
    @Test
    fun `fastEncrypt is the inverse of fastDecrypt`() {
        val plain = "the quick brown fox jumps over the lazy dog".toByteArray()
        val key = Ripemd128.digest("p6-dictionary".toByteArray())
        val encrypted = MdictFixtureWriter.fastEncrypt(plain, key)
        assertTrue("encryption must change the bytes", !plain.contentEquals(encrypted))
        assertArrayEquals(plain, MdictCrypto.fastDecrypt(encrypted, key))
    }

    @Test(expected = MdictFormatException::class)
    fun `mdxDecrypt rejects a truncated block`() {
        MdictCrypto.mdxDecrypt(byteArrayOf(1, 2, 3))
    }

    @Test(expected = MdictFormatException::class)
    fun `salsa blocks fail loudly instead of returning garbage`() {
        MdictCrypto.salsaDecryptUnsupported()
    }
}
