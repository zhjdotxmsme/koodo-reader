package com.koodoreader.reader.shell

import com.koodoreader.core.importer.BookRecord
import com.koodoreader.core.importer.BookSource
import com.koodoreader.core.importer.ImportFailure
import com.koodoreader.core.importer.ImportOutcome
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExternalBookOpenerTest {

    @get:Rule
    val temp = TemporaryFolder()

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

    @Test
    fun `successful import preserves unrelated existing files`() = runBlocking {
        val booksDir = temp.newFolder("books")
        val unrelated = File(booksDir, "unowned.epub").apply { writeText("keep") }

        val outcome = ExternalBookOpener.importBook(
            source = BookSource("new.txt", { "new book".byteInputStream() }),
            booksDir = booksDir,
        )

        assertTrue(outcome is ImportOutcome.Success)
        assertEquals("keep", unrelated.readText())
    }

    @Test
    fun `failed import preserves unrelated files and removes only its partial copy`() = runBlocking {
        val booksDir = temp.newFolder("books")
        val unrelated = File(booksDir, "unowned.epub").apply { writeText("keep") }
        var opens = 0
        val source = BookSource(
            name = "broken.txt",
            openStream = {
                opens++
                if (opens == 1) {
                    "detectable text".byteInputStream()
                } else {
                    object : InputStream() {
                        private var reads = 0
                        override fun read(): Int =
                            if (reads++ == 0) 'x'.code else throw IOException("disk full")
                    }
                }
            },
        )

        val outcome = ExternalBookOpener.importBook(source, booksDir)

        assertTrue(outcome is ImportOutcome.Failure)
        assertEquals("keep", unrelated.readText())
        assertTrue(booksDir.listFiles().orEmpty().none { it.name.endsWith(".part") })
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
