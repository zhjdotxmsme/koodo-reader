package com.koodoreader.reader.epubhost

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoliateReaderLayoutTest {

    @Test
    fun `webview attaches during bootstrap before ready`() {
        assertTrue(
            FoliateReaderLayout.attachWebView(
                useFoliate = true,
                hasFile = true,
                assetsAvailable = true,
            ),
        )
        assertTrue(
            FoliateReaderLayout.showLoadingOverlay(ready = false, openError = null),
        )
    }

    @Test
    fun `ready page drops the spinner and keeps the webview`() {
        assertTrue(
            FoliateReaderLayout.attachWebView(
                useFoliate = true,
                hasFile = true,
                assetsAvailable = true,
            ),
        )
        assertFalse(
            FoliateReaderLayout.showLoadingOverlay(ready = true, openError = null),
        )
    }

    @Test
    fun `missing file or engine does not attach a host`() {
        assertFalse(
            FoliateReaderLayout.attachWebView(
                useFoliate = true,
                hasFile = false,
                assetsAvailable = true,
            ),
        )
        assertFalse(
            FoliateReaderLayout.attachWebView(
                useFoliate = true,
                hasFile = true,
                assetsAvailable = false,
            ),
        )
        assertFalse(
            FoliateReaderLayout.attachWebView(
                useFoliate = false,
                hasFile = true,
                assetsAvailable = true,
            ),
        )
    }
}
