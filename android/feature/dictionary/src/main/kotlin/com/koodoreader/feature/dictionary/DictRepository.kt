package com.koodoreader.feature.dictionary

import com.koodoreader.feature.dictionary.mdx.MdxParser
import java.io.Closeable
import java.io.File

/**
 * Where an installed dictionary came from. The desktop only knows "imported" and
 * "cloud" (src/utils/file/dictUtil.ts:26-35 vs :201-209); `BUNDLED` is the Android
 * addition for the 25 open dictionaries the task ships with the APK.
 */
enum class DictSourceKind { BUNDLED, IMPORTED, CLOUD }

/**
 * One installed dictionary.
 *
 * DESKTOP PARITY: `id` / `name` / `extension` are exactly `DictMeta`
 * (src/utils/file/dictUtil.ts:18-22); the desktop keys them under the
 * `customDicts` object config and keeps the order in the `dictList` list config
 * (:111-136). `enabled` / `isDefault` / `order` / `sizeBytes` / `source` are the
 * Android-side **extensions** the acceptance checklist needs (启用/排序/默认词典) —
 * the desktop settings page has no such toggles
 * (src/containers/settings/dictSetting/component.tsx:28-122 only imports, deletes
 * and downloads) and picks the dictionary from the reader plugin config
 * `dictService → config.dictId` (src/components/popups/popupDict/component.tsx:189).
 * Unknown fields are ignored by `ConfigService`, so a backup round-trip stays safe.
 */
data class DictEntry(
    val id: String,
    val name: String,
    val extension: String,
    val enabled: Boolean = true,
    val isDefault: Boolean = false,
    val order: Int = 0,
    val sizeBytes: Long = 0L,
    val source: DictSourceKind = DictSourceKind.IMPORTED,
    val installedAt: Long = 0L,
)

/** The whole persisted index — the Android mirror of `dictList` + `customDicts`. */
data class DictStore(
    val dicts: List<DictEntry> = emptyList(),
) {
    val defaultEntry: DictEntry? get() = dicts.firstOrNull { it.isDefault && it.enabled }
    val enabled: List<DictEntry> get() = dicts.filter { it.enabled }
}

/** Result of a cross-dictionary lookup. */
data class DictLookupResult(
    val word: String,
    val entry: DictEntry?,
    val html: String?,
    /** Suggestions from the dictionary that answered (edit-distance based). */
    val suggestions: List<String> = emptyList(),
) {
    val found: Boolean get() = html != null
}

/**
 * Installed-dictionary registry: files, enable/order/default flags, persistence and
 * the lookup pipeline the reader uses.
 *
 * STORAGE LAYOUT — mirrors the desktop folder exactly
 * (src/utils/file/dictUtil.ts:16, 26-35: `getStorageLocation()/dict/<id>.<ext>`):
 *
 * ```
 * <rootDir>/dict/<id>.mdx          the dictionary itself
 * <rootDir>/dict/<id>.mdd          optional resource companion
 * <rootDir>/dict/dict-index.json   this registry (DictStore)
 * <rootDir>/dict/bundled/          dictionaries unpacked from the APK assets
 * <rootDir>/dict/tmp/              in-flight downloads (atomically renamed on success)
 * ```
 *
 * Everything here is plain `java.io` so the whole class is unit-testable without
 * Android; the app passes `context.filesDir`.
 */
class DictRepository(
    private val rootDir: File,
) : Closeable {

    private var cached: DictStore? = null

    /**
     * Parsers stay open for the lifetime of the repository.
     *
     * Reading the keyword list is the expensive part of opening a `.mdx`
     * (`mdict-base.js:657-683` walks every key block), so re-opening per lookup would
     * make a 100k-entry dictionary unusable. js-mdict keeps one open `MDX` instance
     * per file for the same reason.
     */
    private val openParsers = HashMap<String, MdxParser>()

    val dictFolder: File get() = File(rootDir, DICT_FOLDER)

    val bundledFolder: File get() = File(dictFolder, "bundled")

    /** In-flight downloads; `OnDemandDownloader` writes here then renames. */
    val tempFolder: File get() = File(dictFolder, "tmp")

    private val indexFile: File get() = File(dictFolder, INDEX_FILE)

    // ------------------------------------------------------------------ read

    fun store(): DictStore {
        cached?.let { return it }
        val loaded = if (indexFile.isFile) {
            runCatching { decode(MiniJson.parseObject(indexFile.readText())) }
                .getOrElse { DictStore() }
        } else {
            DictStore()
        }
        cached = loaded
        return loaded
    }

    /** Installed dictionaries, in UI order (the order the reader walks for a lookup). */
    fun dicts(): List<DictEntry> = store().dicts.sortedBy { it.order }

    fun enabledDicts(): List<DictEntry> = store().enabled.sortedBy { it.order }

    fun defaultEntry(): DictEntry? = store().defaultEntry

    fun entry(id: String): DictEntry? = store().dicts.firstOrNull { it.id == id }

    /** `dictUtil.getDictFilePath` (:74-83) — the `<id>.<ext>` file, or `null`. */
    fun dictFile(id: String): File? {
        val entry = entry(id) ?: return null
        val candidates = listOf(
            File(dictFolder, "${entry.id}.${entry.extension}"),
            File(bundledFolder, "${entry.id}.${entry.extension}"),
        )
        return candidates.firstOrNull { it.isFile }
    }

    /** The `.mdd` companion of a `.mdx` dictionary, when present. */
    fun resourceFile(id: String): File? {
        val entry = entry(id) ?: return null
        val candidates = listOf(
            File(dictFolder, "${entry.id}.mdd"),
            File(bundledFolder, "${entry.id}.mdd"),
        )
        return candidates.firstOrNull { it.isFile }
    }

    // ----------------------------------------------------------------- write

    fun setEnabled(id: String, enabled: Boolean): DictStore =
        mutate { entry ->
            if (entry.id != id) entry
            else if (!enabled && entry.isDefault) entry.copy(enabled = false, isDefault = false)
            else entry.copy(enabled = enabled)
        }

    /** Exactly one default, and it is always enabled (a disabled default is a bug). */
    fun setDefault(id: String): DictStore = mutate { entry ->
        when {
            entry.id == id -> entry.copy(isDefault = true, enabled = true)
            else -> entry.copy(isDefault = false)
        }
    }

    fun clearDefault(): DictStore = mutate { it.copy(isDefault = false) }

    /** Move a dictionary one slot up (`delta = -1`) or down (`delta = +1`). */
    fun move(id: String, delta: Int): DictStore {
        val ordered = dicts().toMutableList()
        val from = ordered.indexOfFirst { it.id == id }
        if (from < 0) return store()
        val to = (from + delta).coerceIn(0, ordered.size - 1)
        if (to == from) return store()
        val moved = ordered.removeAt(from)
        ordered.add(to, moved)
        return replaceAll(ordered)
    }

    /** Reorder by an explicit id list (drag & drop); unknown ids are ignored. */
    fun reorder(ids: List<String>): DictStore {
        val byId = dicts().associateBy { it.id }
        val reordered = ids.mapNotNull { byId[it] }.toMutableList()
        val present = reordered.map { it.id }.toSet()
        dicts().forEach { if (it.id !in present) reordered.add(it) }
        return replaceAll(reordered)
    }

    /** Assign contiguous `order` values from the list position and persist. */
    private fun replaceAll(ordered: List<DictEntry>): DictStore {
        val next = ordered.mapIndexed { index, entry -> entry.copy(order = index) }
        return persist(DictStore(next))
    }

    /**
     * Install dictionary bytes under `<id>.<ext>`.
     *
     * `id` follows the desktop convention of `Date.now().toString()`
     * (dictSetting/component.tsx:50) so that ids stay unique across both platforms.
     */
    fun install(
        name: String,
        extension: String = "mdx",
        bytes: ByteArray,
        id: String = System.currentTimeMillis().toString(),
        source: DictSourceKind = DictSourceKind.IMPORTED,
    ): DictEntry {
        dictFolder.mkdirs()
        File(dictFolder, "$id.$extension").writeBytes(bytes)
        return register(id, name, extension, bytes.size.toLong(), source)
    }

    /** Copy a dictionary that already exists on disk (the desktop SAF/import path). */
    fun installFromFile(
        source: File,
        name: String = source.nameWithoutExtension,
        id: String = System.currentTimeMillis().toString(),
        kind: DictSourceKind = DictSourceKind.IMPORTED,
    ): DictEntry {
        dictFolder.mkdirs()
        val extension = source.extension.lowercase().ifEmpty { "mdx" }
        val target = File(dictFolder, "$id.$extension")
        source.copyTo(target, overwrite = true)
        // A `.mdx` import drags its `.mdd` sibling along when it exists.
        val mdd = File(source.parentFile, "${source.nameWithoutExtension}.mdd")
        if (extension == "mdx" && mdd.isFile) mdd.copyTo(File(dictFolder, "$id.mdd"), overwrite = true)
        return register(id, name, extension, target.length(), kind)
    }

    /** Registers a dictionary that is already in place (e.g. unpacked from assets). */
    fun register(
        id: String,
        name: String,
        extension: String = "mdx",
        sizeBytes: Long = 0L,
        source: DictSourceKind = DictSourceKind.IMPORTED,
    ): DictEntry {
        val existing = entry(id)
        val entry = DictEntry(
            id = id,
            name = name,
            extension = extension,
            enabled = existing?.enabled ?: true,
            isDefault = existing?.isDefault ?: store().dicts.isEmpty(),
            order = existing?.order ?: nextOrder(),
            sizeBytes = sizeBytes,
            source = source,
            installedAt = existing?.installedAt ?: System.currentTimeMillis(),
        )
        persist(DictStore(dicts().filter { it.id != id } + entry))
        return entry
    }

    private fun nextOrder(): Int = (dicts().maxOfOrNull { it.order } ?: -1) + 1

    /** Delete the dictionary file(s) plus its registry entry (dictUtil.deleteDict :58-71). */
    fun delete(id: String): Boolean {
        val entry = entry(id) ?: return false
        val files = listOf(
            File(dictFolder, "${entry.id}.${entry.extension}"),
            File(dictFolder, "${entry.id}.mdd"),
        )
        var removed = false
        for (file in files) {
            if (file.isFile && file.delete()) removed = true
        }
        val remaining = dicts().filter { it.id != id }
        // Keep the "exactly one default" invariant after deleting the default.
        val next = if (remaining.isNotEmpty() && remaining.none { it.isDefault }) {
            listOf(remaining.first().copy(isDefault = true)) + remaining.drop(1)
        } else {
            remaining
        }
        persist(DictStore(next))
        return removed
    }

    // ---------------------------------------------------------------- lookup

    /**
     * Look the word up across every enabled dictionary.
     *
     * DESKTOP PARITY: `DictUtil.lookupWord(id, word)` looks up exactly one
     * dictionary chosen by the caller (src/utils/file/dictUtil.ts:86-108, driven by
     * the plugin config in popupDict/component.tsx:189). Android keeps that
     * behaviour for the configured default but falls back to the remaining enabled
     * dictionaries in order, so a miss in the default dictionary still answers.
     */
    fun lookup(word: String): DictLookupResult {
        val query = word.trim()
        if (query.isEmpty()) return DictLookupResult(word, null, null)
        val ordered = enabledDicts().sortedByDescending { it.isDefault }
        for (entry in ordered) {
            val parser = openParser(entry.id) ?: continue
            val result = parser.lookup(query)
            val html = result.definition
            if (html != null) {
                val suggestions = parser.suggest(query, DEFAULT_SUGGEST_DISTANCE)
                    .map { it.keyText }
                    .filter { it != result.keyText }
                return DictLookupResult(query, entry, html, suggestions)
            }
        }
        return DictLookupResult(query, null, null)
    }

    /** Suggestions from the default (or first enabled) dictionary, for a miss. */
    fun suggest(word: String, distance: Int = DEFAULT_SUGGEST_DISTANCE): List<String> {
        val entry = defaultEntry() ?: enabledDicts().firstOrNull() ?: return emptyList()
        return openParser(entry.id)?.suggest(word.trim(), distance)?.map { it.keyText }.orEmpty()
    }

    /** Open (once) the dictionary behind `id`; `null` when the file is missing/corrupt. */
    fun openParser(id: String): MdxParser? {
        openParsers[id]?.let { return it }
        val file = dictFile(id) ?: return null
        val parser = runCatching { MdxParser.open(file) }.getOrNull() ?: return null
        openParsers[id] = parser
        return parser
    }

    /** Resource companion of a dictionary — used by the HTML renderer for images/audio. */
    fun openResourceParser(id: String): MddParser? {
        val file = resourceFile(id) ?: return null
        return runCatching { MddParser.open(file) }.getOrNull()
    }

    override fun close() {
        openParsers.values.forEach { runCatching { it.close() } }
        openParsers.clear()
    }

    // ----------------------------------------------------------- persistence

    private fun mutate(transform: (DictEntry) -> DictEntry): DictStore =
        persist(DictStore(dicts().map(transform)))

    private fun persist(store: DictStore): DictStore {
        val normalized = store.dicts.mapIndexed { index, entry -> entry.copy(order = index) }
        cached = DictStore(normalized)
        dictFolder.mkdirs()
        indexFile.writeText(MiniJson.write(encode(DictStore(normalized))))
        return cached!!
    }

    /** Force a re-read from disk (after the download manager writes files). */
    fun invalidate() {
        cached = null
    }

    internal fun encode(store: DictStore): Map<String, Any?> = linkedMapOf(
        // Desktop keys, verbatim: `dictList` + `customDicts` (configUtil.ts:284-290).
        "dictList" to store.dicts.map { it.id },
        "customDicts" to store.dicts.associate { entry ->
            entry.id to linkedMapOf<String, Any?>(
                "id" to entry.id,
                "name" to entry.name,
                "extension" to entry.extension,
                "enabled" to entry.enabled,
                "isDefault" to entry.isDefault,
                "order" to entry.order,
                "size" to entry.sizeBytes,
                "source" to entry.source.name.lowercase(),
                "installedAt" to entry.installedAt,
            )
        },
    )

    internal fun decode(json: Map<String, Any?>): DictStore {
        val list = (json["dictList"] as? List<*>)?.mapNotNull { it as? String }.orEmpty()
        val custom = (json["customDicts"] as? Map<*, *>).orEmpty()
        val entries = ArrayList<DictEntry>()
        // `dictList` carries the order; `customDicts` carries the metadata.
        for ((index, id) in list.withIndex()) {
            val raw = custom[id] as? Map<*, *> ?: continue
            entries.add(
                DictEntry(
                    id = id,
                    name = raw["name"] as? String ?: id,
                    extension = raw["extension"] as? String ?: "mdx",
                    enabled = raw["enabled"] as? Boolean ?: true,
                    isDefault = raw["isDefault"] as? Boolean ?: false,
                    order = (raw["order"] as? Number)?.toInt() ?: index,
                    sizeBytes = (raw["size"] as? Number)?.toLong() ?: 0L,
                    source = runCatching {
                        DictSourceKind.valueOf((raw["source"] as? String ?: "imported").uppercase())
                    }.getOrDefault(DictSourceKind.IMPORTED),
                    installedAt = (raw["installedAt"] as? Number)?.toLong() ?: 0L,
                )
            )
        }
        // Metadata present but missing from `dictList` (older/partial index).
        for ((key, value) in custom) {
            val id = key as? String ?: continue
            if (entries.any { it.id == id }) continue
            val raw = value as? Map<*, *> ?: continue
            entries.add(
                DictEntry(
                    id = id,
                    name = raw["name"] as? String ?: id,
                    extension = raw["extension"] as? String ?: "mdx",
                    enabled = raw["enabled"] as? Boolean ?: true,
                    isDefault = raw["isDefault"] as? Boolean ?: false,
                    order = (raw["order"] as? Number)?.toInt() ?: entries.size,
                )
            )
        }
        return DictStore(entries.sortedBy { it.order })
    }

    companion object {
        /** `dictUtil.DICT_FOLDER` (:16) — the desktop stores dictionaries in `dict/`. */
        const val DICT_FOLDER = "dict"

        const val INDEX_FILE = "dict-index.json"

        /** `mdx.js:78-82` caps the edit distance at 5 as well. */
        const val DEFAULT_SUGGEST_DISTANCE = 2
    }
}
