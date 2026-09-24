package com.koodoreader.engine.mobi

/**
 * PalmDB (PDB) container — the outer shell of every MOBI/AZW/AZW3 file.
 *
 * Layout used here (all multi-byte integers big-endian):
 * ```
 * 0x00  32  database name (NUL padded)
 * 0x20   2  attributes     0x22  2  version
 * 0x24   4  creation date  0x28  4  modification date
 * 0x2C   4  last backup    0x30  4  modification number
 * 0x34   4  app info offset 0x38 4  sort info offset
 * 0x3C   8  type + creator  ("BOOK" + "MOBI", or "TEXt" + "REAd")
 * 0x44   4  uniqueID seed
 * 0x48   4  next record list id
 * 0x4C   2  number of records
 * 0x4E 8*n record entries: u32 offset | u32 uniqueID   <- uid MUST be skipped
 *       2  gap (filler) after the record table
 * ```
 * Each record runs from its own offset to the next entry's offset; the last one
 * ends at EOF (the "EOF sentinel").
 */
class PalmDatabase private constructor(
    val name: String,
    val type: String,
    val creator: String,
    val records: List<ByteArray>,
) {
    val recordCount: Int get() = records.size

    /** True for the `BOOKMOBI` type+creator pair (MOBI/AZW/AZW3). */
    val isMobi: Boolean get() = type == TYPE_BOOK && creator == CREATOR_MOBI

    /** True for old `TEXtREAd` PalmDOC files, which share the PalmDOC payload. */
    val isPalmDoc: Boolean get() = type == TYPE_TEXT && creator == CREATOR_READ

    /** True when this container can hold MOBI content at all. */
    val isSupportedContainer: Boolean get() = isMobi || isPalmDoc

    override fun toString(): String =
        "PalmDatabase(name=$name, type=$type, creator=$creator, records=$recordCount)"

    companion object {
        const val TYPE_BOOK = "BOOK"
        const val CREATOR_MOBI = "MOBI"
        const val TYPE_TEXT = "TEXt"
        const val CREATOR_READ = "REAd"

        /** Fixed PalmDB header size, up to and including the record count. */
        const val HEADER_SIZE = 78

        /** Bytes per record table entry: 4 offset + 4 uniqueID. */
        const val RECORD_ENTRY_SIZE = 8

        /**
         * Reads and slices a PalmDB container.
         *
         * @throws MalformedMobiException when the header is truncated, the
         *   type/creator pair is not MOBI / PalmDOC, or the record table points
         *   outside the file.
         */
        fun read(bytes: ByteArray): PalmDatabase {
            if (bytes.size < HEADER_SIZE) {
                throw MalformedMobiException(
                    "MobiCapture: file too short for a PalmDB header (${bytes.size} bytes)"
                )
            }
            val name = readName(bytes)
            val type = ascii(bytes, 60, 4)
            val creator = ascii(bytes, 64, 4)
            if (type != TYPE_BOOK && type != TYPE_TEXT) {
                throw MalformedMobiException("MobiCapture: not a PalmDB (type='$type')")
            }
            if (creator != CREATOR_MOBI && creator != CREATOR_READ) {
                throw MalformedMobiException("MobiCapture: not a PalmDB (creator='$creator')")
            }

            val recordCount = u16(bytes, 76)
            if (recordCount <= 0) {
                throw MalformedMobiException("MobiCapture: PalmDB declares no records")
            }
            val tableEnd = HEADER_SIZE.toLong() + recordCount.toLong() * RECORD_ENTRY_SIZE
            if (tableEnd > bytes.size) {
                throw MalformedMobiException(
                    "MobiCapture: truncated record table ($recordCount records, " +
                        "file ${bytes.size} bytes)"
                )
            }

            // Each entry is 8 bytes: a big-endian u32 offset followed by a u32
            // uniqueID. Skipping the wrong 4 bytes here is the classic MOBI
            // parser bug — always advance a full entry.
            val offsets = IntArray(recordCount + 1)
            var i = 0
            while (i < recordCount) {
                val entry = HEADER_SIZE + i * RECORD_ENTRY_SIZE
                offsets[i] = u32(bytes, entry)
                // bytes[entry + 4 .. entry + 7] = uniqueID, intentionally unused.
                i++
            }
            // EOF sentinel: the last record extends to the end of the file.
            offsets[recordCount] = bytes.size

            i = 0
            while (i < recordCount) {
                val start = offsets[i]
                val end = offsets[i + 1]
                if (start < 0 || start > bytes.size) {
                    throw MalformedMobiException(
                        "MobiCapture: record $i offset $start outside the file (${bytes.size})"
                    )
                }
                if (end < start) {
                    throw MalformedMobiException(
                        "MobiCapture: record table not monotonic at $i ($start > $end)"
                    )
                }
                i++
            }

            val records = ArrayList<ByteArray>(recordCount)
            i = 0
            while (i < recordCount) {
                records.add(bytes.copyOfRange(offsets[i], offsets[i + 1]))
                i++
            }
            return PalmDatabase(name, type, creator, records)
        }

        /** Convenience wrapper: `null` instead of a typed exception. */
        fun tryRead(bytes: ByteArray): PalmDatabase? = try {
            read(bytes)
        } catch (_: KoodoMobiException) {
            null
        }

        /** Builds an in-memory container (used by tests and by callers that
         *  already hold the records). */
        fun of(name: String, type: String, creator: String, records: List<ByteArray>): PalmDatabase =
            PalmDatabase(name, type, creator, records)

        private fun readName(bytes: ByteArray): String {
            var end = 0
            while (end < 32 && end < bytes.size && bytes[end] != 0.toByte()) end++
            return String(bytes, 0, end, Charsets.ISO_8859_1)
        }

        private fun ascii(bytes: ByteArray, offset: Int, length: Int): String {
            if (offset < 0 || offset + length > bytes.size) return ""
            return String(bytes, offset, length, Charsets.US_ASCII)
        }

        private fun u16(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

        private fun u32(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
    }
}
