package com.koodoreader.core.importer

import java.io.File
import java.io.RandomAccessFile

/**
 * MOBI/AZW/AZW3 cover extraction (P1 收口) — pure JVM, lenient: any
 * structural surprise degrades to null (book still imports, placeholder
 * cover).
 *
 * Layout (PalmDB + MOBI + EXTH):
 *  - PDB header: type/creator "BOOKMOBI" at 60, record count u16 at 76,
 *    record offsets (u32 BE, offset in the high 3 bytes) from 78;
 *  - record 0 holds the MOBI header: "MOBI" magic at 16, header length u32
 *    at 20, first-image-record u32 at 108 (needs header length ≥ 112),
 *    EXTH flags u32 at 128 (bit 0x40 = EXTH present, needs ≥ 132);
 *  - EXTH at 16 + headerLength: "EXTH" magic, total length u32, record count
 *    u32, then (type u32, len u32, data) records — type 201 carries the
 *    cover offset relative to the first image record;
 *  - the cover image spans record[firstImage + coverOffset] until the next
 *    record (or EOF); format sniffed from magic bytes.
 */
object MobiCover {

    fun extract(file: File): BookCover? = try {
        extractInternal(file).also {
            java.io.File("build/mobi-trace.txt").appendText("result=${it != null}\n")
        }
    } catch (t: Throwable) {
        java.io.File("build/mobi-trace.txt").appendText("THROWN: $t\n")
        null
    }

    private fun extractInternal(file: File): BookCover? {
        RandomAccessFile(file, "r").use { raf ->
            val length = file.length()
            if (length < 132) {
                java.io.File("build/mobi-trace.txt").appendText("trace: too short\n")
                return null
            }

            raf.seek(60)
            val typeCreator = ByteArray(8).also { raf.readFully(it) }
            if (String(typeCreator, 0, 8) != "BOOKMOBI") {
                java.io.File("build/mobi-trace.txt").appendText("trace: not BOOKMOBI\n")
                return null
            }

            raf.seek(76)
            val recordCount = readU16(raf)
            if (recordCount < 2) return null

            // Record offset table: 8 bytes per entry — u32 BE offset
            // (3-byte offset + 1 attribute byte) followed by a 4-byte
            // uniqueID that must be skipped.
            raf.seek(78)
            val offsets = IntArray(recordCount + 1) // extra slot = EOF sentinel
            var i = 0
            while (i < recordCount) {
                offsets[i] = readU32(raf).toInt()
                raf.skipBytes(4) // uniqueID
                i++
            }
            offsets[recordCount] = length.toInt()

            val record0 = readRecord(raf, offsets, 0) ?: return null
            if (record0.size < 132 || String(record0, 16, 4) != "MOBI") return null

            val headerLength = u32(record0, 20).toInt()
            if (headerLength < 132 || 16 + headerLength > record0.size) return null

            val exthFlags = u32(record0, 128)
            if (exthFlags and 0x40L == 0L) return null

            // Field offsets below are relative to record0 (calibre parity,
            // verified against a real file): first-image-record at record0+108,
            // EXTH flags at record0+128, EXTH at record0+16+headerLength.
            val firstImageRecord = if (headerLength >= 112) u32(record0, 108).toInt() else 0

            // ---- EXTH records ----
            var coverOffset = -1
            var p = 16 + headerLength
            val exthHeader = "EXTH".toByteArray(Charsets.US_ASCII)
            if (p + 12 <= record0.size &&
                record0.copyOfRange(p, p + 4).contentEquals(exthHeader)
            ) {
                val exthLength = u32(record0, p + 4).toInt()
                val recordCountExth = u32(record0, p + 8).toInt()
                var q = p + 12
                val exthEnd = minOf(p + exthLength, record0.size)
                var n = 0
                while (n < recordCountExth && q + 8 <= exthEnd) {
                    val type = u32(record0, q)
                    val len = u32(record0, q + 4).toInt()
                    if (len < 8 || q + len > exthEnd) break
                    if (type == 201L && len >= 12) {
                        coverOffset = u32(record0, q + 8).toInt()
                    }
                    q += len
                    n++
                }
            }
            if (coverOffset < 0) {
                java.io.File("build/mobi-trace.txt").appendText("trace: coverOffset<0\n")
                return null
            }

            val imageIndex = firstImageRecord + coverOffset
            if (imageIndex <= 0 || imageIndex >= recordCount) return null
            val image = readRecord(raf, offsets, imageIndex) ?: return null
            val ext = sniffImageExt(image) ?: return null
            return BookCover(image, ext)
        }
    }

    // ---- helpers -----------------------------------------------------------

    private fun readRecord(raf: RandomAccessFile, offsets: IntArray, index: Int): ByteArray? {
        val start = offsets.getOrNull(index) ?: return null
        val end = offsets.getOrNull(index + 1) ?: return null
        if (start < 0 || end <= start) return null
        raf.seek(start.toLong())
        return ByteArray(end - start).also { raf.readFully(it) }
    }

    private fun readU16(raf: RandomAccessFile): Int {
        val b = ByteArray(2).also { raf.readFully(it) }
        return ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
    }

    private fun readU32(raf: RandomAccessFile): Long {
        val b = ByteArray(4).also { raf.readFully(it) }
        return u32(b, 0)
    }

    private fun u32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)

    private fun sniffImageExt(b: ByteArray): String? = when {
        b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "jpeg"
        b.size >= 4 && b[0] == 0x89.toByte() && b[1] == 0x50.toByte() &&
            b[2] == 0x4E.toByte() && b[3] == 0x47.toByte() -> "png"
        b.size >= 3 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() &&
            b[2] == 'F'.code.toByte() -> "gif"
        else -> null
    }
}
