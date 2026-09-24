package com.koodoreader.core.locale

import java.io.File
import java.nio.file.Files

/**
 * Framework-free self-check entry point for the locale module.
 * Mirrors the pattern used by :core:designsystem (DesignSystemSelfCheckKt) and
 * :engine:link.
 *
 * Prints a one-line summary:
 *   OK  — all checks passed (exit 0)
 *   FAIL — one or more checks failed (reasons on stderr, exit 1)
 *
 * Execute with:
 *   gradle :core:locale:selfCheck
 */
fun main() {
    val failures = mutableListOf<String>()

    runCheck("tri-state wires match the desktop reader config") {
        check(ZhConvertMode.AUTO.wire == "" && ZhConvertMode.TRADITIONAL.wire == "Simplified To Traditional") {
            "unexpected wire values: ${ZhConvertMode.entries.map { it.wire }}"
        }
        check(ZhConvertMode.fromWire("Traditional To Simplified") == ZhConvertMode.SIMPLIFIED) {
            "fromWire did not parse the desktop value"
        }
    }.onFailure { failures.add(it.message ?: it.toString()) }

    val engine = ZhConvertEngine.fromSeed()

    runCheck("简 → 繁 → 简 round trip") {
        val simplified = "后来我在计划里面写了一个软件，网络也很好。"
        val traditional = "後來我在計劃裏面寫了一個軟件，網絡也很好。"
        check(engine.toTraditional(simplified) == traditional) {
            "s2t: ${engine.toTraditional(simplified)}"
        }
        check(engine.toSimplified(traditional) == simplified) {
            "t2s: ${engine.toSimplified(traditional)}"
        }
        check(engine.toTraditional(simplified, taiwan = true).endsWith("網路也很好。")) {
            "s2tw: ${engine.toTraditional(simplified, taiwan = true)}"
        }
    }.onFailure { failures.add(it.message ?: it.toString()) }

    runCheck("AUTO leaves non-Chinese readers untouched, follows zh-CN/zh-TW otherwise") {
        val simplified = "后来我在计划里面写了一个软件，网络也很好。"
        check(engine.convert(simplified, ZhConvertMode.AUTO, "en") == simplified) { "en must be a no-op" }
        check(engine.convert(simplified, ZhConvertMode.AUTO, null) == simplified) { "null language must be a no-op" }
        check(engine.convert(simplified, ZhConvertMode.AUTO, "zh-TW").startsWith("後來")) { "zh-TW must convert" }
        check(engine.signal(simplified).script() == ChineseScript.SIMPLIFIED) { "detection failed" }
    }.onFailure { failures.add(it.message ?: it.toString()) }

    runCheck("seed dictionaries are duplicate-free and non-trivial") {
        for ((name, text) in listOf(
            "STCharacters" to OpenCcSeed.ST_CHARACTERS,
            "STPhrases" to OpenCcSeed.ST_PHRASES,
            "TWPhrases" to OpenCcSeed.TW_PHRASES,
            "TSCharactersExtra" to OpenCcSeed.TS_EXTRA_CHARACTERS,
        )) {
            val dict = OpenCcDictionary.parse(name, text)
            check(dict.duplicateKeys == 0) { "$name has ${dict.duplicateKeys} duplicated keys" }
            check(dict.size > 0) { "$name is empty" }
        }
        check(OpenCcDictionary.parse("STCharacters", OpenCcSeed.ST_CHARACTERS).size >= 500) {
            "STCharacters shrank"
        }
    }.onFailure { failures.add(it.message ?: it.toString()) }

    runCheck("locale catalogs: 2 bundled + 39 on demand = 41 desktop locales") {
        check(LocaleRuntimeLoader.BUNDLED_LOCALE_CODES == listOf("en", "zh-CN")) { "bundled subset changed" }
        check(LocaleRuntimeLoader.REMOTE_LOCALE_CODES.size == 39) {
            "expected 39 remote locales, got ${LocaleRuntimeLoader.REMOTE_LOCALE_CODES.size}"
        }
        check(LocaleRuntimeLoader.ALL_LOCALE_CODES.size == 41) { "expected 41 locales in total" }
    }.onFailure { failures.add(it.message ?: it.toString()) }

    runCheck("on-demand load: needs download → downloaded → visible to the chain") {
        val loader = LocaleRuntimeLoader(
            MapLocaleAssetSource(mapOf("en" to """{"Books":"Books","Only":"Only"}""")),
            InMemoryLocalePackStore(),
            object : LocalePackDownloader {
                override fun download(code: String, expectedSha256: String?): String =
                    """{"Books":"Bücher"}"""
            },
        )
        loader.load("en")
        val chain = loader.chain("de")
        check(chain.lookup(FallbackKey("Books")).step == FallbackStep.FALLBACK_LANGUAGE) {
            "de should fall back to en before the pack exists"
        }
        check(loader.ensure("de") is LocaleEnsureResult.Downloaded) { "de was not downloaded" }
        check(chain.resolve("Books") == "Bücher") { "chain did not pick up the new pack" }
        check(chain.lookup(FallbackKey("Only")).step == FallbackStep.FALLBACK_LANGUAGE) { "en fallback broken" }
        check(chain.resolve("Missing Key") == "Missing Key") { "key fallback broken" }
    }.onFailure { failures.add(it.message ?: it.toString()) }

    runCheck("settings: desktop key, codec round trip, live engine") {
        val repository = ZhConvertSettingsRepository()
        repository.setMode(ZhConvertMode.TRADITIONAL)
        check(repository.engine().toTraditional("干净") == "乾淨") { "engine hand-off broken" }
        check(ZhConvertPrefsCodec.KEYS.first() == "convertChinese") { "desktop key drifted" }
        val decoded = ZhConvertPrefsCodec.decode(ZhConvertPrefsCodec.encode(repository.settings))
        check(decoded == repository.settings) { "codec round trip mismatch" }
        repository.setUserDictionary("计划\t計畫", enabled = true)
        check(repository.engine().toTraditional("计划") == "計畫") { "user dictionary not applied" }
    }.onFailure { failures.add(it.message ?: it.toString()) }

    runCheck("pack store persists to disk and survives a restart") {
        val root = Files.createTempDirectory("p6-locale-selfcheck").toFile()
        try {
            val store = FilesLocalePackStore(File(root, "locales/packs"))
            val json = """{"Books":"Bücher"}"""
            val loader = LocaleRuntimeLoader(
                MapLocaleAssetSource(mapOf("en" to """{"Books":"Books"}""")),
                store,
                object : LocalePackDownloader {
                    override fun download(code: String, expectedSha256: String?): String = json
                },
            )
            check(loader.ensure("de") is LocaleEnsureResult.Downloaded) { "download failed" }
            val restarted = LocaleRuntimeLoader(
                MapLocaleAssetSource(mapOf("en" to """{"Books":"Books"}""")),
                FilesLocalePackStore(File(root, "locales/packs")),
            )
            val ready = restarted.ensure("de")
            check(ready is LocaleEnsureResult.Ready) { "pack did not survive the restart: $ready" }
            check(restarted.chain("de").resolve("Books") == "Bücher") { "restarted chain failed" }
        } finally {
            root.deleteRecursively()
        }
    }.onFailure { failures.add(it.message ?: it.toString()) }

    if (failures.isEmpty()) {
        println("OK — locale module self-check passed (${ZhConvertDictionaries.SEED.stCharacters.size} S→T characters, " +
            "${LocaleRuntimeLoader.REMOTE_LOCALE_CODES.size} on-demand locales)")
        kotlin.system.exitProcess(0)
    } else {
        System.err.println("FAIL")
        failures.forEach { System.err.println("  • $it") }
        kotlin.system.exitProcess(1)
    }
}

/** Runs one named check, capturing the failure message. */
private fun runCheck(label: String, block: () -> Unit): Result<Unit> = try {
    block()
    Result.success(Unit)
} catch (e: Throwable) {
    Result.failure(IllegalStateException("$label: ${e.message ?: e::class.simpleName}", e))
}
