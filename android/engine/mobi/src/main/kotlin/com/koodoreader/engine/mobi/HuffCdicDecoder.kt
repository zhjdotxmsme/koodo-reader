package com.koodoreader.engine.mobi

import java.io.ByteArrayOutputStream

class HuffCdicDecoder(records: List<ByteArray>) {

    private data class HuffEntry(val found: Boolean, val codeLength: Int, val value: Long)
    private data class DictionaryEntry(val bytes: ByteArray, val decompressed: Boolean)

    private val table1: Array<HuffEntry>
    private val table2: Array<Pair<Long, Long>>
    private val dictionary = mutableListOf<DictionaryEntry>()

    init {
        if (records.isEmpty()) {
            throw MalformedMobiException("MobiCapture: missing HUFF record")
        }
        if (records.size == 1) {
            throw MalformedMobiException("MobiCapture: missing CDIC record")
        }

        val huff = records[0]
        if (huff.size < HUFF_HEADER_SIZE || huff.text(0, 4) != "HUFF") {
            throw MalformedMobiException("MobiCapture: invalid HUFF record")
        }
        val offset1 = huff.offset(8, "HUFF table1")
        val offset2 = huff.offset(12, "HUFF table2")
        huff.requireRange(offset1, TABLE1_SIZE, "HUFF table1")
        huff.requireRange(offset2, TABLE2_SIZE, "HUFF table2")

        table1 = Array(256) { i ->
            val x = huff.u32(offset1 + i * 4)
            HuffEntry(x and 0x80L != 0L, (x and 0x1fL).toInt(), x ushr 8)
        }
        table2 = Array(33) { i ->
            if (i == 0) {
                0L to 0L
            } else {
                val offset = offset2 + (i - 1) * 8
                huff.u32(offset) to huff.u32(offset + 4)
            }
        }

        for (recordIndex in 1 until records.size) {
            val cdic = records[recordIndex]
            if (cdic.size < CDIC_HEADER_SIZE || cdic.text(0, 4) != "CDIC") {
                throw MalformedMobiException("MobiCapture: invalid CDIC record $recordIndex")
            }
            val headerLength = cdic.offset(4, "CDIC header")
            val numEntries = cdic.u32(8)
            val codeLength = cdic.u32(12)
            if (headerLength < CDIC_HEADER_SIZE || headerLength > cdic.size) {
                throw MalformedMobiException("MobiCapture: invalid CDIC header length $headerLength")
            }
            if (codeLength > 30) {
                throw MalformedMobiException("MobiCapture: invalid CDIC code length $codeLength")
            }
            val count = minOf(1L shl codeLength.toInt(), numEntries - dictionary.size)
            if (count <= 0) continue
            if (count > (cdic.size - headerLength) / 2) {
                throw MalformedMobiException("MobiCapture: truncated CDIC offset table")
            }
            for (i in 0 until count.toInt()) {
                val offset = cdic.u16(headerLength + i * 2)
                val x = cdic.u16(headerLength + offset)
                val length = x and 0x7fff
                val decompressed = x and 0x8000 != 0
                val start = headerLength + offset + 2
                cdic.requireRange(start, length, "CDIC dictionary slice")
                dictionary += DictionaryEntry(cdic.copyOfRange(start, start + length), decompressed)
            }
        }
    }

    fun decompress(data: ByteArray): ByteArray = decompress(data, mutableSetOf(), depth = 0)

    private fun decompress(data: ByteArray, activeSlices: MutableSet<Int>, depth: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val bitLength = data.size * 8
        var bitOffset = 0
        while (bitOffset < bitLength) {
            val bits = read32Bits(data, bitOffset)
            val entry = table1[(bits ushr 24).toInt()]
            var codeLength = entry.codeLength
            var value = entry.value
            if (!entry.found) {
                while (codeLength < 32 && bits ushr (32 - codeLength) < table2[codeLength].first) {
                    codeLength += 1
                }
                if (codeLength == 0 || bits ushr (32 - codeLength) < table2[codeLength].first) {
                    throw MalformedMobiException("MobiCapture: invalid HUFF code length")
                }
                value = table2[codeLength].second
            }
            if (codeLength == 0) {
                throw MalformedMobiException("MobiCapture: zero-length HUFF code")
            }
            bitOffset += codeLength
            if (bitOffset > bitLength) break

            val code = value - (bits ushr (32 - codeLength))
            if (code < 0 || code >= dictionary.size) {
                throw MalformedMobiException("MobiCapture: HUFF dictionary index $code is out of range")
            }
            val index = code.toInt()
            var result = dictionary[index]
            if (!result.decompressed) {
                if (depth >= MAX_DICTIONARY_DEPTH) {
                    throw MalformedMobiException("MobiCapture: HUFF dictionary nesting is too deep")
                }
                if (!activeSlices.add(index)) {
                    throw MalformedMobiException("MobiCapture: cyclic HUFF dictionary slice $index")
                }
                try {
                    result = DictionaryEntry(
                        decompress(result.bytes, activeSlices, depth + 1),
                        decompressed = true,
                    )
                    dictionary[index] = result
                } finally {
                    activeSlices.remove(index)
                }
            }
            output.writeBytes(result.bytes)
        }
        return output.toByteArray()
    }

    private companion object {
        const val HUFF_HEADER_SIZE = 24
        const val TABLE1_SIZE = 256 * 4
        const val TABLE2_SIZE = 32 * 8
        const val CDIC_HEADER_SIZE = 16
        const val MAX_DICTIONARY_DEPTH = 256
    }
}

private fun read32Bits(data: ByteArray, from: Int): Long {
    val startByte = from shr 3
    val end = from + 32
    val endByte = end shr 3
    var bits = 0L
    for (i in startByte..endByte) {
        val byte = if (i < data.size) data[i].toLong() and 0xffL else 0L
        bits = (bits shl 8) or byte
    }
    return (bits ushr (8 - (end and 7))) and 0xffff_ffffL
}

private fun ByteArray.u16(offset: Int): Int {
    requireRange(offset, 2, "uint16")
    return ((this[offset].toInt() and 0xff) shl 8) or (this[offset + 1].toInt() and 0xff)
}

private fun ByteArray.u32(offset: Int): Long {
    requireRange(offset, 4, "uint32")
    return ((this[offset].toLong() and 0xffL) shl 24) or
        ((this[offset + 1].toLong() and 0xffL) shl 16) or
        ((this[offset + 2].toLong() and 0xffL) shl 8) or
        (this[offset + 3].toLong() and 0xffL)
}

private fun ByteArray.text(offset: Int, length: Int): String {
    requireRange(offset, length, "text")
    return String(this, offset, length, Charsets.US_ASCII)
}

private fun ByteArray.offset(fieldOffset: Int, name: String): Int {
    val value = u32(fieldOffset)
    if (value > Int.MAX_VALUE) {
        throw MalformedMobiException("MobiCapture: $name offset is too large")
    }
    return value.toInt()
}

private fun ByteArray.requireRange(offset: Int, length: Int, name: String) {
    if (offset < 0 || length < 0 || offset.toLong() + length > size) {
        throw MalformedMobiException("MobiCapture: truncated $name")
    }
}
