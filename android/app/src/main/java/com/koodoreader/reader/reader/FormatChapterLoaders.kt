package com.koodoreader.reader.reader

import com.koodoreader.core.importer.EpubChapterLoader
import com.koodoreader.engine.layout.reader.ChapterLoader
import com.koodoreader.engine.mobi.MobiParser
import com.koodoreader.engine.text.CharsetDetector
import com.koodoreader.engine.text.FileTextSource
import com.koodoreader.engine.text.StreamingTextIndex
import com.koodoreader.engine.text.TextChapterLoader
import java.io.File

object FormatChapterLoaders {

    fun open(format: String, file: File): ChapterLoader =
        when (format.lowercase()) {
            "epub" -> EpubChapterLoader.open(file)
            "txt", "md", "markdown" -> {
                val source = FileTextSource(file)
                val guess = CharsetDetector.detect(source)
                val index = StreamingTextIndex(source, guess).buildIndex()
                TextChapterLoader.open(source, index)
            }
            "mobi", "azw", "azw3" -> {
                val book = MobiParser.parse(file)
                    ?: throw IllegalArgumentException("Cannot parse MOBI: ${file.path}")
                MobiChapterLoader(book)
            }
            "fb2" -> Fb2ChapterLoader.open(file)
            "docx" -> DocxChapterLoader.open(file)
            "html", "htm", "xhtml", "xml", "mhtml", "mht" -> WebChapterLoader.open(file)
            else -> throw IllegalArgumentException("Unsupported text format: $format")
        }
}
