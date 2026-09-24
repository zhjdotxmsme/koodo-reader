package com.koodoreader.engine.mobi

import java.io.ByteArrayOutputStream

/**
 * Builds synthetic MOBI files byte by byte so the tests can exercise exactly the
 * structures they care about (record tables with uids, PalmDOC opcode streams,
 * EXTH records, DRM flags, truncated files) without depending on a real sample.
 *
 * Layout produced:
 * ```
 * PalmDB header (78 bytes) | record table (8 bytes per record) | 2-byte gap
 * record 0: PalmDOC header (16 bytes) + MOBI header + EXTH block
 * record 1..: text / image / FLIS records
 * ```
 */
object TestMobiBuilder {

    /** `byteArrayOf(0xFF, …)` needs Byte arguments on Kotlin 1.9 — this helper is not. */
    fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    fun ascii(s: String): ByteArray = s.toByteArray(Charsets.US_ASCII)

    /** Encodes a MOBI variable-length quantity (7 bits per byte, big-endian). */
    fun vlq(value: Int): ByteArray {
        if (value == 0) return bytes(0)
        val groups = ArrayList<Int>(4)
        var v = value
        while (v != 0) {
            groups.add(v and 0x7F)
            v = v ushr 7
        }
        val out = ByteArray(groups.size)
        var i = 0
        while (i < groups.size) {
            val group = groups[groups.size - 1 - i]
            out[i] = (group or if (i < groups.size - 1) 0x80 else 0).toByte()
            i++
        }
        return out
    }

    /** A text record: `<VLQ extra length><payload><trailer>`. */
    fun textRecord(payload: ByteArray, extra: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        out.writeBytes(vlq(extra))
        out.writeBytes(payload)
        out.writeBytes(ByteArray(extra))
        return out.toByteArray()
    }

    /** An EXTH record: `type | length | data`. */
    fun exthRecord(type: Int, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.writeBytes(u32(type))
        out.writeBytes(u32(data.size + 8))
        out.writeBytes(data)
        return out.toByteArray()
    }

    fun exthString(type: Int, value: String): ByteArray =
        exthRecord(type, value.toByteArray(Charsets.UTF_8))

    fun exthInt(type: Int, value: Int): ByteArray = exthRecord(type, u32(value))

    fun u16(value: Int): ByteArray = bytes((value ushr 8) and 0xFF, value and 0xFF)

    fun u32(value: Int): ByteArray =
        bytes((value ushr 24) and 0xFF, (value ushr 16) and 0xFF, (value ushr 8) and 0xFF, value and 0xFF)

    /** A tiny but structurally valid PNG (never decoded by the tests). */
    val PNG: ByteArray = bytes(
        0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
        0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4,
        0x89, 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41,
        0x54, 0x78, 0x9C, 0x63, 0x00, 0x01, 0x00, 0x00,
        0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4, 0x00,
        0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE,
        0x42, 0x60, 0x82,
    )

    /** A tiny but structurally valid JPEG (SOI + APP0 + EOI). */
    val JPEG: ByteArray = bytes(
        0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46,
        0x49, 0x46, 0x00, 0x01, 0x01, 0x01, 0x00, 0x64,
        0x00, 0x64, 0x00, 0x00, 0xFF, 0xD9,
    )

    val FLIS: ByteArray = ascii("FLIS") + ByteArray(32)

    /**
     * Options for the synthetic record 0. `null` means "derive a sensible value
     * from the record layout" (see [build]).
     */
    data class Options(
        val compression: Int = MobiCompression.PALMDOC,
        val encryptionType: Int = 0,
        val textEncoding: Int = 65001,
        val fileVersion: Int = 6,
        val mobiType: Int = 2,
        val headerLength: Int = 264,
        val textLengthOverride: Int? = null,
        val recordCountOverride: Int? = null,
        val firstNonBookIndex: Int? = null,
        val firstImageRecord: Int? = null,
        val kf8Boundary: Int? = null,
        val fullName: String? = null,
        val exthRecords: List<ByteArray> = emptyList(),
        val exthFlagsOverride: Int? = null,
        val drmOffset: Int = -1,
        val drmRecordCount: Int = 0,
        /** Offsets patched into the record table instead of the real ones. */
        val recordOffsetOverrides: Map<Int, Int>? = null,
    )

    /**
     * Assembles a complete MOBI byte image.
     *
     * @param textRecords raw MOBI text records (use [textRecord] /
     *   [palmDocRecord] to prepend the VLQ extra-length header).
     * @param trailingRecords records after the text region (images, FLIS, …).
     * @param decompressedLength value for the `textLength` header field.
     */
    fun build(
        textRecords: List<ByteArray>,
        options: Options = Options(),
        trailingRecords: List<ByteArray> = emptyList(),
        decompressedLength: Int? = null,
    ): ByteArray {
        val allRecords = ArrayList<ByteArray>(1 + textRecords.size + trailingRecords.size)
        allRecords.add(ByteArray(0)) // placeholder for record 0
        allRecords.addAll(textRecords)
        allRecords.addAll(trailingRecords)
        val count = allRecords.size

        val firstTextRecord = 1
        val firstNonBook = options.firstNonBookIndex
            ?: (firstTextRecord + textRecords.size).coerceAtMost(count)
        val firstImage = options.firstImageRecord
            ?: if (textRecords.size < count - 1) firstTextRecord + textRecords.size else 0

        // ---- EXTH block -----------------------------------------------------
        val exthBody = ByteArrayOutputStream().also { body ->
            options.exthRecords.forEach { body.writeBytes(it) }
        }.toByteArray()
        val exthBlock: ByteArray = if (exthBody.isNotEmpty()) {
            ByteArrayOutputStream().also {
                it.writeBytes(ascii("EXTH"))
                it.writeBytes(u32(exthBody.size + 12))
                it.writeBytes(u32(options.exthRecords.size))
                it.writeBytes(exthBody)
            }.toByteArray()
        } else {
            ByteArray(0)
        }

        // ---- record 0 -------------------------------------------------------
        val headerLength = options.headerLength
        // record 0 = PalmDOC header (16) + MOBI header (headerLength, measured
        // from the "MOBI" magic) + EXTH block + optional MOBI6 full name.
        val fullNameBytes = options.fullName?.let {
            val encoded = it.toByteArray(Charsets.UTF_8)
            byteArrayOf(encoded.size.toByte()) + encoded
        }
        val record0 = ByteArray(
            16 + headerLength + exthBlock.size + (fullNameBytes?.size ?: 0)
        )
        // The 16-byte PalmDOC header: compression(2) unused(2) textLength(4)
        // textRecordCount(2) textRecordSize(2) encryptionType(2) unused(2).
        // The text length is stored high-word-first (verified against real
        // calibre files: 00 00 04 75 for 1141).
        val textLength = options.textLengthOverride ?: decompressedLength ?: 0
        val palmDocHeader = ByteArrayOutputStream().also { h ->
            h.writeBytes(u16(options.compression))
            h.writeBytes(u16(0)) // unused
            h.writeBytes(u16((textLength ushr 16) and 0xFFFF))
            h.writeBytes(u16(textLength and 0xFFFF))
            h.writeBytes(u16(options.recordCountOverride ?: textRecords.size))
            h.writeBytes(u16(4096))
            h.writeBytes(u16(options.encryptionType))
            h.writeBytes(u16(0)) // unused
        }.toByteArray()
        require(palmDocHeader.size == 16) {
            "PalmDOC header must be 16 bytes, was ${palmDocHeader.size}"
        }
        palmDocHeader.copyInto(record0, 0)

        // ---- MOBI header (written at explicit record-0 offsets) -------------
        val exthFlags = options.exthFlagsOverride
            ?: if (exthBlock.isNotEmpty()) MobiHeader.EXTH_FLAG.toInt() else 0
        // `headerLength` is the raw MOBI header-length field: the number of bytes
        // from the "MOBI" magic to the end of the header, so the EXTH block (and
        // any full-name string) starts at 16 + headerLength. Real calibre files
        // use 232 for MOBI6 and 264 for KF8.
        require(headerLength in 232..65519) {
            "this builder needs headerLength in 232..65519 (got $headerLength)"
        }
        fun at(offset: Int, b: ByteArray) {
            require(offset + b.size <= record0.size) {
                "record 0 write at $offset (${b.size} bytes) exceeds ${record0.size}"
            }
            b.copyInto(record0, offset)
        }
        at(16, ascii(MobiHeader.MAGIC))
        // The raw field is the byte count from the MOBI magic, so the EXTH
        // block lands at 16 + headerLength.
        at(20, u32(headerLength))
        at(24, u32(options.mobiType))
        at(28, u32(options.textEncoding))
        at(32, u32(12345)) // uniqueID
        at(36, u32(options.fileVersion))
        at(80, u32(firstNonBook))
        val fullNameOffsetAt = 84
        at(108, u32(firstImage))
        at(128, u32(exthFlags))
        at(168, u32(options.drmOffset))
        at(172, u32(options.drmRecordCount))
        at(196, u32(0xFFFFFFFF.toInt())) // first content record
        at(200, u32(0xFFFFFFFF.toInt())) // last content record
        at(204, u32(0xFFFFFFFF.toInt())) // FCIS record
        at(208, u32(0xFFFFFFFF.toInt())) // FLIS record
        at(224, u32(options.kf8Boundary ?: 0xFFFFFFFF.toInt()))
        // Everything between the fields listed above stays zero, which is exactly
        // what a minimal MOBI header looks like.
        require(16 + headerLength <= record0.size) { "record 0 is smaller than its header" }
        if (exthBlock.isNotEmpty()) {
            at(16 + headerLength, exthBlock)
        }
        // Optional MOBI6 "full name" string living inside record 0.
        if (fullNameBytes != null) {
            val nameAt = 16 + headerLength + exthBlock.size
            at(nameAt, fullNameBytes)
            at(fullNameOffsetAt, u32(nameAt))
        }
        allRecords[0] = record0

        // ---- assemble -------------------------------------------------------
        // Compute absolute offsets up front so the record table and the emitted
        // bytes cannot drift apart: record 0 starts right after the header,
        // record table and its 2-byte gap, and every record is followed by a
        // 2-byte gap that the next record's offset skips.
        val tableStart = PalmDatabase.HEADER_SIZE
        val firstRecordAt = tableStart + count * PalmDatabase.RECORD_ENTRY_SIZE + 2
        val realOffsets = IntArray(count)
        var at = firstRecordAt
        var i = 0
        while (i < count) {
            realOffsets[i] = at
            at += allRecords[i].size + 2
            i++
        }
        val totalSize = at
        check(totalSize == firstRecordAt + allRecords.sumOf { it.size } + count * 2) {
            "builder offset bookkeeping is inconsistent"
        }

        val out = ByteArray(totalSize)
        var p = 0
        fun emit(b: ByteArray) {
            b.copyInto(out, p)
            p += b.size
        }
        emit(ascii("TestBook"))
        emit(ByteArray(24)) // 32-byte NUL-padded name
        emit(u16(0)) // attributes
        emit(u16(0)) // version
        emit(ByteArray(12)) // creation / modification / backup dates
        emit(u32(0)) // modification number
        emit(u32(0)) // app info offset
        emit(u32(0)) // sort info offset
        emit(ascii("BOOK"))
        emit(ascii("MOBI"))
        emit(u32(1)) // uniqueID seed
        emit(u32(0)) // next record list id
        emit(u16(count)) // 76
        i = 0
        while (i < count) {
            emit(u32(options.recordOffsetOverrides?.get(i) ?: realOffsets[i]))
            // uniqueID: a parser that forgets to skip these 4 bytes reads them as
            // the next record offset and fails loudly.
            emit(u32(i * 2))
            i++
        }
        emit(u16(0)) // 2-byte gap after the record table
        i = 0
        while (i < count) {
            check(p == realOffsets[i]) { "record $i emitted at $p, table says ${realOffsets[i]}" }
            emit(allRecords[i])
            emit(u16(0)) // per-record trailing gap (record table points past it)
            i++
        }
        check(p == out.size) { "wrote $p bytes of ${out.size}" }
        return out
    }

    /**
     * A MOBI text record: the compressed PalmDOC stream followed by [extra]
     * trailer bytes. Real calibre/kindlegen records start directly with the
     * stream (there is no leading length prefix), which is what the engine's
     * `textLength`-bounded reader depends on.
     */
    fun palmDocRecord(payload: ByteArray, extra: Int = 0): ByteArray = payload + ByteArray(extra)

    /**
     * An uncompressed (compression type 1) text record: the raw text padded with
     * NULs to the PalmDOC record size, exactly like a real writer emits.
     */
    fun uncompressedRecord(payload: ByteArray, recordSize: Int = 4096): ByteArray {
        val out = ByteArrayOutputStream()
        out.writeBytes(payload)
        var written = payload.size
        while (written < recordSize) {
            val chunk = minOf(1024, recordSize - written)
            out.writeBytes(ByteArray(chunk))
            written += chunk
        }
        return out.toByteArray()
    }

    // ---- PalmDOC opcode helpers -------------------------------------------

    /** A literal run of 1..8 bytes: opcode + the bytes themselves. */
    fun literals(vararg values: Int): ByteArray {
        require(values.size in 1..8) { "literal run must be 1..8 bytes" }
        return bytes(values.size, *values)
    }

    /** Two-byte back reference: distance 1..2047, length 3..10. */
    fun backReference(distance: Int, length: Int): ByteArray {
        require(distance in 1..0x7FF) { "distance must be 1..2047" }
        require(length in 3..10) { "length must be 3..10" }
        // PalmDOC packs both into the pair: 0x8000 | (distance << 3) | (length - 3)
        return u16(0x8000 or (distance shl 3) or (length - 3))
    }

    /** `0xC0..0xFF` opcode for "space + (byte XOR 0x80)". */
    fun spacePlus(ch: Char): Int {
        require(ch.code in 0x00..0x7F) { "spacePlus needs an ASCII character" }
        // (opcode ^ 0x80) must give the character back, so the opcode is 0x80 ^ ch.
        return 0x80 xor ch.code
    }

    /** Assembles a PalmDOC opcode stream from raw opcode fragments. */
    fun opcodes(vararg fragments: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        fragments.forEach { out.writeBytes(it) }
        return out.toByteArray()
    }
}
