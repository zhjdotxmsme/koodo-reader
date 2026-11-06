package com.koodoreader.reader.shell

import com.koodoreader.core.data.dao.NoteDao
import com.koodoreader.core.data.entity.NoteEntity
import com.koodoreader.feature.stats.AnnotationEvent
import com.koodoreader.feature.stats.AnnotationEvents
import com.koodoreader.feature.stats.AnnotationStatsStore
import com.koodoreader.feature.stats.IsoDay
import com.koodoreader.feature.stats.platform.ReadingSessionWiring
import kotlinx.coroutines.flow.first

class RoomAnnotationStatsStore(
    private val noteDao: NoteDao,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) : AnnotationStatsStore {

    override suspend fun events(): List<AnnotationEvent> {
        val now = nowMillis()
        val offset = ReadingSessionWiring.zoneOffsetMinutesAt(now)
        val rows = noteDao.observeAll().first().map { it.toNoteRow(now, offset) }
        return AnnotationEvents.fromRows(rows)
    }

    private fun NoteEntity.toNoteRow(now: Long, zoneOffsetMinutes: Int): AnnotationEvents.NoteRow {
        val millis = key.toLongOrNull() ?: now
        val day = IsoDay.ofMillis(millis, zoneOffsetMinutes)
        val hasNote = !notes.isNullOrBlank()
        return AnnotationEvents.NoteRow(
            bookKey = bookKey.orEmpty(),
            hasNoteBody = hasNote,
            day = day,
        )
    }
}
