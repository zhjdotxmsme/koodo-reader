package com.koodoreader.core.locale

import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Runtime locale loading (P6 acceptance item ③): bundled subset, on-demand
 * download of the remaining 30 locales, hash verification, persistence across
 * restarts, cache eviction and validation parity with the build-time guard.
 */
class LocaleRuntimeLoaderTest {

    // ─── fixtures ────────────────────────────────────────────────────────────

    private val en = """{"Books":"Books","Language":"Language","Only In English":"Only In English"}"""
    private val zhCn = """{"Books":"书库","Language":"语言"}"""
    private val el = """{"Books":"Βιβλιοθήκη","Language":"Γλώσσα","Μόνο Ελληνικά":"Μόνο Ελληνικά"}"""

    /**
     * Every bundled locale gets a catalog, so `preloadBundled()` — which
     * walks the whole [LocaleRuntimeLoader.BUNDLED_LOCALE_CODES] list — sees
     * a complete asset set, exactly like the APK does.
     */
    private fun bundled(): MapLocaleAssetSource = MapLocaleAssetSource(
        LocaleRuntimeLoader.BUNDLED_LOCALE_CODES.associateWith { code ->
            when (code) {
                "en" -> en
                "zh-CN" -> zhCn
                "de" -> """{"Books":"Bücher","Language":"Sprache"}"""
                else -> """{"Books":"$code","Language":"$code"}"""
            }
        },
    )

    private class RecordingDownloader(
        private val payloads: Map<String, String>,
        private val fail: Boolean = false,
    ) : LocalePackDownloader {
        val calls = ArrayList<String>()
        override fun download(code: String, expectedSha256: String?): String {
            calls.add(code)
            if (fail) throw IllegalStateException("offline")
            return payloads[code] ?: throw IllegalStateException("no payload for $code")
        }
    }

    private fun loader(
        packs: LocalePackStore = InMemoryLocalePackStore(),
        downloader: LocalePackDownloader? = null,
        expected: Map<String, String> = emptyMap(),
        maxCached: Int = LocaleRuntimeLoader.DEFAULT_MAX_CACHED_PACKS,
        assets: LocaleAssetSource = bundled(),
    ) = LocaleRuntimeLoader(assets, packs, downloader, expected, LocaleCatalogRegistry(), maxCached)

    // ─── catalog constants ───────────────────────────────────────────────────

    @Test
    @DisplayName("the 30 remote locales plus the 11 bundled ones are exactly the desktop 41")
    fun `locale catalogs cover the desktop 41`() {
        assertEquals(
            listOf("en", "zh-CN", "zh-TW", "ja", "ko", "es", "fr", "de", "ru", "pt", "it"),
            LocaleRuntimeLoader.BUNDLED_LOCALE_CODES,
        )
        assertEquals(30, LocaleRuntimeLoader.REMOTE_LOCALE_CODES.size)
        assertEquals(41, LocaleRuntimeLoader.ALL_LOCALE_CODES.size)
        assertEquals(41, LocaleRuntimeLoader.ALL_LOCALE_CODES.toSet().size)
        assertFalse(LocaleRuntimeLoader.REMOTE_LOCALE_CODES.contains("en"))
        // zh-TW moved from on-demand to bundled; pt-BR stayed on demand.
        assertTrue(LocaleRuntimeLoader.BUNDLED_LOCALE_CODES.contains("zh-TW"))
        assertFalse(LocaleRuntimeLoader.REMOTE_LOCALE_CODES.contains("zh-TW"))
        assertTrue(LocaleRuntimeLoader.REMOTE_LOCALE_CODES.contains("pt-BR"))
        assertEquals("zh-CN.json", LocaleRuntimeLoader.packFileName("zh_cn"))
    }

    // ─── bundled ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("bundled locales load from assets, normalize the code and hit the cache")
    fun `bundled locales load`() {
        val loader = loader()
        val first = loader.load("zh_cn")
        assertTrue(first is LocaleLoadResult.Loaded)
        val loaded = first as LocaleLoadResult.Loaded
        assertEquals("zh-CN", loaded.code)
        assertEquals(LocaleSourceKind.BUNDLED, loaded.kind)
        assertEquals("语言", loaded.catalog["Language"])
        assertFalse(loaded.fromCache)

        val second = loader.load("zh-CN") as LocaleLoadResult.Loaded
        assertTrue(second.fromCache)
        assertEquals(LocaleSourceKind.BUNDLED, loader.locales().kindOf("zh-CN"))
        // preloadBundled walks the whole bundled list — every one of them
        // must come back Ready, because the APK ships all of them.
        assertEquals(
            LocaleRuntimeLoader.BUNDLED_LOCALE_CODES,
            loader.preloadBundled().map { (it as LocaleEnsureResult.Ready).code },
        )
    }

    @Test
    @DisplayName("a listed-but-missing bundled catalog is reported as corrupt, not missing")
    fun `missing bundled catalog is corrupt`() {
        val broken = object : LocaleAssetSource {
            override fun read(code: String): String? = if (code == "en") null else zhCn
            override fun list(): List<String> = listOf("en", "zh-CN")
        }
        val loader = LocaleRuntimeLoader(broken, InMemoryLocalePackStore())
        val result = loader.load("en")
        assertTrue(result is LocaleLoadResult.Corrupt)
        assertTrue((result as LocaleLoadResult.Corrupt).reason.contains("bundled"))
    }

    // ─── on-demand download ──────────────────────────────────────────────────

    @Test
    @DisplayName("a known locale without a pack reports NeedsDownload (no downloader)")
    fun `remote locale without pack needs download`() {
        val loader = loader()
        assertTrue(loader.load("el") is LocaleLoadResult.NotDownloaded)
        val ensure = loader.ensure("el")
        assertTrue(ensure is LocaleEnsureResult.NeedsDownload)
        assertEquals("el", (ensure as LocaleEnsureResult.NeedsDownload).code)
        assertEquals(null, ensure.expectedSha256)
        assertFalse(loader.locales().codes().contains("el"))

        // unknown codes are unsupported, never downloaded
        val unknown = loader.ensure("xx")
        assertTrue(unknown is LocaleEnsureResult.Unsupported)
        assertFalse(loader.isKnown("xx"))
    }

    @Test
    @DisplayName("ensure() downloads, verifies the sha256, installs and registers the pack")
    fun `ensure downloads and installs`() {
        val store = InMemoryLocalePackStore()
        val downloader = RecordingDownloader(mapOf("el" to el))
        val expected = mapOf("el" to Sha256.hex(el))
        val loader = loader(store, downloader, expected)

        val result = loader.ensure("el")
        assertTrue(result is LocaleEnsureResult.Downloaded)
        val downloaded = result as LocaleEnsureResult.Downloaded
        assertEquals(3, downloaded.keys)
        assertEquals(Sha256.hex(el), downloaded.sha256)
        assertEquals(listOf("el"), downloader.calls)

        // registered → the fallback chain resolves German immediately
        assertEquals(LocaleSourceKind.DOWNLOADED, loader.locales().kindOf("el"))
        assertEquals("Βιβλιοθήκη", loader.chain("el").resolve("Books"))
        // installed on disk → survives an app restart / cache drop
        assertEquals(listOf("el"), loader.installedPacks().map { it.code })
        val second = loader.ensure("el")
        assertTrue(second is LocaleEnsureResult.Ready)
        assertEquals("el", (second as LocaleEnsureResult.Ready).code)
        assertEquals("Βιβλιοθήκη", loader.locales().get("el")!!["Books"])
        assertEquals(listOf("el"), downloader.calls) // second ensure: cache hit, no re-download
    }

    @Test
    @DisplayName("a hash mismatch fails the install and leaves nothing behind")
    fun `hash mismatch fails install`() {
        val store = InMemoryLocalePackStore()
        val loader = loader(
            store,
            RecordingDownloader(mapOf("el" to el)),
            mapOf("el" to Sha256.hex("""{"Books":"tampered"}""")),
        )
        val result = loader.ensure("el")
        assertTrue(result is LocaleEnsureResult.Failed)
        assertTrue((result as LocaleEnsureResult.Failed).reason.contains("sha256 mismatch"))
        assertTrue(store.installed().isEmpty())
        assertFalse(loader.locales().codes().contains("el"))
    }

    @Test
    @DisplayName("download errors and invalid packs are reported without throwing")
    fun `download errors are reported`() {
        val offline = loader(InMemoryLocalePackStore(), RecordingDownloader(emptyMap(), fail = true))
        val failed = offline.ensure("el")
        assertTrue(failed is LocaleEnsureResult.Failed)
        assertTrue((failed as LocaleEnsureResult.Failed).reason.contains("download failed"))

        val invalid = loader(InMemoryLocalePackStore(), RecordingDownloader(mapOf("el" to """{"Books":12}""")))
        val invalidResult = invalid.ensure("el")
        assertTrue(invalidResult is LocaleEnsureResult.Failed)
        assertTrue((invalidResult as LocaleEnsureResult.Failed).reason.contains("invalid pack"))
    }

    // ─── corrupt packs ───────────────────────────────────────────────────────

    @Test
    @DisplayName("corrupt installed packs are rejected and removed")
    fun `corrupt packs are removed`() {
        val store = InMemoryLocalePackStore()
        store.write("el", """{"Books":""", Sha256.hex("""{"Books":"""))
        val loader = loader(store)
        assertTrue(loader.load("el") is LocaleLoadResult.Corrupt)
        val ensure = loader.ensure("el")
        assertTrue(ensure is LocaleEnsureResult.Failed)
        assertTrue(store.read("el") == null, "corrupt pack should be deleted")

        // non-string values are rejected too (same rule as sync-locales-android.js)
        val store2 = InMemoryLocalePackStore()
        store2.write("el", """{"Books":12}""", Sha256.hex("""{"Books":12}"""))
        assertTrue(loader(store2).load("el") is LocaleLoadResult.Corrupt)

        // hash drift between index and file is detected
        val store3 = InMemoryLocalePackStore()
        store3.write("el", el, Sha256.hex("""{"Books":"other"}"""))
        val drifted = loader(store3).load("el")
        assertTrue(drifted is LocaleLoadResult.Corrupt)
        assertTrue((drifted as LocaleLoadResult.Corrupt).reason.contains("hash mismatch"))
    }

    // ─── cache ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("LRU eviction frees memory but never the installed pack")
    fun `lru eviction keeps packs on disk`() {
        val store = InMemoryLocalePackStore()
        // Both packs must be ON-DEMAND locales: a bundled one is served from
        // the assets and never becomes an installed pack.
        val downloader = RecordingDownloader(mapOf("el" to el, "ro" to """{"Books":"Bibliotecă"}"""))
        // Only "en" is available as a bundled asset here, so the pinned set
        // stays small and the bound governs the downloaded packs.
        val loader = loader(
            packs = store,
            downloader = downloader,
            maxCached = 2,
            assets = MapLocaleAssetSource(mapOf("en" to en)),
        )

        assertTrue(loader.ensure("en") is LocaleEnsureResult.Ready)
        assertTrue(loader.ensure("el") is LocaleEnsureResult.Downloaded)
        assertTrue(loader.ensure("ro") is LocaleEnsureResult.Downloaded)

        // "en" is pinned, so "el" (the least recently used pack) is evicted
        assertEquals(listOf("en", "ro"), loader.loadedCodes())
        assertFalse(loader.locales().codes().contains("el"))
        assertEquals(listOf("el", "ro"), loader.installedPacks().map { it.code })

        // re-selecting the evicted language is a file read, not a download
        val again = loader.ensure("el") as LocaleEnsureResult.Ready
        assertEquals(LocaleSourceKind.DOWNLOADED, again.kind)
        assertEquals(listOf("el", "ro"), downloader.calls)

        // pinned selection is never evicted
        assertTrue(loader.pin("el"))
        assertFalse(loader.evict("el"))
        assertFalse(loader.evict("en"), "bundled locales are never evicted")
    }

    @Test
    @DisplayName("removePack() uninstalls and unbundled codes are protected")
    fun `remove pack`() {
        val store = InMemoryLocalePackStore()
        val loader = loader(store, RecordingDownloader(mapOf("el" to el)))
        loader.ensure("el")
        assertTrue(loader.removePack("el"))
        assertTrue(store.installed().isEmpty())
        assertFalse(loader.locales().codes().contains("el"))
        assertFalse(loader.removePack("en"), "bundled locale cannot be uninstalled")
        assertFalse(loader.removePack("el"))
    }

    // ─── filesystem persistence ──────────────────────────────────────────────

    @Test
    @DisplayName("FilesLocalePackStore persists packs and the index across instances")
    fun `files pack store round trip`() {
        val root = Files.createTempDirectory("p6-locale-packs").toFile()
        try {
            val store = FilesLocalePackStore(File(root, "locales/packs"))
            val first = loader(store, RecordingDownloader(mapOf("el" to el)), mapOf("el" to Sha256.hex(el)))
            assertTrue(first.ensure("el") is LocaleEnsureResult.Downloaded)
            assertTrue(File(store.packPath("el")).isFile)
            assertFalse(File(root, "locales/packs/el.json.tmp").exists(), "temp file must not survive")

            // a fresh loader (new app start) finds the installed pack, no downloader
            val second = LocaleRuntimeLoader(bundled(), FilesLocalePackStore(File(root, "locales/packs")))
            val ready = second.ensure("el") as LocaleEnsureResult.Ready
            assertEquals(LocaleSourceKind.DOWNLOADED, ready.kind)
            assertEquals("Βιβλιοθήκη", second.chain("el").resolve("Books"))
            assertEquals(listOf("el"), second.installedPacks().map { it.code })
            assertEquals(Sha256.hex(el), second.installedPacks().first().sha256)
            assertEquals(el.toByteArray(Charsets.UTF_8).size, second.installedPacks().first().bytes)

            assertTrue(second.removePack("el"))
            assertFalse(File(store.packPath("el")).exists())
            assertTrue(FilesLocalePackStore(File(root, "locales/packs")).installed().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    @DisplayName("validation matches the build-time guard: object, string values, non-empty")
    fun `validation parity with the build guard`() {
        val cases = mapOf(
            """{"Books":"书库"}""" to true,
            """  {"Books":"书库"}  """ to true,
            """{}""" to false,
            """[]""" to false,
            """{"Books":12}""" to false,
            """{"Books":null}""" to false,
            """{"Books":["a"]}""" to false,
            """{"Books":"a"}""" to true,
            """{"Books":"a:b [x]"}""" to true, // colons/brackets inside a value are fine
            """{"Books":"unterminated}""" to false,
        )
        for ((json, valid) in cases) {
            val store = InMemoryLocalePackStore()
            store.write("el", json, Sha256.hex(json))
            val result = loader(store).load("el")
            if (valid) {
                assertTrue(result is LocaleLoadResult.Loaded, "expected valid: $json")
            } else {
                assertTrue(result is LocaleLoadResult.Corrupt, "expected corrupt: $json")
            }
        }
    }
}
