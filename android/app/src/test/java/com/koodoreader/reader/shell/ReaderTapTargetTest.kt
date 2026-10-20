package com.koodoreader.reader.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderTapTargetTest {

    @Test
    fun `image src wins over surrounding text`() {
        val kind = ReaderTapTarget.classify(
            imageSrc = "images/cover.jpg",
            text = "see https://example.com",
            linkHref = "https://example.com",
        )
        assertTrue(kind is ReaderTapKind.Image)
        assertEquals("images/cover.jpg", (kind as ReaderTapKind.Image).src)
    }

    @Test
    fun `explicit href is an in-book or external link`() {
        val kind = ReaderTapTarget.classify(imageSrc = null, text = "next chapter", linkHref = "ch2.xhtml#s1")
        assertTrue(kind is ReaderTapKind.Link)
        assertEquals("ch2.xhtml#s1", (kind as ReaderTapKind.Link).href)
    }

    @Test
    fun `bare url in the line text is classified as a link`() {
        val kind = ReaderTapTarget.classify(null, "See https://koodoreader.com/docs for help")
        assertTrue(kind is ReaderTapKind.Link)
        assertTrue((kind as ReaderTapKind.Link).href.startsWith("https://koodoreader.com"))
    }

    @Test
    fun `bracketed number is a footnote marker`() {
        val kind = ReaderTapTarget.classify(null, "as previously shown[12]")
        assertTrue(kind is ReaderTapKind.Footnote)
        assertEquals(12, (kind as ReaderTapKind.Footnote).number)
    }

    @Test
    fun `plain prose is not a special tap target`() {
        assertEquals(ReaderTapKind.None, ReaderTapTarget.classify(null, "Call me Ishmael."))
    }

    @Test
    fun `relative href matches the spine file name`() {
        val hrefs = listOf("OEBPS/ch1.xhtml", "OEBPS/ch2.xhtml")
        assertEquals(1, ReaderHrefJump.chapterIndex("ch2.xhtml#s1", hrefs))
        assertEquals(null, ReaderHrefJump.chapterIndex("missing.xhtml", hrefs))
    }
}
