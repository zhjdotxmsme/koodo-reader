package com.koodoreader.engine.text

/**
 * Line-based chapter-title matcher shared by [ChapterSplitter] (whole-text path)
 * and by [StreamingTextIndex] / [TextChapterLoader] (streaming path).
 *
 * The streaming scanner only ever looks at ONE line at a time (capped to
 * [CANDIDATE_CAP] characters), so a "does this line look like a chapter title"
 * predicate had to be lifted out of [ChapterSplitter.split] as a public rule.
 *
 * Rules, applied in order — first match wins:
 *  1. custom regex (user's `parserRegex`);
 *  2. Markdown ATX heading (`# Title` … `###### Title`);
 *  3. Chinese `第N[章节卷回…]` / `卷N`;
 *  4. English `Chapter N` / `Part 2` / `Section 4.1` / …;
 *  5. special pre-/post-face titles (序章, 楔子, 后记, …);
 *  6. standalone decimal number (when [numericTitles]);
 *  7. a `第` within the first 7 characters, followed by `第N[…` from there.
 *
 * Titles longer than [maxTitleLength] (cleaned) are body text — the scanner
 * uses this to cap how much of a line it ever buffers ([CANDIDATE_CAP]).
 */
internal object TitleMatcher {

    val CN_UNIT = "章节節回卷部輯辑話话集篇"
    val CN_NUM =
        "零〇一二三四五六七八九十百千万萬亿兆廿卅卌拾佰仟壹贰貳叁參肆伍陆陸柒捌玖拾两兩"
    val SPECIAL_TITLES = listOf(
        "CHAPTER ", "Chapter ", "chapter ",
        "序章", "序言", "前言", "写在前面的话", "寫在前面的話", "楔子", "引子",
        "后记", "後記", "尾声", "尾聲", "后序", "後序", "声明", "聲明",
        "章节目录", "章節目錄", "目录", "目錄", "番外",
    )

    val RE_DI = Regex(
        "^第\\s*([0-9$CN_NUM]+)\\s*([$CN_UNIT])(?:[\\s　:：、.·\\-—]*.*)?$",
    )
    val RE_JUAN = Regex("^卷\\s*([0-9$CN_NUM]+).*$")
    val RE_EN = Regex(
        "^(?:chapter|part|section|book|volume|prologue|epilogue|preface|appendix)\\s+" +
            "(?:[0-9]+(?:\\.[0-9]+)*|[IVXLCDM]+)\\b.*$",
        RegexOption.IGNORE_CASE,
    )
    val RE_NUMERIC = Regex("^[0-9]{1,4}\\s*[.、:：]?$")
    val RE_MD_HEADING = Regex("^#{1,6}\\s+.*$")

    /** A line longer than this (after [com.koodoreader.engine.text.TextNormalizer.cleanText]) is never
     * a chapter title (matching [ChapterSplitter.Options.maxTitleLength]'s max useful length).
     * Used by the streaming scanner to bound the per-line buffer.
     */
    const val MAX_TITLE_AFTER_CLEAN = 50
    const val CANDIDATE_CAP = 160

    fun maxTitleLengthAfterClean(options: ChapterSplitter.Options): Int = options.maxTitleLength

    /**
     * Returns `(title, level)` if [line] is a chapter title, or `null` otherwise.
     *
     * [custom] should be a compiled user regex (or `null`); when present it
     * takes precedence and is a partial match (`containsMatchIn`), matching
     * what [ChapterSplitter.Options.parserRegex] has always meant.
     */
    fun detectTitle(
        line: String,
        options: ChapterSplitter.Options = ChapterSplitter.Options.Default,
        custom: Regex? = null,
    ): Pair<String, Int>? {
        if (line.isEmpty()) return null
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null

        if (custom != null) {
            return if (custom.containsMatchIn(line)) trimmed to 1 else null
        }

        val cleaned = TextNormalizer.cleanText(line)
        if (cleaned.isEmpty() || cleaned.length >= options.maxTitleLength) return null

        // Markdown ATX heading: level = hash count, title is the rendered text
        // (hashes stripped), which is what the TOC should show.
        val md = RE_MD_HEADING.matchEntire(trimmed)
        if (md != null) {
            val level = trimmed.takeWhile { it == '#' }.length
            val title = trimmed.dropWhile { it == '#' }.trim()
            if (title.isNotEmpty()) return title to level
        }

        val candidate = cleaned

        if (RE_DI.matches(candidate)) {
            // Exclude "第二天早上…"-style prose: require the unit to sit within
            // the first 8 characters and the line to be short-ish.
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
}
