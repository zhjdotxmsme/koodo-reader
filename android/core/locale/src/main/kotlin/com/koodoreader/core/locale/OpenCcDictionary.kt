package com.koodoreader.core.locale

/**
 * OpenCC-compatible conversion dictionary + MaxMatch (longest-match)
 * segmentation over a code-point trie.
 *
 * This is a native port of the parts of OpenCC that the desktop reader uses
 * for 简繁转换 (`kookit zh-convert.ts`, 8143 LOC — the minified bundle is not
 * readable, so the algorithm is reimplemented from the published OpenCC model,
 * see docs/p6-zh-locale-design.md §3):
 *
 *  - **file format** — identical to upstream OpenCC `*.txt` dictionaries:
 *    `key<TAB>value1 value2 ...`, one entry per line, `#` starts a comment
 *    line. Upstream files (STCharacters.txt ≈ 30k entries, STPhrases.txt,
 *    TWPhrasesIT.txt, …) therefore load **unchanged** through [parse]; the
 *    in-module seed (see [OpenCcSeed]) is a curated high-frequency subset.
 *  - **multi-value entries** — OpenCC stores alternatives separated by spaces;
 *    the first value is the default and is what this port emits ([firstValue]).
 *    Alternative values are kept for callers that want to re-rank them.
 *  - **MaxMatch segmentation** — at each position the *longest* key that has an
 *    entry wins; unmatched code points pass through verbatim (so Latin text,
 *    punctuation, digits and emoji are never touched). Surrogate pairs are
 *    advanced as one code point.
 *  - **dictionary order** — a *chain* of dictionaries is applied in order
 *    (OpenCC: STPhrases then STCharacters, etc.). Stages are applied to the
 *    whole string; [locked] marks a stage whose output must not be touched by
 *    later stages (used for the user dictionary, which is authoritative).
 *
 * Pure JVM, zero dependencies.
 */
class OpenCcDictionary private constructor(
    /** Dictionary name used in diagnostics ("STCharacters", "TWPhrases", …). */
    val name: String,
    private val entries: Map<String, List<String>>,
    /** When true, spans emitted by this dictionary are protected from later stages. */
    val locked: Boolean,
    /** Number of duplicated keys seen while parsing (first occurrence wins). */
    val duplicateKeys: Int,
) {
    private class Node {
        val children = HashMap<Char, Node>(4)
        var value: String? = null
    }

    private val root = Node()

    /** Longest key length in UTF-16 units (bounds the trie walk). */
    val maxKeyLength: Int

    init {
        var longest = 0
        for ((key, values) in entries) {
            require(key.isNotEmpty()) { "$name: empty key" }
            require(values.isNotEmpty()) { "$name: key '$key' has no value" }
            var node = root
            for (ch in key) {
                node = node.children.getOrPut(ch) { Node() }
            }
            node.value = values.first()
            if (key.length > longest) longest = key.length
        }
        maxKeyLength = longest
    }

    /** Number of entries (keys). */
    val size: Int get() = entries.size

    /** All keys of this dictionary (insertion order). */
    fun keys(): Set<String> = entries.keys

    /** All alternative values of [key], or null when absent. */
    fun values(key: String): List<String>? = entries[key]

    /** OpenCC default (first) value of [key], or null when absent. */
    fun firstValue(key: String): String? = entries[key]?.first()

    /**
     * One MaxMatch pass over [text] as `(piece, converted)` segments:
     * longest-key-wins, first value emitted, unmatched code points copied
     * through as unconverted pieces. The `converted` flag is what makes a
     * [locked] stage (the user dictionary) authoritative: only the pieces it
     * actually rewrote are protected from later stages, everything else stays
     * open for the built-in tables.
     *
     * O(n · maxKeyLength) — the trie walk stops at the first missing child, so
     * ordinary Han text visits only 1–2 nodes per character.
     */
    fun segments(text: String): List<Pair<String, Boolean>> {
        if (text.isEmpty() || entries.isEmpty()) return listOf(text to false)
        val out = ArrayList<Pair<String, Boolean>>(8)
        val pending = StringBuilder()
        var i = 0
        while (i < text.length) {
            var node: Node? = root
            var j = i
            var bestValue: String? = null
            var bestEnd = -1
            while (j < text.length && j - i < maxKeyLength) {
                node = node?.children?.get(text[j]) ?: break
                j++
                val hit = node.value
                if (hit != null) {
                    bestValue = hit
                    bestEnd = j
                }
            }
            val value = bestValue
            if (value != null) {
                if (pending.isNotEmpty()) {
                    out.add(pending.toString() to false)
                    pending.setLength(0)
                }
                out.add(value to true)
                i = bestEnd
            } else {
                val cp = text.codePointAt(i)
                pending.appendCodePoint(cp)
                i += Character.charCount(cp)
            }
        }
        if (pending.isNotEmpty()) out.add(pending.toString() to false)
        return out
    }

    /** One MaxMatch pass over [text]; equivalent to joining [segments]. */
    fun convert(text: String): String {
        if (text.isEmpty() || entries.isEmpty()) return text
        val segments = segments(text)
        if (segments.size == 1) return segments[0].first
        return segments.joinToString("") { it.first }
    }

    private class Span(val text: String, val locked: Boolean)

    override fun toString(): String =
        "OpenCcDictionary($name, entries=$size, maxKeyLength=$maxKeyLength, locked=$locked)"

    companion object {
        /** Separator between key and values in the upstream file format. */
        const val SEPARATOR = '\t'

        /** Multi-value separator inside the value column. */
        const val VALUE_SEPARATOR = ' '

        /**
         * Applies a chain of dictionaries in order (OpenCC conversion config).
         * A dictionary with `locked = true` protects **only the pieces it
         * rewrote** ([segments]) from later stages — that is how the user
         * dictionary stays authoritative over the built-in tables without
         * freezing the rest of the paragraph.
         */
        fun applyChain(text: String, chain: List<OpenCcDictionary>): String {
            if (chain.isEmpty() || text.isEmpty()) return text
            val active = chain.filter { it.size > 0 }
            if (active.isEmpty()) return text
            if (active.size == 1 && !active[0].locked) return active[0].convert(text)
            var spans = listOf(Span(text, false))
            for (dict in active) {
                val next = ArrayList<Span>(spans.size)
                for (span in spans) {
                    when {
                        span.locked -> next.add(span)
                        dict.locked -> for ((piece, converted) in dict.segments(span.text)) {
                            next.add(Span(piece, converted))
                        }
                        else -> next.add(Span(dict.convert(span.text), false))
                    }
                }
                spans = next
            }
            return spans.joinToString("") { it.text }
        }

        /**
         * Parses the upstream OpenCC text format. Blank lines and lines whose
         * first non-blank character is `#` are ignored; every other line must
         * contain exactly one [SEPARATOR]. Duplicated keys keep the first
         * occurrence and increment [duplicateKeys].
         *
         * @throws IllegalArgumentException on a malformed line (index included).
         */
        fun parse(
            name: String,
            text: String,
            locked: Boolean = false,
        ): OpenCcDictionary {
            val entries = LinkedHashMap<String, List<String>>()
            var duplicates = 0
            text.lineSequence().forEachIndexed { index, rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
                val sep = line.indexOf(SEPARATOR)
                require(sep > 0) {
                    val hint = "expected: key" + SEPARATOR + "value1 value2 …"
                    "$name: line ${index + 1} is malformed ($hint): $line"
                }
                val key = line.substring(0, sep).trim()
                val values = line.substring(sep + 1)
                    .split(VALUE_SEPARATOR)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                require(key.isNotEmpty()) { "$name: line ${index + 1} has an empty key" }
                require(values.isNotEmpty()) { "$name: line ${index + 1} has no value" }
                if (entries.containsKey(key)) {
                    duplicates++
                } else {
                    entries[key] = values
                }
            }
            return OpenCcDictionary(name, entries, locked, duplicates)
        }

        /** Builds a dictionary from pairs (first value wins on duplicate keys). */
        fun of(
            name: String,
            pairs: List<Pair<String, String>>,
            locked: Boolean = false,
        ): OpenCcDictionary {
            val entries = LinkedHashMap<String, List<String>>()
            var duplicates = 0
            for ((key, value) in pairs) {
                require(key.isNotEmpty()) { "$name: empty key" }
                require(value.isNotEmpty()) { "$name: empty value for key '$key'" }
                if (entries.containsKey(key)) duplicates++ else entries[key] = listOf(value)
            }
            return OpenCcDictionary(name, entries, locked, duplicates)
        }

        /**
         * Value→key reversal (OpenCC `T→S` direction): `乾 干` becomes `干 乾`.
         * First occurrence wins on collisions (deterministic, insertion order),
         * identity mappings are dropped.
         */
        fun reverse(
            name: String,
            source: OpenCcDictionary,
            locked: Boolean = false,
        ): OpenCcDictionary {
            val entries = LinkedHashMap<String, List<String>>()
            var duplicates = 0
            for (key in source.entries.keys) {
                val value = source.firstValue(key) ?: continue
                if (value == key) continue
                if (entries.containsKey(value)) duplicates++ else entries[value] = listOf(key)
            }
            return OpenCcDictionary(name, entries, locked, duplicates)
        }

        /**
         * Concatenates dictionaries into one stage: [first] wins on key
         * collisions (used for "explicit rules override derived rules").
         */
        fun merge(
            name: String,
            first: OpenCcDictionary,
            second: OpenCcDictionary,
            locked: Boolean = false,
        ): OpenCcDictionary {
            val entries = LinkedHashMap<String, List<String>>(first.entries)
            var duplicates = 0
            for ((key, value) in second.entries) {
                if (entries.containsKey(key)) duplicates++ else entries[key] = value
            }
            return OpenCcDictionary(name, entries, locked, duplicates)
        }

        /** Drops [excluded] keys from [source] (e.g. blocked ambiguous T→S chars). */
        fun without(source: OpenCcDictionary, excluded: Set<String>): OpenCcDictionary {
            if (excluded.isEmpty()) return source
            val entries = LinkedHashMap<String, List<String>>()
            for ((key, value) in source.entries) {
                if (!excluded.contains(key)) entries[key] = value
            }
            return OpenCcDictionary(source.name, entries, source.locked, source.duplicateKeys)
        }
    }
}
