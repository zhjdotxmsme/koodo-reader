package com.koodoreader.core.dbio

import java.util.Date
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DataExportMarkdownTest {
    @Test
    fun `markdown export retains note and highlight text`() {
        val row = DataExport.NoteRow(
            key = "n1",
            bookKey = "b1",
            bookName = "Book",
            bookAuthor = "Author",
            bookMd5 = "md5",
            chapter = "Ch1",
            chapterIndex = 0L,
            text = "selected text",
            notes = "note body",
            percentage = "0.1",
            color = 0L,
            tag = emptyList(),
            date = Date(0),
            exportType = DataExport.ExportType.NOTE,
        )
        val output = DataExport.encode(listOf(row), DataExport.Format.MD)
        assertTrue(output.contains("> selected text"))
        assertTrue(output.contains("**Note:** note body"))
    }
}
