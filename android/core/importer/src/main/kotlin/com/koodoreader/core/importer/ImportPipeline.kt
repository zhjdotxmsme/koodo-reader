package com.koodoreader.core.importer

import com.koodoreader.core.importer.metadata.MetadataParsers
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/** One import candidate with a fresh stream available per open. */
data class BookSource(
    val name: String,
    val openStream: () -> InputStream,
    val size: Long? = null,
    val mime: String? = null,
)

/** One imported book, column-for-column with `books` (schema.lock / BookEntity). */
data class BookRecord(
    val key: String,
    val name: String?,
    val author: String?,
    val description: String?,
    val md5: String,
    val cover: String? = null,
    val format: String?,
    val publisher: String?,
    val size: Long?,
    val page: Long?,
    val path: String?,
    val charset: String? = null,
)

/** Per-book failure, isolated from the rest of the batch. */
data class BookFailure(val name: String, val reason: String)

/** Batch outcome of [ImportPipeline.process]. */
data class ImportResult(
    val records: List<BookRecord>,
    /** bookKey -> cover image (the host writes these into its `cover/` dir). */
    val covers: Map<String, BookCover>,
    val duplicates: Int,
    val unsupported: Int,
    val failures: List<BookFailure>,
) {
    val imported: Int get() = records.size
}

/**
 * Streaming native import. Format detection happens before copying; the copy
 * calculates MD5 in the same pass and commits through a `.part` rename.
 */
class ImportPipeline(
    /** Injectable clock (tests): ms since epoch for [BookRules.buildBookKey]. */
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** Injectable 0..999 random (tests): suffix of [BookRules.buildBookKey]. */
    private val random: () -> Int = { (0..999).random() },
    /** Host-provided cover extractors for formats requiring Android APIs. */
    private val extraCoverExtractors: Map<String, (File) -> BookCover?> = emptyMap(),
    /** PDF Document Info supplied by the Android/PDFium host, never loaded by this JVM module. */
    private val pdfMetadataExtractor: ((File) -> BookMetadata?)? = null,
    /** Injectable EPUB package read; one result supplies both metadata and cover. */
    private val epubParser: (File) -> EpubResult = EpubBook::parse,
) {
    private data class PreparedImport(
        val outcome: ImportOutcome,
        val cover: BookCover? = null,
    )

    /** Import one book and return the existing row unchanged for a duplicate. */
    suspend fun importBook(
        book: BookSource,
        booksOut: File,
        existingBook: (suspend (String) -> BookRecord?) = { null },
    ): ImportOutcome = importInternal(book, booksOut, existingBook).outcome

    /**
     * Import a batch while retaining the original result shape. [existingMd5]
     * remains for callers that only need duplicate counts; [existingRecord]
     * additionally preserves and returns the existing row for navigation.
     */
    suspend fun process(
        pending: List<BookSource>,
        booksOut: File,
        existingMd5: (suspend (String) -> Boolean) = { false },
        onProgress: ((processed: Int, total: Int) -> Unit)? = null,
        existingRecord: (suspend (String) -> BookRecord?)? = null,
    ): ImportResult {
        val seenThisBatch = HashMap<String, BookRecord>(minOf(pending.size, BookRules.MAX_FILES))
        val records = ArrayList<BookRecord>(pending.size)
        val covers = LinkedHashMap<String, BookCover>(pending.size)
        val failures = ArrayList<BookFailure>()
        var duplicates = 0
        var unsupported = 0
        var taken = 0

        for (book in pending) {
            if (taken >= BookRules.MAX_FILES) continue
            taken++
            var uncommitted: BookRecord? = null
            try {
                val prepared = importInternal(book, booksOut) { md5 ->
                    existingRecord?.invoke(md5) ?: seenThisBatch[md5]
                }
                when (val outcome = prepared.outcome) {
                    is ImportOutcome.Failure -> {
                        when (outcome.reason) {
                            is ImportFailure.Unsupported, ImportFailure.Unknown -> unsupported++
                            else -> failures.add(BookFailure(book.name, failureMessage(outcome.reason)))
                        }
                    }
                    is ImportOutcome.Success -> {
                        val record = outcome.record
                        if (outcome.duplicate) {
                            duplicates++
                        } else {
                            uncommitted = record
                            if (existingMd5(record.md5)) {
                                deleteCopiedFile(record)
                                duplicates++
                            } else {
                                seenThisBatch[record.md5] = record
                                records.add(record)
                                prepared.cover?.let { covers[record.key] = it }
                            }
                            uncommitted = null
                        }
                    }
                }
            } catch (e: CancellationException) {
                uncommitted?.let { record ->
                    runCatching { deleteCopiedFile(record) }.exceptionOrNull()?.let(e::addSuppressed)
                }
                throw e
            } catch (e: Exception) {
                uncommitted?.let { record ->
                    runCatching { deleteCopiedFile(record) }.exceptionOrNull()?.let(e::addSuppressed)
                }
                failures.add(BookFailure(book.name, e.message?.take(200) ?: "error"))
            }
            onProgress?.invoke(taken, pending.size)
        }

        return ImportResult(records, covers, duplicates, unsupported, failures)
    }

    private suspend fun importInternal(
        book: BookSource,
        booksOut: File,
        existingBook: (suspend (String) -> BookRecord?),
    ): PreparedImport {
        var copied: File? = null
        try {
            if (!booksOut.isDirectory && !booksOut.mkdirs()) {
                throw IOException("cannot create books dir: ${booksOut.path}")
            }
            val available = booksOut.usableSpace
            book.size?.let { required ->
                if (required > available) {
                    return PreparedImport(
                        ImportOutcome.Failure(ImportFailure.InsufficientSpace(required, available)),
                    )
                }
            }
            val detection = try {
                BookFormat.detect(book.name, book.mime, book.openStream)
            } catch (e: IOException) {
                if (!BookRules.isBookName(book.name)) {
                    return PreparedImport(
                        ImportOutcome.Failure(ImportFailure.Unsupported("unsupported format")),
                    )
                }
                throw e
            }
            val format = when (detection) {
                is FormatDetection.Supported -> detection.format
                is FormatDetection.Unsupported -> return PreparedImport(
                    ImportOutcome.Failure(ImportFailure.Unsupported(detection.reason)),
                )
                FormatDetection.Unknown -> return PreparedImport(
                    ImportOutcome.Failure(ImportFailure.Unknown),
                )
            }

            val key = BookRules.buildBookKey(clock(), random())
            val stored = AtomicBookStore(booksOut).copy(book.openStream(), "$key.${format.extension}")
            copied = stored.file
            val existing = existingBook(stored.md5)
            if (existing != null) {
                deleteFile(stored.file)
                copied = null
                return PreparedImport(ImportOutcome.Success(existing, duplicate = true))
            }

            val fallbackName = BookRules.bookNameFromFile(book.name)
            var epub: EpubResult? = null
            val metadata = when (format) {
                BookFormat.PDF -> {
                    val extracted = try {
                        pdfMetadataExtractor?.invoke(stored.file)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    extracted ?: BookMetadata(fallbackName, null, null, null)
                }
                BookFormat.EPUB -> {
                    val parsed = runCatching { epubParser(stored.file) }.getOrNull()
                    epub = parsed
                    parsed?.metadata?.let {
                        BookMetadata(it.title, it.creator, it.description, it.publisher)
                    } ?: BookMetadata(fallbackName, null, null, null)
                }
                else -> MetadataParsers.parse(format, stored.file, fallbackName)
            }
            var record = BookRecord(
                key = key,
                name = metadata.name?.takeIf { it.isNotBlank() } ?: fallbackName,
                author = metadata.author?.takeIf { it.isNotBlank() },
                description = metadata.description?.takeIf { it.isNotBlank() },
                md5 = stored.md5,
                format = format.extension.uppercase(Locale.ROOT),
                publisher = metadata.publisher?.takeIf { it.isNotBlank() },
                size = stored.file.length(),
                page = null,
                path = stored.file.path,
            )
            var cover: BookCover? = null
            when (format.extension) {
                "epub" -> {
                    cover = epub?.cover
                }
                "cbz" -> {
                    ComicCover.extract(stored.file)?.let { comic ->
                        if (comic.pageCount > 0) record = record.copy(page = comic.pageCount.toLong())
                        cover = comic.cover
                    }
                }
                "mobi", "azw", "azw3" -> cover = MobiCover.extract(stored.file)
                else -> extraCoverExtractors[format.extension]?.let { extractor ->
                    cover = runCatching { extractor(stored.file) }.getOrNull()
                }
            }
            copied = null
            return PreparedImport(ImportOutcome.Success(record, duplicate = false), cover)
        } catch (e: CancellationException) {
            copied?.let { file ->
                runCatching { deleteFile(file) }.exceptionOrNull()?.let(e::addSuppressed)
            }
            throw e
        } catch (e: Exception) {
            copied?.let { file ->
                runCatching { deleteFile(file) }.exceptionOrNull()?.let(e::addSuppressed)
            }
            return PreparedImport(
                ImportOutcome.Failure(
                    ImportFailure.Io(e.message?.take(200) ?: e::class.simpleName ?: "error"),
                ),
            )
        }
    }

    private fun deleteCopiedFile(record: BookRecord) {
        record.path?.let { deleteFile(File(it)) }
    }

    private fun deleteFile(file: File) {
        if (file.exists() && !file.delete()) throw IOException("cannot delete book file: ${file.path}")
    }

    private fun failureMessage(failure: ImportFailure): String = when (failure) {
        is ImportFailure.Unsupported -> failure.reason
        ImportFailure.Unknown -> "unknown format"
        is ImportFailure.InsufficientSpace ->
            "insufficient space: ${failure.required} bytes required, ${failure.available} available"
        is ImportFailure.Io -> failure.message
    }
}
