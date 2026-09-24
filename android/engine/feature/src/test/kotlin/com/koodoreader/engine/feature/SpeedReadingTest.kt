package com.koodoreader.engine.feature

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpeedReadingTest {

    @Test
    fun `wpm is clamped to the desktop slider range`() {
        assertEquals(300, SpeedReading().wpm)
        assertEquals(100, SpeedReading(50).wpm)
        assertEquals(900, SpeedReading(2000).wpm)
        assertEquals(450, SpeedReading(450).wpm)
        assertEquals(200.0, SpeedReading().baseDurationMs, 1e-9)
    }

    @Test
    fun `text is split into one chunk per word`() {
        val chunks = SpeedReading().chunks("hello world")
        assertEquals(2, chunks.size)
        assertEquals("hello", chunks[0].text)
        assertEquals("world", chunks[1].text)
        assertEquals(200, chunks[0].durationMs)
        assertEquals(200, chunks[1].durationMs)
    }

    @Test
    fun `orp index follows the spritz table`() {
        assertEquals(0, SpeedReading.orpIndex(""))
        assertEquals(0, SpeedReading.orpIndex("a"))
        assertEquals(1, SpeedReading.orpIndex("ab"))
        assertEquals(1, SpeedReading.orpIndex("abcde"))
        assertEquals(2, SpeedReading.orpIndex("abcdef"))
        assertEquals(2, SpeedReading.orpIndex("abcdefghi"))
        assertEquals(3, SpeedReading.orpIndex("abcdefghij"))
        assertEquals(3, SpeedReading.orpIndex("abcdefghijklm"))
        assertEquals(4, SpeedReading.orpIndex("abcdefghijklmn"))
        // the pivot never points outside the word
        assertTrue(SpeedReading.orpIndex("a".repeat(80)) < 80)
    }

    @Test
    fun `punctuation adds a dwell pause`() {
        val speed = SpeedReading()
        assertEquals(2.0, SpeedReading.durationMultiplier("end."), 1e-9)
        assertEquals(400, speed.durationFor("end."))
        assertEquals(300, speed.durationFor("x,"))
        assertEquals(400, speed.durationFor("\u7ed3\u675f\u3002"))
        assertEquals(300, speed.durationFor("\u4f60\u597d\uff0c"))
        assertEquals(200, speed.durationFor("plain"))
    }

    @Test
    fun `long words get extra dwell time`() {
        val speed = SpeedReading()
        assertEquals(1.0, SpeedReading.durationMultiplier("12345678"), 1e-9)
        assertEquals(1.2, SpeedReading.durationMultiplier("123456789012"), 1e-9)
        assertEquals(240, speed.durationFor("abcdefghijkl"))
        // capped at +100%
        assertEquals(2.0, SpeedReading.durationMultiplier("a".repeat(40)), 1e-9)
    }

    @Test
    fun `blank text yields no chunks`() {
        assertTrue(SpeedReading().chunks("").isEmpty())
        assertTrue(SpeedReading().chunks("   ").isEmpty())
        assertTrue(SpeedReading().chunks("\n\t").isEmpty())
    }

    @Test
    fun `over long tokens are split so no frame overflows`() {
        val chunks = SpeedReading().chunks("a".repeat(30))
        assertEquals(3, chunks.size)
        assertEquals(listOf(12, 12, 6), chunks.map { it.text.length })
        // 240 + 240 + 200 (only the 12-char frames get the long-word penalty)
        assertEquals(680, SpeedReading().totalDurationMs(chunks))

        val capped = SpeedReading(maxChunkChars = 4).chunks("a".repeat(10))
        assertEquals(listOf(4, 4, 2), capped.map { it.text.length })
    }

    @Test
    fun `cjk text chunking`() {
        val chunks = SpeedReading().chunks("\u4f60\u597d \u4e16\u754c")
        assertEquals(listOf("\u4f60\u597d", "\u4e16\u754c"), chunks.map { it.text })
        assertEquals(1, chunks[0].orpIndex)

        val ideographic = SpeedReading().chunks("\u4f60\u597d\u3000\u4e16\u754c")
        assertEquals(2, ideographic.size)
    }

    @Test
    fun `chunkAt maps elapsed playback time to a frame`() {
        val chunks = SpeedReading().chunks("hello world")
        assertEquals(400, SpeedReading().totalDurationMs(chunks))
        assertEquals(0, SpeedReading().chunkAt(chunks, 0))
        assertEquals(0, SpeedReading().chunkAt(chunks, 199))
        assertEquals(1, SpeedReading().chunkAt(chunks, 200))
        assertEquals(1, SpeedReading().chunkAt(chunks, 399))
        assertEquals(1, SpeedReading().chunkAt(chunks, 5000))
        assertEquals(-1, SpeedReading().chunkAt(emptyList(), 10))
    }
}
