package com.koodoreader.engine.layout.reader

import com.koodoreader.engine.layout.LayoutResult

data class TextLocation(
    val chapterId: String,
    val start: Int,
    val end: Int = start,
)

data class ReaderFrame(
    val chapterId: String,
    val layout: LayoutResult,
    val location: TextLocation,
)
