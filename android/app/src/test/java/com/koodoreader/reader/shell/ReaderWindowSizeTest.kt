package com.koodoreader.reader.shell

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReaderWindowSizeTest {

    @Test
    fun `600dp is the expanded reader boundary`() {
        assertEquals(ReaderWindowSize.COMPACT, ReaderWindowSize.fromWidth(599))
        assertEquals(ReaderWindowSize.EXPANDED, ReaderWindowSize.fromWidth(600))
    }

    @Test
    fun `phones stay compact`() {
        assertEquals(ReaderWindowSize.COMPACT, ReaderWindowSize.fromWidth(360))
        assertEquals(ReaderWindowSize.EXPANDED, ReaderWindowSize.fromWidth(1024))
    }
}
