package com.koodoreader.reader.shell

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderPurifiedTextTest {

    @Test
    fun `empty rules leave text unchanged`() {
        assertEquals("hello", ReaderPurifiedText.plain("hello", ""))
    }

    @Test
    fun `replacement rule mutates plain text`() {
        val json = """[{"id":"1","pattern":"foo","replacement":"bar"}]"""
        assertEquals("bar baz", ReaderPurifiedText.plain("foo baz", json))
    }
}
