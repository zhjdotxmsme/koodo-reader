package com.koodoreader.engine.mobi

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PalmDOC opcode coverage: every branch of the compression type 2 decoder is
 * exercised with programmatically built opcode streams.
 */
class PalmDocTest {

    private val B = TestMobiBuilder

    private fun decode(vararg fragments: ByteArray): String =
        String(PalmDoc.decompress(B.opcodes(*fragments)), Charsets.ISO_8859_1)

    @Test
    fun `literal run 0x01 to 0x08 copies the following n bytes verbatim`() {
        val payload = B.opcodes(
            B.literals(0x41, 0x42, 0x43), // opcode 0x03 + "ABC"
            B.literals(0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4A, 0x4B), // opcode 0x08 + 8 bytes
        )
        assertEquals("ABCDEFGHIJK", String(PalmDoc.decompress(payload), Charsets.US_ASCII))
    }

    @Test
    fun `single byte literals 0x09 to 0x7F are copied as-is`() {
        val payload = B.bytes('h'.code, 'i'.code, 0x7F, 0x09, 0x00)
        val out = PalmDoc.decompress(payload)
        assertArrayEquals(B.bytes('h'.code, 'i'.code, 0x7F, 0x09, 0x00), out)
    }

    @Test
    fun `0xC0 to 0xFF emits a space plus the byte XOR 0x80`() {
        // 0xE8 -> " " + (0xE8 ^ 0x80) = " h";  0xC1 -> " " + 0x41 = " A"
        assertEquals(0xE8, B.spacePlus('h'))
        assertEquals(0xC1, B.spacePlus('A'))
        val payload = B.opcodes(B.bytes(0xE8, 0xC1))
        assertArrayEquals(B.bytes(' '.code, 'h'.code, ' '.code, 'A'.code), PalmDoc.decompress(payload))
        assertEquals(" h A", decode(payload))
    }

    @Test
    fun `two byte back reference copies distance and length`() {
        // "hello" then a copy of 5 bytes from distance 5 -> "hellohello"
        val payload = B.opcodes(
            B.literals('h'.code, 'e'.code, 'l'.code, 'l'.code, 'o'.code),
            B.backReference(distance = 5, length = 5),
        )
        assertEquals("hellohello", decode(payload))
    }

    @Test
    fun `back reference works for every length class 3 to 10`() {
        // Seed 16 distinct bytes, then copy 3..10 bytes from distance 16.
        val seed = B.literals(0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48)
        val seed2 = B.literals(0x49, 0x4A, 0x4B, 0x4C, 0x4D, 0x4E, 0x4F, 0x50)
        for (length in 3..10) {
            val payload = B.opcodes(seed, seed2, B.backReference(distance = 16, length = length))
            val expected = "ABCDEFGHIJKLMNOP" + "ABCDEFGHIJKLMNOP".substring(0, length)
            assertEquals(expected, decode(payload), "length class $length")
        }
    }

    @Test
    fun `overlapping back reference repeats a single byte run length encoded`() {
        // "A" + copy 9 bytes from distance 1 -> AAAAAAAAAA (10 A's).
        val payload = B.opcodes(B.bytes(0x41), B.backReference(distance = 1, length = 9))
        assertEquals("AAAAAAAAAA", decode(payload))
    }

    @Test
    fun `zero byte opcode emits a NUL`() {
        val out = PalmDoc.decompress(B.bytes(0x00, 0x41))
        assertArrayEquals(B.bytes(0x00, 0x41), out)
    }

    @Test
    fun `decompress works on a slice of a larger buffer`() {
        val payload = B.bytes(0xAA, 0xBB, 0x03, 0x41, 0x42, 0x43, 0xCC)
        val out = PalmDoc.decompress(payload, offset = 2, size = 4)
        assertEquals("ABC", String(out, Charsets.US_ASCII))
    }

    @Test
    fun `truncated back reference raises a typed malformed error`() {
        val payload = B.bytes(0x41, 0x80) // 0x80 needs a second byte
        val ex = assertThrows(MalformedMobiException::class.java) { PalmDoc.decompress(payload) }
        assertTrue(ex.message!!.contains("back reference"), ex.message)
    }

    @Test
    fun `back reference beyond the output raises a typed malformed error`() {
        val payload = B.opcodes(B.bytes(0x41), B.backReference(distance = 5, length = 3))
        val ex = assertThrows(MalformedMobiException::class.java) { PalmDoc.decompress(payload) }
        assertTrue(ex.message!!.contains("distance"), ex.message)
    }

    @Test
    fun `truncated literal run raises a typed malformed error`() {
        val payload = B.bytes(0x08, 0x41, 0x42) // claims 8 bytes, only 2 present
        assertThrows(MalformedMobiException::class.java) { PalmDoc.decompress(payload) }
    }

    @Test
    fun `stripExtras removes the VLQ trailer and keeps the payload`() {
        val record = B.textRecord(B.bytes(0x41, 0x42, 0x43), extra = 4)
        // 1 VLQ byte + 3 payload bytes + 4 trailer bytes = 8 bytes.
        assertEquals(8, record.size)
        // Trailing NULs are padding, so they are dropped; the VLQ prefix (0x04
        // here, the trailer length) is part of the record and is kept.
        assertArrayEquals(B.bytes(0x04, 0x41, 0x42, 0x43), PalmDoc.stripExtras(record))
    }

    @Test
    fun `stripExtras handles a multi byte VLQ trailer`() {
        // extra = 200 needs two VLQ groups (0x81 0x48).
        val extra = 200
        val payload = ByteArray(300) { 0x5A }
        val record = B.textRecord(payload, extra = extra)
        assertEquals(502, record.size)
        // VLQ(200) = 81 48, then the 300 payload bytes.
        assertArrayEquals(B.bytes(0x81, 0x48) + payload, PalmDoc.stripExtras(record))
    }

    @Test
    fun `stripExtras ignores trailing NUL padding and keeps an empty trailer`() {
        val payload = B.bytes(0x41, 0x42, 0x43)
        // VLQ(0) + payload + NUL padding (calibre pads to the record size).
        val record = B.textRecord(payload, extra = 0) + B.bytes(0x00, 0x00)
        // textRecord prepends the VLQ extra-length byte; stripExtras drops only NULs.
        assertArrayEquals(B.bytes(0x00, 0x41, 0x42, 0x43), PalmDoc.stripExtras(record))
        // Decompressing the stripped record yields the VLQ NUL then the payload.
        assertArrayEquals(B.bytes(0x00) + payload, PalmDoc.decompress(PalmDoc.stripExtras(record)))
    }
}
