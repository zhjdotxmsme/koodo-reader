package com.koodoreader.feature.dictionary.mdx

import java.io.ByteArrayOutputStream
import java.util.zip.Adler32
import java.util.zip.Deflater

/**
 * Independent MDX / MDD **writer** used only by the tests.
 *
 * The port under test is a reader; to test it without shipping binary fixtures for
 * every variant, the tests build the container themselves. This writer is written
 * from the format description in `mdict-analysis` /
 * `node_modules/js-mdict/dist/esm/mdict-base.js:38-59`, NOT from [MdictCore], so a
 * shared misunderstanding of the layout cannot make both sides agree.
 *
 * The byte-level interoperability of the same layout is additionally pinned by
 * `GoldenInteropTest`, whose fixture was produced by a JavaScript writer and read
 * back with js-mdict 6.0.8 itself.
 *
 * Supported knobs (each one exercises a different branch of the reader):
 *  - engine version 2.0 (8-byte numbers, zlib key-info) vs 1.2 (4-byte, raw key-info)
 *  - UTF-8 / UTF-16 key encodings (1- vs 2-byte terminators)
 *  - one or many key blocks (offset accumulation across blocks)
 *  - zlib / stored blocks (`compression type` 2 vs 0)
 *  - `Encrypted="Yes"` record blocks (RIPEMD-128 + XOR stream)
 *
 * ENCRYPTION NOTE: `mdict-analysis` defines `_fast_encrypt` with
 * `previous = b[i] ^ t` while `_fast_decrypt` uses `previous = b[i]` — the two are
 * exact inverses. js-mdict only implements the decrypt direction
 * (`utils.js:235-245`), which is what [MdictCrypto.fastDecrypt] ports; [fastEncrypt]
 * below is the writing side and `MdictCryptoTest` asserts the round-trip.
 */
object MdictFixtureWriter {

    data class Entry(val key: String, val definition: ByteArray) {
        constructor(key: String, definition: String) : this(key, definition.toByteArray(Charsets.UTF_8))
    }

    // ------------------------------------------------------------------ MDX

    fun writeMdx(
        entries: List<Entry>,
        version: String = "2.0",
        encoding: String = "UTF-8",
        keyBlockCount: Int = 1,
        compressKeyInfo: Boolean = true,
        compressKeyBlocks: Boolean = true,
        compressRecordBlocks: Boolean = true,
        encryptRecordBlocks: Boolean = false,
        title: String = "fixture",
    ): ByteArray {
        require(entries.isNotEmpty()) { "a dictionary needs at least one entry" }
        val isV2 = version.toDouble() >= 2.0
        val width = if (isV2) 8 else 4
        val utf16 = encoding.equals("UTF-16", ignoreCase = true)
        val blocks = chunk(entries, keyBlockCount)

        // ---- record section: one record block per key block --------------
        val recordPlains = blocks.map { block -> concat(block.map { it.definition }) }
        val recordEnvelopes = recordPlains.map {
            envelope(it, compressRecordBlocks, encryptRecordBlocks)
        }
        // Global (whole decompressed record space) start offset per entry.
        val startOffsets = LinkedHashMap<String, Long>()
        var recordCursor = 0L
        for ((bi, block) in blocks.withIndex()) {
            for ((ei, entry) in block.withIndex()) {
                startOffsets[key(entry, bi, ei)] = recordCursor
                recordCursor += entry.definition.size.toLong()
            }
        }

        // ---- key blocks --------------------------------------------------
        val keyPlains = ArrayList<ByteArray>()
        for ((bi, block) in blocks.withIndex()) {
            val out = ByteArrayOutputStream()
            for ((ei, entry) in block.withIndex()) {
                out.write(beNumber(startOffsets[key(entry, bi, ei)]!!, width))
                out.write(entry.key.toByteArray(charset(utf16)))
                if (utf16) out.write(byteArrayOf(0, 0)) else out.write(byteArrayOf(0))
            }
            keyPlains.add(out.toByteArray())
        }
        // A key block always carries the 8-byte envelope, even in v1.2
        // (`mdict-base.js:625-651` slices it unconditionally).
        val keyEnvelopes = keyPlains.map { envelope(it, compressKeyBlocks, false) }
        val keyBlockPackedSize = keyEnvelopes.sumOf { it.size.toLong() }

        // ---- key-info table ---------------------------------------------
        val keyInfoRaw = keyInfoTable(blocks, keyEnvelopes, keyPlains, width, isV2, utf16)
        val keyInfo = if (isV2 && compressKeyInfo) {
            val compressed = envelope(keyInfoRaw, compress = true, encrypt = false)
            compressed
        } else {
            keyInfoRaw
        }

        val header = headerXml(
            version = version,
            encoding = encoding,
            encrypted = encryptRecordBlocks,
            title = title,
        )
        val keyHeader = ByteArrayOutputStream().apply {
            write(beNumber(blocks.size.toLong(), width))
            write(beNumber(entries.size.toLong(), width))
            if (isV2) write(beNumber(keyInfoRaw.size.toLong(), width))
            write(beNumber(keyInfo.size.toLong(), width))
            write(beNumber(keyBlockPackedSize, width))
            if (isV2) write(beNumber(adler32(byteArrayOf()), 4)) // key header adler32 (unchecked, like the reference)
        }.toByteArray()

        val recordHeader = ByteArrayOutputStream().apply {
            write(beNumber(recordEnvelopes.size.toLong(), width))
            write(beNumber(entries.size.toLong(), width))
            write(beNumber((recordEnvelopes.size * 2 * width).toLong(), width))
            write(beNumber(recordEnvelopes.sumOf { it.size.toLong() }, width))
        }.toByteArray()

        val recordInfo = ByteArrayOutputStream().apply {
            for ((bi, env) in recordEnvelopes.withIndex()) {
                write(beNumber(env.size.toLong(), width))
                write(beNumber(recordPlains[bi].size.toLong(), width))
            }
        }.toByteArray()

        return concat(
            listOf(
                header, keyHeader, keyInfo,
                concat(keyEnvelopes),
                recordHeader, recordInfo,
                concat(recordEnvelopes),
            )
        )
    }

    private fun keyInfoTable(
        blocks: List<List<Entry>>,
        keyEnvelopes: List<ByteArray>,
        keyPlains: List<ByteArray>,
        width: Int,
        isV2: Boolean,
        utf16: Boolean,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val half = width / 4
        for ((bi, block) in blocks.withIndex()) {
            val first = block.first().key.toByteArray(charset(utf16))
            val last = block.last().key.toByteArray(charset(utf16))
            out.write(beNumber(block.size.toLong(), width))
            out.write(beNumber(storedWordSize(first.size, isV2, utf16).toLong(), half))
            out.write(first)
            out.write(beNumber(storedWordSize(last.size, isV2, utf16).toLong(), half))
            out.write(last)
            out.write(beNumber(keyEnvelopes[bi].size.toLong(), width))
            out.write(beNumber(keyPlains[bi].size.toLong(), width))
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ MDD

    fun writeMdd(
        resources: List<Pair<String, ByteArray>>,
        version: String = "2.0",
        title: String = "fixture-mdd",
    ): ByteArray {
        val entries = resources.map { (k, bytes) -> Entry(k, bytes) }
        // MDD keys are UTF-16LE regardless of the header (`mdict-base.js:418-421`).
        return writeMdx(
            entries = entries,
            version = version,
            encoding = "UTF-16",
            title = title,
        )
    }

    // -------------------------------------------------------------- helpers

    /** Stored word size = the inverse of `mdict-base.js:550-581` (`adjustWordSize`). */
    private fun storedWordSize(byteLength: Int, isV2: Boolean, utf16: Boolean): Int = when {
        isV2 && utf16 -> byteLength / 2 - 1
        isV2 -> byteLength - 1
        utf16 -> byteLength / 2
        else -> byteLength
    }

    /**
     * `[4-byte LE compression type][adler32 of the plaintext][payload]`.
     * Type 0 = stored, type 2 = zlib (`mdict-base.js:632-649`, `mdict.js:134-159`).
     */
    private fun envelope(plain: ByteArray, compress: Boolean, encrypt: Boolean): ByteArray {
        val payload = if (compress) deflate(plain) else plain
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(if (compress) 2 else 0, 0, 0, 0))
        out.write(beNumber(adler32(plain), 4))
        out.write(payload)
        val bytes = out.toByteArray()
        if (!encrypt) return bytes
        // Encryption keeps the 8-byte envelope; the payload key derives from bytes 4..8.
        val key = MdictCryptoKey.fromEnvelope(bytes)
        val encrypted = fastEncrypt(bytes.copyOfRange(8, bytes.size), key)
        return concat(listOf(bytes.copyOfRange(0, 8), encrypted))
    }

    /**
     * The writing counterpart of `utils.js:235` (`fast_decrypt`).
     *
     * IMPORTANT — this is NOT MDict's own `_fast_encrypt`, and deliberately so. The
     * MDict "fast" transform is not a clean inverse pair: its reader feeds back the raw
     * *ciphertext* byte (`previous = b[i]`, `utils.js:242`) while the historical writer
     * fed back `b[i] ^ t`, and the two do not compose back to the plaintext (the
     * nibble swap inside the XOR chain breaks the cancellation). Since no encrypted
     * sample dictionary is available in this workspace and js-mdict cannot read
     * `Encrypted="Yes"` files at all (it throws at `mdict-base.js:444-457`, see
     * [MdictCore]), guessing the writer would only produce a fixture that tests
     * nothing.
     *
     * Instead this function is derived algebraically FROM the ported reader:
     * the reader computes `p[i] = swap(c[i]) ^ P(i) ^ i ^ key[i]` with
     * `P(i) = 0x36` for `i == 0` and `P(i) = c[i-1]` otherwise, so solving for `c[i]`
     * gives `c[i] = swap(p[i] ^ P(i) ^ i ^ key[i])`, a forward recurrence that is
     * exact by construction. `MdictCryptoTest` asserts the round-trip, and
     * `MdxParserTest.encrypted record blocks are decrypted` therefore exercises the
     * real decryption path in [MdictCore.decompressBuff].
     */
    fun fastEncrypt(data: ByteArray, key: ByteArray): ByteArray {
        val out = data.copyOf()
        var previous = 0x36
        for (i in out.indices) {
            val plain = out[i].toInt() and 0xFF
            val mixed = plain xor previous xor (i and 0xFF) xor (key[i % key.size].toInt() and 0xFF)
            val cipher = ((mixed shr 4) or (mixed shl 4)) and 0xFF
            previous = cipher
            out[i] = cipher.toByte()
        }
        return out
    }

    private fun headerXml(version: String, encoding: String, encrypted: Boolean, title: String): ByteArray {
        val attrs = linkedMapOf(
            "GeneratedByEngineVersion" to version,
            "RequiredEngineVersion" to version,
            "Encrypted" to if (encrypted) "Yes" else "No",
            "Encoding" to encoding,
            "Format" to "Html",
            "KeyCaseSensitive" to "No",
            "StripKey" to "Yes",
            "Title" to title,
            "Description" to "p6 dictionary test fixture",
        )
        val xml = "<Dictionary " + attrs.entries.joinToString(" ") { "${it.key}=\"${it.value}\"" } + "/>"
        val bytes = xml.toByteArray(Charsets.UTF_16LE)
        return concat(listOf(beNumber(bytes.size.toLong(), 4), bytes, beNumber(adler32(bytes), 4)))
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16384)
        while (!deflater.finished()) {
            val n = deflater.deflate(buffer)
            out.write(buffer, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }

    private fun adler32(data: ByteArray): Long {
        val adler = Adler32()
        adler.update(data)
        return adler.value
    }

    /** Big-endian integer of `width` bytes, mirroring `utils.js:216` (`b2n`). */
    private fun beNumber(value: Long, width: Int): ByteArray {
        val out = ByteArray(width)
        for (i in 0 until width) {
            out[width - 1 - i] = ((value ushr (8 * i)) and 0xFF).toByte()
        }
        return out
    }

    private fun charset(utf16: Boolean) = if (utf16) Charsets.UTF_16LE else Charsets.UTF_8

    private fun concat(parts: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun <T> chunk(items: List<T>, count: Int): List<List<T>> {
        val size = (items.size + count - 1) / count
        return items.chunked(size.coerceAtLeast(1))
    }

    /** Identity helper: entries are keyed by position so duplicate words stay usable. */
    private fun key(entry: Entry, blockIndex: Int, entryIndex: Int): String = "$blockIndex:$entryIndex:${entry.key}"
}

/** Derives the MDX block key exactly like `utils.js:258-271` (`mdxDecrypt`). */
internal object MdictCryptoKey {
    fun fromEnvelope(envelope: ByteArray): ByteArray {
        val seed = ByteArray(8)
        System.arraycopy(envelope, 4, seed, 0, 4)
        seed[4] = (seed[4].toInt() xor 0x95).toByte()
        seed[5] = (seed[5].toInt() xor 0x36).toByte()
        return Ripemd128.digest(seed)
    }
}
