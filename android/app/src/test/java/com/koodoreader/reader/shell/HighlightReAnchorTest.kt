package com.koodoreader.reader.shell

import com.koodoreader.core.data.entity.NoteEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightReAnchorTest {

    private fun note(
        key: String,
        cfi: String?,
        text: String? = null,
        range: String? = null,
    ) = NoteEntity(
        key = key,
        bookKey = "bk",
        cfi = cfi,
        text = text,
        range = range,
        color = 0xFFFF00,
    )

    @Test
    fun `requests skip notes without a cfi`() {
        val notes = listOf(
            note("a", "epubcfi(/6/4!/4/2:0)", "hello world"),
            note("b", null, "no anchor"),
            note("c", "   ", "blank anchor"),
            note("d", "epubcfi(/6/4!/4/4:9)", null),
        )
        val requests = HighlightReAnchor.requests(notes) { "#ffe066" }
        assertEquals(
            listOf("epubcfi(/6/4!/4/2:0)", "epubcfi(/6/4!/4/4:9)"),
            requests.map { it.cfi },
        )
        assertEquals(listOf("hello world", ""), requests.map { it.text })
        assertEquals(requests.map { it.colorCss }.distinct(), listOf("#ffe066"))
    }

    @Test
    fun `requests carry the colour the caller derives`() {
        val notes = listOf(note("a", "epubcfi(/6/4!/4/2:0)", "hello world"))
        val requests = HighlightReAnchor.requests(notes) { it.color?.toString(16) ?: "none" }
        assertEquals("ffff00", requests.single().colorCss)
    }

    @Test
    fun `remaps rewrite the cfi and clear the stale dom range`() {
        val notes = listOf(
            note("a", "old-a", "text a", range = "{\"start\":1}"),
            note("b", "old-b", "text b"),
        )
        val changed = HighlightReAnchor.applyRemaps(
            notes,
            listOf(HighlightRemap("old-a", "new-a"), HighlightRemap("old-b", "old-b")),
        )
        assertEquals(1, changed.size)
        assertEquals("new-a", changed.single().cfi)
        assertNull(changed.single().range)
        assertEquals("text a", changed.single().text)
    }

    @Test
    fun `applyRemaps is idempotent against already written-back notes`() {
        val notes = listOf(note("a", "new-a", "text a"))
        val changed = HighlightReAnchor.applyRemaps(
            notes,
            listOf(HighlightRemap("old-a", "new-a")),
        )
        assertTrue(changed.isEmpty())
    }

    @Test
    fun `degenerate remaps are dropped`() {
        val notes = listOf(note("a", "old-a"))
        assertTrue(
            HighlightReAnchor.applyRemaps(
                notes,
                listOf(
                    HighlightRemap("", "x"),
                    HighlightRemap("old-a", ""),
                    HighlightRemap("old-a", "old-a"),
                ),
            ).isEmpty(),
        )
    }

    @Test
    fun `first remap for a cfi wins`() {
        val notes = listOf(note("a", "old-a"))
        val changed = HighlightReAnchor.applyRemaps(
            notes,
            listOf(HighlightRemap("old-a", "new-1"), HighlightRemap("old-a", "new-2")),
        )
        assertEquals("new-1", changed.single().cfi)
    }
}
