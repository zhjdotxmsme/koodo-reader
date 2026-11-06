package com.koodoreader.reader.reader

import com.koodoreader.engine.layout.reader.ChapterDescriptor
import com.koodoreader.engine.layout.reader.ChapterLoader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** TOC list state: title filter + current-chapter highlight. */
class TocViewModel(loader: ChapterLoader, currentChapterId: String = "") {

    private val all: List<ChapterDescriptor> = loader.descriptors()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _currentChapterId = MutableStateFlow(currentChapterId)
    val currentChapterId: StateFlow<String> = _currentChapterId

    fun setQuery(value: String) {
        _query.value = value
    }

    fun setCurrentChapter(id: String) {
        _currentChapterId.value = id
    }

    fun filtered(): List<ChapterDescriptor> {
        val q = _query.value.trim()
        if (q.isEmpty()) return all
        return all.filter { it.title?.contains(q, ignoreCase = true) == true }
    }

    fun isCurrent(id: String): Boolean = id == _currentChapterId.value
}
