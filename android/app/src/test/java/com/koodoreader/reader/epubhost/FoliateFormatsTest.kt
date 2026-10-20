package com.koodoreader.reader.epubhost

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins which imported formats the visible foliate-js WebView hosts.
 * PDF / comics stay on their dedicated screens; CBR stays on the island.
 */
class FoliateFormatsTest {

    @Test
    fun `reflowable books use the foliate host`() {
        for (ext in listOf(
            "epub", "EPUB",
            "mobi", "azw", "azw3",
            "fb2",
            "txt", "md",
            "html", "htm", "xhtml", "xml", "mhtml",
            "docx",
        )) {
            assertTrue("$ext should use foliate", FoliateFormats.usesHost(ext))
        }
    }

    @Test
    fun `pdf and comics do not use the foliate host`() {
        for (ext in listOf("pdf", "cbz", "cbt", "cb7", "cbr")) {
            assertFalse("$ext must not use foliate", FoliateFormats.usesHost(ext))
        }
        assertFalse(FoliateFormats.usesHost(null))
        assertFalse(FoliateFormats.usesHost(""))
        assertFalse(FoliateFormats.usesHost("unknown"))
    }
}
