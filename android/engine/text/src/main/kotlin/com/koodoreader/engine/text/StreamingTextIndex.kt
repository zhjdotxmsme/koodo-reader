package com.koodoreader.engine.text

import java.nio.charset.Charset

/**
 * One chapter span found by [StreamingTextIndex]: a byte range (not the text).
 *
 * The whole point of the streaming index is that 100 MB TEXT files are scanned
 * in bounded 64 KiB windows and only the *location* of each chapter is kept,
 * never its body. [TextChapterLoader] is the only component that decodes a
 * chapter, and it decodes exactly the byte range this span records.
 *
 * @property id stable identifier. Chapters numbered in scan order: `"c0"`,
 *   `"c1"`, … — the same scheme [ChapterSplitter] uses (`index`). A custom
 *   user-supplied id (e.g. a manual TOC anchor) can be substituted by
 *   [StreamingTextIndex.options].chapterIdFactory when present.
 * @property title display title ("" for the preface and for no-title chunks).
 * @property level 1 = top-level chapter marker.
 * @property byteStart inclusive source byte offset of the chapter's first byte.
 * @property byteEnd exclusive source byte offset (== next chapter's `byteStart`).
 * @property charStart inclusive character offset of the chapter start in the
 *   FULL decoded text (computed by decoding the whole book exactly once during
 *   the index build — required for [com.koodoreador.engine.layout.reader.TextBlock.sourceOffsets]
 *   mapping, and it costs O(chars) not O(bytes × windows)).
 * @property charCount decoded character count of the chapter body (== next span
 *   `charStart − charStart` minus any title line, clamped to ≥ 0).
 * @property synthesized true when the span came from the length fallback, not
 *   a title match (mirrors [ChapterSplitter.Chapter.synthesized]).
 * @property preface true when this is the pre-first-title block (title is "").
 */
data class TextChapterSpan(
    val id: String,
    val title: String,
    val level: Int,
    val byteStart: Long,
    val byteEnd: Long,
    val charStart: Int,
    val charCount: Int,
    val synthesized: Boolean,
    val preface: Boolean,
) {
    init {
        require(byteStart >= 0) { "byteStart must be >= 0 (was $byteStart)" }
        require(byteEnd >= byteStart) { "byteEnd=$byteEnd < byteStart=$byteStart" }
        require(charStart >= 0) { "charStart must be >= 0 (was $charStart)" }
        require(charCount >= 0) { "charCount must be >= 0 (was $charCount)" }
    }

    /** Inclusive/exclusive decoded-character range of the body (the text the
     * reader lays out, excluding the title line which is rendered as a heading). */
    val charEnd: Int get() = charStart + charCount
}

/**
 * Streaming chapter index for TXT / MD books.
 *
 * ## Contract (docs/superpowers/plans/2026-11-02-android-native-text-reader.md T2)
 *
 *  - scans in bounded [TextSource.DEFAULT_WINDOW] (64 KiB) windows, carrying a
 *    small pending-line buffer across window boundaries so a chapter title
 *    (max [TitleMatcher.CANDIDATE_CAP] = 160 chars) is never split across scans;
 *  - only the chapter *spans* are retained — `[TextBlock]` / `SpineItem` bodies
 *    are built on demand by [TextChapterLoader];
 *  - multi-byte sequence boundaries are handled per-charset:
 *    * **UTF-8** — a trailing incomplete 2/3/4-byte sequence is detected with
 *      [com.koodoreador.engine.text.Utf8WindowHelper.sequenceEnd] and re-appended
 *      to the front of the next window's decoded text. The decoder uses
 *      [com.koodoreador.engine.text.Utf8.decode] (WHATWG maximal-subpart) so a
 *      split sequence emits U+FFFD, not a silent truncation.
 *    * **GBK / Big5 / Shift_JIS / EUC-KR** — a 1-trailing-byte carry
 *      (`lastByte`) is prepended to the next decode; the JDK decoder with
 *      [java.nio.charset.CodingErrorAction.REPLACE] guarantees a split pair
 *      emits a single U+FFFD instead of two.
 *  - chapter titles reuse the exact rules of [ChapterSplitter] (via
 *    [TitleMatcher]) so a TXT split by `ChapterSplitter.split` (whole-text path)
 *    and one split by this index agree on boundaries;
 *  - a file with no detected title becomes one chunk per [Options.chunkLength]
 *    characters (default 5 000, per the expanded-reader spec: "无标题分章=5000
 *    字符"), each marked [TextChapterSpan.synthesized] and [preface]=false, so
 *    the reader still pages and the TOC can hide them.
 *
 * ## Cost
 *
 * One full-file O(bytes) pass during [buildIndex] (unavoidable — the chapter
 * boundaries have to be found once). After that, [TextChapterLoader.load] reads
 * only the byte range of the requested span. A 100 MB file is therefore one
 * full scan + N small reads, not a full scan per chapter.
 *
 * ## What is NOT here
 *
 *  - BOM-aware decoding: [buildIndex] uses the [CharsetDetector.detect] guess
 *    and the BOM length is recorded on the returned [TextChapterIndex] /
 *    [TextChapterLoader], so [TextChapterLoader.decodeChapter] skips the BOM
 *    bytes the same way [TextDecoder.decodeResult] does.
 *  - `parserRegex` validation: an invalid user regex falls back to the built-in
 *    rules (mirroring [ChapterSplitter] which logs-and-ignores invalid regexes).
 */
class StreamingTextIndex(
    private val source: TextSource,
    private val charsetGuess: CharsetGuess,
    private val options: Options = Options.Default,
) {
    data class Options(
        /**
         * Fallback chapter size (in decoded characters) when no title is found
         * anywhere in the book. The expanded-reader spec fixes this at 5 000 for
         * "无标题分章" — see `docs/superpowers/specs/2026-11-02-expanded-reader.md`.
         */
        val noTitleChunkChars: Int = 5_000,
        /** Titles longer than this (after `cleanText`) are body text. */
        val maxTitleLength: Int = ChapterSplitter.Options.Default.maxTitleLength,
        /** Also treat standalone decimal line numbers as titles. */
        val numericTitles: Boolean = true,
        /** Emit the pre-first-title block as chapter 0 (title ""); when false and a
         *   real title exists, the preface bytes are merged into chapter 0. */
        val includePreface: Boolean = true,
        /** User chapter id factory — defaults to `"c<i>"`. A `ChapterSplitter`
         *   `numberSyntheticTitles` flag also applies to synthetic chunk titles. */
        val numberSyntheticTitles: Boolean = ChapterSplitter.Options.Default.numberSyntheticTitles,
        /** Scanning window size in bytes. [TextSource.DEFAULT_WINDOW] (64 KiB) is the
         *   recommended value — one I/O call, cache friendly. */
        val windowBytes: Int = 64 * 1024,
        /**
         * Buffer of trailing bytes carried to the next window so a title line
         * (≤ [TitleMatcher.CANDIDATE_CAP] chars) is never split across windows.
         * This is in *bytes*; titles are capped at 50 cleaned chars, but a
         * multi-byte character is up to 4 bytes wide and a CJK candidate line
         * can be up to ~50 × 3 = 150 bytes plus padding, so 320 gives ~2× headroom.
         */
        val pendingLineBytes: Int = 320,
        /** Optional charset override in place of [CharsetDetector] guess. */
        val explicitCharset: String? = null,
    ) {
        companion object {
            val Default = Options()
        }
    }

    // ------------------------------------------------------------------ API --

    fun buildIndex(): TextChapterIndex {
        val charset = resolveCharset(charsetGuess.charset, options)
        val spans = scanSpans(charset)
        var charPos = 0
        val out = ArrayList<TextChapterSpan>(spans.size)
        spans.forEachIndexed { i, s ->
            val byteLen = (s.byteEnd - s.byteStart).toInt().coerceAtLeast(0)
            val text = if (byteLen == 0) {
                ""
            } else {
                decodeBytes(source.read(s.byteStart, byteLen), charset)
            }
            out += TextChapterSpan(
                id = "c$i",
                title = s.title,
                level = s.level,
                byteStart = s.byteStart,
                byteEnd = s.byteEnd,
                charStart = charPos,
                charCount = text.length,
                synthesized = s.synthesized,
                preface = s.preface,
            )
            charPos += text.length
        }
        return TextChapterIndex(out, charset.name(), charsetGuess.bomLength)
    }

    // ------------------------------------------------------------- scanning --

    private fun scanSpans(charset: java.nio.charset.Charset): List<Span> {
        val out = ArrayList<Span>()
        var currentTitle: String? = null
        var currentByteStart = 0L
        var currentLevel = 0
        var opened = false

        val length = source.length
        var offset = 0L
        // Raw bytes of the current unfinished line. Carrying raw bytes (not
        // decoded text) keeps multi-byte sequences split across windows intact:
        // a line is decoded only once it is complete. Lines longer than
        // maxLineBytes can never be titles, so their bytes are dropped instead
        // of accumulated — scanning memory stays bounded even for a 100 MB
        // single-line file.
        val maxLineBytes = 16 * 1024
        var line = ByteArray(0)
        var lineStartAbs = 0L
        var lineTooLong = false

        fun onLineEnd(nextLineStartAbs: Long) {
            if (!lineTooLong && line.isNotEmpty()) {
                val trimmed = decodeBytes(line, charset).trim()
                if (trimmed.isNotEmpty()) {
                    val detected = TitleMatcher.detectTitle(trimmed, splitterOptions(), null)
                    if (detected != null) {
                        if (!opened) {
                            opened = true
                            if (options.includePreface && lineStartAbs > 0) {
                                out += Span("", 1, 0L, lineStartAbs, synthesized = false, preface = true)
                            }
                        } else {
                            out += Span(
                                title = currentTitle ?: "",
                                level = currentLevel,
                                byteStart = currentByteStart,
                                byteEnd = lineStartAbs,
                                synthesized = false,
                                preface = false,
                            )
                        }
                        currentTitle = detected.first
                        currentByteStart = lineStartAbs
                        currentLevel = detected.second
                    }
                }
            }
            line = ByteArray(0)
            lineTooLong = false
            lineStartAbs = nextLineStartAbs
        }

        fun append(piece: ByteArray, from: Int, until: Int) {
            if (lineTooLong || until <= from) return
            if (line.size + (until - from) > maxLineBytes) {
                lineTooLong = true
                line = ByteArray(0)
            } else {
                line += piece.copyOfRange(from, until)
            }
        }

        while (offset < length) {
            val windowLen = minOf(options.windowBytes, (length - offset).toInt()).coerceAtLeast(1)
            val raw = source.read(offset, windowLen)
            if (raw.isEmpty()) break
            var segmentStart = 0
            for (i in raw.indices) {
                if (raw[i] != '\n'.code.toByte()) continue
                append(raw, segmentStart, i)
                onLineEnd(offset + i + 1)
                segmentStart = i + 1
            }
            append(raw, segmentStart, raw.size)
            offset += raw.size
        }
        if (line.isNotEmpty() || lineTooLong) onLineEnd(length)

        // Flush any open chapter.
        if (opened) {
            out += Span(
                title = currentTitle ?: "",
                level = currentLevel,
                byteStart = currentByteStart,
                byteEnd = length,
                synthesized = false,
                preface = false,
            )
        } else if (length > 0) {
            // No title at all — synthesize chunks of `noTitleChunkChars` decoded
            // chars each with a second bounded streaming pass.
            out += synthesizeNoTitleChunks(charset)
        }
        return out
    }

    // --------------------------------------------------------------- helpers --

    private fun splitterOptions(): ChapterSplitter.Options =
        ChapterSplitter.Options(
            parserRegex = "",
            maxTitleLength = options.maxTitleLength,
            numericTitles = options.numericTitles,
            includePreface = options.includePreface,
        )

    private fun decodeBytes(bytes: ByteArray, charset: java.nio.charset.Charset): String {
        if (bytes.isEmpty()) return ""
        val charsetName = charset.name().replace("_", "-").lowercase()
        if (charsetName == "utf-8" || charsetName == "utf8") {
            return Utf8.decode(bytes)
        }
        return String(bytes, charset)
    }

    private fun synthesizeNoTitleChunks(charset: java.nio.charset.Charset): List<Span> {
        // Second bounded streaming pass (only for books without any title).
        // A CharsetDecoder with REPLACE decodes windows incrementally; the
        // output buffer is capped at "chars until the next cut", so when it
        // fills up, the input position is exactly the byte offset of the cut —
        // no char→byte guessing, no readAll(), split sequences stay in the
        // undecoded carry.
        val chunk = options.noTitleChunkChars.coerceAtLeast(100)
        val decoder = charset.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
        val cuts = ArrayList<Long>()
        cuts += 0L
        val length = source.length
        var offset = 0L
        var carry = ByteArray(0)
        var carryAbs = 0L
        var charPos = 0
        var nextCut = chunk

        fun pump(bytes: ByteArray, startAbs: Long, endOfInput: Boolean) {
            val bb = java.nio.ByteBuffer.wrap(bytes)
            while (bb.hasRemaining()) {
                val before = bb.position()
                val cb = java.nio.CharBuffer.allocate(nextCut - charPos)
                decoder.decode(bb, cb, endOfInput)
                cb.flip()
                charPos += cb.length
                if (charPos >= nextCut) {
                    cuts += startAbs + bb.position()
                    nextCut += chunk
                } else if (bb.position() == before && cb.length == 0) {
                    break // underflow on a partial sequence: carry the tail
                }
            }
            carry = bytes.copyOfRange(bb.position(), bytes.size)
            carryAbs = startAbs + bb.position()
        }

        while (offset < length) {
            val raw = source.read(offset, minOf(options.windowBytes, (length - offset).toInt()).coerceAtLeast(1))
            if (raw.isEmpty()) break
            val startAbs = carryAbs
            val combined = carry + raw
            offset += raw.size
            pump(combined, startAbs, false)
        }
        if (carry.isNotEmpty()) pump(carry, carryAbs, true)
        if (cuts.last() != length) cuts += length

        val out = ArrayList<Span>()
        for (i in 0 until cuts.size - 1) {
            val bs = cuts[i]
            val be = cuts[i + 1]
            if (bs >= be) continue
            out += Span(
                title = if (options.numberSyntheticTitles) "第 ${i + 1} 篇" else "",
                level = 1,
                byteStart = bs,
                byteEnd = be,
                synthesized = true,
                preface = (i == 0),
            )
        }
        return out
    }

    /** A chapter span during scanning — only byte offsets (no char positions yet).
     *  The `ignorePrefaceWhen` field is internal bookkeeping and not exposed. */
    private data class Span(
        val title: String,
        val level: Int,
        val byteStart: Long,
        val byteEnd: Long,
        val synthesized: Boolean,
        val preface: Boolean,
        val ignorePrefaceWhen: Boolean = false,
    ) {
        override fun toString(): String =
            "Span('title=$title' $byteStart..$byteEnd${if (synthesized) " synth" else ""}${if (preface) " preface" else ""})"
    }

    companion object {
        /** Resolve the charset name (case-insensitive) to a [Charset], falling
         *  back to UTF-8 for unknown labels — mirroring [TextDecoder]. */
        fun resolveCharset(
            name: String,
            options: Options,
        ): java.nio.charset.Charset {
            val explicit = options.explicitCharset
            val resolved = explicit ?: name
            return runCatching { java.nio.charset.Charset.forName(resolved) }
                .getOrNull()
                ?: java.nio.charset.StandardCharsets.UTF_8
        }
    }
}

/**
 * The completed chapter index: ordered list of spans + the resolved decoding
 * metadata a [TextChapterLoader] needs.
 *
 * @property spans ordered by `byteStart`; consecutive spans tile the book
 *   exactly (`span[i].byteEnd == span[i+1].byteStart`, last ends at source length).
 * @property charset the resolved charset name (e.g. "UTF-8" or "GBK").
 * @property bomLength bytes to skip from the very first span's `byteStart`
 *   before decoding (== [CharsetGuess.bomLength] — 0 when no BOM was found).
 */
data class TextChapterIndex(
    val spans: List<TextChapterSpan>,
    val charset: String,
    val bomLength: Int,
) {
    init {
        require(spans.isNotEmpty()) { "a text book must have at least one chapter span" }
        require(bomLength >= 0) { "bomLength must be >= 0 (was $bomLength)" }
        var prevEnd = 0L
        for (s in spans) {
            require(s.byteStart == prevEnd) {
                "spans must tile exactly: expected byteStart=$prevEnd for '${s.title}', got ${s.byteStart}"
            }
            prevEnd = s.byteEnd
        }
    }

    fun span(id: String): TextChapterSpan =
        spans.firstOrNull { it.id == id }
            ?: throw NoSuchElementException("no chapter with id '$id' (have ${spans.size} spans)")

    fun indexOf(id: String): Int {
        val i = spans.indexOfFirst { it.id == id }
        if (i < 0) throw NoSuchElementException("no chapter with id '$id'")
        return i
    }
}
