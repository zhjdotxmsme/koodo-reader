package com.koodoreader.reader.epubhost

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderEnginePolicyTest {

    @Test
    fun `default engine hosts reflowable formats`() {
        assertTrue(ReaderEnginePolicy.useFoliate("epub"))
        assertTrue(ReaderEnginePolicy.useFoliate("txt"))
        assertTrue(ReaderEnginePolicy.useFoliate("azw3"))
        assertFalse(ReaderEnginePolicy.useFoliate("pdf"))
        assertFalse(ReaderEnginePolicy.useFoliate("cbz"))
    }

    @Test
    fun `canvas engine flag rolls reflowable books back to the factory`() {
        assertFalse(ReaderEnginePolicy.useFoliate("epub", ReaderEnginePolicy.CANVAS))
        assertFalse(ReaderEnginePolicy.useFoliate("txt", ReaderEnginePolicy.CANVAS))
        assertTrue(ReaderEnginePolicy.useFoliate("epub", ReaderEnginePolicy.FOLIATE))
    }
}
