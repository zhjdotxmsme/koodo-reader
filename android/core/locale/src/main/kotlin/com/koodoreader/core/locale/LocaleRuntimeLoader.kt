package com.koodoreader.core.locale

import com.koodoreader.core.common.FlatJson
import java.nio.charset.StandardCharsets

/** Where a catalog in the registry came from. */
enum class LocaleSourceKind {
    /** Shipped in the APK (`assets/locales`, ADR-004 subset). */
    BUNDLED,

    /** Installed at runtime from a downloaded pack. */
    DOWNLOADED,
}

/**
 * Runtime locale registry: the single live view of "which catalogs exist right
 * now". [LocaleFallbackChain] reads it through [snapshot] (O(1) — the immutable
 * snapshot is rebuilt on mutation only), so a pack that finishes downloading is
 * immediately visible to every `t()` call without rebuilding anything.
 */
class LocaleCatalogRegistry {

    private val catalogs = LinkedHashMap<String, Map<String, String>>()
    private val kinds = LinkedHashMap<String, LocaleSourceKind>()
    private var view: Map<String, Map<String, String>> = emptyMap()

    /** Registers/overwrites a catalog; returns true when it was new. */
    fun put(code: String, catalog: Map<String, String>, kind: LocaleSourceKind): Boolean {
        val normalized = LocaleCodes.normalize(code)
        val isNew = !catalogs.containsKey(normalized)
        catalogs[normalized] = catalog
        kinds[normalized] = kind
        rebuild()
        return isNew
    }

    fun get(code: String): Map<String, String>? = catalogs[LocaleCodes.normalize(code)]

    fun kindOf(code: String): LocaleSourceKind? = kinds[LocaleCodes.normalize(code)]

    fun remove(code: String): Boolean {
        val normalized = LocaleCodes.normalize(code)
        kinds.remove(normalized)
        val removed = catalogs.remove(normalized) != null
        if (removed) rebuild()
        return removed
    }

    /** Registered locale codes, sorted. */
    fun codes(): List<String> = catalogs.keys.sorted()

    /** Registered locales of one origin, sorted. */
    fun codesOfKind(kind: LocaleSourceKind): List<String> =
        kinds.filterValues { it == kind }.keys.sorted()

    fun size(): Int = catalogs.size

    /** Immutable snapshot handed to the fallback chain (cheap, O(1)). */
    fun snapshot(): Map<String, Map<String, String>> = view

    private fun rebuild() {
        view = LinkedHashMap(catalogs)
    }
}

/** Result of a pure lookup ([LocaleRuntimeLoader.load]). */
sealed interface LocaleLoadResult {

    /** Catalog ready for use. */
    data class Loaded(
        val code: String,
        val kind: LocaleSourceKind,
        val catalog: Map<String, String>,
        val fromCache: Boolean,
    ) : LocaleLoadResult

    /** Not a known locale code (not bundled, not in the remote catalog). */
    data class Missing(val code: String) : LocaleLoadResult

    /** Known remote pack that is not installed yet. */
    data class NotDownloaded(val code: String) : LocaleLoadResult

    /** Present but unusable (malformed JSON / non-string values / hash mismatch). */
    data class Corrupt(val code: String, val reason: String) : LocaleLoadResult
}

/** Result of [LocaleRuntimeLoader.ensure] (lookup + download when needed). */
sealed interface LocaleEnsureResult {

    /** Already available (bundled or previously downloaded). */
    data class Ready(
        val code: String,
        val kind: LocaleSourceKind,
        val keys: Int,
    ) : LocaleEnsureResult

    /** Fetched and installed during this call. */
    data class Downloaded(
        val code: String,
        val keys: Int,
        val bytes: Int,
        val sha256: String,
    ) : LocaleEnsureResult

    /** No downloader configured (or offline): the app should offer/queue a download. */
    data class NeedsDownload(
        val code: String,
        val expectedSha256: String?,
    ) : LocaleEnsureResult

    /** [code] is not one of the 41 desktop locales. */
    data class Unsupported(val code: String) : LocaleEnsureResult

    /** Download or validation failed — [reason] is safe to log (no paths/secrets). */
    data class Failed(val code: String, val reason: String) : LocaleEnsureResult
}

/**
 * On-demand locale loader for the remaining **39** desktop locales
 * (`en` + `zh-CN` stay bundled per ADR-004 — 41 − 2 = 39).
 *
 * Lifecycle:
 *
 * ```
 * ensure(code) ──bundled──────────────────────────▶ Ready(BUNDLED)
 *              ├─installed pack──────────────────▶ Ready(DOWNLOADED)
 *              ├─known, no pack, downloader──────▶ Downloaded  (verify sha256 → install)
 *              ├─known, no pack, no downloader───▶ NeedsDownload
 *              └─unknown─────────────────────────▶ Unsupported
 * ```
 *
 * Keys stay identical to the desktop, and the lookup chain does not change:
 * a loaded pack only *adds* a catalog to the registry that
 * [LocaleFallbackChain] already reads (`selected → en → key`).
 *
 * Cache: an access-ordered LRU bounded by [maxCachedPacks]; bundled locale
 * codes, the fallback language and any [pin]ned code (the selected language)
 * are never evicted. Eviction only drops the in-memory catalog — the installed
 * pack stays on disk, so re-selecting the language is a file read, not a
 * download.
 *
 * Synchronous by design (see build.gradle header): call from `Dispatchers.IO`.
 */
class LocaleRuntimeLoader(
    private val assets: LocaleAssetSource,
    private val packs: LocalePackStore = InMemoryLocalePackStore(),
    private val downloader: LocalePackDownloader? = null,
    /** Expected sha256 per remote code (from `scripts/check-locales.mjs --print-pack-table`). */
    private val expectedPacks: Map<String, String> = emptyMap(),
    private val registry: LocaleCatalogRegistry = LocaleCatalogRegistry(),
    private val maxCachedPacks: Int = DEFAULT_MAX_CACHED_PACKS,
) {
    private val cache = object : LinkedHashMap<String, Map<String, String>>(16, 0.75f, true) {}
    private val pinned = LinkedHashSet<String>()

    init {
        require(maxCachedPacks >= 1) { "maxCachedPacks must be >= 1" }
        pinned.addAll(BUNDLED_LOCALE_CODES)
        pinned.add(LocaleCodes.FALLBACK)
    }

    /** Registry shared with [chain] — grows as packs are loaded. */
    fun locales(): LocaleCatalogRegistry = registry

    /** Fallback chain over the live registry (selected → en → key). */
    fun chain(language: String): LocaleFallbackChain = LocaleFallbackChain(registry, language)

    fun isBundled(code: String): Boolean = BUNDLED_LOCALE_CODES.contains(LocaleCodes.normalize(code))

    fun isRemote(code: String): Boolean = REMOTE_LOCALE_CODES.contains(LocaleCodes.normalize(code))

    fun isKnown(code: String): Boolean {
        val normalized = LocaleCodes.normalize(code)
        return BUNDLED_LOCALE_CODES.contains(normalized) || REMOTE_LOCALE_CODES.contains(normalized)
    }

    /** Installed (downloaded) packs on disk, sorted. */
    fun installedPacks(): List<LocalePackInfo> = packs.installed()

    /** Locale codes currently in the in-memory cache. */
    fun loadedCodes(): List<String> = cache.keys.sorted()

    /** Keeps [code] resident even beyond [maxCachedPacks] (e.g. the selected language). */
    fun pin(code: String): Boolean = pinned.add(LocaleCodes.normalize(code))

    fun unpin(code: String): Boolean = pinned.remove(LocaleCodes.normalize(code))

    /** Pure lookup: cache → bundled asset → installed pack (never downloads). */
    fun load(rawCode: String): LocaleLoadResult {
        val code = LocaleCodes.normalize(rawCode)
        cache[code]?.let {
            return LocaleLoadResult.Loaded(code, registry.kindOf(code) ?: LocaleSourceKind.BUNDLED, it, true)
        }
        if (isBundled(code) || assets.list().contains(code)) {
            val json = assets.read(code)
                ?: return LocaleLoadResult.Corrupt(code, "bundled catalog missing from assets")
            return try {
                val catalog = CatalogValidator.validate(code, json)
                register(code, catalog, LocaleSourceKind.BUNDLED)
                LocaleLoadResult.Loaded(code, LocaleSourceKind.BUNDLED, catalog, false)
            } catch (e: Exception) {
                LocaleLoadResult.Corrupt(code, reasonOf(e))
            }
        }
        if (!isRemote(code)) return LocaleLoadResult.Missing(code)
        val json = packs.read(code) ?: return LocaleLoadResult.NotDownloaded(code)
        return try {
            val catalog = CatalogValidator.validate(code, json)
            val info = packs.installed().firstOrNull { it.code == code }
            if (info != null) {
                val actual = Sha256.hex(json)
                if (!actual.equals(info.sha256, ignoreCase = true)) {
                    return LocaleLoadResult.Corrupt(
                        code,
                        "pack hash mismatch (index ${info.sha256.take(12)}…, file ${actual.take(12)}…)",
                    )
                }
            }
            register(code, catalog, LocaleSourceKind.DOWNLOADED)
            LocaleLoadResult.Loaded(code, LocaleSourceKind.DOWNLOADED, catalog, false)
        } catch (e: Exception) {
            LocaleLoadResult.Corrupt(code, reasonOf(e))
        }
    }

    /** Lookup, and download+install when the pack is missing and a downloader exists. */
    fun ensure(rawCode: String): LocaleEnsureResult {
        val code = LocaleCodes.normalize(rawCode)
        when (val result = load(code)) {
            is LocaleLoadResult.Loaded ->
                return LocaleEnsureResult.Ready(code, result.kind, result.catalog.size)
            is LocaleLoadResult.Missing ->
                return LocaleEnsureResult.Unsupported(code)
            is LocaleLoadResult.Corrupt -> {
                packs.delete(code)
                return LocaleEnsureResult.Failed(code, "corrupt pack removed: ${result.reason}")
            }
            is LocaleLoadResult.NotDownloaded -> Unit // fall through to download
        }
        val expected = expectedPacks[code]
        val remote = downloader ?: return LocaleEnsureResult.NeedsDownload(code, expected)
        val json = try {
            remote.download(code, expected)
        } catch (e: Exception) {
            return LocaleEnsureResult.Failed(code, "download failed: ${reasonOf(e)}")
        }
        val actual = Sha256.hex(json)
        if (expected != null && !expected.equals(actual, ignoreCase = true)) {
            return LocaleEnsureResult.Failed(
                code,
                "sha256 mismatch (expected ${expected.take(12)}…, got ${actual.take(12)}…)",
            )
        }
        val catalog = try {
            CatalogValidator.validate(code, json)
        } catch (e: Exception) {
            return LocaleEnsureResult.Failed(code, "invalid pack: ${reasonOf(e)}")
        }
        packs.write(code, json, actual)
        register(code, catalog, LocaleSourceKind.DOWNLOADED)
        return LocaleEnsureResult.Downloaded(
            code,
            catalog.size,
            json.toByteArray(StandardCharsets.UTF_8).size,
            actual,
        )
    }

    /** Ensures every bundled locale is resident (startup warm-up). */
    fun preloadBundled(): List<LocaleEnsureResult> = BUNDLED_LOCALE_CODES.map { ensure(it) }

    /** Uninstalls a downloaded pack and drops it from memory. */
    fun removePack(code: String): Boolean {
        val normalized = LocaleCodes.normalize(code)
        if (isBundled(normalized)) return false
        evict(normalized)
        return packs.delete(normalized)
    }

    /** Drops the in-memory catalog (installed pack/file stays). */
    fun evict(code: String): Boolean {
        val normalized = LocaleCodes.normalize(code)
        if (pinned.contains(normalized)) return false
        val removed = cache.remove(normalized) != null
        registry.remove(normalized)
        return removed
    }

    private fun register(code: String, catalog: Map<String, String>, kind: LocaleSourceKind) {
        cache[code] = catalog
        registry.put(code, catalog, kind)
        trimCache(code)
    }

    private fun trimCache(justAdded: String) {
        if (cache.size <= maxCachedPacks) return
        val iterator = cache.keys.iterator()
        while (cache.size > maxCachedPacks && iterator.hasNext()) {
            val code = iterator.next()
            if (code == justAdded || pinned.contains(code)) continue
            iterator.remove()
            registry.remove(code)
        }
    }

    private fun reasonOf(e: Exception): String = e.message ?: e::class.simpleName ?: "error"

    companion object {
        /** Bundled subset — keep aligned with ADR-004 and `sync-locales-android.js`. */
        val BUNDLED_LOCALE_CODES: List<String> = listOf("en", "zh-CN")

        /**
         * The remaining 39 desktop locales (`src/assets/locales` minus the
         * bundled subset), loaded on demand.
         *
         * CANONICAL LIST — `scripts/check-locales.mjs` parses this block and
         * fails when it drifts from the desktop locale directory.
         */
        val REMOTE_LOCALE_CODES: List<String> = listOf(
            "am", "ar", "bg", "bn", "bo", "cs", "da", "de", "el", "es",
            "fa", "fi", "fr", "ga", "hi", "hu", "hy", "id", "ie", "it",
            "ja", "ko", "nl", "pl", "pt-BR", "pt", "ro", "ru", "sl", "sr",
            "sv", "ta", "th", "tl", "tr", "uk", "vi", "zh-MO", "zh-TW",
        )

        /** All 41 desktop locales (2 bundled + 39 on demand). */
        val ALL_LOCALE_CODES: List<String> = BUNDLED_LOCALE_CODES + REMOTE_LOCALE_CODES

        /** Default LRU bound for resident catalogs (~1.4k keys each). */
        const val DEFAULT_MAX_CACHED_PACKS = 4

        /** Where the app keeps downloaded packs, relative to `filesDir`. */
        const val PACK_DIR = "locales/packs"

        /** Pack file name for [code] (inside [PACK_DIR]). */
        fun packFileName(code: String): String = "${LocaleCodes.normalize(code)}.json"
    }
}

/**
 * Catalog validation with the exact semantics of
 * `scripts/sync-locales-android.js --check`: valid JSON object, **every value a
 * string** (a non-string value is an error, even though the desktop files never
 * contain one), and at least one entry.
 */
internal object CatalogValidator {

    fun validate(code: String, json: String): Map<String, String> {
        require(json.trimStart().startsWith("{")) { "$code: catalog is not a JSON object" }
        val catalog = try {
            FlatJson.parse(json)
        } catch (e: Exception) {
            throw IllegalArgumentException("$code: invalid JSON (${e.message ?: "parse error"})")
        }
        require(catalog.isNotEmpty()) { "$code: catalog has no entries" }
        requireNoNonStringValues(code, json)
        return catalog
    }

    /** Structural scan: at object depth 1 every value must start with a quote. */
    private fun requireNoNonStringValues(code: String, json: String) {
        var i = 0
        var depth = 0
        var expectValue = false
        while (i < json.length) {
            val c = json[i]
            when {
                c == '"' -> {
                    i = skipString(json, i)
                    if (depth == 1) expectValue = false
                }
                c == '{' || c == '[' -> {
                    require(!(expectValue && depth == 1)) {
                        "$code: non-string value in catalog at index $i"
                    }
                    depth++
                    i++
                }
                c == '}' || c == ']' -> {
                    depth--
                    i++
                }
                c == ':' && depth == 1 -> {
                    expectValue = true
                    i++
                }
                c == ',' && depth == 1 -> {
                    expectValue = false
                    i++
                }
                c.isWhitespace() -> i++
                expectValue && depth == 1 -> throw IllegalArgumentException(
                    "$code: non-string value in catalog at index $i",
                )
                else -> i++
            }
        }
    }

    private fun skipString(s: String, start: Int): Int {
        var i = start + 1
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i += 2
                '"' -> return i + 1
                else -> i++
            }
        }
        throw IllegalArgumentException("unterminated string in catalog")
    }
}
