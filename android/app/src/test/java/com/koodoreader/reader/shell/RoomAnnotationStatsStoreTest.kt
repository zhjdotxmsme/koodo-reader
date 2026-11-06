package com.koodoreader.reader.shell

import com.koodoreader.core.data.entity.NoteEntity
import com.koodoreader.feature.stats.AnnotationEventKind
import com.koodoreader.feature.stats.IsoDay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RoomAnnotationStatsStoreTest {

    @Test
    fun `note rows map to highlight and note events by key millis day`() = runTest {
        val day = IsoDay(2026, 11, 2)
        val noonUtc = day.epochDay * 86_400_000L
        val store = RoomAnnotationStatsStore(
            noteDao = FakeNoteDao(
                listOf(
                    NoteEntity(key = noonUtc.toString(), bookKey = "b", notes = "", text = "x"),
                    NoteEntity(key = (noonUtc + 1).toString(), bookKey = "b", notes = "body", text = "y"),
                ),
            ),
            nowMillis = { noonUtc },
        )
        val events = store.events()
        assertEquals(2, events.size)
        assertEquals(1, events.count { it.kind == AnnotationEventKind.HIGHLIGHT })
        assertEquals(1, events.count { it.kind == AnnotationEventKind.NOTE })
    }

    private class FakeNoteDao(private val rows: List<NoteEntity>) : com.koodoreader.core.data.dao.NoteDao {
        override fun observeForBook(bookKey: String): Flow<List<NoteEntity>> = flowOf(emptyList())
        override fun observeAll(): Flow<List<NoteEntity>> = flowOf(rows)
        override suspend fun upsert(note: NoteEntity) = Unit
        override suspend fun upsertAll(notes: List<NoteEntity>) = Unit
        override suspend fun getByKey(key: String): NoteEntity? = null
        override suspend fun deleteByKey(key: String) = Unit
        override suspend fun deleteForBook(bookKey: String) = Unit
        override suspend fun count(): Long = rows.size.toLong()
        override fun observeCountForBook(bookKey: String): Flow<Long> = flowOf(0)
    }
}
