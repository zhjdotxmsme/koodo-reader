package com.koodoreader.core.locale

import com.koodoreader.core.common.FlatJson
import com.koodoreader.core.common.Localization
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The ADR-004 fallback chain (P6 acceptance item ④): 所选 → en → key, unchanged
 * by the runtime loader, and **provably identical** to the P1 [Localization]
 * implementation — including against the real desktop `en.json` / `zh-CN.json`.
 */
class LocaleFallbackChainTest {

    private val catalogs: Map<String, Map<String, String>> = mapOf(
        "en" to mapOf(
            "Books" to "Books",
            "Language" to "Language",
            "Only In English" to "Only In English",
        ),
        "zh-CN" to mapOf("Books" to "书库", "Language" to "语言"),
        "de" to mapOf("Books" to "Bücher"),
    )

    @Test
    @DisplayName("selected → en → key, with the step that produced the value")
    fun `chain order is selected then english then key`() {
        val chain = LocaleFallbackChain(catalogs, "zh-CN")

        val selected = chain.lookup(FallbackKey("Books"))
        assertEquals(FallbackStep.SELECTED, selected.step)
        assertEquals("zh-CN", selected.language)
        assertEquals("书库", selected.value)
        assertFalse(selected.isFallback)

        val english = chain.lookup(FallbackKey("Only In English"))
        assertEquals(FallbackStep.FALLBACK_LANGUAGE, english.step)
        assertEquals("en", english.language)
        assertEquals("Only In English", english.value)
        assertTrue(english.isFallback)

        val missing = chain.lookup(FallbackKey("No Such Key"))
        assertEquals(FallbackStep.KEY, missing.step)
        assertEquals(null, missing.language)
        assertEquals("No Such Key", missing.value)

        // unknown locale behaves like "no selected pack": straight to English
        assertEquals("Books", LocaleFallbackChain(catalogs, "ko").resolve("Books"))
        assertEquals("书库", LocaleFallbackChain(catalogs, "zh-CN").resolve("Books"))
    }

    @Test
    @DisplayName("locale codes are normalized and the language switch is live")
    fun `codes are normalized and switching is live`() {
        val chain = LocaleFallbackChain(catalogs, "zh_cn")
        assertEquals("zh-CN", chain.language)
        assertEquals(listOf("zh-CN", "en", LocaleFallbackChain.KEY_STEP), chain.chainFor())

        chain.language = "de"
        assertEquals("de", chain.language)
        assertEquals("Bücher", chain.resolve("Books")) // de hit
        // "Language" is missing from the de pack → the English pack answers it…
        assertEquals("Language", chain.resolve("Language"))
        // …and a key nobody has falls back to the key text itself (never blank).
        assertEquals("L", chain.resolve("L"))

        chain.language = "zh_CN"
        assertEquals("zh-CN", chain.language)
        assertEquals("书库", chain.resolve("Books"))
        assertEquals(listOf("de", "en", "zh-CN"), chain.languages())
        assertTrue(chain.has(FallbackKey("Books")))
        assertFalse(chain.has(FallbackKey("Nope")))
    }

    @Test
    @DisplayName("resolveAll keeps order; blank keys are rejected")
    fun `resolve all and key validation`() {
        val chain = LocaleFallbackChain(catalogs, "zh-CN")
        val resolved = chain.resolveAll(listOf(FallbackKey("Books"), FallbackKey("Only In English")))
        assertEquals(listOf("Books", "Only In English"), resolved.keys.toList())
        assertEquals("书库", resolved["Books"])
        assertEquals("Only In English", resolved["Only In English"])
        assertThrows(IllegalArgumentException::class.java) { FallbackKey("") }
        assertThrows(IllegalArgumentException::class.java) { FallbackKey("   ") }
    }

    @Test
    @DisplayName("parity with the P1 Localization.t() for every language and key")
    fun `parity with localization`() {
        val keys = catalogs.values.flatMap { it.keys }.toSet() + "Missing Key"
        for (language in listOf("en", "zh-CN", "de", "ko", "zh_cn")) {
            val chain = LocaleFallbackChain(catalogs, language)
            val reference = Localization(catalogs, language)
            for (key in keys) {
                assertEquals(
                    reference.t(key),
                    chain.resolve(key),
                    "chain/localization disagree for '$key' in '$language'",
                )
            }
            assertEquals(reference.language, chain.language)
            assertEquals(reference.t("Missing Key"), "Missing Key")
        }
    }

    @Test
    @DisplayName("registry-backed chain sees packs that arrive later")
    fun `chain follows the runtime loader`() {
        val store = InMemoryLocalePackStore()
        val downloader = object : LocalePackDownloader {
            override fun download(code: String, expectedSha256: String?): String =
                """{"Books":"Bücher"}"""
        }
        val loader = LocaleRuntimeLoader(
            MapLocaleAssetSource(mapOf("en" to """{"Books":"Books","Only":"Only"}""")),
            store,
            downloader,
        )
        val chain = loader.chain("de")
        assertTrue(loader.load("en") is LocaleLoadResult.Loaded) // bundled English ready
        val before = chain.lookup(FallbackKey("Books"))
        assertEquals(FallbackStep.FALLBACK_LANGUAGE, before.step)
        assertEquals("Books", before.value)

        val result = loader.ensure("de")
        assertTrue(result is LocaleEnsureResult.Downloaded)
        // same chain instance, no rebuild: the new catalog is picked up
        assertEquals("Bücher", chain.resolve("Books"))
        assertEquals(FallbackStep.SELECTED, chain.lookup(FallbackKey("Books")).step)
        assertEquals("Only", chain.resolve("Only"))
    }

    @Test
    @DisplayName("real desktop en.json / zh-CN.json: chain parity and drift guard")
    fun `real desktop catalogs`() {
        val root = repoRoot()
        assumeTrue(root != null, "desktop locale sources not reachable from ${System.getProperty("user.dir")}")
        val en = FlatJson.parse(File(root, "src/assets/locales/en.json").readText())
        val zh = FlatJson.parse(File(root, "src/assets/locales/zh-CN.json").readText())

        assertTrue(en.size >= 1300, "en.json shrank to ${en.size} keys")
        assertTrue(zh.size >= 1300, "zh-CN.json shrank to ${zh.size} keys")
        assertEquals("语言", zh["Language"])

        val catalogs = mapOf("en" to en, "zh-CN" to zh)
        for (language in listOf("en", "zh-CN", "de")) {
            val chain = LocaleFallbackChain(catalogs, language)
            val reference = Localization(catalogs, language)
            for (key in en.keys) {
                assertEquals(reference.t(key), chain.resolve(key), "drift for '$key' in '$language'")
            }
        }

        // keys the Chinese catalog is missing must fall back to English (ADR-004)
        val missingInChinese = en.keys.firstOrNull { !zh.containsKey(it) }
        assumeTrue(missingInChinese != null, "zh-CN.json is a full superset — nothing to assert")
        val hit = LocaleFallbackChain(catalogs, "zh-CN").lookup(FallbackKey(missingInChinese!!))
        assertEquals(FallbackStep.FALLBACK_LANGUAGE, hit.step)
        assertEquals(en[missingInChinese], hit.value)
    }

    /** Walks up from the test working directory to the repository root. */
    private fun repoRoot(): File? {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            val candidate = dir ?: return null
            if (File(candidate, "src/assets/locales/en.json").isFile) return candidate
            dir = candidate.parentFile
        }
        return null
    }
}
