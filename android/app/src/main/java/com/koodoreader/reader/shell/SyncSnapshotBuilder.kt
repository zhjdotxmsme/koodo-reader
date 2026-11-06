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

    suspend fun build(
        db: KoodoDatabase,
        progress: ReaderProgressPrefs,
        settings: Map<String, String>,
    ): SyncSnapshot {
        val books = db.bookDao().observeAll().first()
        val notes = db.noteDao().observeAll().first()
        val bookmarks = db.bookmarkDao().observeAll().first()
        val progressRows = books.mapNotNull { b ->
            val cfi = progress.cfiOf(b.key) ?: return@mapNotNull null
            ProgressSnapshot(b.key, cfi, progress.fractionOf(b.key))
        }
        return SyncSnapshot(
            books = books.map {
                BookSnapshot(it.key, it.name, it.author, it.md5, it.format)
            },
            progress = progressRows,
            annotations = notes.map {
                AnnotationSnapshot(
                    it.key,
                    it.bookKey,
                    it.chapter,
                    it.text,
                    it.notes,
                    it.color,
                )
            },
            bookmarks = bookmarks.map {
                BookmarkSnapshot(it.key, it.bookKey, it.cfi, it.label ?: it.chapter)
            },
            settings = settings,
        )
    }

    fun encode(snapshot: SyncSnapshot): ByteArray =
        SyncSnapshotCodec.encode(snapshot).toByteArray(Charsets.UTF_8)

    suspend fun decodeAndApply(db: KoodoDatabase, bytes: ByteArray, progress: ReaderProgressPrefs) {
        val snapshot = SyncSnapshotCodec.decode(bytes.decodeToString())
        snapshot.annotations.forEach { row ->
            db.noteDao().upsert(
                NoteEntity(
                    key = row.key,
                    bookKey = row.bookKey.orEmpty(),
                    chapter = row.chapter,
                    text = row.text,
                    notes = row.notes,
                    color = row.color,
                ),
            )
        }
        snapshot.bookmarks.forEach { row ->
            db.bookmarkDao().upsert(
                BookmarkEntity(
                    key = row.key,
                    bookKey = row.bookKey.orEmpty(),
                    chapter = row.label,
                    cfi = row.cfi,
                    label = row.label,
                ),
            )
        }
        snapshot.progress.forEach { row ->
            val cfi = row.cfi ?: return@forEach
            progress.save(row.bookKey, cfi, row.fraction ?: 0.0)
        }
    }
}
