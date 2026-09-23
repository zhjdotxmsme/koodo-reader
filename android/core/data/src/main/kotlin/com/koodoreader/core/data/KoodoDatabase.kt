package com.koodoreader.core.data

import androidx.room.Database
import androidx.room.RoomDatabase
import com.koodoreader.core.data.dao.BookDao
import com.koodoreader.core.data.dao.BookmarkDao
import com.koodoreader.core.data.dao.NoteDao
import com.koodoreader.core.data.dao.PluginDao
import com.koodoreader.core.data.dao.WordDao
import com.koodoreader.core.data.entity.BookEntity
import com.koodoreader.core.data.entity.BookmarkEntity
import com.koodoreader.core.data.entity.NoteEntity
import com.koodoreader.core.data.entity.PluginEntity
import com.koodoreader.core.data.entity.WordEntity

/**
 * Native persistence root.
 *
 * One Room database holds the five desktop tables (books/notes/bookmarks/
 * plugins/words). The desktop splits them into separate `.db` files plus
 * `temp-*` session copies; on Android the temp copies are unnecessary (the
 * desktop uses them as an editing scratch space synced back on save — the
 * native client writes directly).
 *
 * Column names are frozen by `schema.lock` + `scripts/check-room-schema.js`
 * (see module build.gradle for the type-affinity caveat on desktop import).
 */
@Database(
    entities = [
        BookEntity::class,
        NoteEntity::class,
        BookmarkEntity::class,
        PluginEntity::class,
        WordEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class KoodoDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun noteDao(): NoteDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun pluginDao(): PluginDao
    abstract fun wordDao(): WordDao

    companion object {
        const val NAME = "koodo.db"
    }
}
