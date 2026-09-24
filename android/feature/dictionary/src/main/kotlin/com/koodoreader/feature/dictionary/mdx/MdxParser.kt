package com.koodoreader.feature.dictionary.mdx

import java.io.Closeable
import java.io.File

/**
 * Native `.mdx` (text dictionary) reader.
 *
 * PORT SOURCE — `node_modules/js-mdict@6.0.8/dist/esm/mdx.js`:
 *  - `lookup`            mdx.js:10-29   (binary search + record fetch + decode)
 *  - `fetch`             mdx.js:31-43
 *  - `prefix`            mdx.js:50-55
 *  - `associate`         mdx.js:62-70   (all keywords of the same key block)
 *  - `suggest`           mdx.js:78-93   (associate + edit distance, 0..5 only)
 *  - `fuzzy_search`      mdx.js:115-129 (associate + edit distance, sorted, capped)
 *  - `fetch_definition`  mdx.js:94-106
 *
 * This is the class the reader UI uses: one instance per enabled dictionary, held
 * open while reading, `lookup()` per selected word.
 *
 * ```kotlin
 * MdxParser.open(File(dictDir, "cambridge.mdx")).use { dict ->
 *     dict.lookup("apple").definition // "<div class=\"entry\">…"
 * }
 * ```
 */
class MdxParser(
    private val core: MdictCore,
    private val ownsCore: Boolean = true,
) : Closeable {

    constructor(source: MdictByteSource, options: MdictCore.Options = MdictCore.Options()) :
        this(MdictCore(source, options), true)

    /** `mdx.js:10` — the definition plus the key that actually matched. */
    data class Result(val keyText: String, val definition: String?) {
        val found: Boolean get() = definition != null
    }

    val header: MdictHeader get() = core.header
    val meta: MdictMeta get() = core.meta

    /** Every keyword in the dictionary, in dictionary order. */
    val keywords: List<String> get() = core.keywordList.map { it.keyText }

    val keywordCount: Int get() = core.keywordList.size

    /** Number of key blocks — the unit of the on-disk index (`mdict-base.js:128`). */
    val keyBlockCount: Int get() = core.keyInfoList.size

    /** Number of record blocks — the unit of lazy record reads (`mdict-base.js:151`). */
    val recordBlockCount: Int get() = core.recordInfoList.size

    /** `mdx.js:10-29` — look a word up, `null` definition when absent. */
    fun lookup(word: String): Result {
        val item = core.lookupKeyBlockByWord(word) ?: return Result(word, null)
        return fetch(item)
    }

    /** Convenience for the popup: just the HTML, or `null`. */
    fun lookupDefinition(word: String): String? = lookup(word).definition

    /** `mdx.js:31-43` / `mdx.js:94-106` — read the definition of an already-found key. */
    fun fetch(item: MdictCore.KeyWordItem): Result {
        val raw = core.lookupRecordByKeyBlock(item) ?: return Result(item.keyText, null)
        return Result(item.keyText, meta.encoding.decode(raw, 0, raw.size))
    }

    /** `mdx.js:50-55` — exact prefix match over the whole keyword list. */
    fun prefix(prefix: String): List<MdictCore.KeyWordItem> =
        associate(prefix).filter { it.keyText.startsWith(prefix) }

    /** `mdx.js:62-70` — every keyword sharing the key block of `phrase`. */
    fun associate(phrase: String): List<MdictCore.KeyWordItem> {
        val anchor = core.lookupKeyBlockByWord(phrase, isAssociate = true) ?: return emptyList()
        return core.keywordList.filter { it.keyBlockIdx == anchor.keyBlockIdx }
    }

    /**
     * `mdx.js:78-93` — suggestions within `distance` edits. `distance` outside
     * `0..5` yields an empty list, exactly like the reference (which logs instead of
     * throwing).
     */
    fun suggest(phrase: String, distance: Int): List<MdictCore.KeyWordItem> {
        if (distance < 0 || distance > 5) return emptyList()
        val stripped = core.strip(phrase)
        return associate(phrase).filter {
            Levenshtein.distance(core.strip(it.keyText), stripped) <= distance
        }
    }

    /** `mdx.js:115-129` — nearest `fuzzySize` keywords within `edGap` edits. */
    fun fuzzySearch(word: String, fuzzySize: Int, edGap: Int): List<MdictCore.KeyWordItem> {
        val stripped = core.strip(word)
        return associate(word)
            .map { it to Levenshtein.distance(core.strip(it.keyText), stripped) }
            .filter { it.second <= edGap }
            .sortedBy { it.second }
            .take(fuzzySize)
            .map { it.first }
    }

    override fun close() {
        if (ownsCore) core.close()
    }

    companion object {
        /** Open a dictionary from disk (file handle stays open until [close]). */
        fun open(file: File, options: MdictCore.Options = MdictCore.Options()): MdxParser =
            MdxParser(FileMdictSource(file), options)

        /** Open a dictionary already in memory (bundled asset, download buffer, tests). */
        fun open(
            bytes: ByteArray,
            name: String = "memory.mdx",
            options: MdictCore.Options = MdictCore.Options(),
        ): MdxParser = MdxParser(ByteArrayMdictSource(bytes, name), options)
    }
}
