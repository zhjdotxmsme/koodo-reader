package com.koodoreader.feature.dictionary

import com.koodoreader.feature.dictionary.mdx.ByteArrayMdictSource
import com.koodoreader.feature.dictionary.mdx.FileMdictSource
import com.koodoreader.feature.dictionary.mdx.MdictCore
import java.io.Closeable
import java.io.File
import java.util.Base64

/**
 * Native `.mdd` (resource container) reader — the companion file of a `.mdx`
 * dictionary, holding the images, CSS and fonts a definition refers to.
 *
 * PORT SOURCE — `node_modules/js-mdict@6.0.8/dist/esm/mdd.js:5-30` (`locate`):
 *
 * ```js
 * locate(resourceKey) { … BASE64ENCODER(meaningBuff) }   // mdd.js:28
 * ```
 *
 * The reference always returns base64 because that is what its browser caller
 * needs; Android wants the bytes (to decode a Bitmap / cache a file), so both are
 * exposed. Resource keys are UTF-16LE inside the container regardless of the
 * header (`mdict-base.js:418-421`), which [MdictCore] already handles.
 *
 * ```kotlin
 * MddParser.open(File(dictDir, "cambridge.mdd")).use { res ->
 *     res.locateBytes("\\img\\logo.png") // PNG bytes, or null
 * }
 * ```
 */
class MddParser(private val core: MdictCore) : Closeable {

    constructor(source: com.koodoreader.feature.dictionary.mdx.MdictByteSource) : this(MdictCore(source))

    /** One resolved resource: its container key, raw bytes and sniffed MIME type. */
    data class Resource(val keyText: String, val bytes: ByteArray, val mimeType: String) {
        /** `mdd.js:2-4` — base64, the shape the reference returns. */
        fun base64(): String = Base64.getEncoder().encodeToString(bytes)

        override fun equals(other: Any?): Boolean =
            this === other || (other is Resource && other.keyText == keyText && other.bytes.contentEquals(bytes))

        override fun hashCode(): Int = 31 * keyText.hashCode() + bytes.contentHashCode()
    }

    val keywordCount: Int get() = core.keywordList.size

    /** Container metadata (`.mdd` always reports UTF-16LE — `mdict-base.js:418-421`). */
    val meta: com.koodoreader.feature.dictionary.mdx.MdictMeta get() = core.meta

    val keywords: List<String> get() = core.keywordList.map { it.keyText }

    /** `mdd.js:11-30` — resolve a resource key, `null` when the container has no such entry. */
    fun locate(key: String): Resource? {
        val bytes = locateBytes(key) ?: return null
        return Resource(key, bytes, MimeTypes.of(key, bytes))
    }

    fun locateBytes(key: String): ByteArray? {
        val item = core.lookupKeyBlockByWord(key) ?: return null
        return core.lookupRecordByKeyBlock(item)
    }

    /**
     * Resource keys inside real `.mdd` files are inconsistent about the leading
     * `\` and letter case (`\img\a.PNG` vs `img\a.png`), while MDX definitions link
     * with the exact string the compiler emitted. Try the exact key, then the
     * `\`-normalised variants, then a case-insensitive scan.
     */
    fun locateFlexible(key: String): Resource? {
        val candidates = linkedSetOf(
            key,
            key.replace('/', '\\'),
            if (key.startsWith("\\")) key else "\\$key",
        )
        for (candidate in candidates) {
            locate(candidate)?.let { return it }
        }
        val needle = key.replace('/', '\\').trimStart('\\').lowercase()
        val match = core.keywordList.firstOrNull {
            it.keyText.replace('/', '\\').trimStart('\\').lowercase() == needle
        } ?: return null
        val bytes = core.lookupRecordByKeyBlock(match) ?: return null
        return Resource(match.keyText, bytes, MimeTypes.of(match.keyText, bytes))
    }

    override fun close() = core.close()

    companion object {
        fun open(file: File, options: MdictCore.Options = MdictCore.Options()): MddParser =
            MddParser(MdictCore(FileMdictSource(file), options))

        fun open(
            bytes: ByteArray,
            name: String = "memory.mdd",
            options: MdictCore.Options = MdictCore.Options(),
        ): MddParser = MddParser(MdictCore(ByteArrayMdictSource(bytes, name), options))
    }
}

/**
 * MIME resolution for `.mdd` resources: extension first, magic bytes as the
 * fallback (dictionary compilers emit keys without any extension surprisingly
 * often, e.g. `\img\1`).
 */
object MimeTypes {

    private val BY_EXTENSION = mapOf(
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "bmp" to "image/bmp",
        "svg" to "image/svg+xml",
        "css" to "text/css",
        "js" to "application/javascript",
        "ttf" to "font/ttf",
        "otf" to "font/otf",
        "woff" to "font/woff",
        "woff2" to "font/woff2",
        "mp3" to "audio/mpeg",
        "wav" to "audio/wav",
        "ogg" to "audio/ogg",
        "spx" to "audio/ogg",
        "mp4" to "video/mp4",
        "html" to "text/html",
        "htm" to "text/html",
        "txt" to "text/plain",
    )

    private const val DEFAULT = "application/octet-stream"

    fun of(key: String, bytes: ByteArray? = null): String {
        val ext = key.substringAfterLast('.', "").lowercase()
        BY_EXTENSION[ext]?.let { return it }
        return bytes?.let { sniff(it) } ?: DEFAULT
    }

    /** Magic-byte sniffing for keys that carry no usable extension. */
    fun sniff(bytes: ByteArray): String = when {
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() -> "image/png"
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte() -> "image/jpeg"
        bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") -> "image/gif"
        bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        bytes.size >= 4 && bytes[0] == 'O'.code.toByte() && bytes[1] == 'g'.code.toByte() &&
            bytes[2] == 'g'.code.toByte() && bytes[3] == 'S'.code.toByte() -> "audio/ogg"
        bytes.size >= 3 && bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() &&
            bytes[2] == '3'.code.toByte() -> "audio/mpeg"
        else -> DEFAULT
    }
}
