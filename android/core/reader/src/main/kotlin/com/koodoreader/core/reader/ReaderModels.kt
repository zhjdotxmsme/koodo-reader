package com.koodoreader.core.reader

data class Book(
    val bookKey: String,
    val title: String,
    val author: String?,
    val filePath: String?,
    val format: String?,
    val coverPath: String?,
    val fileSize: Long?,
    val md5: String?,
)

data class TextAnchor(
    val chapterId: String?,
    val startOffset: Int,
    val endOffset: Int,
)

data class ReadingProgress(
    val bookKey: String,
    val cfi: String?,
    val fraction: Double,
)
