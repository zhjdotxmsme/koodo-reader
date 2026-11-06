package com.koodoreader.reader.reader

import com.koodoreader.engine.layout.reader.TextLocation

/**
 * Imperative hooks from [NativeTextReaderScreen] for volume keys / [ReaderPageTurn].
 */
class TextReaderScreenControl {
    var goPrevious: () -> Unit = {}
    var goNext: () -> Unit = {}
    var chapterLabel: () -> String = { "" }
    var jumpTo: (TextLocation) -> Unit = {}
    var selectionText: String = ""
    var selectionLocation: TextLocation? = null
    var currentChapterId: () -> String = { "" }
}
