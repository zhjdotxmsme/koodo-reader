package com.koodoreader.feature.dictionary

import java.io.File

/** `getServerRegion()` (src/utils/common) — picks the storage host for downloads. */
enum class ServerRegion { CHINA, GLOBAL }

/**
 * One downloadable dictionary — the desktop `CloudDictItem`
 * (src/utils/file/dictUtil.ts:7-12): an id, an English name, its localized
 * translation and a free-form source note.
 */
data class CloudDictItem(
    val id: String,
    val name: String,
    val translation: String = "",
    val source: String = "",
    val bytes: Long = 0L,
)

/**
 * A dictionary that ships inside the APK (`assets/…`), unpacked on first use.
 *
 * The desktop keeps its 25 open dictionaries in `plugins/renderer/dictionary/`,
 * which is NOT present in this workspace (see the task card and
 * `docs/p6-dictionary-architecture.md` §6), so the Android side is
 * manifest-driven: the app ships `assets/dicts/catalog.json` and this class
 * installs whatever the manifest lists. Nothing about the code depends on the
 * names, so the data can be dropped in later without a code change.
 */
data class BundledDict(
    val id: String,
    val name: String,
    val assetPath: String,
    val extension: String = "mdx",
    /** Optional `.mdd` companion asset (images / CSS / audio). */
    val resourceAssetPath: String? = null,
)

/**
 * Catalog + install pipeline for dictionaries: cloud download, APK-bundled assets,
 * deletion and progress reporting.
 *
 * DESKTOP PARITY (src/utils/file/dictUtil.ts):
 *  - `getCloudDictUrl`   :145-151 — `https://storage.koodoreader.(cn|com)/dicts/<id>.mdx`
 *    (`.cn` only for a signed-in China-region user).
 *  - `downloadCloudDict` :154-199 — streamed fetch with progress; the byte stream is
 *    written verbatim (`Accept-Encoding: identity`).
 *  - `saveDownloadedDict` :201-209 — save the file, then store the metadata and add
 *    the id to the list, in that order.
 *  - `getCloudDictDisplayName` :139-142 — Chinese UI shows `translation`, otherwise `name`.
 *
 * ANDROID ADDITIONS: bundled-asset installs and the enable/order/default flags live
 * in [DictRepository] (the desktop has no equivalents — see [DictEntry]).
 */
class DictDownloadManager(
    private val repository: DictRepository,
    private val downloader: OnDemandDownloader,
    private val catalogJsonProvider: () -> String? = { null },
) {

    /** `.mdx` files are the only thing the desktop downloads, too. */
    private val cloudExtension = "mdx"

    private var manifestCache: Manifest? = null

    internal data class Manifest(val dicts: List<CloudDictItem>, val bundled: List<BundledDict>)

    /** The cloud catalog, read from the manifest the app provides (assets). */
    fun catalog(): List<CloudDictItem> = manifest().dicts

    /** Dictionaries shipped with the APK. */
    fun bundled(): List<BundledDict> = manifest().bundled

    fun isInstalled(id: String): Boolean = repository.entry(id) != null

    /** `getCloudDictDisplayName` (:139-142) — `zh*` locales read `translation`. */
    fun displayName(item: CloudDictItem, language: String): String =
        if (language.startsWith("zh") && item.translation.isNotEmpty()) item.translation else item.name

    /** `getCloudDictUrl` (:145-151). */
    fun cloudUrl(dictId: String, isAuthed: Boolean, region: ServerRegion): String {
        val base = if (region == ServerRegion.CHINA && isAuthed) {
            "https://storage.koodoreader.cn"
        } else {
            "https://storage.koodoreader.com"
        }
        return "$base/dicts/$dictId.$cloudExtension"
    }

    /**
     * Download + install a cloud dictionary.
     *
     * The file lands in `<dict folder>/tmp/<id>.mdx` and is only moved into place by
     * [OnDemandDownloader] after a complete transfer, so a failed download can never
     * leave a half-written dictionary that [DictRepository] would then try to parse.
     */
    fun download(
        item: CloudDictItem,
        isAuthed: Boolean,
        region: ServerRegion,
        language: String = "en",
        onProgress: (OnDemandDownloader.Progress) -> Unit = {},
    ): OnDemandDownloader.Outcome {
        if (isInstalled(item.id)) {
            return OnDemandDownloader.Outcome.Failure("dictionary ${item.id} is already installed")
        }
        val target = File(repository.tempFolder, "${item.id}.$cloudExtension")
        val request = OnDemandDownloader.Request(
            id = item.id,
            url = cloudUrl(item.id, isAuthed, region),
            targetFile = target,
            // A known size turns a truncated transfer into an explicit failure.
            expectedBytes = item.bytes.takeIf { it > 0 },
            // dictUtil.downloadCloudDict (:160-165).
            headers = mapOf(
                "Cache-Control" to "no-transform",
                "Accept-Encoding" to "identity",
                "User-Agent" to USER_AGENT,
            ),
        )
        val outcome = downloader.download(request, onProgress)
        if (outcome !is OnDemandDownloader.Outcome.Success) return outcome
        repository.installFromFile(
            source = target,
            name = displayName(item, language),
            id = item.id,
            kind = DictSourceKind.CLOUD,
        )
        // The temp copy is only a staging file.
        target.delete()
        repository.invalidate()
        return outcome
    }

    /**
     * Unpack a bundled dictionary from the APK into `<dict>/bundled/` and register it.
     * Called on first launch for every manifest entry that is not installed yet.
     */
    fun installBundled(
        dict: BundledDict,
        bytes: ByteArray,
        resourceBytes: ByteArray? = null,
    ): DictEntry {
        repository.bundledFolder.mkdirs()
        File(repository.bundledFolder, "${dict.id}.${dict.extension}").writeBytes(bytes)
        if (resourceBytes != null) {
            File(repository.bundledFolder, "${dict.id}.mdd").writeBytes(resourceBytes)
        }
        repository.invalidate()
        return repository.register(
            id = dict.id,
            name = dict.name,
            extension = dict.extension,
            sizeBytes = bytes.size.toLong(),
            source = DictSourceKind.BUNDLED,
        )
    }

    /** Bundled entries that still need unpacking (first-launch install pass). */
    fun pendingBundled(): List<BundledDict> = bundled().filter { !isInstalled(it.id) }

    /** Removes a dictionary (file + registry entry) — `dictUtil.deleteDict` (:58-71). */
    fun delete(id: String): Boolean {
        val removed = repository.delete(id)
        repository.invalidate()
        return removed
    }

    private fun manifest(): Manifest {
        manifestCache?.let { return it }
        val parsed = catalogJsonProvider()?.let { text ->
            runCatching { parseManifest(text) }.getOrNull()
        } ?: Manifest(emptyList(), emptyList())
        manifestCache = parsed
        return parsed
    }

    internal fun parseManifest(text: String): Manifest {
        val root = MiniJson.parseObject(text)
        val dicts = (root["dicts"] as? List<*>).orEmpty().mapNotNull { raw ->
            val map = raw as? Map<*, *> ?: return@mapNotNull null
            val id = map["id"] as? String ?: return@mapNotNull null
            CloudDictItem(
                id = id,
                name = map["name"] as? String ?: id,
                translation = map["translation"] as? String ?: "",
                source = map["source"] as? String ?: "",
                bytes = (map["bytes"] as? Number)?.toLong() ?: 0L,
            )
        }
        val bundled = (root["bundled"] as? List<*>).orEmpty().mapNotNull { raw ->
            val map = raw as? Map<*, *> ?: return@mapNotNull null
            val id = map["id"] as? String ?: return@mapNotNull null
            val asset = map["asset"] as? String ?: return@mapNotNull null
            BundledDict(
                id = id,
                name = map["name"] as? String ?: id,
                assetPath = asset,
                extension = map["extension"] as? String ?: "mdx",
                resourceAssetPath = map["resourceAsset"] as? String,
            )
        }
        return Manifest(dicts, bundled)
    }

    private companion object {
        /** Same UA family the desktop build sends from Electron. */
        const val USER_AGENT = "KoodoReader-Android"
    }
}
