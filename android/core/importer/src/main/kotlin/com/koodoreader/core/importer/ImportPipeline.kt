package com.koodoreader.core.importer

import java.io.File
import java.io.InputStream

/**
 * One import candidate: [name] + [openStream] (fresh stream per call) +
 * optional [size] hint. SAF `content://` URIs, plain files (tests), HTTP
 * blobs — anything that can hand out a byte stream.
 */
data class BookSource(
    val name: String,
    val openStream: () -> InputStream,
    val size: Long? = null,
)

/**
 * One imported book, column-for-column with `books` (schema.lock /
 * BookEntity). [cover] stays null for native imports: the image is written
 * to the host's `cover/` directory (desktop `CoverUtil.addCover` parity),
 * never inlined into the row.
 */
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
 * The native-track book import pipeline (P1) — pure JVM, deterministic
 * (clock/random injectable), side effects limited to the documented copies.
 *
 * Steps per book:
 *  1. format whitelist ([BookRules.isBookName]) — rejects counted `unsupported`;
 *  2. streaming copy into `booksOut/<key>.<ext>` (desktop local-storage
 *     parity; the P2 native engine reads this stable path, so SAF permission
 *     lifetime never affects reading);
 *  3. streaming MD5 ([FileMd5]) — dedupe against [existingMd5] (Room
 *     `getByMd5`, desktop `getBookByMd5` semantics) and against earlier books
 *     in THIS batch (same file picked twice / duplicated in a folder tree);
 *  4. format-specific enrichment: EPUB metadata + cover ([EpubBook]),
 *     CBZ cover + page count ([ComicCover]); all other formats get basic
 *     fields only (name/file, format, size) — deep parsing is P2+ scope;
 *  5. [BookRecord] (+ optional [BookCover]) into the result.
 *
 * A book failing any step lands in [ImportResult.failures]; the batch
 * continues — one bad book never kills 999 good ones.
 */
class ImportPipeline(
    /** Injectable clock (tests): ms since epoch for [BookRules.buildBookKey]. */
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** Injectable 0..999 random (tests): suffix of [BookRules.buildBookKey]. */
    private val random: () -> Int = { (0..999).random() },
    /**
     * Host-provided cover extractors for formats the pure-JVM module cannot
     * handle (e.g. `"pdf"` → PdfRenderer on Android). Failures degrade to no
     * cover, exactly like the built-in extractors.
     */
    private val extraCoverExtractors: Map<String, (File) -> BookCover?> = emptyMap(),
) {

    /**
     * @param existingMd5 dedupe hook (suspend — callers pass Room queries);
     *   content hit in the DB or an earlier book of this batch = duplicate.
     * @param onProgress optional per-book progress hook (processed, total);
     *   invoked once per candidate examined.
     */
    suspend fun process(
        pending: List<BookSource>,
        booksOut: File,
        existingMd5: (suspend (String) -> Boolean) = { false },
        onProgress: ((processed: Int, total: Int) -> Unit)? = null,
    ): ImportResult {
        val seenThisBatch = HashSet<String>(minOf(pending.size, BookRules.MAX_FILES))
        val records = ArrayList<BookRecord>(pending.size)
        val covers = LinkedHashMap<String, BookCover>(pending.size)
        val failures = ArrayList<BookFailure>()
        var duplicates = 0
        var unsupported = 0
        var taken = 0

        for (book in pending) {
            if (taken >= BookRules.MAX_FILES) continue
            taken++

            val name = book.name
            val ext = BookRules.getExt(name)
            if (ext.isEmpty() || !BookRules.isBookName(name)) {
                unsupported++
                continue
            }

            // key = timestamp + random3 (importLocal desktop parity, comic
            // branch); uniqueness inside the batch does not depend on it
            // because dedupe is content-based (md5).
            val key = BookRules.buildBookKey(clock(), random())
            val dest = File(booksOut, "$key.$ext")
            try {
                if (!booksOut.isDirectory && !booksOut.mkdirs()) {
                    throw IllegalStateException("cannot create books dir: ${booksOut.path}")
                }
                streamCopy(book.openStream(), dest)

                val md5 = FileMd5.ofFile(dest)
                if (existingMd5(md5) || !seenThisBatch.add(md5)) {
                    duplicates++
                    dest.delete() // deduped: leave no orphan on disk
                    continue
                }

                var record = BookRecord(
                    key = key,
                    name = BookRules.bookNameFromFile(name),
                    author = null,
                    description = null,
                    md5 = md5,
                    format = BookRules.formatFromFile(name),
                    publisher = null,
                    size = runCatching { dest.length() }.getOrNull(),
                    page = null,
                    path = dest.path,
                )
                var cover: BookCover? = null
                when (ext) {
                    "epub" -> {
                        // Malformed OPF degrades to the basic record (still
                        // importable; no cover) instead of failing the book.
                        runCatching { EpubBook.parse(dest) }.getOrNull()?.let { parsed ->
                            record = record.copy(
                                name = parsed.metadata.title ?: record.name,
                                author = parsed.metadata.creator,
                                description = parsed.metadata.description,
                                publisher = parsed.metadata.publisher,
                            )
                            cover = parsed.cover
                        }
                    }
                    "cbz" -> {
                        ComicCover.extract(dest)?.let { comic ->
                            if (comic.pageCount > 0) {
                                record = record.copy(page = comic.pageCount.toLong())
                            }
                            cover = comic.cover
                        }
                    }
                    "mobi", "azw", "azw3" -> {
                        cover = MobiCover.extract(dest)
                    }
                    else -> {
                        extraCoverExtractors[ext]?.let { extractor ->
                            cover = runCatching { extractor(dest) }.getOrNull()
                        }
                    }
                }
                records.add(record)
                // `cover` is captured by the lambdas above, so no smart-cast.
                cover?.let { covers[key] = it }
            } catch (e: Exception) {
                dest.delete()
                failures.add(
                    BookFailure(name, e.message?.take(200) ?: e::class.simpleName ?: "error"),
                )
            }
            onProgress?.invoke(taken, pending.size)
        }

        return ImportResult(records, covers, duplicates, unsupported, failures)
    }

    // ---- file IO -------------------------------------------------------------

    private fun streamCopy(input: InputStream, dst: File) {
        input.use {
            dst.outputStream().use { output ->
                val buffer = ByteArray(FileMd5.CHUNK_SIZE)
                var n = input.read(buffer)
                while (n >= 0) {
                    if (n > 0) output.write(buffer, 0, n)
                    if (n < buffer.size) break
                    n = input.read(buffer)
                }
            }
        }
    }
}
