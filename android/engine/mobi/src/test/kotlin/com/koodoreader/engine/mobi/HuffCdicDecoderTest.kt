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

    fun twoSymbolBook(): List<ByteArray> {
        val offset1 = HUFF_HEADER_SIZE
        val offset2 = offset1 + TABLE1_SIZE
        val huff = ByteArray(offset2 + TABLE2_SIZE)
        TestMobiBuilder.ascii("HUFF").copyInto(huff)
        TestMobiBuilder.u32(offset1).copyInto(huff, 8)
        TestMobiBuilder.u32(offset2).copyInto(huff, 12)

        // Leading bit 1 selects dictionary entry 1 ("c") directly. Leading bit
        // 0 takes the table2 fallback, whose min=0/max=0 selects "ab".
        val directEntry = (2L shl 8) or 0x80L or 1L
        for (i in 0 until 256) {
            val entry = if (i < 128) 1L else directEntry
            TestMobiBuilder.u32(entry.toInt()).copyInto(huff, offset1 + i * 4)
        }
        TestMobiBuilder.u32(0).copyInto(huff, offset2)
        TestMobiBuilder.u32(0).copyInto(huff, offset2 + 4)

        val slices = listOf("ab".encodeToByteArray(), "c".encodeToByteArray())
        val offsetTableSize = slices.size * 2
        var nextOffset = offsetTableSize
        val sliceOffsets = slices.map { slice ->
            nextOffset.also { nextOffset += 2 + slice.size }
        }
        val cdic = ByteArray(CDIC_HEADER_SIZE + nextOffset)
        TestMobiBuilder.ascii("CDIC").copyInto(cdic)
        TestMobiBuilder.u32(CDIC_HEADER_SIZE).copyInto(cdic, 4)
        TestMobiBuilder.u32(slices.size).copyInto(cdic, 8)
        TestMobiBuilder.u32(1).copyInto(cdic, 12)
        slices.forEachIndexed { index, slice ->
            TestMobiBuilder.u16(sliceOffsets[index]).copyInto(cdic, CDIC_HEADER_SIZE + index * 2)
            val x = CDIC_HEADER_SIZE + sliceOffsets[index]
            TestMobiBuilder.u16(0x8000 or slice.size).copyInto(cdic, x)
            slice.copyInto(cdic, x + 2)
        }
        return listOf(huff, cdic)
    }
}
