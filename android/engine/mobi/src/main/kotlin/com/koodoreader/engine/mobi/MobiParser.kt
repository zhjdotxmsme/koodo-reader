package com.koodoreader.engine.mobi

/**
 * Native MOBI / AZW / AZW3 parser (P4, pure JVM).
 *
 * Pipeline:
 * 1. [PalmDatabase] slices the PDB container (78-byte header, 8-byte record
 *    entries — the u32 uniqueID is skipped — and an EOF sentinel for the last
 *    record).
 * 2. [MobiHeader] reads the MOBI header out of record 0 (text encoding,
 *    compression, DRM, first image/non-book records, EXTH flag, KF8 boundary).
 * 3. [Exth] reads the metadata block (title/author/publisher/…, cover offset,
 *    KF8 boundary).
 * 4. Text records are [PalmDoc] decompressed and concatenated, then decoded once
 *    with the header charset (decoding per record would corrupt UTF-8 sequences
 *    that straddle a record boundary).
 * 5. Image records are sniffed from magic bytes ([ImageSniffer]) and the cover is
 *    resolved from EXTH 201 (falling back to the first image).
 *
 * Degradation contract: [parse] returns `null` on any structural surprise and
 * never throws, so a bad book cannot crash the import/reader pipeline; use
 * [parseOrThrow] to surface the typed [KoodoMobiException] subclasses instead.
 *
 * Not implemented (typed error / no cover): HUFF/CDIC compression (17480) and
 * DRM-protected text (`encryptionType != 0`).
 */
object MobiParser {

    /** Parses [file]; returns `null` for anything unreadable. */
    fun parse(file: java.io.File): MobiBook? = parse(file.readBytes())

    /**
     * Parses an in-memory MOBI; returns `null` instead of throwing for every
     * failure (unreadable container, DRM, unsupported compression, …).
     */
    fun parse(bytes: ByteArray): MobiBook? = try {
        parseStrict(bytes)
    } catch (_: KoodoMobiException) {
        null
    }

    /** Like [parse] but lets the typed exceptions escape. */
    fun parseOrThrow(bytes: ByteArray): MobiBook = parseStrict(bytes)

    fun parseOrThrow(file: java.io.File): MobiBook = parseStrict(file.readBytes())

    private fun parseStrict(bytes: ByteArray): MobiBook {
        val db = PalmDatabase.read(bytes)
        if (db.recordCount < 1) {
            throw MalformedMobiException("MobiCapture: no records in PalmDB")
        }
        val record0 = db.records[0]
        val header = MobiHeader.read(record0)

        val exth = Exth.read(record0, header.exthStart, header.exthEnd, header.charset)

        // DRM: the text records are encrypted, refuse explicitly instead of
        // emitting garbage.
        if (header.isEncrypted) {
            throw DrmProtectedException(header.encryptionType)
        }

        val metadata = readMetadata(db, record0, header, exth)

        val text = readText(db, header)

        val resources = extractResources(db, header)
        val cover = resolveCover(resources, header, exth)

        return MobiBook(
            metadata = metadata,
            text = text,
            resources = resources,
            cover = cover,
            compression = header.compression,
            encoding = header.encodingName,
            pdbName = db.name,
            kf8 = metadata.kf8,
        )
    }

    // ---- metadata ----------------------------------------------------------

    private fun readMetadata(
        db: PalmDatabase,
        record0: ByteArray,
        header: MobiHeader,
        exth: Exth?,
    ): MobiMetadata {
        val title = exth?.text(ExthType.TITLE)
            ?: header.let { readFullName(record0, it) }
            ?: db.name.takeIf { it.isNotBlank() }

        // KF8 detection: EXTH 121 / the boundary record field in the header is
        // authoritative; a `BOUNDARY` record is the backup signal kindlegen and
        // calibre write for hybrid MOBI6+KF8 files. Only when neither field
        // exists does the KF8 file version (>= 8) stand in for it.
        val kf8Boundary = exth?.int(ExthType.KF8_BOUNDARY)
        val kf8 = header.declaresKf8Boundary ||
            (kf8Boundary ?: 0) > 0 ||
            hasBoundaryRecord(db, header) ||
            (exth?.first(ExthType.KF8_BOUNDARY) == null && header.fileVersion >= 8)

        return MobiMetadata(
            title = title,
            authors = exth?.texts(ExthType.AUTHOR) ?: emptyList(),
            publisher = exth?.text(ExthType.PUBLISHER),
            description = exth?.text(ExthType.DESCRIPTION),
            isbn = exth?.text(ExthType.ISBN),
            subjects = exth?.texts(ExthType.SUBJECT) ?: emptyList(),
            date = exth?.text(ExthType.PUBLISHING_DATE),
            contributor = exth?.text(ExthType.CONTRIBUTOR),
            language = exth?.text(ExthType.LANGUAGE),
            coverOffset = exth?.int(ExthType.COVER_OFFSET),
            thumbnailOffset = exth?.int(ExthType.THUMBNAIL_OFFSET),
            kf8Boundary = kf8Boundary,
            kf8 = kf8,
        )
    }

    /**
     * The MOBI6 "full name" is a length-prefixed string inside record 0, used as
     * a title fallback when the file has no EXTH 503.
     */
    private fun readFullName(record0: ByteArray, header: MobiHeader): String? {
        val offset = header.fullNameOffset
        if (offset <= 0 || offset >= record0.size) return null
        val r = ByteReader(record0, offset, record0.size)
        val declared = r.u8(0)
        val declaredLength = if (declared and 0x80 != 0) {
            ((declared and 0x7F) shl 8) or r.u8(1)
        } else {
            declared
        }
        val available = minOf(declaredLength, r.length - 1)
        if (available <= 0) return null
        return r.decode(1, available, header.charset).trim().takeIf { it.isNotEmpty() }
    }

    /** A standalone `BOUNDARY` record splits MOBI6 and KF8 content in a hybrid. */
    private fun hasBoundaryRecord(db: PalmDatabase, header: MobiHeader): Boolean {
        val from = (header.firstNonBookIndex).coerceAtLeast(1)
        var i = from
        while (i < db.recordCount) {
            val rec = db.records[i]
            if (rec.size in 8..64 &&
                String(rec, 0, 8, Charsets.US_ASCII) == MobiHeader.BOUNDARY_MARKER
            ) {
                return true
            }
            i++
        }
        return false
    }

    // ---- text --------------------------------------------------------------

    private fun readText(db: PalmDatabase, header: MobiHeader): String {
        if (header.isHuffCdic) {
            // HUFF/CDIC needs the HUFF + CDIC records; declared but not
            // implemented yet so callers get a precise, typed failure.
            throw UnsupportedCompressionException(header.compression)
        }
        val (first, lastExclusive) = textRecordRange(db.recordCount, header)
        if (first >= lastExclusive) {
            throw MalformedMobiException(
                "MobiCapture: no text records (first=$first, last=$lastExclusive, " +
                    "records=${db.recordCount})"
            )
        }

        // The header's `textLength` states how much text the book holds. Records
        // are decoded strictly first; a record whose trailing index data parses
        // as garbage instead falls back to a bounded, lenient decode (see
        // [decodeRecord]) — that is what lets real calibre/kindlegen files, whose
        // trailers are not valid opcode streams, still be read while genuinely
        // damaged records are still rejected.
        val declared = header.textLength ?: Int.MAX_VALUE
        val chunks = ArrayList<ByteArray>(lastExclusive - first)
        var total = 0
        var i = first
        while (i < lastExclusive && total < declared) {
            val decoded = decodeRecord(db.records[i], header.compression, declared - total)
            chunks.add(decoded)
            total += decoded.size
            i++
        }
        if (declared != Int.MAX_VALUE && total < declared / MIN_TEXT_COVERAGE_DIVISOR) {
            throw MalformedMobiException(
                "MobiCapture: text region produced $total of $declared declared bytes"
            )
        }

        val raw = ByteArray(total)
        var at = 0
        for (chunk in chunks) {
            chunk.copyInto(raw, at)
            at += chunk.size
        }
        // Decoding happens once for the whole book: a UTF-8 sequence may straddle
        // a record boundary and would corrupt if each record were decoded alone.
        val text = String(raw, header.charset)
        // Leading NUL padding is never meaningful content.
        return text.trimStart('\u0000')
    }

    /**
     * Decodes one text record.
     *
     * The strict decode is preferred because it is exact. Records written by
     * calibre/kindlegen may be followed by index bytes that the decompressor
     * reads as opcodes, so a strict failure falls back to a bounded, lenient
     * decode which keeps the valid prefix up to `remaining` bytes.
     */
    private fun decodeRecord(record: ByteArray, compression: Int, remaining: Int): ByteArray {
        if (remaining <= 0) return ByteArray(0)
        return when (compression) {
            MobiCompression.NONE -> {
                // Uncompressed records are padded to the record size with NULs.
                var payload = PalmDoc.stripExtras(record)
                while (payload.isNotEmpty() && payload[payload.size - 1] == 0.toByte()) {
                    payload = payload.copyOf(payload.size - 1)
                }
                if (payload.size <= remaining) payload else payload.copyOf(remaining)
            }

            MobiCompression.PALMDOC -> {
                val payload = PalmDoc.stripExtras(record)
                runCatching { PalmDoc.decompress(payload) }
                    .getOrElse { PalmDoc.decompressAtMost(payload, remaining) }
                    .let { if (it.size <= remaining) it else it.copyOf(remaining) }
            }

            else -> throw UnsupportedCompressionException(compression)
        }
    }

    /** Text records are `[1, firstNonBookIndex)`; record 0 is the MOBI header. */
    private fun textRecordRange(recordCount: Int, header: MobiHeader): Pair<Int, Int> {        val first = 1
        val byCount = header.textRecordCount?.let { first + it }
        val byNonBook = header.firstNonBookIndex.takeIf { it in (first + 1)..recordCount }
        val last = minOf(byNonBook ?: recordCount, byCount ?: recordCount, recordCount)
        return first to last
    }

    // ---- resources ---------------------------------------------------------

    /**
     * Image records start at `firstImageRecord` (a direct record index in every
     * calibre/kindlegen file inspected) and run to `firstNonBookIndex` when that
     * boundary is usable, otherwise to EOF. Non-image records (FLIS/FCIS/FDST/
     * DATP/…) in the window are skipped, not reported as resources.
     */
    private fun extractResources(db: PalmDatabase, header: MobiHeader): List<MobiResource> {
        val first = header.firstImageRecord
        if (first <= 0 || first >= db.recordCount) return emptyList()
        val nonBook = header.firstNonBookIndex
        val lastExclusive = if (nonBook in (first + 1)..db.recordCount) nonBook else db.recordCount

        val out = ArrayList<MobiResource>()
        var i = first
        while (i < lastExclusive) {
            val record = db.records[i]
            val mediaType = ImageSniffer.mediaType(record)
            if (mediaType != null) {
                out.add(MobiResource(i, mediaType, record))
            }
            i++
        }
        return out
    }

    /**
     * Cover resolution, in order of authority:
     * 1. EXTH 201 (offset relative to the first image record);
     * 2. EXTH 202 (thumbnail) when 201 is missing/broken;
     * 3. the first image record in the file.
     */
    private fun resolveCover(
        resources: List<MobiResource>,
        header: MobiHeader,
        exth: Exth?,
    ): ByteArray? {
        val base = header.firstImageRecord
        val coverOffset = exth?.int(ExthType.COVER_OFFSET)
        val thumbnailOffset = exth?.int(ExthType.THUMBNAIL_OFFSET)

        if (base > 0 && coverOffset != null && coverOffset >= 0) {
            byIndex(resources, base + coverOffset)?.let { return it }
        }
        if (base > 0 && thumbnailOffset != null && thumbnailOffset >= 0) {
            byIndex(resources, base + thumbnailOffset)?.let { return it }
        }
        return resources.firstOrNull()?.data
    }

    private fun byIndex(resources: List<MobiResource>, index: Int): ByteArray? =
        resources.firstOrNull { it.index == index }?.data

    /**
     * How far short of `textLength` the decoded text may fall before the book is
     * considered corrupt (record trailers are occasionally unavoidable losses).
     */
    private const val MIN_TEXT_COVERAGE_DIVISOR = 2
}
