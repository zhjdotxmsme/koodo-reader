package com.koodoreader.core.designsystem

/**
 * Framework-free self-check entry point for the design system module.
 * Mirrors the pattern used by :engine:link (LinkSelfCheckKt) and :engine:gesture.
 *
 * Runs all critical invariant checks and prints a one-line summary:
 *   OK  — all checks passed
 *   FAIL — one or more checks failed (stack trace printed, exit code 1)
 *
 * Execute with:
 *   gradle :core:designsystem:selfCheck
 *
 * (Registered in build.gradle as tasks.register('selfCheck', JavaExec) pointing
 * to this file's main class.)
 */
fun main(args: Array<String>) {
    val failures = mutableListOf<String>()

    // ─── 1. TypographyTokens defaults ─────────────────────────────────────────
    runCheck("TypographyTokens default fontSizeSp == 17f") {
        check(TypographyTokens().fontSizeSp == 17f) {
            "Expected 17f, got ${TypographyTokens().fontSizeSp}"
        }
    }.onFailure { failures.add(it) }

    runCheck("TypographyTokens default lineHeightRatio == 1.5f") {
        check(TypographyTokens().lineHeightRatio == 1.5f) {
            "Expected 1.5f, got ${TypographyTokens().lineHeightRatio}"
        }
    }.onFailure { failures.add(it) }

    runCheck("TypographyTokens default textAlign == left") {
        check(TypographyTokens().textAlign == "left") {
            "Expected 'left', got '${TypographyTokens().textAlign}'"
        }
    }.onFailure { failures.add(it) }

    runCheck("TypographyTokens default margin fields == 0") {
        val t = TypographyTokens()
        check(t.marginLeftPx == 0 && t.marginRightPx == 0 && t.marginTopPx == 0 && t.marginBottomPx == 0) {
            "margin fields not all 0: L=${t.marginLeftPx} R=${t.marginRightPx} T=${t.marginTopPx} B=${t.marginBottomPx}"
        }
    }.onFailure { failures.add(it) }

    // ─── 2. Theme presets ────────────────────────────────────────────────────
    runCheck("DEFAULT preset backgroundColor == rgba(255,255,255,1)") {
        check(ThemeSpec.DEFAULT_PRESET.backgroundColor == "rgba(255,255,255,1)") {
            "Got ${ThemeSpec.DEFAULT_PRESET.backgroundColor}"
        }
    }.onFailure { failures.add(it) }

    runCheck("DEFAULT preset foregroundColor == rgba(0,0,0,1)") {
        check(ThemeSpec.DEFAULT_PRESET.foregroundColor == "rgba(0,0,0,1)") {
            "Got ${ThemeSpec.DEFAULT_PRESET.foregroundColor}"
        }
    }.onFailure { failures.add(it) }

    runCheck("PROTECT_EYE preset backgroundColor == rgba(197, 231, 207,1)") {
        check(ThemeSpec.PROTECT_EYE_PRESET.backgroundColor == "rgba(197, 231, 207,1)") {
            "Got ${ThemeSpec.PROTECT_EYE_PRESET.backgroundColor}"
        }
    }.onFailure { failures.add(it) }

    runCheck("PROTECT_EYE preset foregroundColor == rgba(54, 80, 62,1)") {
        check(ThemeSpec.PROTECT_EYE_PRESET.foregroundColor == "rgba(54, 80, 62,1)") {
            "Got ${ThemeSpec.PROTECT_EYE_PRESET.foregroundColor}"
        }
    }.onFailure { failures.add(it) }

    runCheck("NIGHT preset backgroundColor == rgba(44, 47, 49,1)") {
        check(ThemeSpec.NIGHT_PRESET.backgroundColor == "rgba(44, 47, 49,1)") {
            "Got ${ThemeSpec.NIGHT_PRESET.backgroundColor}"
        }
    }.onFailure { failures.add(it) }

    runCheck("NIGHT preset foregroundColor == rgba(255,255,255,1)") {
        check(ThemeSpec.NIGHT_PRESET.foregroundColor == "rgba(255,255,255,1)") {
            "Got ${ThemeSpec.NIGHT_PRESET.foregroundColor}"
        }
    }.onFailure { failures.add(it) }

    runCheck("BUILT_IN_PRESETS has 3 entries") {
        check(ThemeSpec.BUILT_IN_PRESETS.size == 3) {
            "Expected 3, got ${ThemeSpec.BUILT_IN_PRESETS.size}"
        }
    }.onFailure { failures.add(it) }

    // ─── 3. Font catalog entry keys ──────────────────────────────────────────
    runCheck("FontCatalogEntry.BUILT_IN.key == Built-in font") {
        check(FontCatalogEntry.BUILT_IN.key == "Built-in font") {
            "Got ${FontCatalogEntry.BUILT_IN.key}"
        }
    }.onFailure { failures.add(it) }

    runCheck("CSS_FAMILY_KEYS contains serif, sans-serif, monospace") {
        val keys = FontCatalogEntry.CSS_FAMILY_KEYS
        check(keys.contains("serif") && keys.contains("sans-serif") && keys.contains("monospace")) {
            "Got $keys"
        }
    }.onFailure { failures.add(it) }

    runCheck("KNOWN_BUNDLED_KEYS is non-empty") {
        check(FontCatalogEntry.KNOWN_BUNDLED_KEYS.isNotEmpty()) {
            "KNOWN_BUNDLED_KEYS is empty"
        }
    }.onFailure { failures.add(it) }

    runCheck("KNOWN_BUNDLED_KEYS contains Inter-Regular") {
        check(FontCatalogEntry.KNOWN_BUNDLED_KEYS.contains("Inter-Regular")) {
            "Inter-Regular not found in ${FontCatalogEntry.KNOWN_BUNDLED_KEYS}"
        }
    }.onFailure { failures.add(it) }

    // ─── 4. Codec round-trip ─────────────────────────────────────────────────
    runCheck("Codec round-trip: default ReaderAppearanceConfig") {
        val original = ReaderAppearanceConfig()
        val json = AppearanceCodec.encode(original)
        val decoded = AppearanceCodec.decode(json)
        check(original == decoded) {
            "Round-trip mismatch for default config"
        }
    }.onFailure { failures.add(it) }

    runCheck("Codec round-trip: custom ThemeSpec with backgroundImage") {
        val original = ReaderAppearanceConfig(
            theme = ThemeSpec(
                kind = ThemeKind.CUSTOM,
                backgroundColor = "#123456",
                backgroundImage = "official-background-2",
                foregroundColor = "#abcdef",
                name = "Test Theme",
            ),
        )
        val decoded = AppearanceCodec.decode(AppearanceCodec.encode(original))
        check(original == decoded) {
            "Round-trip mismatch for custom theme"
        }
    }.onFailure { failures.add(it) }

    runCheck("Codec round-trip: custom font entry") {
        val original = ReaderAppearanceConfig(
            fontCatalogEntry = FontCatalogEntry(
                key = "LXGWWenKai-Regular",
                displayName = "霞鹜文楷",
                fontFamily = "LXGW Wenkai",
            ),
        )
        val decoded = AppearanceCodec.decode(AppearanceCodec.encode(original))
        check(original == decoded) {
            "Round-trip mismatch for custom font entry"
        }
    }.onFailure { failures.add(it) }

    runCheck("Codec round-trip: PROTECT_EYE preset") {
        val original = ReaderAppearanceConfig(theme = ThemeSpec.PROTECT_EYE_PRESET)
        val decoded = AppearanceCodec.decode(AppearanceCodec.encode(original))
        check(original == decoded) {
            "Round-trip mismatch for PROTECT_EYE preset"
        }
    }.onFailure { failures.add(it) }

    runCheck("Codec round-trip: NIGHT preset") {
        val original = ReaderAppearanceConfig(theme = ThemeSpec.NIGHT_PRESET)
        val decoded = AppearanceCodec.decode(AppearanceCodec.encode(original))
        check(original == decoded) {
            "Round-trip mismatch for NIGHT preset"
        }
    }.onFailure { failures.add(it) }

    runCheck("Codec produces valid JSON for default config") {
        val json = AppearanceCodec.encode(ReaderAppearanceConfig())
        check(json.startsWith("{") && json.endsWith("}")) {
            "Encoded JSON does not look valid: $json"
        }
    }.onFailure { failures.add(it) }

    // ─── Summary ───────────────────────────────────────────────────────────
    if (failures.isEmpty()) {
        println("OK")
        kotlin.system.exitProcess(0)
    } else {
        System.err.println("FAIL")
        failures.forEach { System.err.println("  • $it") }
        kotlin.system.exitProcess(1)
    }
}

/**
 * Run a single named check, returning its failure message or null on success.
 */
private fun runCheck(label: String, block: () -> Unit): Result<Unit> {
    return try {
        block()
        Result.success(Unit)
    } catch (e: Throwable) {
        Result.failure(e)
    }
}
