package com.koodoreader.reader.reader

import com.koodoreader.engine.layout.reader.ChapterLoader
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.coroutines.coroutineContext

data class TextSearchHit(
    val chapterId: String,
    val start: Int,
    val length: Int,
    val excerpt: String,
)

object IncrementalBookSearch {

    fun search(query: String, loader: ChapterLoader): Flow<TextSearchHit> = flow {
        if (query.isEmpty()) return@flow
        for (chapter in loader.descriptors()) {
            coroutineContext.ensureActive()
            val text = loader.load(chapter.id).blocks.joinToString("\n") { it.text }
            var offset = text.indexOf(query, ignoreCase = true)
            while (offset >= 0) {
                val excerptStart = (offset - 40).coerceAtLeast(0)
                val excerptEnd = (offset + query.length + 40).coerceAtMost(text.length)
                emit(
                    TextSearchHit(
                        chapterId = chapter.id,
                        start = offset,
                        length = query.length,
                        excerpt = text.substring(excerptStart, excerptEnd),
                    ),
                )
                offset = text.indexOf(query, offset + query.length, ignoreCase = true)
            }
        }
    }
}
