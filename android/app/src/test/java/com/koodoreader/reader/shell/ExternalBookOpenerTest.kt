package com.koodoreader.reader.shell

import com.koodoreader.core.importer.BookRecord
import com.koodoreader.core.importer.ImportFailure
import com.koodoreader.core.importer.ImportOutcome
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalBookOpenerTest {

    @Test
    fun `new import opens the imported book`() {
        val state = ExternalBookOpener.stateAfter(
            ImportOutcome.Success(book("new-key"), duplicate = false),
        )

        assertEquals(ExternalOpenState.Open("new-key"), state)
    }

    @Test
    fun `duplicate import opens the existing book`() {
        val state = ExternalBookOpener.stateAfter(
            ImportOutcome.Success(book("stable-key"), duplicate = true),
        )

        assertEquals(ExternalOpenState.Open("stable-key"), state)
    }

    @Test
    fun `unsupported file does not navigate`() {
        val state = ExternalBookOpener.stateAfter(
            ImportOutcome.Failure(ImportFailure.Unsupported("CBR is not supported")),
        )

        assertEquals(ExternalOpenState.Failed("Unsupported book format"), state)
    }

    @Test
    fun `open waits for the import outcome`() = runBlocking {
        val states = ExternalBookOpener.open {
            ImportOutcome.Success(book("after-import"), duplicate = false)
        }.toList()

        assertEquals(
            listOf(ExternalOpenState.Importing, ExternalOpenState.Open("after-import")),
            states,
        )
    }

    private fun book(key: String) = BookRecord(
        key = key,
        name = "Book",
        author = null,
        description = null,
        md5 = "md5",
        format = "EPUB",
        publisher = null,
        size = 1,
        page = null,
        path = null,
    )
}
