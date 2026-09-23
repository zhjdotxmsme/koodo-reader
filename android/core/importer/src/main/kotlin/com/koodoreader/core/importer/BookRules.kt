package com.koodoreader.core.importer

import java.util.Locale
import kotlin.random.Random

/**
 * Book-file import rules — the KOTLIN SINGLE SOURCE OF TRUTH for the native
 * track (P1: "SAF 导入（规则迁 Kotlin 为单一事实源）").
 *
 * Semantics are a 1:1 port of `src/utils/android/folderBridge.js`, which the
 * WebView track keeps using until P8. CI runs `node scripts/check-import-rules.js`
 * to fail on drift between the two.
 *
 * Book-row key/name/format generation mirrors the desktop importer
 * (`src/components/importLocal/component.tsx`): key = timestamp + random
 * 3-digit suffix, name = file name without extension, format = upper-case
 * extension — so rows round-trip between desktop and Android `.db` files.
 */
object BookRules {
    /** Name of the folder-picked event (WebView-track protocol constant). */
    const val FOLDER_EVENT: String = "folder-picked"

    /** Maximum number of files enumerated / delivered (folderBridge MAX_FILES). */
    const val MAX_FILES: Int = 1000

    /** Directory depth below the root to enumerate (folderBridge FOLDER_DEPTH). */
    const val FOLDER_DEPTH: Int = 2

    /** Book extensions the reader supports (lower-case), same order as JS. */
    val BOOK_EXTENSIONS: List<String> = listOf(
        "epub", "pdf", "mobi", "azw3", "azw", "txt", "fb2",
        "cbz", "cbr", "cbt", "cb7", "md", "docx",
        "html", "htm", "xhtml", "mhtml",
    )

    /** Canonical MIME type per book extension. */
    val MIME_BY_EXT: Map<String, String> = mapOf(
        "epub" to "application/epub+zip",
        "pdf" to "application/pdf",
        "mobi" to "application/x-mobipocket-ebook",
        "azw3" to "application/vnd.amazon.ebook",
        "azw" to "application/vnd.amazon.ebook",
        "txt" to "text/plain",
        "fb2" to "application/x-fictionbook+xml",
        "cbz" to "application/x-cbz",
        "cbr" to "application/x-cbr",
        "cbt" to "application/x-cbt",
        "cb7" to "application/x-cb7",
        "md" to "text/markdown",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "html" to "text/html",
        "htm" to "text/html",
        "xhtml" to "application/xhtml+xml",
        "mhtml" to "message/rfc822",
    )

    /**
     * Lower-cased extension of a file name (without the dot), or "" when there
     * is none. "archive.tar.epub" -> "epub"; "readme" -> "".
     * Null input (unknown type in the JS original) yields "".
     */
    fun getExt(name: String?): String {
        if (name.isNullOrEmpty()) return ""
        val idx = name.lastIndexOf('.')
        if (idx <= 0 || idx == name.length - 1) return ""
        return name.substring(idx + 1).lowercase(Locale.ROOT)
    }

    /** Whether a file name is a supported book (case-insensitive). */
    fun isBookName(name: String?): Boolean = getExt(name) in BOOK_EXTENSIONS

    /** Canonical MIME type for a file name's extension ("" if not a known book). */
    fun mimeForExt(name: String?): String = MIME_BY_EXT[getExt(name)] ?: ""

    /**
     * Desktop-compatible book key: `timestamp + random 0..999`, string
     * concatenated exactly like `new Date().getTime() + "" + Math.floor(Math.random() * 1000)`
     * in importLocal/component.tsx.
     */
    fun generateBookKey(
        timestampMillis: Long = System.currentTimeMillis(),
        random: Int = Random.nextInt(1000),
    ): String = buildBookKey(timestampMillis, random)

    /** Pure form of [generateBookKey] (deterministic, for tests). */
    fun buildBookKey(timestampMillis: Long, random: Int): String =
        "$timestampMillis$random"

    /**
     * Book display name: file name with its last extension stripped, matching
     * desktop `file.name.substr(0, file.name.length - extension.length - 1)`.
     * Names without an extension are returned unchanged.
     */
    fun bookNameFromFile(fileName: String): String {
        val ext = getExt(fileName)
        if (ext.isEmpty()) return fileName
        return fileName.substring(0, fileName.length - ext.length - 1)
    }

    /** Book format column: upper-case extension, e.g. "epub" -> "EPUB". */
    fun formatFromFile(fileName: String): String =
        getExt(fileName).uppercase(Locale.ROOT)
}

/**
 * One raw entry as produced by the host / SAF enumerator. [name] null or ""
 * marks an invalid entry; [size] null marks a missing/unknown size.
 */
data class RawFolderEntry(
    val name: String?,
    val uri: String = "",
    val size: Long? = null,
    val mime: String? = null,
)

/** A normalized, supported book file after [filterBooks]. */
data class FolderBookEntry(
    val name: String,
    val uri: String,
    val size: Long?,
    val mime: String,
)

/**
 * Filter a raw folder file list down to supported books, normalize each entry,
 * and cap the result at [BookRules.MAX_FILES]. Pure; order is preserved.
 * Non-book / invalid entries are dropped.
 *
 * MIME resolution mirrors JS `normalizeEntry`: canonical MIME by extension,
 * else the raw entry's MIME, else "".
 *
 * @throws IllegalArgumentException when [files] is null (the JS original's
 *   `FOLDER_LIST_INVALID` typed error — Kotlin's type system makes the
 *   non-list case impossible).
 */
fun filterBooks(files: List<RawFolderEntry?>?): List<FolderBookEntry> {
    requireNotNull(files) {
        "FOLDER_LIST_INVALID: filterBooks(files) expects a folder file list array"
    }
    val books = ArrayList<FolderBookEntry>(minOf(files.size, BookRules.MAX_FILES))
    for (file in files) {
        val name = file?.name
        if (name.isNullOrEmpty() || !BookRules.isBookName(name)) continue
        if (books.size >= BookRules.MAX_FILES) break
        val ext = BookRules.getExt(name)
        val mime = BookRules.MIME_BY_EXT[ext]?.takeIf { it.isNotEmpty() }
            ?: file.mime?.takeIf { it.isNotEmpty() }
            ?: ""
        books.add(FolderBookEntry(name, file.uri, file.size, mime))
    }
    return books
}
