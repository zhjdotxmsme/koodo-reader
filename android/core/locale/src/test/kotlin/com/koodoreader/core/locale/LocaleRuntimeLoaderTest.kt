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
 * download of the remaining 39 locales, hash verification, persistence across
 * restarts, cache eviction and validation parity with the build-time guard.
 */
class LocaleRuntimeLoaderTest {

    // ─── fixtures ────────────────────────────────────────────────────────────

    private val en = """{"Books":"Books","Language":"Language","Only In English":"Only In English"}"""
    private val zhCn = """{"Books":"书库","Language":"语言"}"""
    private val de = """{"Books":"Bücher","Language":"Sprache","Nur Deutsch":"Nur Deutsch"}"""

    private fun bundled(): MapLocaleAssetSource = MapLocaleAssetSource(mapOf("en" to en, "zh-CN" to zhCn))

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
    ) = LocaleRuntimeLoader(bundled(), packs, downloader, expected, LocaleCatalogRegistry(), maxCached)

    // ─── catalog constants ───────────────────────────────────────────────────

    @Test
    @DisplayName("the 39 remote locales plus the 2 bundled ones are exactly the desktop 41")
    fun `locale catalogs cover the desktop 41`() {
        assertEquals(listOf("en", "zh-CN"), LocaleRuntimeLoader.BUNDLED_LOCALE_CODES)
        assertEquals(39, LocaleRuntimeLoader.REMOTE_LOCALE_CODES.size)
        assertEquals(41, LocaleRuntimeLoader.ALL_LOCALE_CODES.size)
        assertEquals(41, LocaleRuntimeLoader.ALL_LOCALE_CODES.toSet().size)
        assertFalse(LocaleRuntimeLoader.REMOTE_LOCALE_CODES.contains("en"))
        assertTrue(LocaleRuntimeLoader.REMOTE_LOCALE_CODES.contains("zh-TW"))
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
        assertEquals(listOf("en", "zh-CN"), loader.preloadBundled().map { (it as LocaleEnsureResult.Ready).code })
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
        assertTrue(loader.load("de") is LocaleLoadResult.NotDownloaded)
        val ensure = loader.ensure("de")
        assertTrue(ensure is LocaleEnsureResult.NeedsDownload)
        assertEquals("de", (ensure as LocaleEnsureResult.NeedsDownload).code)
        assertEquals(null, ensure.expectedSha256)
        assertFalse(loader.locales().codes().contains("de"))

        // unknown codes are unsupported, never downloaded
        val unknown = loader.ensure("xx")
        assertTrue(unknown is LocaleEnsureResult.Unsupported)
        assertFalse(loader.isKnown("xx"))
    }

    @Test
    @DisplayName("ensure() downloads, verifies the sha256, installs and registers the pack")
    fun `ensure downloads and installs`() {
        val store = InMemoryLocalePackStore()
        val downloader = RecordingDownloader(mapOf("de" to de))
        val expected = mapOf("de" to Sha256.hex(de))
        val loader = loader(store, downloader, expected)

        val result = loader.ensure("de")
        assertTrue(result is LocaleEnsureResult.Downloaded)
        val downloaded = result as LocaleEnsureResult.Downloaded
        assertEquals(3, downloaded.keys)
        assertEquals(Sha256.hex(de), downloaded.sha256)
        assertEquals(listOf("de"), downloader.calls)

        // registered → the fallback chain resolves German immediately
        assertEquals(LocaleSourceKind.DOWNLOADED, loader.locales().kindOf("de"))
        assertEquals("Bücher", loader.chain("de").resolve("Books"))
        // installed on disk → survives an app restart / cache drop
        assertEquals(listOf("de"), loader.installedPacks().map { it.code })
        val second = loader.ensure("de")
        assertTrue(second is LocaleEnsureResult.Ready)
        assertEquals("de", (second as LocaleEnsureResult.Ready).code)
        assertEquals("Bücher", loader.locales().get("de")!!["Books"])
        assertEquals(listOf("de"), downloader.calls) // second ensure: cache hit, no re-download
    }

    @Test
    @DisplayName("a hash mismatch fails the install and leaves nothing behind")
    fun `hash mismatch fails install`() {
        val store = InMemoryLocalePackStore()
        val loader = loader(
            store,
            RecordingDownloader(mapOf("de" to de)),
            mapOf("de" to Sha256.hex("""{"Books":"tampered"}""")),
        )
        val result = loader.ensure("de")
        assertTrue(result is LocaleEnsureResult.Failed)
        assertTrue((result as LocaleEnsureResult.Failed).reason.contains("sha256 mismatch"))
        assertTrue(store.installed().isEmpty())
        assertFalse(loader.locales().codes().contains("de"))
    }

    @Test
    @DisplayName("download errors and invalid packs are reported without throwing")
    fun `download errors are reported`() {
        val offline = loader(InMemoryLocalePackStore(), RecordingDownloader(emptyMap(), fail = true))
        val failed = offline.ensure("de")
        assertTrue(failed is LocaleEnsureResult.Failed)
        assertTrue((failed as LocaleEnsureResult.Failed).reason.contains("download failed"))

        val invalid = loader(InMemoryLocalePackStore(), RecordingDownloader(mapOf("de" to """{"Books":12}""")))
        val invalidResult = invalid.ensure("de")
        assertTrue(invalidResult is LocaleEnsureResult.Failed)
        assertTrue((invalidResult as LocaleEnsureResult.Failed).reason.contains("invalid pack"))
    }

    // ─── corrupt packs ───────────────────────────────────────────────────────

    @Test
    @DisplayName("corrupt installed packs are rejected and removed")
    fun `corrupt packs are removed`() {
        val store = InMemoryLocalePackStore()
        store.write("de", """{"Books":""", Sha256.hex("""{"Books":"""))
        val loader = loader(store)
        assertTrue(loader.load("de") is LocaleLoadResult.Corrupt)
        val ensure = loader.ensure("de")
        assertTrue(ensure is LocaleEnsureResult.Failed)
        assertTrue(store.read("de") == null, "corrupt pack should be deleted")

        // non-string values are rejected too (same rule as sync-locales-android.js)
        val store2 = InMemoryLocalePackStore()
        store2.write("de", """{"Books":12}""", Sha256.hex("""{"Books":12}"""))
        assertTrue(loader(store2).load("de") is LocaleLoadResult.Corrupt)

        // hash drift between index and file is detected
        val store3 = InMemoryLocalePackStore()
        store3.write("de", de, Sha256.hex("""{"Books":"other"}"""))
        val drifted = loader(store3).load("de")
        assertTrue(drifted is LocaleLoadResult.Corrupt)
        assertTrue((drifted as LocaleLoadResult.Corrupt).reason.contains("hash mismatch"))
    }

    // ─── cache ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("LRU eviction frees memory but never the installed pack")
    fun `lru eviction keeps packs on disk`() {
        val store = InMemoryLocalePackStore()
        val downloader = RecordingDownloader(mapOf("de" to de, "fr" to """{"Books":"Livres"}"""))
        val loader = loader(store, downloader, emptyMap(), maxCached = 3)

        loader.preloadBundled() // en + zh-CN
        assertTrue(loader.ensure("de") is LocaleEnsureResult.Downloaded)
        assertTrue(loader.ensure("fr") is LocaleEnsureResult.Downloaded)

        // en/zh-CN are pinned, so eviction claims the least recently used pack
        assertEquals(listOf("en", "fr", "zh-CN"), loader.loadedCodes())
        assertFalse(loader.locales().codes().contains("de"))
        assertEquals(listOf("de", "fr"), loader.installedPacks().map { it.code })

        // re-selecting the evicted language is a file read, not a download
        val again = loader.ensure("de") as LocaleEnsureResult.Ready
        assertEquals(LocaleSourceKind.DOWNLOADED, again.kind)
        assertEquals(listOf("de", "fr"), downloader.calls)

        // pinned selection is never evicted
        assertTrue(loader.pin("de"))
        assertFalse(loader.evict("de"))
        assertFalse(loader.evict("en"), "bundled locales are never evicted")
    }

    @Test
    @DisplayName("removePack() uninstalls and unbundled codes are protected")
    fun `remove pack`() {
        val store = InMemoryLocalePackStore()
        val loader = loader(store, RecordingDownloader(mapOf("de" to de)))
        loader.ensure("de")
        assertTrue(loader.removePack("de"))
        assertTrue(store.installed().isEmpty())
        assertFalse(loader.locales().codes().contains("de"))
        assertFalse(loader.removePack("en"), "bundled locale cannot be uninstalled")
        assertFalse(loader.removePack("de"))
    }

    // ─── filesystem persistence ──────────────────────────────────────────────

    @Test
    @DisplayName("FilesLocalePackStore persists packs and the index across instances")
    fun `files pack store round trip`() {
        val root = Files.createTempDirectory("p6-locale-packs").toFile()
        try {
            val store = FilesLocalePackStore(File(root, "locales/packs"))
            val first = loader(store, RecordingDownloader(mapOf("de" to de)), mapOf("de" to Sha256.hex(de)))
            assertTrue(first.ensure("de") is LocaleEnsureResult.Downloaded)
            assertTrue(File(store.packPath("de")).isFile)
            assertFalse(File(root, "locales/packs/de.json.tmp").exists(), "temp file must not survive")

            // a fresh loader (new app start) finds the installed pack, no downloader
            val second = LocaleRuntimeLoader(bundled(), FilesLocalePackStore(File(root, "locales/packs")))
            val ready = second.ensure("de") as LocaleEnsureResult.Ready
            assertEquals(LocaleSourceKind.DOWNLOADED, ready.kind)
            assertEquals("Bücher", second.chain("de").resolve("Books"))
            assertEquals(listOf("de"), second.installedPacks().map { it.code })
            assertEquals(Sha256.hex(de), second.installedPacks().first().sha256)
            assertEquals(de.toByteArray(Charsets.UTF_8).size, second.installedPacks().first().bytes)

            assertTrue(second.removePack("de"))
            assertFalse(File(store.packPath("de")).exists())
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
            store.write("de", json, Sha256.hex(json))
            val result = loader(store).load("de")
            if (valid) {
                assertTrue(result is LocaleLoadResult.Loaded, "expected valid: $json")
            } else {
                assertTrue(result is LocaleLoadResult.Corrupt, "expected corrupt: $json")
            }
        }
    }
}
