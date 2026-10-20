package com.koodoreader.reader.epubhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FoliateHostBridgeJsErrorTest {

    @Test
    fun `blank js error is success`() {
        assertNull(FoliateJsResult.errorOrNull(null))
        assertNull(FoliateJsResult.errorOrNull(""))
        assertNull(FoliateJsResult.errorOrNull("   "))
    }

    @Test
    fun `non-blank js error is kept`() {
        assertEquals("fetch failed: 404", FoliateJsResult.errorOrNull("fetch failed: 404"))
    }
}
