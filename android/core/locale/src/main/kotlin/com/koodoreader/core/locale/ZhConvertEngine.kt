package com.koodoreader.core.locale

import com.koodoreader.core.common.Localization

/** Writing system of a Chinese text (or [UNKNOWN] when undecidable). */
enum class ChineseScript {
    SIMPLIFIED,
    TRADITIONAL,
    UNKNOWN,
}

/**
 * 简繁转换 tri-state, wire-compatible with the desktop reader config
 * `convertChinese` (src/constants/dropdownList.tsx: `""` / "Simplified To
 * Traditional" / "Traditional To Simplified").
 *
 *  - [AUTO] — desktop's "Default" (`""`): the text is left alone **unless** the
 *    reader language states a Chinese preference and the text is decisively in
 *    the other script (see [ZhConvertEngine.convert]). For every non-Chinese
 *    reader language AUTO is a byte-for-byte no-op, exactly like the desktop.
 *  - [TRADITIONAL] — 繁 (`Simplified To Traditional`).
 *  - [SIMPLIFIED] — 简 (`Traditional To Simplified`).
 */
enum class ZhConvertMode(val wire: String) {
    AUTO(""),
    TRADITIONAL("Simplified To Traditional"),
    SIMPLIFIED("Traditional To Simplified"),
    ;

    companion object {
        /** Parses the desktop config value; unknown/blank → [AUTO] (desktop default). */
        fun fromWire(raw: String?): ZhConvertMode {
            val value = raw?.trim() ?: return AUTO
            return entries.firstOrNull { it.wire == value } ?: AUTO
        }
    }
}

/**
 * Script evidence of a text: how many characters can only be simplified
 * ([simplified]) versus only be traditional ([traditional]).
 */
data class ScriptSignal(val simplified: Int, val traditional: Int) {

    /** [ChineseScript] decision: needs [minSignal] hits and a [ratio]-fold lead. */
    fun script(minSignal: Int = MIN_SIGNAL, ratio: Double = DECISIVE_RATIO): ChineseScript {
        val strongest = maxOf(simplified, traditional)
        if (strongest < minSignal) return ChineseScript.UNKNOWN
        if (simplified >= traditional * ratio) return ChineseScript.SIMPLIFIED
        if (traditional >= simplified * ratio) return ChineseScript.TRADITIONAL
        return ChineseScript.UNKNOWN
    }

    companion object {
        /** Minimum number of script-exclusive characters before we trust a verdict. */
        const val MIN_SIGNAL = 4

        /** Dominance factor: 2× the opposite signal is required. */
        const val DECISIVE_RATIO = 2.0
    }
}

/**
 * OpenCC dictionaries used by one engine instance: the S→T phrase/character
 * stages, the Taiwan phrase stage and the T→S phrase/character stages.
 */
class ZhConvertDictionaries(
    val stPhrases: OpenCcDictionary,
    val stCharacters: OpenCcDictionary,
    val twPhrases: OpenCcDictionary,
    val tsPhrases: OpenCcDictionary,
    val tsCharacters: OpenCcDictionary,
) {
    /** `TWPhrases` reversed: 軟體 → 軟件 (OpenCC `TWPhrasesRev`). */
    val twPhrasesReverse: OpenCcDictionary by lazy {
        OpenCcDictionary.reverse("TWPhrasesRev", twPhrases)
    }

    companion object {
        /** Curated OpenCC subset shipped with the module (see [OpenCcSeed]). */
        val SEED: ZhConvertDictionaries by lazy { fromSeedTexts() }

        /** Builds the tables from the OpenCC-format seed strings. */
        fun fromSeedTexts(): ZhConvertDictionaries {
            val stCharacters = OpenCcDictionary.parse("STCharacters", OpenCcSeed.ST_CHARACTERS)
            val stPhrases = OpenCcDictionary.parse("STPhrases", OpenCcSeed.ST_PHRASES)
            val twPhrases = OpenCcDictionary.parse("TWPhrases", OpenCcSeed.TW_PHRASES)
            val tsExtra = OpenCcDictionary.parse("TSCharactersExtra", OpenCcSeed.TS_EXTRA_CHARACTERS)
            // T→S = reverse of the S→T tables, plus traditional-only chars that
            // have no S→T counterpart in the seed (臺/裏/隻/髮 …). Explicit
            // entries win over derived ones.
            val tsCharacters = OpenCcDictionary.merge(
                "TSCharacters",
                tsExtra,
                OpenCcDictionary.reverse("STCharactersRev", stCharacters),
            )
            val tsPhrases = OpenCcDictionary.reverse("STPhrasesRev", stPhrases)
            return ZhConvertDictionaries(stPhrases, stCharacters, twPhrases, tsPhrases, tsCharacters)
        }
    }
}

/**
 * 简繁转换 engine — native OpenCC port (see docs/p6-zh-locale-design.md §3).
 *
 * Chain parity with upstream OpenCC configs:
 *
 * | direction | stages |
 * |---|---|
 * | `s2t`  | STPhrases → STCharacters |
 * | `s2tw` | STPhrases → STCharacters → TWPhrases |
 * | `t2s`  | STPhrasesRev → STCharactersRev |
 * | `tw2s` | TWPhrasesRev → STPhrasesRev → STCharactersRev |
 *
 * The optional user dictionary is prepended as a *locked* stage, so whatever it
 * converts is never re-touched by the built-in tables (the desktop lets users
 * keep their own conversion dictionary; here it is persisted via
 * [ZhConvertSettingsStore]).
 *
 * Pure JVM, stateless (safe to share across reader pipelines).
 */
class ZhConvertEngine(
    private val dictionaries: ZhConvertDictionaries = ZhConvertDictionaries.SEED,
    private val userDictionary: OpenCcDictionary? = null,
) {
    private val simplifiedMarkers: Set<String> = dictionaries.stCharacters.keys()
    private val traditionalMarkers: Set<String> = LinkedHashSet<String>().apply {
        dictionaries.stCharacters.keys().forEach { key ->
            dictionaries.stCharacters.firstValue(key)?.let { add(it) }
        }
        addAll(dictionaries.tsCharacters.keys())
    }

    /** True when a user dictionary is installed (conversion dictionary persistence). */
    val hasUserDictionary: Boolean get() = userDictionary != null && userDictionary.size > 0

    /** 简 → 繁 (`s2t`, or `s2tw` when [taiwan]). */
    fun toTraditional(text: String, taiwan: Boolean = false): String =
        OpenCcDictionary.applyChain(text, s2tChain(taiwan))

    /** 繁 → 简 (`t2s`, or `tw2s` when [fromTaiwan]). */
    fun toSimplified(text: String, fromTaiwan: Boolean = false): String =
        OpenCcDictionary.applyChain(text, t2sChain(fromTaiwan))

    /**
     * Tri-state entry point. [language] is the reader language ("zh-CN",
     * "zh-TW", "en", …) and is only consulted by [ZhConvertMode.AUTO].
     */
    fun convert(text: String, mode: ZhConvertMode, language: String? = null): String {
        if (text.isEmpty()) return text
        return when (mode) {
            ZhConvertMode.TRADITIONAL -> toTraditional(text, taiwan = isTaiwan(language))
            ZhConvertMode.SIMPLIFIED -> toSimplified(text, fromTaiwan = isTaiwan(language))
            ZhConvertMode.AUTO -> {
                val target = scriptOf(language)
                val detected = detect(text)
                when {
                    target == ChineseScript.UNKNOWN -> text
                    detected == ChineseScript.UNKNOWN -> text
                    detected == target -> text
                    target == ChineseScript.TRADITIONAL -> toTraditional(text, taiwan = isTaiwan(language))
                    else -> toSimplified(text, fromTaiwan = isTaiwan(language))
                }
            }
        }
    }

    /** Convenience: [convert] using the desktop config value. */
    fun convertWire(text: String, wireValue: String?, language: String? = null): String =
        convert(text, ZhConvertMode.fromWire(wireValue), language)

    /** Script evidence counts for [text] (exposed for tests and diagnostics). */
    fun signal(text: String): ScriptSignal {
        var simplified = 0
        var traditional = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val ch = String(Character.toChars(cp))
            if (simplifiedMarkers.contains(ch)) {
                simplified++
            } else if (traditionalMarkers.contains(ch)) {
                traditional++
            }
            i += Character.charCount(cp)
        }
        return ScriptSignal(simplified, traditional)
    }

    /** Decisive script of [text] (see [ScriptSignal.script]). */
    fun detect(text: String): ChineseScript = signal(text).script()

    private fun s2tChain(taiwan: Boolean): List<OpenCcDictionary> {
        val chain = ArrayList<OpenCcDictionary>(4)
        userDictionary?.let { if (it.size > 0) chain.add(it) }
        chain.add(dictionaries.stPhrases)
        chain.add(dictionaries.stCharacters)
        if (taiwan) chain.add(dictionaries.twPhrases)
        return chain
    }

    private fun t2sChain(fromTaiwan: Boolean): List<OpenCcDictionary> {
        val chain = ArrayList<OpenCcDictionary>(4)
        userDictionary?.let { if (it.size > 0) chain.add(it) }
        if (fromTaiwan) chain.add(dictionaries.twPhrasesReverse)
        chain.add(dictionaries.tsPhrases)
        chain.add(dictionaries.tsCharacters)
        return chain
    }

    companion object {
        /** Engine with the seed tables; [userDictionary] is OpenCC-format text. */
        fun fromSeed(userDictionary: String? = null): ZhConvertEngine = ZhConvertEngine(
            ZhConvertDictionaries.SEED,
            parseUserDictionary(userDictionary),
        )

        /** Parses a persisted user conversion dictionary (blank/absent → null). */
        fun parseUserDictionary(text: String?): OpenCcDictionary? {
            if (text.isNullOrBlank()) return null
            val dict = OpenCcDictionary.parse("UserDictionary", text, locked = true)
            return if (dict.size == 0) null else dict
        }

        /** Reader language → preferred Chinese script ([ChineseScript.UNKNOWN] for non-Chinese). */
        fun scriptOf(language: String?): ChineseScript {
            val code = Localization.normalize(language ?: return ChineseScript.UNKNOWN).lowercase()
            if (!code.startsWith("zh")) return ChineseScript.UNKNOWN
            return when (code) {
                "zh-cn", "zh-sg", "zh-hans" -> ChineseScript.SIMPLIFIED
                else -> ChineseScript.TRADITIONAL // zh-TW / zh-HK / zh-MO / zh-Hant / zh
            }
        }

        /** True for Taiwan/Hong-Kong/Macau locales (TWPhrases stage applies). */
        fun isTaiwan(language: String?): Boolean {
            val code = Localization.normalize(language ?: return false).lowercase()
            return code == "zh-tw" || code == "zh-mo" || code == "zh-hk" || code == "zh-hant"
        }
    }
}
