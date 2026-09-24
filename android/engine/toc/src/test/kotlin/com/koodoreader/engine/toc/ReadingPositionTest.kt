package com.koodoreader.engine.toc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Tests for [ReadingPosition] and [PositionCodec].
 */
class ReadingPositionTest {

    // --- PositionCodec round-trip ---

    @Test
    fun `encode decode round-trip first chapter zero`() {
        val pos = ReadingPosition(
            bookKey = "book-abc",
            spineIndex = 0,
            cfi = "/0",
            chapterPercent = 0.0f,
            totalPercent = 0.0f,
        )
        val json = PositionCodec.encode(pos)
        val decoded = PositionCodec.decode(json)

        assertEquals(pos.bookKey, decoded.bookKey)
        assertEquals(pos.spineIndex, decoded.spineIndex)
        assertEquals(pos.cfi, decoded.cfi)
        assertEquals(pos.chapterPercent, decoded.chapterPercent)
        assertEquals(pos.totalPercent, decoded.totalPercent)
    }

    @Test
    fun `encode decode round-trip last chapter near one`() {
        val pos = ReadingPosition(
            bookKey = "book-xyz",
            spineIndex = 9,
            cfi = "/4/2/2:5000",
            chapterPercent = 0.99f,
            totalPercent = 0.995f,
        )
        val json = PositionCodec.encode(pos)
        val decoded = PositionCodec.decode(json)

        assertEquals(pos.bookKey, decoded.bookKey)
        assertEquals(pos.spineIndex, decoded.spineIndex)
        assertEquals(pos.cfi, decoded.cfi)
        assertEquals(pos.chapterPercent, decoded.chapterPercent)
        assertEquals(pos.totalPercent, decoded.totalPercent)
    }

    @Test
    fun `encode decode round-trip middle position`() {
        val pos = ReadingPosition(
            bookKey = "my-book",
            spineIndex = 3,
            cfi = "/4/2/2:1234",
            chapterPercent = 0.45f,
            totalPercent = 0.12f,
        )
        val json = PositionCodec.encode(pos)
        val decoded = PositionCodec.decode(json)

        assertEquals(pos.bookKey, decoded.bookKey)
        assertEquals(pos.spineIndex, decoded.spineIndex)
        assertEquals(pos.cfi, decoded.cfi)
        assertEquals(pos.chapterPercent, decoded.chapterPercent)
        assertEquals(pos.totalPercent, decoded.totalPercent)
    }

    /**
     * Regression: the numeric keys used to be emitted without quotes, so the payload
     * was invalid JSON for any real parser (and only this codec's own reader could
     * read it back). Room / the desktop side need valid JSON.
     */
    @Test
    fun `encode emits valid JSON with every key quoted`() {
        val json = PositionCodec.encode(
            ReadingPosition(
                bookKey = "my-book",
                spineIndex = 3,
                cfi = "/4/2/2:1234",
                chapterPercent = 0.45f,
                totalPercent = 0.12f,
            ),
        )
        assertEquals(
            "{\"bookKey\":\"my-book\",\"spineIndex\":3,\"cfi\":\"/4/2/2:1234\"," +
                "\"chapterPercent\":0.45,\"totalPercent\":0.12}",
            json,
        )
    }

    // --- ReadingPosition validation ---

    @Test
    fun `ReadingPosition rejects negative spineIndex`() {
        assertThrows(IllegalArgumentException::class.java) {
            ReadingPosition("book", -1, "/0", 0f, 0f)
        }
    }

    @Test
    fun `ReadingPosition rejects chapterPercent out of range`() {
        assertThrows(IllegalArgumentException::class.java) {
            ReadingPosition("book", 0, "/0", -0.01f, 0f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReadingPosition("book", 0, "/0", 1.01f, 0f)
        }
    }

    @Test
    fun `ReadingPosition rejects totalPercent out of range`() {
        assertThrows(IllegalArgumentException::class.java) {
            ReadingPosition("book", 0, "/0", 0f, -0.01f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReadingPosition("book", 0, "/0", 0f, 1.01f)
        }
    }

    @Test
    fun `ReadingPosition accepts boundary values`() {
        ReadingPosition("book", 0, "/0", 0f, 0f)
        ReadingPosition("book", 5, "/0", 1f, 1f)
    }
}
