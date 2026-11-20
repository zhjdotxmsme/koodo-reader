package com.koodoreader.reader.shell

import com.koodoreader.core.data.KoodoDatabase
import com.koodoreader.core.data.entity.BookmarkEntity
import com.koodoreader.core.data.entity.NoteEntity
import com.koodoreader.core.sync.AnnotationSnapshot
import com.koodoreader.core.sync.BookSnapshot
import com.koodoreader.core.sync.BookmarkSnapshot
import com.koodoreader.core.sync.ProgressSnapshot
import com.koodoreader.core.sync.SyncSnapshot
import com.koodoreader.core.sync.SyncSnapshotCodec
import kotlinx.coroutines.flow.first

/** Room → [SyncSnapshot] for [com.koodoreader.core.sync.SyncCoordinator] transports. */
object SyncSnapshotBuilder {

    /** What a pull actually changed on this device. */
    data class ApplyReport(
        val progressApplied: Int = 0,
        val notesAdded: Int = 0,
        val notesKept: Int = 0,
        val bookmarksAdded: Int = 0,
        val bookmarksKept: Int = 0,
    )

    suspend fun build(
        db: KoodoDatabase,
        progress: ReaderProgressPrefs,
        settings: Map<String, String>,
    ): SyncSnapshot {
        val books = db.bookDao().observeAll().first()
        val notes = db.noteDao().observeAll().first()
        val bookmarks = db.bookmarkDao().observeAll().first()
        return SyncSnapshot(
            books = books.map {
                BookSnapshot(it.key, it.name, it.author, it.md5, it.format)
            },
            progress = progressRows(db, progress),
            annotations = notes.map {
                AnnotationSnapshot(
                    key = it.key,
                    bookKey = it.bookKey,
                    chapter = it.chapter,
                    text = it.text,
                    notes = it.notes,
                    color = it.color,
                    cfi = it.cfi,
                    percentage = it.percentage,
                )
            },
            bookmarks = bookmarks.map {
                BookmarkSnapshot(it.key, it.bookKey, it.cfi, it.label ?: it.chapter)
            },
            settings = settings,
        )
    }

    /**
     * The device's reading positions, stamped with their last write time.
     *
     * The timestamp is what lets [com.koodoreader.core.sync.SyncMerge] tell a
     * stale remote row from a genuinely newer one; without it a pull can only
     * move forwards.
     */
    suspend fun progressRows(
        db: KoodoDatabase,
        progress: ReaderProgressPrefs,
    ): List<ProgressSnapshot> =
        db.bookDao().observeAll().first().mapNotNull { book ->
            val cfi = progress.cfiOf(book.key) ?: return@mapNotNull null
            ProgressSnapshot(
                bookKey = book.key,
                cfi = cfi,
                fraction = progress.fractionOf(book.key),
                updatedAt = progress.updatedAtOf(book.key),
            )
        }

    fun encode(snapshot: SyncSnapshot): ByteArray =
        SyncSnapshotCodec.encode(snapshot).toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): SyncSnapshot =
        SyncSnapshotCodec.decode(bytes.decodeToString())

    /**
     * Applies the rows chosen by the merge plan.
     *
     * Notes and bookmarks are merged **additively**: a row whose key is absent
     * locally is inserted, but an existing row is never overwritten. Remote data
     * must not silently replace a highlight body the user edited on this
     * device — that was the second half of the old overwrite bug.
     *
     * @param rows progress rows to write (already resolved by the caller).
     * @param now fallback timestamp for peers that predate the timestamp field.
     */
    suspend fun apply(
        db: KoodoDatabase,
        snapshot: SyncSnapshot,
        progress: ReaderProgressPrefs,
        rows: List<ProgressSnapshot>,
        now: Long = System.currentTimeMillis(),
    ): ApplyReport {
        var progressApplied = 0
        rows.forEach { row ->
            val cfi = row.cfi ?: return@forEach
            if (row.bookKey.isBlank()) return@forEach
            progress.save(
                bookKey = row.bookKey,
                cfi = cfi,
                fraction = row.fraction ?: 0.0,
                updatedAt = row.updatedAt ?: now,
            )
            progressApplied++
        }

        val existingNotes = db.noteDao().observeAll().first().associateBy { it.key }
        var notesAdded = 0
        var notesKept = 0
        snapshot.annotations.forEach { row ->
            val current = existingNotes[row.key]
            if (current == null) {
                db.noteDao().upsert(
                    NoteEntity(
                        key = row.key,
                        bookKey = row.bookKey.orEmpty(),
                        chapter = row.chapter,
                        text = row.text,
                        cfi = row.cfi,
                        notes = row.notes,
                        percentage = row.percentage,
                        color = row.color,
                    ),
                )
                notesAdded++
            } else {
                // Fill in fields this device never had, keep everything else.
                val merged = current.copy(
                    chapter = current.chapter?.takeIf { it.isNotBlank() } ?: row.chapter,
                    text = current.text?.takeIf { it.isNotBlank() } ?: row.text,
                    cfi = current.cfi?.takeIf { it.isNotBlank() } ?: row.cfi,
                    notes = current.notes?.takeIf { it.isNotBlank() } ?: row.notes,
                    percentage = current.percentage?.takeIf { it.isNotBlank() } ?: row.percentage,
                    color = current.color ?: row.color,
                )
                if (merged != current) db.noteDao().upsert(merged) else notesKept++
            }
        }

        val existingBookmarks = db.bookmarkDao().observeAll().first().associateBy { it.key }
        var bookmarksAdded = 0
        var bookmarksKept = 0
        snapshot.bookmarks.forEach { row ->
            if (existingBookmarks.containsKey(row.key)) {
                bookmarksKept++
            } else {
                db.bookmarkDao().upsert(
                    BookmarkEntity(
                        key = row.key,
                        bookKey = row.bookKey.orEmpty(),
                        chapter = row.label,
                        cfi = row.cfi,
                        label = row.label,
                    ),
                )
                bookmarksAdded++
            }
        }

        return ApplyReport(
            progressApplied = progressApplied,
            notesAdded = notesAdded,
            notesKept = notesKept,
            bookmarksAdded = bookmarksAdded,
            bookmarksKept = bookmarksKept,
        )
    }
}
