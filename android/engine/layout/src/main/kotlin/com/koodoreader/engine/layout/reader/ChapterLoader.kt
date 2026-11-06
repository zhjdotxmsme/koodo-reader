package com.koodoreader.engine.layout.reader

import com.koodoreader.engine.layout.SpineItem

data class ChapterDescriptor(
    val id: String,
    val title: String?,
    val level: Int,
    val charCount: Int,
)

interface ChapterLoader : AutoCloseable {
    fun descriptors(): List<ChapterDescriptor>

    fun load(id: String): SpineItem

    override fun close()
}
