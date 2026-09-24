package com.koodoreader.feature.dictionary.mdx

import java.nio.charset.Charset

/**
 * Header XML parsing + the `MdictMeta` state machine that derives every layout
 * parameter from it.
 *
 * PORT SOURCE — `node_modules/js-mdict@6.0.8/dist/esm/`:
 *  - `MdictMeta`          mdict-base.js:17-37
 *  - `_readHeader`        mdict-base.js:334-422  (length prefix, UTF-16LE XML,
 *                                                 adler32, version, numWidth,
 *                                                 encoding, encrypt flag)
 *  - `parseHeader`        utils.js:91-111
 *  - `unescapeEntities`   utils.js:326-332
 *  - `isTrue`             utils.js:289-294
 *  - `REGEXP_STRIPKEY`    utils.js:2-6
 *  - `getExtension`       utils.js:37-40
 */
enum class MdictEncoding(val charset: Charset, val label: String) {
    UTF8(Charsets.UTF_8, "UTF-8"),
    UTF16LE(Charset.forName("UTF-16LE"), "UTF-16"),
    GB18030(Charset.forName("GB18030"), "GB18030"),
    BIG5(Charset.forName("Big5"), "BIG5");

    /** Decode `len` bytes at `offset` with this dictionary's charset. */
    fun decode(bytes: ByteArray, offset: Int, len: Int): String {
        if (len <= 0) return ""
        var end = offset + len
        // A GB18030/Big5 key can end on a truncated multi-byte sequence when the
        // terminator scan ran against a malformed block; be liberal like TextDecoder.
        if (end > bytes.size) end = bytes.size
        return String(bytes, offset, end - offset, charset)
    }
}

/** Header attributes plus the parsed `StyleSheet` table. */
class MdictHeader(
    val attributes: Map<String, String>,
    val styleSheet: Map<String, List<String>>,
) {
    operator fun get(key: String): String? = attributes[key]

    val title: String get() = attributes["Title"].orEmpty()
    val description: String get() = attributes["Description"].orEmpty()
    val encodingLabel: String get() = attributes["Encoding"].orEmpty()

    /** `utils.js:289` — MDict booleans are `Yes`/`No` (occasionally `true`/`false`). */
    fun isTrue(key: String): Boolean {
        val v = attributes[key] ?: return false
        val lower = v.lowercase()
        return lower == "yes" || lower == "true"
    }
}

/**
 * Everything the reader needs to walk the container. Derived exactly like
 * `MdictBase._readHeader` so that a file the JS reference reads is read here too.
 */
class MdictMeta(
    val fileName: String,
    val ext: String,
    val header: MdictHeader,
    encryptType: Int = -1,
) {
    /** `mdict-base.js:383` — `parseFloat(header.GeneratedByEngineVersion)`. */
    val version: Double = header["GeneratedByEngineVersion"]?.toDoubleOrNull() ?: Double.NaN

    /** `mdict-base.js:384-391` — v2.0+ widened every structural number to 8 bytes. */
    val numWidth: Int = if (version >= 2.0) 8 else 4

    /** `mdict-base.js:359-367` — `No`/absent → 0, `Yes` → 1, otherwise the numeric value. */
    private val headerEncrypt: Int = when {
        header["Encrypted"].isNullOrEmpty() || header["Encrypted"] == "No" -> 0
        header["Encrypted"] == "Yes" -> 1
        else -> header["Encrypted"]!!.toIntOrNull() ?: 0
    }

    /**
     * `mdict-base.js:368-370` — the caller may override the header flag
     * (`options.encryptType`), which is how a dictionary with a wrong `Encrypted`
     * field is forced down one path or the other. 1 = record blocks encrypted,
     * 2 = key-info block encrypted.
     */
    val encrypt: Int = if (encryptType != -1) encryptType else headerEncrypt

    /**
     * `mdict-base.js:392-421`. Note the ordering traps kept from the reference:
     * `GBK`/`GB2312` are read as GB18030 (superset, so this is lossless), and an
     * `.mdd` file is ALWAYS UTF-16LE regardless of its header.
     */
    val encoding: MdictEncoding = when {
        ext == "mdd" -> MdictEncoding.UTF16LE
        header.encodingLabel.isEmpty() -> MdictEncoding.UTF8
        header.encodingLabel == "GBK" || header.encodingLabel == "GB2312" -> MdictEncoding.GB18030
        header.encodingLabel.lowercase() == "big5" -> MdictEncoding.BIG5
        header.encodingLabel.lowercase() == "utf16" || header.encodingLabel.lowercase() == "utf-16" ->
            MdictEncoding.UTF16LE
        else -> MdictEncoding.UTF8
    }

    /** `mdict-base.js:289` — terminator width while splitting key blocks. */
    val keyTerminatorWidth: Int = if (encoding == MdictEncoding.UTF16LE || ext == "mdd") 2 else 1

    /** `mdict-base.js:252` — `KeyCaseSensitive` in the header, `isCaseSensitive` in the code. */
    val headerKeyCaseSensitive: Boolean get() = header.isTrue("isCaseSensitive")

    /** `mdict-base.js:255`. */
    val headerStripKey: Boolean get() = header.isTrue("StripKey")

    override fun toString(): String =
        "MdictMeta(ext=$ext, version=$version, numWidth=$numWidth, encoding=${encoding.label}, encrypt=$encrypt)"
}

object MdictHeaderParser {

    /** `utils.js:91` — attribute regex, non-greedy and newline tolerant. */
    private val ATTR = Regex("(\\w+)=\"((.|\\r|\\n)*?)\"")

    private val LINE_SPLIT = Regex("[\\r\\n]+")

    /** `utils.js:37` — file extension with a default. */
    fun extension(fileName: String, defaultExt: String): String =
        Regex("\\.([^.]+)$").find(fileName)?.groupValues?.get(1) ?: defaultExt

    /**
     * Parse the UTF-16LE header text into attributes.
     *
     * The reference decodes the whole length-prefixed buffer, NUL terminator
     * included (`mdict-base.js:347-349`), which is harmless because the regex
     * stops at the closing quote; the same is true here.
     */
    fun parse(headerText: String): MdictHeader {
        val attributes = LinkedHashMap<String, String>()
        ATTR.findAll(headerText).forEach { match ->
            attributes[match.groupValues[1]] = unescapeEntities(match.groupValues[2])
        }
        val styleSheet = LinkedHashMap<String, List<String>>()
        val raw = attributes["StyleSheet"]
        if (!raw.isNullOrEmpty()) {
            val lines = raw.split(LINE_SPLIT)
            var i = 0
            while (i < lines.size) {
                val begin = lines.getOrNull(i + 1).orEmpty()
                val end = lines.getOrNull(i + 2).orEmpty()
                styleSheet[lines[i]] = listOf(begin, end)
                i += 3
            }
            // Keep the raw text too: desktop config / debugging wants it verbatim.
        }
        return MdictHeader(attributes, styleSheet)
    }

    /** `utils.js:326` — order matters: `&amp;` must be unescaped last. */
    fun unescapeEntities(text: String): String = text
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&amp;", "&")

    /**
     * `utils.js:333` — expand the `` `N` `` style markers a definition may use into
     * the header `StyleSheet` begin/end pairs. Returns the input unchanged when the
     * dictionary has no stylesheet (the overwhelmingly common case).
     */
    fun substituteStyleSheet(styleSheet: Map<String, List<String>>, text: String): String {
        if (styleSheet.isEmpty() || !text.contains('`')) return text
        val marker = Regex("`(\\d+)`")
        val tags = marker.findAll(text).map { it.groupValues[1] }.toList()
        if (tags.isEmpty()) return text
        val parts = text.split(marker)
        val body = parts.drop(1)
        val out = StringBuilder()
        for (i in body.indices) {
            val style = styleSheet[tags.getOrNull(i) ?: ""] ?: continue
            out.append(style.getOrElse(0) { "" }).append(body[i]).append(style.getOrElse(1) { "" })
        }
        return out.toString()
    }
}
