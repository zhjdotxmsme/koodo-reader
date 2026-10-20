package com.koodoreader.reader.shell

import com.koodoreader.engine.feature.TextRule
import com.koodoreader.engine.feature.TextRuleEngine
import com.koodoreader.engine.feature.TextRuleJson
import com.koodoreader.reader.epubhost.PagedDocumentSession
import com.koodoreader.reader.zhconvert.ZhConvertBridge

/**
 * Single [PagedDocumentSession.textTransform] owner.
 *
 * Zh-convert used to install the hook by itself; text-replacement rules have
 * to run on the same string. This object composes both so neither stomps the
 * other when settings change.
 */
object ReaderTextPipeline {

    @Volatile
    var rules: List<TextRule> = emptyList()
        private set

    private val engine = TextRuleEngine()

    fun setRulesJson(json: String) {
        rules = if (json.isBlank()) emptyList() else TextRuleJson.importRules(json).rules
        rewire()
    }

    fun rewire() {
        val before = PagedDocumentSession.textTransform
        ZhConvertBridge.applyTransform()
        // Zh-convert no-ops until KoodoReaderApp installs the repo; still
        // hook replacement rules so settings take effect on the next paginate.
        if (PagedDocumentSession.textTransform === before) {
            PagedDocumentSession.textTransform = { afterConvert(it) }
        }
    }

    /** Apply replacement rules after [converted] (already zh-converted). */
    fun afterConvert(converted: String): String {
        if (rules.isEmpty()) return converted
        return engine.applyToText(converted, rules)
    }
}

/** Hook used by [ZhConvertBridge] so convert + rules share one transform. */
fun installReaderTextTransform(convert: (String) -> String) {
    PagedDocumentSession.textTransform = { text ->
        ReaderTextPipeline.afterConvert(convert(text))
    }
}
