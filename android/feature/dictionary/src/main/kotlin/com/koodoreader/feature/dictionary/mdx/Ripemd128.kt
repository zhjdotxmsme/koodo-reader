package com.koodoreader.feature.dictionary.mdx

/**
 * RIPEMD-128 (ISO/IEC 10118-3), the digest MDict uses to derive the per-block
 * decryption key.
 *
 * PORT SOURCE — `node_modules/js-mdict@6.0.8/dist/esm/ripemd128.js:1-144`
 * (itself a port of the reference C implementation), consumed by
 * `utils.js:258 mdxDecrypt`:
 *
 * ```js
 * const key = ripemd128(keyinBuffer);   // utils.js:265
 * fast_decrypt(comp_block.slice(8), Uint8Array.from(key));
 * ```
 *
 * The JS file uses a table-driven 64-step loop; this implementation expands the
 * same four rounds per line into explicit step tables (`R_LEFT`/`S_LEFT` …),
 * which is the layout used by the paper's reference implementation and is much
 * easier to verify against the published test vectors
 * (`Ripemd128Test.ripemd128PublishedVectors`).
 */
object Ripemd128 {

    private val H0 = intArrayOf(0x67452301, -0x10325477, -0x67452302, 0x10325476)

    /** Round constants, left line: 0x00000000, 0x5A827999, 0x6ED9EBA1, 0x8F1BBCDC. */
    private val K_LEFT = intArrayOf(0x00000000, 0x5A827999, 0x6ED9EBA1, -0x70E44324)

    /** Round constants, right line: 0x50A28BE6, 0x5C4DD124, 0x6D703EF3, 0x00000000. */
    private val K_RIGHT = intArrayOf(0x50A28BE6, 0x5C4DD124, 0x6D703EF3, 0x00000000)

    private val R_LEFT = intArrayOf(
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
        7, 4, 13, 1, 10, 6, 15, 3, 12, 0, 9, 5, 2, 14, 11, 8,
        3, 10, 14, 4, 9, 15, 8, 1, 2, 7, 0, 6, 13, 11, 5, 12,
        1, 9, 11, 10, 0, 8, 12, 4, 13, 3, 7, 15, 14, 5, 6, 2,
    )

    private val R_RIGHT = intArrayOf(
        5, 14, 7, 0, 9, 2, 11, 4, 13, 6, 15, 8, 1, 10, 3, 12,
        6, 11, 3, 7, 0, 13, 5, 10, 14, 15, 8, 12, 4, 9, 1, 2,
        15, 5, 1, 3, 7, 14, 6, 9, 11, 8, 12, 2, 10, 0, 4, 13,
        8, 6, 4, 1, 3, 11, 15, 0, 5, 12, 2, 13, 9, 7, 10, 14,
    )

    private val S_LEFT = intArrayOf(
        11, 14, 15, 12, 5, 8, 7, 9, 11, 13, 14, 15, 6, 7, 9, 8,
        7, 6, 8, 13, 11, 9, 7, 15, 7, 12, 15, 9, 11, 7, 13, 12,
        11, 13, 6, 7, 14, 9, 13, 15, 14, 8, 13, 6, 5, 12, 7, 5,
        11, 12, 14, 15, 14, 15, 9, 8, 9, 14, 5, 6, 8, 6, 5, 12,
    )

    private val S_RIGHT = intArrayOf(
        8, 9, 9, 11, 13, 15, 15, 5, 7, 7, 8, 11, 14, 14, 12, 6,
        9, 13, 15, 7, 12, 8, 9, 11, 7, 7, 12, 7, 6, 15, 13, 11,
        9, 7, 15, 11, 8, 6, 6, 14, 12, 13, 5, 14, 13, 13, 7, 5,
        15, 5, 8, 11, 14, 14, 6, 14, 6, 9, 12, 9, 12, 5, 15, 8,
    )

    /** Digest `message`, returning the 16 key bytes (little-endian word order). */
    fun digest(message: ByteArray): ByteArray {
        val h = H0.copyOf()
        val padded = pad(message)
        val x = IntArray(16)
        for (blockStart in padded.indices step 64) {
            for (i in 0 until 16) {
                val o = blockStart + i * 4
                x[i] = (padded[o].toInt() and 0xFF) or
                    ((padded[o + 1].toInt() and 0xFF) shl 8) or
                    ((padded[o + 2].toInt() and 0xFF) shl 16) or
                    ((padded[o + 3].toInt() and 0xFF) shl 24)
            }
            var al = h[0]; var bl = h[1]; var cl = h[2]; var dl = h[3]
            var ar = h[0]; var br = h[1]; var cr = h[2]; var dr = h[3]
            for (step in 0 until 64) {
                val round = step / 16
                // RIPEMD-128 keeps FOUR registers: T = rol_s(a + f(b,c,d) + X[r] + K),
                // then a := d, d := c, c := b, b := T. (RIPEMD-160 adds a fifth
                // register `e` and rotates c by 10 — using that form here produces a
                // digest that only looks plausible.)
                var t = rotl(al + fLeft(round, bl, cl, dl) + x[R_LEFT[step]] + K_LEFT[round], S_LEFT[step])
                al = dl; dl = cl; cl = bl; bl = t
                t = rotl(ar + fRight(round, br, cr, dr) + x[R_RIGHT[step]] + K_RIGHT[round], S_RIGHT[step])
                ar = dr; dr = cr; cr = br; br = t
            }
            val t = h[1] + cl + dr
            h[1] = h[2] + dl + ar
            h[2] = h[3] + al + br
            h[3] = h[0] + bl + cr
            h[0] = t
        }
        val out = ByteArray(16)
        for (i in 0 until 4) {
            out[i * 4] = (h[i] and 0xFF).toByte()
            out[i * 4 + 1] = ((h[i] ushr 8) and 0xFF).toByte()
            out[i * 4 + 2] = ((h[i] ushr 16) and 0xFF).toByte()
            out[i * 4 + 3] = ((h[i] ushr 24) and 0xFF).toByte()
        }
        return out
    }

    /** MD4-style padding: 0x80, zeros, then the bit length as a little-endian u64. */
    private fun pad(message: ByteArray): ByteArray {
        val bitLen = message.size.toLong() * 8
        var padLen = 64 - ((message.size + 9) % 64)
        if (padLen == 64) padLen = 0
        val out = ByteArray(message.size + 9 + padLen)
        System.arraycopy(message, 0, out, 0, message.size)
        out[message.size] = 0x80.toByte()
        for (i in 0 until 8) {
            out[out.size - 8 + i] = ((bitLen ushr (8 * i)) and 0xFF).toByte()
        }
        return out
    }

    private fun rotl(value: Int, amount: Int): Int = (value shl amount) or (value ushr (32 - amount))

    private fun fLeft(round: Int, x: Int, y: Int, z: Int): Int = when (round) {
        0 -> x xor y xor z
        1 -> (x and y) or (x.inv() and z)
        2 -> (x or y.inv()) xor z
        else -> (x and z) or (y and z.inv())
    }

    private fun fRight(round: Int, x: Int, y: Int, z: Int): Int = when (round) {
        0 -> (x and z) or (y and z.inv())
        1 -> (x or y.inv()) xor z
        2 -> (x and y) or (x.inv() and z)
        else -> x xor y xor z
    }
}
