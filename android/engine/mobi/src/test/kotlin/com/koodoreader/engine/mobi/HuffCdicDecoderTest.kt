package com.koodoreader.engine.mobi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class HuffCdicDecoderTest {

    private fun syntheticRecords(): List<ByteArray> = HuffCdicTestFixtures.twoSymbolBook()

    @Test
    fun `huff and cdic tables parse and decompress`() {
        val decoder = HuffCdicDecoder(syntheticRecords())

        assertEquals("ab".repeat(8), decoder.decompress(byteArrayOf(0x00)).decodeToString())
        assertEquals("c" + "ab".repeat(7), decoder.decompress(byteArrayOf(0x80.toByte())).decodeToString())
        assertEquals("ab" + "c" + "ab".repeat(6), decoder.decompress(byteArrayOf(0x40)).decodeToString())
    }

    @Test
    fun `nested compressed slice is recursively decoded and reused`() {
        val decoder = HuffCdicDecoder(HuffCdicTestFixtures.nestedSliceBook())

        assertEquals("a".repeat(64), decoder.decompress(byteArrayOf(0xff.toByte())).decodeToString())
    }

    @Test
    fun `32 bit fallback code uses an unsigned shift by zero`() {
        val decoder = HuffCdicDecoder(HuffCdicTestFixtures.longestCodeBook())

        assertEquals("z", decoder.decompress(ByteArray(4)).decodeToString())
    }

    @Test
    fun `invalid code length progression is a typed malformed error`() {
        val decoder = HuffCdicDecoder(HuffCdicTestFixtures.longestCodeBook(terminalMin = 1))

        assertThrows(MalformedMobiException::class.java) { decoder.decompress(ByteArray(4)) }
    }

    @Test
    fun `dictionary code out of range is a typed malformed error`() {
        val records = syntheticRecords().toMutableList()
        records[0] = records[0].copyOf().also {
            TestMobiBuilder.u32(((3L shl 8) or 0x80L or 1L).toInt()).copyInto(it, 24 + 128 * 4)
        }
        val decoder = HuffCdicDecoder(records)

        assertThrows(MalformedMobiException::class.java) {
            decoder.decompress(byteArrayOf(0x80.toByte()))
        }
    }

    @Test
    fun `recursive dictionary cycle is a typed malformed error`() {
        val decoder = HuffCdicDecoder(HuffCdicTestFixtures.cyclicSliceBook())

        assertThrows(MalformedMobiException::class.java) { decoder.decompress(byteArrayOf(0)) }
    }

    @Test
    fun `excessive recursive slice depth is a typed malformed error`() {
        val decoder = HuffCdicDecoder(HuffCdicTestFixtures.deepSliceBook(257))

        assertThrows(MalformedMobiException::class.java) {
            decoder.decompress(TestMobiBuilder.u32(0xffffffffL.toInt()))
        }
    }

    @Test
    fun `bad HUFF magic is a typed malformed error`() {
        val records = syntheticRecords().toMutableList()
        records[0] = records[0].copyOf().also { it[0] = 'X'.code.toByte() }

        assertThrows(MalformedMobiException::class.java) { HuffCdicDecoder(records) }
    }

    @Test
    fun `bad CDIC magic is a typed malformed error`() {
        val records = syntheticRecords().toMutableList()
        records[1] = records[1].copyOf().also { it[0] = 'X'.code.toByte() }

        assertThrows(MalformedMobiException::class.java) { HuffCdicDecoder(records) }
    }

    @Test
    fun `missing HUFF or CDIC records are typed malformed errors`() {
        assertThrows(MalformedMobiException::class.java) { HuffCdicDecoder(emptyList()) }
        assertThrows(MalformedMobiException::class.java) {
            HuffCdicDecoder(syntheticRecords().take(1))
        }
    }

    @Test
    fun `truncated table and dictionary structures are typed malformed errors`() {
        val truncatedHuff = syntheticRecords()[0].copyOf(24)
        assertThrows(MalformedMobiException::class.java) {
            HuffCdicDecoder(listOf(truncatedHuff, syntheticRecords()[1]))
        }

        val truncatedCdic = syntheticRecords()[1].copyOf(18)
        assertThrows(MalformedMobiException::class.java) {
            HuffCdicDecoder(listOf(syntheticRecords()[0], truncatedCdic))
        }
    }
}

private object HuffCdicTestFixtures {
    private const val HUFF_HEADER_SIZE = 24
    private const val TABLE1_SIZE = 256 * 4
    private const val TABLE2_SIZE = 32 * 8
    private const val CDIC_HEADER_SIZE = 16

    data class Slice(val bytes: ByteArray, val decompressed: Boolean = true)

    fun twoSymbolBook(): List<ByteArray> = directCodeBook(
        listOf(Slice("ab".encodeToByteArray()), Slice("c".encodeToByteArray())),
        fallbackZero = true,
    )

    fun nestedSliceBook(): List<ByteArray> = directCodeBook(
        listOf(
            Slice("a".encodeToByteArray()),
            Slice("b".encodeToByteArray()),
            Slice(byteArrayOf(0), decompressed = false),
        ),
        codes = intArrayOf(0, 2),
    )

    fun cyclicSliceBook(): List<ByteArray> = directCodeBook(
        listOf(Slice(byteArrayOf(0), decompressed = false)),
        codes = intArrayOf(0, 0),
    )

    fun longestCodeBook(
        slices: List<Slice> = listOf(Slice("z".encodeToByteArray())),
        terminalMin: Long = 0,
        terminalValue: Long = 0,
    ): List<ByteArray> {
        val huff = emptyHuff()
        for (i in 0 until 256) {
            TestMobiBuilder.u32(1).copyInto(huff, HUFF_HEADER_SIZE + i * 4)
        }
        val offset2 = HUFF_HEADER_SIZE + TABLE1_SIZE
        for (codeLength in 1 until 32) {
            TestMobiBuilder.u32((1L shl codeLength).toInt())
                .copyInto(huff, offset2 + (codeLength - 1) * 8)
        }
        TestMobiBuilder.u32(terminalMin.toInt()).copyInto(huff, offset2 + 31 * 8)
        TestMobiBuilder.u32(terminalValue.toInt()).copyInto(huff, offset2 + 31 * 8 + 4)
        return listOf(huff, cdic(slices))
    }

    fun deepSliceBook(depth: Int): List<ByteArray> {
        val terminalValue = 0xffffffffL
        val slices = (0 until depth).map { index ->
            Slice(TestMobiBuilder.u32((terminalValue - index - 1).toInt()), decompressed = false)
        } + Slice("z".encodeToByteArray())
        return longestCodeBook(slices = slices, terminalValue = terminalValue)
    }

    private fun directCodeBook(
        slices: List<Slice>,
        codes: IntArray = intArrayOf(0, 1),
        fallbackZero: Boolean = false,
    ): List<ByteArray> {
        val huff = emptyHuff()
        for (i in 0 until 256) {
            val prefix = i ushr 7
            val value = prefix + codes[prefix]
            val entry = if (fallbackZero && prefix == 0) {
                1L
            } else {
                (value.toLong() shl 8) or 0x80L or 1L
            }
            TestMobiBuilder.u32(entry.toInt()).copyInto(huff, HUFF_HEADER_SIZE + i * 4)
        }
        return listOf(huff, cdic(slices))
    }

    private fun emptyHuff(): ByteArray {
        val offset2 = HUFF_HEADER_SIZE + TABLE1_SIZE
        val huff = ByteArray(offset2 + TABLE2_SIZE)
        TestMobiBuilder.ascii("HUFF").copyInto(huff)
        TestMobiBuilder.u32(HUFF_HEADER_SIZE).copyInto(huff, 8)
        TestMobiBuilder.u32(offset2).copyInto(huff, 12)
        return huff
    }

    private fun cdic(slices: List<Slice>): ByteArray {
        val offsetTableSize = slices.size * 2
        var nextOffset = offsetTableSize
        val sliceOffsets = slices.map { slice ->
            nextOffset.also { nextOffset += 2 + slice.bytes.size }
        }
        val cdic = ByteArray(CDIC_HEADER_SIZE + nextOffset)
        TestMobiBuilder.ascii("CDIC").copyInto(cdic)
        TestMobiBuilder.u32(CDIC_HEADER_SIZE).copyInto(cdic, 4)
        TestMobiBuilder.u32(slices.size).copyInto(cdic, 8)
        TestMobiBuilder.u32(16).copyInto(cdic, 12)
        slices.forEachIndexed { index, slice ->
            TestMobiBuilder.u16(sliceOffsets[index]).copyInto(cdic, CDIC_HEADER_SIZE + index * 2)
            val x = CDIC_HEADER_SIZE + sliceOffsets[index]
            val flags = if (slice.decompressed) 0x8000 else 0
            TestMobiBuilder.u16(flags or slice.bytes.size).copyInto(cdic, x)
            slice.bytes.copyInto(cdic, x + 2)
        }
        return cdic
    }
}
