package com.koodoreader.reader.metadata

object MetadataSearchQuery {
    fun build(title: String, author: String): String {
        val t = title.trim()
        val a = author.trim()
        return when {
            t.isNotEmpty() && a.isNotEmpty() -> "$t $a"
            t.isNotEmpty() -> t
            else -> a
        }
    }

    fun looksLikeIsbn(query: String): Boolean {
        val compact = query.replace("-", "").replace(" ", "").trim()
        if (compact.length !in listOf(10, 13)) return false
        return compact.all { it.isDigit() || it == 'X' || it == 'x' }
    }
}