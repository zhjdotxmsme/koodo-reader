package com.koodoreader.engine.feature

/** Reader presentation mode, mirroring the desktop `isParagraphMode` switch. */
enum class ReadingMode {
    /** Continuous scroll (default). */
    SCROLL,

    /** One paragraph at a time, advanced with next/prev. */
    PARAGRAPH,
}

/**
 * Paragraph splitting + the paragraph-mode state machine.
 *
 * Splitting heuristics (matching how TXT/EPUB text arrives from the parser):
 *  - a blank line always ends a paragraph;
 *  - a line starting with an indent — a tab, an ideographic space U+3000, or at
 *    least [DEFAULT_MIN_INDENT] ASCII spaces — also starts a new paragraph;
 *  - wrapped lines inside a paragraph are rejoined: with a single space between
 *    two Latin words, directly between two CJK characters.
 *
 * The state machine is deliberately tiny and immutable-ish so the reader can
 * drive it from Compose without a ViewModel: [next] / [prev] return `false` at
 * the boundaries and [progress] gives the 0…1 position for the progress bar.
 *
 * Reference: kookit `paragraphModeUtil` (docs/android-native-migration.md, P6).
 */
class ParagraphMode(
    val paragraphs: List<String>,
    startIndex: Int = 0,
    initialMode: ReadingMode = ReadingMode.SCROLL,
) {

    /** Current presentation mode; see [setMode] / [toggleMode]. */
    var mode: ReadingMode = initialMode
        private set

    /** Index of the paragraph shown in [ReadingMode.PARAGRAPH]. */
    var index: Int =
        if (paragraphs.isEmpty()) 0 else startIndex.coerceIn(0, paragraphs.size - 1)
        private set

    val size: Int get() = paragraphs.size

    val isEmpty: Boolean get() = paragraphs.isEmpty()

    /** Current paragraph, or `""` when there is none. */
    val current: String get() = paragraphs.getOrElse(index) { "" }

    /** 1-based position of the current paragraph: 0…1. */
    val progress: Float
        get() = if (paragraphs.isEmpty()) 0f else (index + 1f) / paragraphs.size

    val atStart: Boolean get() = index <= 0

    val atEnd: Boolean get() = paragraphs.isEmpty() || index >= paragraphs.size - 1

    /** Switch the presentation mode; the paragraph index is preserved. */
    fun setMode(mode: ReadingMode) {
        this.mode = mode
    }

    /** Flip between [ReadingMode.SCROLL] and [ReadingMode.PARAGRAPH]. */
    fun toggleMode(): ReadingMode {
        mode = if (mode == ReadingMode.SCROLL) ReadingMode.PARAGRAPH else ReadingMode.SCROLL
        return mode
    }

    /** Advance one paragraph. @return false when already at the last one. */
    fun next(): Boolean {
        if (atEnd) return false
        index++
        return true
    }

    /** Go back one paragraph. @return false when already at the first one. */
    fun prev(): Boolean {
        if (atStart) return false
        index--
        return true
    }

    /** Jump to [target] (clamped). @return true when the index actually changed. */
    fun seek(target: Int): Boolean {
        if (paragraphs.isEmpty()) return false
        val clamped = target.coerceIn(0, paragraphs.size - 1)
        if (clamped == index) return false
        index = clamped
        return true
    }

    /** Progress-based jump: [fraction] in 0…1 → paragraph index (clamped). */
    fun seekToFraction(fraction: Float): Boolean {
        if (paragraphs.isEmpty()) return false
        val clamped = fraction.coerceIn(0f, 1f)
        return seek(Math.round(clamped * (paragraphs.size - 1)))
    }

    /** Static helpers; the instance above is only about navigation state. */
    companion object {
        const val DEFAULT_MIN_INDENT = 2

        /**
         * Split [text] into paragraphs using the blank-line / indentation
         * heuristics described on [ParagraphMode].
         *
         * @param minIndentSpaces ASCII spaces that count as an indent.
         */
        fun split(text: String, minIndentSpaces: Int = DEFAULT_MIN_INDENT): List<String> {
            if (text.isBlank()) return emptyList()

            val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
            val paragraphs = ArrayList<String>()
            val buffer = StringBuilder()

            fun flush() {
                val paragraph = buffer.toString().trim()
                if (paragraph.isNotEmpty()) paragraphs.add(paragraph)
                buffer.setLength(0)
            }

            for (line in normalized.split('\n')) {
                if (line.isBlank()) {
                    flush()
                    continue
                }
                val indent = line.takeWhile { it == ' ' || it == '\t' || it == '\u3000' }
                val body = line.substring(indent.length)
                if (body.isEmpty()) {
                    flush()
                    continue
                }
                val indented = indent.contains('\t') ||
                    indent.contains('\u3000') ||
                    indent.count { it == ' ' } >= minIndentSpaces
                if (indented && buffer.isNotEmpty()) flush()

                if (buffer.isEmpty()) {
                    buffer.append(body)
                } else {
                    val previous = buffer[buffer.length - 1]
                    val next = body[0]
                    if (!(isCjkChar(previous) && isCjkChar(next))) buffer.append(' ')
                    buffer.append(body)
                }
            }
            flush()
            return paragraphs
        }

        /** Build a state machine directly from a book's plain text. */
        fun fromText(
            text: String,
            initialMode: ReadingMode = ReadingMode.SCROLL,
            minIndentSpaces: Int = DEFAULT_MIN_INDENT,
        ): ParagraphMode = ParagraphMode(split(text, minIndentSpaces), 0, initialMode)
    }
}
