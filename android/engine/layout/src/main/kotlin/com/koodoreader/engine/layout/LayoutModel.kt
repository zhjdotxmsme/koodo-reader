package com.koodoreader.engine.layout

/**
 * The pagination result model.
 *
 * Coordinates are page-relative **pixels**, with the origin at the top-left of
 * the page — the same space the desktop engine draws into (it positions text
 * with absolute offsets inside a page-sized container). So a [LayoutLine] can
 * be drawn directly onto a Canvas / Compose `TextLayer`.
 */

/**
 * A position inside the document: spine item + block (element) + character.
 *
 * This is the native equivalent of a DOM anchor and the direct input of
 * [CfiAddressing] — it is the address format shared with the desktop app, so
 * annotations and reading progress stay compatible (ADR-002).
 *
 * @property spineIndex the spine index; becomes the CFI spine step.
 * @property elementIndex the block's sibling index; becomes the CFI element
 *   step. `-1` means "the start of the chapter, no element" (a chapter-level
 *   CFI such as `epubcfi(/6/3!)`).
 * @property charOffset the character offset inside the block's collapsed text.
 *   Always 0 when [elementIndex] is -1.
 */
data class LayoutPosition(
    val spineIndex: Int,
    val elementIndex: Int,
    val charOffset: Int,
) : Comparable<LayoutPosition> {
    init {
        require(spineIndex >= 0) { "spineIndex must be >= 0 (was $spineIndex)" }
        require(elementIndex >= -1) { "elementIndex must be >= -1 (was $elementIndex)" }
        require(charOffset >= 0) { "charOffset must be >= 0 (was $charOffset)" }
    }

    override fun compareTo(other: LayoutPosition): Int = compareValuesBy(
        this,
        other,
        { it.spineIndex },
        { it.elementIndex },
        { it.charOffset },
    )

    /** True when the position addresses the chapter rather than an element. */
    val isChapterStart: Boolean get() = elementIndex == -1

    /** The chapter-level form of this position (no element). */
    fun chapterStart(): LayoutPosition = if (isChapterStart) this else LayoutPosition(spineIndex, -1, 0)
}

/**
 * One laid-out line.
 *
 * @property page / column the page and column this line belongs to.
 * @property blockIndex the block's flat index inside the document.
 * @property text the line's text (trailing whitespace already trimmed).
 * @property start / end character range inside the block, `end` exclusive.
 * @property indentPx the left inset of this line (first-line indent or 0) —
 *   renderers need it to place the line inside the column.
 * @property x / y top-left of the *ink* (after the indent), page-relative px.
 * @property width the used width, excluding any justification stretch.
 * @property height the line box height (`line-height` x natural height).
 * @property baselineY the baseline y, page-relative.
 * @property justifyStretchPx extra space to spread over the [wordGaps] gaps
 *   when [align] is [TextAlign.JUSTIFY] (0 otherwise).
 * @property wordGaps the number of inter-word spaces in the line.
 * @property forced true when the line was cut without a legal break
 *   opportunity (the overflow-wrap fallback); renderers may hint it.
 */
data class LayoutLine(
    val page: Int,
    val column: Int,
    val position: LayoutPosition,
    val blockIndex: Int,
    val text: String,
    val start: Int,
    val end: Int,
    val fontSizePx: Float,
    val indentPx: Float,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val baselineY: Float,
    val align: TextAlign,
    val justifyStretchPx: Float,
    val wordGaps: Int,
    val forced: Boolean,
) {
    /** The length in characters. */
    val length: Int get() = end - start

    /** The line's right edge, including justification. */
    val rightPx: Float get() = x + width + justifyStretchPx

    /** The vertical band `[y, y + height)`; used for hit-testing. */
    fun containsY(pointY: Float): Boolean = pointY in y..y + height
}

/** One column of a page (CSS `column-count` = 1 gives a single column). */
data class LayoutColumn(
    val index: Int,
    val x: Float,
    val width: Float,
    val height: Float,
    val lines: List<LayoutLine>,
)

/** One page: 1..n columns in reading order. */
data class LayoutPage(val index: Int, val columns: List<LayoutColumn>) {
    /** All lines of the page in reading order (left column first). */
    val lines: List<LayoutLine> get() = columns.flatMap { it.lines }

    val firstPosition: LayoutPosition? get() = lines.firstOrNull()?.position

    val lastPosition: LayoutPosition? get() = lines.lastOrNull()?.position
}

/** A chapter of the result: its character count and flat block range. */
data class LayoutChapter(val spineIndex: Int, val charCount: Int, val blockStart: Int, val blockEnd: Int)

/**
 * One block of the result, so a [LayoutPosition] can be resolved to a flat
 * block index without re-walking the document.
 *
 * @property blockIndex flat index across the whole document.
 * @property charCount the block's character count.
 * @property globalStart the block's first character index inside the document.
 */
data class LayoutBlock(
    val spineIndex: Int,
    val elementIndex: Int,
    val blockIndex: Int,
    val charCount: Int,
    val globalStart: Int,
)

/**
 * Reading progress as fractions in `0..1`.
 *
 * Per R3 of `docs/android-native-migration.md`, the UI must show chapter +
 * percent rather than an absolute page number: native pagination and the
 * browser's CSS-columns pagination produce different page counts, while
 * character-based progress is identical.
 */
data class ReadingProgress(val chapterPercent: Float, val documentPercent: Float) {
    /** 0..100, for direct UI binding. */
    val chapterPercent100: Float get() = chapterPercent * 100f
    val documentPercent100: Float get() = documentPercent * 100f
}

/**
 * The result of paginating an [EpubDocument].
 *
 * This is the contract the reader UI and the annotation layer consume:
 *
 *  - [pageOf] for "jump to an annotation";
 *  - [positionAt] for tap-to-select;
 *  - [progress] for the chapter/percent HUD;
 *  - [cfiSequence] as the deterministic golden vector.
 *
 * Not a data class on purpose: it holds a [TextMeasurer] (used by [positionAt])
 * whose identity must not participate in structural equality.
 */
class LayoutResult(
    val tokens: LayoutTokens,
    val pages: List<LayoutPage>,
    val lines: List<LayoutLine>,
    val chapters: List<LayoutChapter>,
    val blocks: List<LayoutBlock>,
    private val measurer: TextMeasurer,
) {
    val totalChars: Int get() = chapters.sumOf { it.charCount }
    val lineCount: Int get() = lines.size
    val pageCount: Int get() = pages.size

    /** `blockEnds[i]` = the last character index of flat block `i`. */
    private val blockEnds: IntArray = IntArray(blocks.size) { blocks[it].charCount - 1 }

    /** The flat block index for a position, or `null` when unknown. */
    fun blockIndexOf(position: LayoutPosition): Int? = if (position.isChapterStart) {
        blocks.firstOrNull { it.spineIndex == position.spineIndex }?.blockIndex
    } else {
        blocks.firstOrNull {
            it.spineIndex == position.spineIndex && it.elementIndex == position.elementIndex
        }?.blockIndex
    }

    /**
     * The line containing [position].
     *
     * A character offset that sits exactly on a line boundary belongs to the
     * *earlier* line, which keeps the mapping deterministic.
     */
    fun lineAt(position: LayoutPosition): LayoutLine? {
        val blockIndex = blockIndexOf(position) ?: return null
        val last = blockEnds[blockIndex].coerceAtLeast(0)
        if (position.charOffset > last) return null
        return lines.firstOrNull {
            it.blockIndex == blockIndex &&
                position.charOffset >= it.start &&
                position.charOffset <= it.end
        }
    }

    /** The page index containing [position], or `null` when unknown. */
    fun pageOf(position: LayoutPosition): Int? = lineAt(position)?.page

    /** True when [position] addresses a real character of this document. */
    fun contains(position: LayoutPosition): Boolean = lineAt(position) != null

    /**
     * Hit-testing: the position under a page-relative (x, y) tap.
     *
     * The line's band is `[y, y + height)`; the character is found by walking
     * the line's text with the measurer's prefix widths. Linear by design — it
     * runs once, on tap, on a page of at most a few dozen lines.
     */
    fun positionAt(x: Float, y: Float): LayoutPosition? {
        for (page in pages) {
            for (column in page.columns) {
                for (line in column.lines) {
                    if (!line.containsY(y)) continue
                    if (x < line.x || x > line.x + line.width + line.justifyStretchPx) continue
                    return line.position.copy(charOffset = charOffsetAt(line, x))
                }
            }
        }
        return null
    }

    private fun charOffsetAt(line: LayoutLine, x: Float): Int {
        val text = line.text
        if (text.isEmpty()) return line.start
        val relX = (x - line.x).coerceIn(0f, line.width)
        for (i in 1..text.length) {
            if (measurer.prefixWidth(text, i, line.fontSizePx, tokens.letterSpacingPx) > relX) return line.start + i - 1
        }
        return (line.start + text.length - 1).coerceAtMost(line.end)
    }

    /** The position of the n-th character of the whole document. */
    fun positionForChar(globalChar: Int): LayoutPosition? {
        if (globalChar < 0 || globalChar > totalChars) return null
        var acc = 0
        for (chapter in chapters) {
            if (globalChar < acc + chapter.charCount) {
                val offset = globalChar - acc
                return lineAt(LayoutPosition(chapter.spineIndex, blocks.first { it.blockIndex == chapter.blockStart }.elementIndex, offset))
                    ?.position
                    ?.copy(charOffset = offset)
            }
            acc += chapter.charCount
        }
        return lines.lastOrNull()?.position
    }

    /** Character-based progress for a position (stable across renderers). */
    fun progress(position: LayoutPosition): ReadingProgress {
        val chapter = chapters.firstOrNull { it.spineIndex == position.spineIndex } ?: return ReadingProgress(0f, 0f)
        val offset = if (position.isChapterStart) 0 else position.charOffset.coerceIn(0, chapter.charCount)
        val chapterPercent = if (chapter.charCount == 0) 0f else offset.toFloat() / chapter.charCount
        val before = chapters.takeWhile { it.spineIndex != chapter.spineIndex }.sumOf { it.charCount }
        val documentPercent = if (totalChars == 0) 0f else (before + offset).toFloat() / totalChars
        return ReadingProgress(chapterPercent, documentPercent)
    }

    /**
     * The per-line CFI sequence, page by page, as one deterministic string.
     *
     * This is the golden vector behind the P2 acceptance gate "the native
     * character stream matches the desktop engine": any algorithm change that
     * reorders characters shows up as a diff here.
     */
    fun cfiSequence(): String = pages.joinToString("; ") { page ->
        "p${page.index + 1}:" + page.lines.joinToString("|") { CfiAddressing.toCfi(it.position) }
    }

    override fun toString(): String =
        "LayoutResult(pages=${pages.size}, lines=${lines.size}, chars=$totalChars)"
}
