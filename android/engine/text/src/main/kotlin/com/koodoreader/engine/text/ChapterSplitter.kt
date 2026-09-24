package com.koodoreader.engine.text

/**
 * One chapter span inside the *normalised* text.
 *
 * @property title display title ("" for the synthetic pre-title block).
 * @property startOffset inclusive character offset of the block start.
 * @property endOffset exclusive character offset (== next `startOffset`, or
 *   `text.length` for the last chapter).
 * @property level 1 for a top-level chapter marker. Reserved for volume/chapter
 *   nesting; kept in the model so the reader can render a two-level TOC without
 *   re-splitting.
 * @property index 0-based position in the returned list.
 * @property synthesized true when the chapter was produced by the length
 *   fallback rather than by a title match — the reader can then hide it from the
 *   TOC while still using it as a pagination unit.
 */
data class Chapter(
    val title: String,
    val startOffset: Int,
    val endOffset: Int,
    val level: Int = 1,
    val index: Int = 0,
    val synthesized: Boolean = false,
) {
    val length: Int get() = endOffset - startOffset
    val isPreface: Boolean get() = title.isEmpty() && index == 0

    /** The exact substring of the normalised source text. */
    fun extract(text: String): String =
        text.substring(startOffset.coerceIn(0, text.length), endOffset.coerceIn(0, text.length))

    override fun toString(): String =
        "Chapter(#$index '$title' $startOffset..$endOffset${if (synthesized) " synth" else ""})"
}

/**
 * Splits a normalised TXT/MD body into chapters.
 *
 * NATIVE PORT of the desktop's title heuristic
 * (`kookit/src/libs/textProcessor.ts`: `isTitle` / `cleanText` / `startWithDI` /
 * `startWithJUAN`), used by `makeHtmlBook` → `txtToHtml` to emit `<h1>`s. The
 * desktop returns HTML; we return offsets instead, because the native pagination
 * engine needs stable anchors (offset → page) rather than a rebuilt DOM, and
 * because building a 100 MB HTML string is exactly what P5 is meant to avoid.
 *
 * Behaviour kept from the desktop:
 *  - a title line must be shorter than [Options.maxTitleLength] (50) **after**
 *    `cleanText`, so rulers and stray whitespace do not disqualify it;
 *  - `=`, `-`, `_`, `+` are ignored when measuring/compating titles;
 *  - `第N[章节卷回…]`, `卷N`, and a `第` within the first 7 characters are
 *    recognised, with the numeral part validated as Han numerals (including the
 *    financial forms 壹贰叁…) or ASCII digits;
 *  - `Chapter N` / `CHAPTER` / `序章` / `前言` / `楔子` / `后记` … prefixes start
 *    a chapter;
 *  - when no title is found at all, the desktop falls back to fixed-size chunks
 *    (500 lines, `noTitle` mode) — we use a character-length fallback instead,
 *    which is layout-independent.
 *
 * Behaviour added for the native engine:
 *  - the text *before* the first title becomes an explicit preface chapter
 *    (the desktop silently prepends it as content);
 *  - chapters longer than [Options.maxChapterLength] are cut at paragraph
 *    boundaries (falling back to a hard cut) and marked [Chapter.synthesized];
 *  - [Options.parserRegex] overrides the built-in regexes, matching the
 *    user-supplied "custom parser" the desktop stores in `Book.parserRegex`.
 */
object ChapterSplitter {

    data class Options(
        /** Title pattern override (empty = built-in heuristics). */
        val parserRegex: String = "",
        /** Titles longer than this (after `cleanText`) are body text. */
        val maxTitleLength: Int = 50,
        /**
         * Split a chapter that exceeds this many characters. 0 disables the
         * fallback. 20 000 chars ≈ 12–20 screens on a phone: long enough that a
         * normal chapter is never touched, short enough that one giant
         * "chapter" (a file with no markers at all) still pages acceptably.
         */
        val maxChapterLength: Int = 20_000,
        /** Emit the pre-first-title block as chapter 0 (title ""). */
        val includePreface: Boolean = true,
        /** Also treat standalone decimal line numbers ("12", "12.") as titles. */
        val numericTitles: Boolean = true,
        /** Titles emitted by the length fallback get a ` (k)` suffix. */
        val numberSyntheticTitles: Boolean = true,
    ) {
        companion object {
            val Default = Options()
        }
    }

    private val CN_UNIT = "章节節回卷部輯辑話话集篇"
    private val CN_NUM =
        "零〇一二三四五六七八九十百千万萬亿兆廿卅卌拾佰仟壹贰貳叁參肆伍陆陸柒捌玖拾两兩"
    private val SPECIAL_TITLES = listOf(
        "CHAPTER ", "Chapter ", "chapter ",
        "序章", "序言", "前言", "写在前面的话", "寫在前面的話", "楔子", "引子",
        "后记", "後記", "尾声", "尾聲", "后序", "後序", "声明", "聲明",
        "章节目录", "章節目錄", "目录", "目錄", "番外",
    )

    /** Matches `第X[章节卷回…]` where X is Han numerals or ASCII digits. */
    private val RE_DI = Regex(
        "^第\\s*([0-9$CN_NUM]+)\\s*([$CN_UNIT])(?:[\\s　:：、.·\\-—]*.*)?$",
    )

    /** Matches `卷X` / `卷 X` / `卷X·Y`. */
    private val RE_JUAN = Regex("^卷\\s*([0-9$CN_NUM]+).*$")

    /** `Chapter 3`, `Part 2`, `Section 4.1`, `Book II`. */
    private val RE_EN = Regex(
        "^(?:chapter|part|section|book|volume|prologue|epilogue|preface|appendix)\\s+" +
            "(?:[0-9]+(?:\\.[0-9]+)*|[IVXLCDM]+)\\b.*$",
        RegexOption.IGNORE_CASE,
    )

    /** A line that is only a number, optionally followed by punctuation. */
    private val RE_NUMERIC = Regex("^[0-9]{1,4}\\s*[.、:：]?$")

    /** Markdown ATX heading — the only reliable chapter marker in MD. */
    private val RE_MD_HEADING = Regex("^#{1,6}\\s+.*$")

    /**
     * Splits [text] (already normalised — see [TextNormalizer]) into chapters.
     * The returned spans always tile the whole input: chapter *i*'s
     * `endOffset == chapter i+1`'s `startOffset`, and the last one ends at
     * `text.length`. Empty input yields an empty list.
     */
    fun split(text: String, options: Options = Options.Default): List<Chapter> {
        if (text.isEmpty()) return emptyList()

        val rawBlocks = titleBlocks(text, options)
        val blocks = ArrayList<Chapter>(rawBlocks.size)
        for ((i, b) in rawBlocks.withIndex()) {
            blocks += Chapter(
                title = b.title,
                startOffset = b.start,
                endOffset = b.end,
                level = b.level,
                index = i,
            )
        }
        val result = ArrayList<Chapter>(blocks.size)
        for (b in blocks) {
            if (options.maxChapterLength > 0 && b.length > options.maxChapterLength) {
                result += splitOversized(text, b, options)
            } else {
                result += b
            }
        }
        return result.mapIndexed { i, c -> c.copy(index = i) }
    }

    /** Just the titles, in order (TOC without the offset bookkeeping). */
    fun titles(text: String, options: Options = Options.Default): List<String> =
        split(text, options).map { it.title }

    /** True when at least one real (non-synthetic, non-preface) title was found. */
    fun hasChapters(text: String, options: Options = Options.Default): Boolean =
        split(text, options).any { it.title.isNotEmpty() }

    // ------------------------------------------------------------ internals --

    private class Block(val title: String, val start: Int, val end: Int, val level: Int)

    private class BlockBuilder(private val includePreface: Boolean) {
        val out = ArrayList<Block>()
        var currentTitle: String? = null
        var currentStart = 0
        var currentLevel = 1
        private var opened = false

        fun openAt(pos: Int, title: String, level: Int) {
            if (!opened) {
                opened = true
                // Text before the first title is its own (untitled) chapter: the
                // desktop silently merges it into the first <h1>'s content, but a
                // native reader needs it addressable so page 1 is not lost.
                if (includePreface && pos > 0) out += Block("", 0, pos, 1)
            } else {
                out += Block(currentTitle!!, currentStart, pos, currentLevel)
            }
            currentTitle = title
            currentStart = pos
            currentLevel = level
        }

        fun close(end: Int) {
            if (currentTitle != null) {
                out += Block(currentTitle!!, currentStart, end, currentLevel)
            } else if (end > 0) {
                // No title at all. This is the "no chapters detected" fallback and
                // it ALWAYS covers the whole body, even when
                // `includePreface = false`: dropping it would leave the reader with
                // an empty book. `includePreface` only controls the synthetic block
                // that precedes a *real* title.
                out += Block("", 0, end, 1)
            }
        }
    }

    private fun titleBlocks(text: String, options: Options): List<Block> {
        val builder = BlockBuilder(options.includePreface)
        val custom = options.parserRegex.takeIf { it.isNotBlank() }?.let {
            runCatching { Regex(it) }.getOrNull()
        }
        var pos = 0

        while (pos < text.length) {
            val nl = text.indexOf('\n', pos)
            val lineEnd = if (nl < 0) text.length else nl
            val line = text.substring(pos, lineEnd)
            val detection = detectTitle(line, options, custom)
            if (detection != null) {
                builder.openAt(pos, detection.first, detection.second)
            }
            pos = lineEnd + 1
        }
        builder.close(text.length)
        return builder.out
    }

    /** @return title to level, or null when [line] is body text. */
    private fun detectTitle(
        line: String,
        options: Options,
        custom: Regex?,
    ): Pair<String, Int>? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null

        if (custom != null) {
            return if (custom.containsMatchIn(line)) trimmed to 1 else null
        }

        val cleaned = TextNormalizer.cleanText(line)
        if (cleaned.isEmpty() || cleaned.length >= options.maxTitleLength) return null

        // Markdown heading: `## Title` — level from the hash count, title is the
        // rendered text (hashes stripped), which is what the TOC should show.
        val md = RE_MD_HEADING.matchEntire(trimmed)
        if (md != null) {
            val level = trimmed.takeWhile { it == '#' }.length
            val title = trimmed.dropWhile { it == '#' }.trim()
            if (title.isNotEmpty()) return title to level
        }

        val candidate = cleaned

        if (RE_DI.matches(candidate)) {
            // Exclude "第二天早上…"-style prose: require the unit to sit within
            // the first 8 characters and the remainder to be short-ish.
            val unitIdx = candidate.indexOfFirst { it in CN_UNIT }
            if (unitIdx in 1..8) return candidate to 1
        }
        if (RE_JUAN.matches(candidate) && candidate.length <= 8) return candidate to 1
        if (RE_EN.matches(candidate)) return candidate to 1
        if (SPECIAL_TITLES.any { candidate.startsWith(it) }) return candidate to 1
        if (options.numericTitles && RE_NUMERIC.matches(candidate)) return candidate to 1
        // `第` appearing early, as the desktop's last-resort branch.
        val di = candidate.indexOf('第')
        if (di in 0..6 && RE_DI.matches(candidate.substring(di))) return candidate to 1
        return null
    }

    /**
     * Length fallback. Cuts [chapter] into pieces of at most
     * `options.maxChapterLength`, preferring a paragraph break, then a sentence
     * break, then a hard cut. The first piece keeps the original title, the rest
     * become `"<title> (2)"` … so the TOC stays readable and, crucially, the
     * spans still tile the text exactly.
     */
    private fun splitOversized(
        text: String,
        chapter: Chapter,
        options: Options,
    ): List<Chapter> {
        val limit = options.maxChapterLength
        val out = ArrayList<Chapter>()
        var start = chapter.startOffset
        var part = 1
        while (start < chapter.endOffset) {
            val hardEnd = nudgePastSurrogate(text, minOf(start + limit, chapter.endOffset))
            val end = if (hardEnd >= chapter.endOffset) {
                chapter.endOffset
            } else {
                findBreak(text, start, hardEnd)
            }
            val title = when {
                part == 1 -> chapter.title
                options.numberSyntheticTitles && chapter.title.isNotEmpty() ->
                    "${chapter.title} ($part)"
                else -> chapter.title
            }
            out += Chapter(
                title = title,
                startOffset = start,
                endOffset = end,
                level = chapter.level,
                index = 0,
                synthesized = true,
            )
            start = end
            part++
        }
        return out
    }

    /**
     * Looks backwards from [hardEnd] for a natural break: a blank line first
     * (paragraph), then a line break, then CJK sentence enders, then a space.
     * Only the last 20 % of the window is searched so chunks stay even, and only
     * positions that do not split a surrogate pair are returned.
     */
    private fun findBreak(text: String, start: Int, hardEnd: Int): Int {
        val floor = start + ((hardEnd - start) * 8 / 10)
        for (i in hardEnd downTo floor + 1) {
            if (text[i - 1] == '\n' && i - 2 >= floor && text[i - 2] == '\n') {
                if (isSafeCut(text, i - 1)) return i - 1
            }
        }
        for (i in hardEnd downTo floor + 1) {
            if (text[i - 1] == '\n' && isSafeCut(text, i)) return i
        }
        for (i in hardEnd downTo floor + 1) {
            val c = text[i - 1]
            if ((c == '。' || c == '！' || c == '？' || c == '.' || c == '!' || c == '?') &&
                isSafeCut(text, i)
            ) {
                return i
            }
        }
        for (i in hardEnd downTo floor + 1) {
            if (text[i - 1] == ' ' && isSafeCut(text, i)) return i
        }
        return hardEnd
    }

    private fun isSafeCut(text: String, pos: Int): Boolean =
        pos in 1 until text.length &&
            !(Character.isHighSurrogate(text[pos - 1]) && Character.isLowSurrogate(text[pos]))

    /** Moves [pos] forward past a low surrogate so a cut never splits a pair. */
    private fun nudgePastSurrogate(text: String, pos: Int): Int =
        if (pos in 1 until text.length &&
            Character.isHighSurrogate(text[pos - 1]) &&
            Character.isLowSurrogate(text[pos])
        ) {
            pos + 1
        } else {
            pos
        }
}
