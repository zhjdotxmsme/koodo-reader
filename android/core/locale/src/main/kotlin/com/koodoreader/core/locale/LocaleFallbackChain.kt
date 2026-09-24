package com.koodoreader.core.locale

import com.koodoreader.core.common.Localization

/** Which link of the fallback chain produced a translation. */
enum class FallbackStep {
    /** The selected locale pack (bundled or downloaded) had the key. */
    SELECTED,

    /** The selected pack missed — English (ADR-004 fallback language) had it. */
    FALLBACK_LANGUAGE,

    /** Nobody had it — the key text itself is returned, as on the desktop. */
    KEY,
}

/** One resolved translation plus where it came from. */
data class FallbackHit(
    val step: FallbackStep,
    val language: String?,
    val value: String,
) {
    val isFallback: Boolean get() = step != FallbackStep.SELECTED
}

/**
 * The ADR-004 lookup chain, made explicit and observable:
 *
 * ```
 * selected locale  →  en  →  key itself
 * ```
 *
 * The catalogs are read through a provider on every lookup, so packs installed
 * later by [LocaleRuntimeLoader] participate **without rebuilding the chain**
 * and without touching `core:common`'s [Localization] (whose `t()` semantics
 * this class mirrors 1:1 — asserted by `LocaleFallbackChainTest` against the
 * real desktop `en.json` / `zh-CN.json`).
 *
 * Not thread-safe by design (a reader owns one instance); [language] is mutable
 * so a live language switch re-targets the chain instantly.
 */
class LocaleFallbackChain private constructor(
    private val provider: () -> Map<String, Map<String, String>>,
    language: String,
) {
    /** Catalogs keyed by normalized locale code. */
    constructor(
        catalogs: Map<String, Map<String, String>>,
        language: String,
    ) : this({ catalogs }, language)

    /** Live view over the runtime loader's registry (packs may appear later). */
    constructor(
        registry: LocaleCatalogRegistry,
        language: String,
    ) : this({ registry.snapshot() }, language)

    var language: String = Localization.normalize(language)
        set(value) {
            field = Localization.normalize(value)
        }

    /**
     * Full lookup: selected → en → key. Never throws, never returns blank for a
     * non-blank key (a present-but-empty translation is returned as-is, exactly
     * like the desktop, which renders the empty string).
     */
    fun lookup(key: FallbackKey): FallbackHit {
        val catalogs = provider()
        catalogs[language]?.get(key.raw)?.let {
            return FallbackHit(FallbackStep.SELECTED, language, it)
        }
        if (language != FALLBACK_LANGUAGE) {
            catalogs[FALLBACK_LANGUAGE]?.get(key.raw)?.let {
                return FallbackHit(FallbackStep.FALLBACK_LANGUAGE, FALLBACK_LANGUAGE, it)
            }
        }
        return FallbackHit(FallbackStep.KEY, null, key.raw)
    }

    /** Translated value of [key]. */
    fun resolve(key: FallbackKey): String = lookup(key).value

    /** Translated value of a raw desktop key. */
    fun resolve(raw: String): String = resolve(FallbackKey(raw))

    /** True when the selected locale has [key] (no fallback involved). */
    fun has(key: FallbackKey): Boolean = provider()[language]?.containsKey(key.raw) == true

    /** Resolves [keys] preserving order. */
    fun resolveAll(keys: List<FallbackKey>): Map<String, String> {
        val out = LinkedHashMap<String, String>(keys.size)
        for (key in keys) out[key.raw] = resolve(key)
        return out
    }

    /**
     * Ordered diagnostic view of the chain for [selected]:
     * `[selected, "en", "<key>"]` — the third element is the synthetic key step.
     */
    fun chainFor(selected: String = language): List<String> =
        listOf(Localization.normalize(selected), FALLBACK_LANGUAGE, KEY_STEP)

    /** Locale codes currently available to the chain. */
    fun languages(): List<String> = provider().keys.sorted()

    /**
     * The P1 implementation for this exact state — lets the shell keep using
     * `Localization.t()` while the chain stays the single source of truth for
     * the order (parity is unit-tested).
     */
    fun toLocalization(): Localization = Localization(provider(), language)

    companion object {
        /** Fallback language of the chain (desktop: English). */
        const val FALLBACK_LANGUAGE: String = Localization.FALLBACK_LANGUAGE

        /** Synthetic third step used by [chainFor] diagnostics. */
        const val KEY_STEP = "<key>"
    }
}
