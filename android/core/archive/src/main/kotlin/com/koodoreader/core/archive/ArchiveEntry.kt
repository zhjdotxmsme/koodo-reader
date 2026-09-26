package com.koodoreader.core.archive

/**
 * One entry of an archive, in archive order. [name] is the raw zip entry
 * name (forward slashes, may contain directory prefixes); [sizeBytes] is the
 * UNCOMPRESSED size (-1 when the writer used a data descriptor and did not
 * backfill it — streamed zips do this; see engine/image PageEntry for the
 * same convention).
 */
data class ArchiveEntry(
    val name: String,
    val sizeBytes: Long,
    val isDirectory: Boolean,
)
