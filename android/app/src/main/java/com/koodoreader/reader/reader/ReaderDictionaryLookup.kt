package com.koodoreader.reader.reader

import com.koodoreader.feature.dictionary.DictRepository

/** Local MDX/Mdict lookup only — install a Chinese dictionary from Settings. */
object ReaderDictionaryLookup {

    fun lookup(repository: DictRepository, word: String): DictionaryLookupResult {
        val query = word.trim()
        if (query.isEmpty()) return DictionaryLookupResult.Empty
        val local = repository.lookup(query)
        val html = local.html
        if (!html.isNullOrBlank()) {
            val plain = html
                .replace(Regex("<[^>]+>"), "")
                .replace(Regex("&[a-z]+;"), " ")
                .trim()
            if (plain.isNotEmpty()) {
                return DictionaryLookupResult.Found(local.word, plain)
            }
        }
        val suggestions = repository.suggest(query).take(5)
        return if (suggestions.isNotEmpty()) {
            DictionaryLookupResult.Suggestions(query, suggestions)
        } else {
            DictionaryLookupResult.NotFound(query)
        }
    }
}

sealed interface DictionaryLookupResult {
    data object Empty : DictionaryLookupResult
    data class Found(val headword: String, val definition: String) : DictionaryLookupResult
    data class Suggestions(val word: String, val suggestions: List<String>) : DictionaryLookupResult
    data class NotFound(val word: String) : DictionaryLookupResult
}