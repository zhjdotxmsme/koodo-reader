package com.koodoreader.engine.mobi

/**
 * The MOBI header that lives at the start of record 0 (right after the 16-byte
 * PalmDOC header).
 *
 * Offsets are relative to the start of the *record*, matching the layout used by
 * mobipocket/calibre and the existing `core/importer` cover reader:
 * ```
 *  0  compression (u16)              2  unused (u16)
 *  4  textLength (u32)               8  textRecordCount (u16)
 * 10  textRecordSize (u16)          12  encryptionType (u16)
 * 16  "MOBI"                         20  headerLength (measured from the magic)
 * 24  mobiType                       28  textEncoding
 * 32  uniqueID                       36  fileVersion
 * 80  firstNonBookIndex              84/88 fullNameOffset / fullNameLength
 * 108 firstImageRecord               112/116 huffRecordOffset / huffRecordCount
 * 128 EXTH flags (bit 0x40 = EXTH present)
 * 168/172 DRM offset / record count
 * 224 KF8 boundary record index
 * ```
 * The EXTH block starts at `16 + headerLength`.
 */
data class MobiHeader(
    val headerLength: Int,
    val mobiType: Int,
    val textEncoding: Int,
    val fileVersion: Int,
    /** Decompressed length of the whole book text; `null` when implausible. */
    val textLength: Int?,
    /** Number of text records, or `null` when the field is nonsense. */
    val textRecordCount: Int?,
    val textRecordSize: Int,
    val compression: Int,
    val encryptionType: Int,
    val firstNonBookIndex: Int,
    val fullNameOffset: Int,
    val fullNameLength: Int,
    val firstImageRecord: Int,
    val huffRecordOffset: Int,
    val huffRecordCount: Int,
    val exthFlags: Long,
    val drmOffset: Int,
    val drmRecordCount: Int,
    val kf8BoundaryRecord: Int?,
    val hasExth: Boolean,
    /** EXTH block window inside record 0, or `null` when absent/truncated. */
    val exthStart: Int?,
    val exthEnd: Int?,
) {
    val charset: java.nio.charset.Charset
        get() = when (textEncoding) {
            1252 -> CP1252
            65001 -> Charsets.UTF_8
            else -> Charsets.UTF_8
        }

    val encodingName: String
        get() = when (textEncoding) {
            1252 -> "windows-1252"
            65001 -> "UTF-8"
            else -> "UTF-8"
        }

    /** DRM protected: the text records are encrypted and cannot be read. */
    val isEncrypted: Boolean get() = encryptionType != MobiEncryption.NONE

    val isHuffCdic: Boolean get() = compression == MobiCompression.HUFF_CDIC

    /**
     * Header-declared KF8 marker (EXTH 121 / boundary record field). Calibre and
     * kindlegen both set it for AZW3; a `BOUNDARY` record is the backup signal.
     */
    val declaresKf8Boundary: Boolean
        get() = (kf8BoundaryRecord ?: 0) > 0

    companion object {
        const val MAGIC = "MOBI"
        const val MAGIC_OFFSET = 16
        const val EXTH_FLAG = 0x40L

        /** Minimum header length that still contains the EXTH flag field. */
        const val MIN_HEADER_LENGTH = 132

        const val BOUNDARY_MARKER = "BOUNDARY"

        fun read(record0: ByteArray): MobiHeader {
            val r = ByteReader(record0)
            if (!r.matches(MAGIC_OFFSET, MAGIC)) {
                throw MalformedMobiException("MobiCapture: record 0 has no MOBI header")
            }
            val headerLength = r.u32AsInt(20)
            if (headerLength < MIN_HEADER_LENGTH) {
                throw MalformedMobiException(
                    "MobiCapture: MOBI headerLength $headerLength is too small"
                )
            }
            if (MAGIC_OFFSET + headerLength > record0.size) {
                throw MalformedMobiException(
                    "MobiCapture: MOBI header ($headerLength) exceeds record 0 (${record0.size})"
                )
            }

            val textLengthRaw = r.u32(4).toInt()
            val textRecordCountRaw = r.u16(8)
            val encryptionType = r.u16(12)
            val firstImage = if (headerLength >= 112) r.u32AsInt(108) else 0
            val huffOffset = if (headerLength >= 116) r.u32AsInt(112) else -1
            val huffCount = if (headerLength >= 120) r.u32AsInt(116) else 0
            val drmOffset = if (headerLength >= 172) r.u32AsInt(168) else -1
            val drmCount = if (headerLength >= 176) r.u32AsInt(172) else 0
            val kf8Boundary = if (headerLength >= 228) r.u32AsInt(224) else null

            // EXTH block: right after 16 + headerLength.
            var exthStart: Int? = null
            var exthEnd: Int? = null
            val exthFlags = r.u32(128)
            if (exthFlags and EXTH_FLAG != 0L) {
                val p = MAGIC_OFFSET + headerLength
                if (p + 12 <= record0.size && r.matches(p, "EXTH")) {
                    val declared = r.u32AsInt(p + 4)
                    exthStart = p
                    exthEnd = if (declared >= 12 && p + declared <= record0.size) {
                        p + declared
                    } else {
                        record0.size
                    }
                }
            }

            return MobiHeader(
                headerLength = headerLength,
                mobiType = r.u32AsInt(24),
                textEncoding = r.u32AsInt(28),
                fileVersion = r.u32AsInt(36),
                // 0 or a wildly large value means the field is unusable; the
                // caller falls back to "all text records".
                textLength = textLengthRaw.takeIf { it in 1..MAX_TEXT_LENGTH },
                textRecordCount = textRecordCountRaw.takeIf { it in 1..MAX_TEXT_RECORDS },
                textRecordSize = r.u16(10),
                // Compression is a u16 at record-0 offset 0; reading it as a u32
                // would fold the following "unused" word into the high bits.
                compression = r.u16(0),
                encryptionType = encryptionType,
                firstNonBookIndex = r.u32AsInt(80),
                fullNameOffset = r.u32AsInt(84),
                fullNameLength = r.u32AsInt(88),
                firstImageRecord = firstImage,
                huffRecordOffset = huffOffset,
                huffRecordCount = huffCount,
                exthFlags = exthFlags,
                drmOffset = drmOffset,
                drmRecordCount = drmCount,
                kf8BoundaryRecord = kf8Boundary?.takeIf { it > 0 },
                hasExth = exthStart != null,
                exthStart = exthStart,
                exthEnd = exthEnd,
            )
        }

        /** 64 MB of text is already an absurd ebook. */
        const val MAX_TEXT_LENGTH = 64 * 1024 * 1024

        /** Record counts above this are certainly corrupt. */
        const val MAX_TEXT_RECORDS = 1 shl 16

        /** `Charsets.WINDOWS_1252` is Kotlin/JVM-only; the engine stays portable. */
        val CP1252: java.nio.charset.Charset = java.nio.charset.Charset.forName("windows-1252")
    }
}
