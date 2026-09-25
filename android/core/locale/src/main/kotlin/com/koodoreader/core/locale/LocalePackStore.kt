package com.koodoreader.core.locale

import com.koodoreader.core.common.FlatJson
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Sources and sinks of locale catalogs.
 *
 * The three ports mirror the three places a catalog can live:
 *
 *  - [LocaleAssetSource] — **bundled**: `assets/locales/<code>.json` inside the
 *    APK, synced from `src/assets/locales` by `scripts/sync-locales-android.js`
 *    (ADR-004: only `en` + `zh-CN` today).
 *  - [LocalePackStore] — **downloaded**: `<filesDir>/locales/packs/<code>.json`
 *    with a hash index, so a pack survives restarts and can be verified.
 *  - [LocalePackDownloader] — **network**: implemented by the app (OkHttp /
 *    DownloadManager); the module never opens a socket itself.
 *
 * All APIs are synchronous: callers use `Dispatchers.IO`. That keeps this
 * module free of coroutines/Android dependencies (see build.gradle header).
 */

/** Bundled catalog source (APK assets). */
interface LocaleAssetSource {

    /** Catalog JSON for [code], or null when this build does not bundle it. */
    fun read(code: String): String?

    /** Locale codes present in the bundle. */
    fun list(): List<String>
}

/** Downloaded locale packs on disk. */
interface LocalePackStore {

    /** Installed pack JSON for [code], or null when not installed. */
    fun read(code: String): String?

    /** Installs [json] for [code] and records [sha256]; returns the new index entry. */
    fun write(code: String, json: String, sha256: String): LocalePackInfo

    /** Installed packs, sorted by code. */
    fun installed(): List<LocalePackInfo>

    /** Removes an installed pack; true when something was removed. */
    fun delete(code: String): Boolean
}

/** One installed/downloaded locale pack. */
data class LocalePackInfo(
    val code: String,
    val sha256: String,
    val bytes: Int,
) {
    /** Index entry format: `<sha256>:<bytes>` (flat JSON string map). */
    fun encode(): String = "$sha256:$bytes"

    companion object {
        /** Parses an index entry; null when malformed. */
        fun decode(code: String, entry: String): LocalePackInfo? {
            val idx = entry.lastIndexOf(':')
            if (idx <= 0) return null
            val sha = entry.substring(0, idx)
            val bytes = entry.substring(idx + 1).toIntOrNull() ?: return null
            if (sha.isEmpty()) return null
            return LocalePackInfo(code, sha, bytes)
        }
    }
}

/** Blocking network fetch for one locale pack. Implemented by the app layer. */
interface LocalePackDownloader {

    /**
     * Fetches `<code>.json` and returns its text. Implementations should throw
     * on any failure; the loader turns that into [LocaleEnsureResult.Failed].
     */
    fun download(code: String, expectedSha256: String?): String
}

/** SHA-256 helper (no dependency). */
object Sha256 {

    /**
     * Canonical pack hash: over the LF-normalised text, BOM stripped.
     *
     * Why not the raw bytes: the desktop locale sources are the pack origin, and
     * git stores them with LF — `core.autocrlf=true` rewrites only the working
     * tree, so a Windows checkout hashes CRLF bytes while CI/Linux hashes LF. The
     * same locale would then have two hashes and the download manifest in
     * docs/p6-zh-locale-design.md could match only one platform. That table is
     * what `scripts/check-locales.mjs` part C compares against, so both sides
     * normalise.
     */
    fun hex(text: String): String = hex(canonicalBytes(text))

    fun hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    /** BOM-stripped, CRLF→LF bytes — the rule the manifest table is generated with. */
    fun canonicalBytes(text: String): ByteArray {
        val withoutBom = if (text.startsWith('\uFEFF')) text.substring(1) else text
        return withoutBom.replace("\r\n", "\n").toByteArray(StandardCharsets.UTF_8)
    }

    private const val HEX = "0123456789abcdef"
}

/** Map-backed bundled source (JVM tests, embedded catalogs). */
class MapLocaleAssetSource(private val catalogs: Map<String, String>) : LocaleAssetSource {

    override fun read(code: String): String? = catalogs[code]

    override fun list(): List<String> = catalogs.keys.sorted()
}

/** Map-backed pack store (JVM tests). */
class InMemoryLocalePackStore : LocalePackStore {

    private val packs = LinkedHashMap<String, String>()
    private val index = LinkedHashMap<String, LocalePackInfo>()

    override fun read(code: String): String? = packs[code]

    override fun write(code: String, json: String, sha256: String): LocalePackInfo {
        packs[code] = json
        val info = LocalePackInfo(code, sha256, json.toByteArray(StandardCharsets.UTF_8).size)
        index[code] = info
        return info
    }

    override fun installed(): List<LocalePackInfo> = index.values.sortedBy { it.code }

    override fun delete(code: String): Boolean {
        index.remove(code)
        return packs.remove(code) != null
    }
}

/**
 * Filesystem pack store — `<root>/<code>.json` plus a flat-JSON hash index
 * (`<root>/index.json`, `{"am":"<sha256>:<bytes>"}`). Writes are atomic
 * (temp file + rename), so a crash never leaves a half-written pack behind.
 */
class FilesLocalePackStore(private val root: File) : LocalePackStore {

    override fun read(code: String): String? {
        val file = packFile(code)
        return if (file.isFile) file.readText(StandardCharsets.UTF_8) else null
    }

    override fun write(code: String, json: String, sha256: String): LocalePackInfo {
        if (!root.isDirectory && !root.mkdirs()) {
            throw IllegalStateException("cannot create locale pack dir: ${root.path}")
        }
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        val tmp = File(root, "$code.json.tmp")
        tmp.writeBytes(bytes)
        moveInto(tmp, packFile(code))
        val info = LocalePackInfo(code, sha256, bytes.size)
        val index = readIndex().toMutableMap()
        index[code] = info.encode()
        writeIndex(index)
        return info
    }

    override fun installed(): List<LocalePackInfo> {
        val entries = readIndex()
        val out = ArrayList<LocalePackInfo>(entries.size)
        for ((code, entry) in entries) {
            LocalePackInfo.decode(code, entry)?.let { if (packFile(code).isFile) out.add(it) }
        }
        return out.sortedBy { it.code }
    }

    override fun delete(code: String): Boolean {
        val removed = packFile(code).delete()
        val index = readIndex().toMutableMap()
        if (index.remove(code) != null) writeIndex(index)
        return removed
    }

    /** Absolute path of an installed pack (diagnostics/tests). */
    fun packPath(code: String): String = packFile(code).path

    private fun packFile(code: String): File = File(root, "$code.json")

    private fun indexFile(): File = File(root, "index.json")

    /** Atomic rename when the filesystem supports it, plain replace otherwise. */
    private fun moveInto(tmp: File, target: File) {
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: java.io.IOException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: UnsupportedOperationException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun readIndex(): Map<String, String> {
        val file = indexFile()
        if (!file.isFile) return emptyMap()
        return runCatching { FlatJson.parse(file.readText(StandardCharsets.UTF_8)) }
            .getOrDefault(emptyMap())
    }

    private fun writeIndex(index: Map<String, String>) {
        if (!root.isDirectory && !root.mkdirs()) {
            throw IllegalStateException("cannot create locale pack dir: ${root.path}")
        }
        val body = index.entries.sortedBy { it.key }
            .joinToString(",", "{", "}") { (code, entry) -> "\"${escape(code)}\":\"${escape(entry)}\"" }
        val tmp = File(root, "index.json.tmp")
        tmp.writeText(body, StandardCharsets.UTF_8)
        moveInto(tmp, indexFile())
    }

    private fun escape(value: String): String = buildString(value.length) {
        for (ch in value) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
    }
}
