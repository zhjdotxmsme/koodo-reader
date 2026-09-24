package com.koodoreader.feature.dictionary.mdx

/**
 * MDict block decryption.
 *
 * PORT SOURCE — `node_modules/js-mdict@6.0.8/dist/esm/utils.js`:
 *  - `fast_decrypt`  utils.js:235-245  (nibble-swap + XOR stream, `previous` seed 0x36)
 *  - `salsa_decrypt` utils.js:246-252  (unimplemented stub in the reference — kept as a
 *                                       documented gap, see [salsaDecryptUnsupported])
 *  - `mdxDecrypt`    utils.js:258-271  (RIPEMD-128 key from bytes 4..8 + `95 36 00 00`)
 *
 * CALL SITE — `mdict-base.js:796-801` (record blocks, `meta.encrypt == 1`) and
 * `mdict-base.js:517-519` (key-info block, `meta.encrypt == 2`).
 *
 * INVARIANT (differs from the reference!): `fast_decrypt` is *not* an involution —
 * the feedback byte is the raw input byte, so the encrypt and decrypt directions
 * are different transforms (which is why `mdict-base.js` keeps two separate code
 * paths). Because of that, both functions here return a NEW array and never touch
 * the caller's bytes; the JS port mutates its input in place via `Buffer.slice()`
 * views, a trap that already produced one wrong golden vector during porting.
 *
 * The MDict "encryption" is an obfuscation, not a cipher: it exists to stop
 * casual text-editor inspection of a dictionary. A dictionary can still only be
 * read if its `Encrypted` header field says so, which is why the plaintext path
 * (`encrypt == 0`) never decrypts at all.
 */
object MdictCrypto {

    /** `utils.js:235` — XOR stream cipher over `data` with a RIPEMD-128 key. */
    fun fastDecrypt(data: ByteArray, key: ByteArray): ByteArray {
        require(key.isNotEmpty()) { "decryption key must not be empty" }
        val out = data.copyOf()
        var previous = 0x36
        for (i in out.indices) {
            val value = out[i].toInt() and 0xFF
            var t = ((value shr 4) or (value shl 4)) and 0xFF
            t = t xor previous xor (i and 0xFF) xor (key[i % key.size].toInt() and 0xFF)
            previous = value
            out[i] = t.toByte()
        }
        return out
    }

    /**
     * `utils.js:258` — decrypt one compressed block.
     *
     * The 8-byte block prefix is preserved verbatim (compression type + adler32);
     * only the payload is passed through [fastDecrypt]. The key is
     * `RIPEMD128(block[4..8] || 95 36 00 00)`; note the reference builds that
     * 8-byte seed by XOR-ing into a zero-filled buffer (`utils.js:259-264`), which
     * is equivalent to the concatenation written here.
     */
    fun mdxDecrypt(block: ByteArray): ByteArray {
        if (block.size < 8) throw MdictFormatException("encrypted block too short: ${block.size} bytes")
        val seed = ByteArray(8)
        System.arraycopy(block, 4, seed, 0, 4)
        seed[4] = (seed[4].toInt() xor 0x95).toByte()
        seed[5] = (seed[5].toInt() xor 0x36).toByte()
        seed[6] = (seed[6].toInt() xor 0x00).toByte()
        seed[7] = (seed[7].toInt() xor 0x00).toByte()
        val key = Ripemd128.digest(seed)
        val out = block.copyOf()
        val payload = fastDecrypt(block.copyOfRange(8, block.size), key)
        System.arraycopy(payload, 0, out, 8, payload.size)
        return out
    }

    /**
     * `utils.js:246` — Salsa20 blocks (`Encrypted="2"` + `RegisterBy`) are a stub in
     * the reference implementation too (`return data`), i.e. js-mdict cannot read
     * those files either. Fail loudly instead of returning garbage.
     */
    fun salsaDecryptUnsupported(): Nothing = throw MdictFormatException(
        "Salsa20-encrypted MDict blocks are not supported (the reference js-mdict " +
            "implementation stubs this path out as well — utils.js:246-252)"
    )
}
