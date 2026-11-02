package com.koodoreader.engine.mobi

import java.io.File
import java.io.RandomAccessFile

/** Reads only the PalmDB header/table and a bounded record 0, never body or resource records. */
object MobiMetadataReader {
    internal const val MAX_RECORD0_BYTES = 1024 * 1024

    /** Returns null when the container or record 0 is unreadable; EXTH is independent of body/DRM. */
    fun read(file: File): MobiMetadata? = try {
        RandomAccessFile(file, "r").use(::readMetadata)
    } catch (_: Exception) {
        null
    }

    internal fun read(input: RandomAccessFile): MobiMetadata? = try {
        readMetadata(input)
    } catch (_: Exception) {
        null
    }

    private fun readMetadata(input: RandomAccessFile): MobiMetadata {
        val header = readBytes(input, 0, PalmDatabase.HEADER_SIZE)
        val type = ascii(header, 60, 4)
        val creator = ascii(header, 64, 4)
        if ((type != PalmDatabase.TYPE_BOOK || creator != PalmDatabase.CREATOR_MOBI) &&
            (type != PalmDatabase.TYPE_TEXT || creator != PalmDatabase.CREATOR_READ)
        ) {
            throw MalformedMobiException("MobiCapture: not a PalmDB (type='$type')")
        }
        val recordCount = u16(header, 76)
        if (recordCount <= 0) throw MalformedMobiException("MobiCapture: PalmDB declares no records")

        val table = readBytes(input, PalmDatabase.HEADER_SIZE.toLong(), recordCount * 8)
        val record0Start = u32(table, 0)
        val record0End = if (recordCount > 1) u32(table, 8) else input.length()
        val record0Size = record0End - record0Start
        if (record0Size <= 0 || record0Size > MAX_RECORD0_BYTES) {
            throw MalformedMobiException("MobiCapture: record 0 exceeds metadata bound ($record0Size)")
        }
        val record0 = readBytes(input, record0Start, record0Size.toInt())
        val mobiHeader = MobiHeader.read(record0)
        val exth = Exth.read(record0, mobiHeader.exthStart, mobiHeader.exthEnd, mobiHeader.charset)
        val kf8Boundary = exth?.int(ExthType.KF8_BOUNDARY)
        return MobiMetadata(
            title = exth?.text(ExthType.TITLE)
                ?: readFullName(record0, mobiHeader)
                ?: readName(header).takeIf { it.isNotBlank() },
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
            kf8 = mobiHeader.declaresKf8Boundary ||
                (kf8Boundary ?: 0) > 0 ||
                (exth?.first(ExthType.KF8_BOUNDARY) == null && mobiHeader.fileVersion >= 8),
        )
    }

    private fun readBytes(input: RandomAccessFile, offset: Long, size: Int): ByteArray {
        if (offset < 0 || size < 0 || offset + size > input.length()) {
            throw MalformedMobiException("MobiCapture: requested record is outside the PalmDB")
        }
        input.seek(offset)
        val bytes = ByteArray(size)
        var position = 0
        while (position < size) {
            val count = input.read(bytes, position, size - position)
            if (count < 0) throw MalformedMobiException("MobiCapture: truncated record")
            position += count
        }
        return bytes
    }

    private fun readFullName(record0: ByteArray, header: MobiHeader): String? {
        val offset = header.fullNameOffset
        if (offset <= 0 || offset >= record0.size) return null
        val reader = ByteReader(record0, offset, record0.size)
        val declared = reader.u8(0)
        val declaredLength = if (declared and 0x80 != 0) {
            ((declared and 0x7F) shl 8) or reader.u8(1)
        } else {
            declared
        }
        val available = minOf(declaredLength, reader.length - 1)
        if (available <= 0) return null
        return reader.decode(1, available, header.charset).trim().takeIf { it.isNotEmpty() }
    }

    private fun readName(header: ByteArray): String {
        var end = 0
        while (end < 32 && header[end] != 0.toByte()) end++
        return String(header, 0, end, Charsets.ISO_8859_1)
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        String(bytes, offset, length, Charsets.US_ASCII)

    private fun u16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun u32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)
}
