package com.koodoreader.reader.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReaderProgressModelTest {

    @Test
    fun `unknown total pages stays pending instead of zero`() {
        assertEquals(
            ReaderProgressModel.PageCountState.Pending,
            ReaderProgressModel.pageState(null),
        )
    }

    @Test
    fun `negative total pages stays pending`() {
        assertEquals(
            ReaderProgressModel.PageCountState.Pending,
            ReaderProgressModel.pageState(-1),
        )
    }

    @Test
    fun `known total pages is ready`() {
        assertEquals(ReaderProgressModel.PageCountState.Ready(12), ReaderProgressModel.pageState(12))
    }

    @Test
    fun `remaining minutes rounds up and handles boundaries`() {
        assertEquals(1, ReaderProgressModel.remainingMinutes(1, 300))
        assertEquals(4, ReaderProgressModel.remainingMinutes(1000, 300))
        assertEquals(0, ReaderProgressModel.remainingMinutes(0, 300))
        assertEquals(null, ReaderProgressModel.remainingMinutes(-5, 300))
        assertEquals(null, ReaderProgressModel.remainingMinutes(100, 0))
    }
}
