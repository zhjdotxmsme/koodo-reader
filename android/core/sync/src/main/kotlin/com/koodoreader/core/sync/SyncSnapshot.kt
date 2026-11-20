package com.koodoreader.core.sync

data class BookSnapshot(
    val bookKey: String,
    val name: String?,
    val author: String?,
    val md5: String?,
    val format: String?,
)

data class ProgressSnapshot(
    val bookKey: String,
    val cfi: String?,
    val fraction: Double?,
    /**
     * Wall-clock millis of the last write, or null for snapshots written by a
     * build that predates the field.
     *
     * Optional so the JSON stays backward/forward compatible: peers that do not
     * know the field ignore it, and a row without it only ever causes a
     * conflict prompt (never a silent overwrite).
     */
    val updatedAt: Long? = null,
)

data class AnnotationSnapshot(
    val key: String,
    val bookKey: String?,
    val chapter: String?,
    val text: String?,
    val notes: String?,
    val color: Long?,
    /**
     * EPUB CFI anchor (ADR-002). Optional because snapshots written before this
     * field existed — and by peers that only ship the body — must still decode;
     * without it a synced highlight has no position to reappear at.
     */
    val cfi: String? = null,
    /** Desktop `percentage` payload, kept as a string for wire compatibility. */
    val percentage: String? = null,
)

data class BookmarkSnapshot(
    val key: String,
    val bookKey: String?,
    val cfi: String?,
    val label: String?,
)

data class ReadingSessionSnapshot(
    val key: String,
    val bookKey: String,
    val day: String,
    val seconds: Long,
)

data class SyncSnapshot(
    val version: Int = 1,
    val books: List<BookSnapshot> = emptyList(),
    val progress: List<ProgressSnapshot> = emptyList(),
    val annotations: List<AnnotationSnapshot> = emptyList(),
    val bookmarks: List<BookmarkSnapshot> = emptyList(),
    val settings: Map<String, String> = emptyMap(),
    val statistics: List<ReadingSessionSnapshot> = emptyList(),
) {
    companion object {
        fun fixture(): SyncSnapshot = SyncSnapshot(
            books = listOf(BookSnapshot("k1", "Title", "Author", "md5", "epub")),
            progress = listOf(ProgressSnapshot("k1", "epubcfi(/6/2!/4/1:0)", 0.12)),
            annotations = listOf(
                AnnotationSnapshot("n1", "k1", "Ch1", "selected", "note body", 0xFFFF00L),
            ),
            bookmarks = listOf(BookmarkSnapshot("b1", "k1", "epubcfi(/6/2!/4/1:0)", "here")),
            settings = mapOf("theme" to "default"),
            statistics = listOf(ReadingSessionSnapshot("s1", "k1", "2026-11-02", 120)),
        )
    }
}

class SyncVersionException(val unsupportedVersion: Int) :
    IllegalArgumentException("unsupported sync snapshot version: $unsupportedVersion")
