package com.koodoreader.engine.mobi

/** MOBI `compression` header field (record 0, offset 0). */
object MobiCompression {
    /** No compression — each text record is plain bytes. */
    const val NONE = 1

    /** PalmDOC LZ77 variant — the format almost every MOBI6/AZW file uses. */
    const val PALMDOC = 2

    /** HUFF/CDIC — declared, explicitly not implemented (typed error). */
    const val HUFF_CDIC = 17480
}

/** MOBI `encryptionType` header field (record 0, offset 12). */
object MobiEncryption {
    const val NONE = 0
    const val OLD_MOBI = 1
    const val MOBI = 2
}

/** EXTH record types consumed by the metadata reader. */
object ExthType {
    const val AUTHOR = 100
    const val PUBLISHER = 101
    const val DESCRIPTION = 103
    const val ISBN = 104
    const val SUBJECT = 105
    const val PUBLISHING_DATE = 106
    const val CONTRIBUTOR = 108
    const val KF8_BOUNDARY = 121
    const val COVER_OFFSET = 201
    const val THUMBNAIL_OFFSET = 202
    const val TITLE = 503
    const val LANGUAGE = 524
}

/**
 * EXTH metadata. All fields are best-effort: a missing record type yields
 * `null` (or an empty list) rather than an error.
 */
data class MobiMetadata(
    val title: String? = null,
    val authors: List<String> = emptyList(),
    val publisher: String? = null,
    val description: String? = null,
    val isbn: String? = null,
    val subjects: List<String> = emptyList(),
    val date: String? = null,
    val contributor: String? = null,
    val language: String? = null,
    /** EXTH 201 — image index of the cover, relative to the first image record. */
    val coverOffset: Int? = null,
    /** EXTH 202 — image index of the thumbnail, same relativity as [coverOffset]. */
    val thumbnailOffset: Int? = null,
    /** EXTH 121 — non-zero means KF8 (AZW3) content follows a `BOUNDARY` record. */
    val kf8Boundary: Int? = null,
    /** True when the file is a KF8 container (EXTH 121 and/or a BOUNDARY record). */
    val kf8: Boolean = false,
) {
    val author: String? get() = authors.firstOrNull()
}

/**
 * One image record extracted from the image/chapter region of the file.
 *
 * @param index PalmDB record index the resource lives in.
 * @param mediaType sniffed from magic bytes: `image/jpeg`, `image/png`,
 *   `image/gif` or `application/octet-stream` when the record is not a
 *   recognised image.
 */
data class MobiResource(
    val index: Int,
    val mediaType: String,
    val data: ByteArray,
) {
    val isImage: Boolean get() = mediaType.startsWith("image/")

    // ByteArray in a data class needs structural equals/hashCode.
    override fun equals(other: Any?): Boolean =
        this === other || (other is MobiResource && index == other.index &&
            mediaType == other.mediaType && data.contentEquals(other.data))

    override fun hashCode(): Int =
        (index * 31 + mediaType.hashCode()) * 31 + data.contentHashCode()
}

/**
 * Parsed MOBI book.
 *
 * @param text concatenated, decompressed text records with the original HTML
 *   markup preserved (cleaning/sanitising is the caller's job).
 * @param encoding the IANA-ish name derived from the MOBI `textEncoding`
 *   header field (`windows-1252` or `UTF-8`).
 * @param compression the raw `compression` header value (see [MobiCompression]).
 * @param cover the cover image bytes, or `null` when the file has none.
 */
data class MobiBook(
    val metadata: MobiMetadata,
    val text: String,
    val resources: List<MobiResource>,
    val cover: ByteArray?,
    val compression: Int,
    val encoding: String,
    /** PalmDB database name (the 32-byte NUL-padded header field). */
    val pdbName: String = "",
    val kf8: Boolean = false,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is MobiBook && metadata == other.metadata &&
            text == other.text && resources == other.resources &&
            cover.contentEqualsNullable(other.cover) && compression == other.compression &&
            encoding == other.encoding && pdbName == other.pdbName && kf8 == other.kf8)

    override fun hashCode(): Int {
        var result = metadata.hashCode()
        result = 31 * result + text.hashCode()
        result = 31 * result + resources.hashCode()
        result = 31 * result + (cover?.contentHashCode() ?: 0)
        result = 31 * result + compression
        result = 31 * result + encoding.hashCode()
        return result
    }
}

private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean = when {
    this == null -> other == null
    other == null -> false
    else -> contentEquals(other)
}
