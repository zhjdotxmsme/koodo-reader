package com.koodoreader.reader.shell

import com.koodoreader.engine.feature.TextRuleEngine
import com.koodoreader.engine.feature.TextRuleJson

/**
 * Applies desktop-compatible text rules to plain strings (TTS, translate, copy).
 */
object ReaderPurifiedText {

    private val engine = TextRuleEngine()

    fun plain(text: String, textRulesJson: String): String {
        if (text.isEmpty() || textRulesJson.isBlank()) return text
        val rules = TextRuleJson.importRules(textRulesJson).rules
        if (rules.isEmpty()) return text
        return engine.applyToText(text, rules)
    }
}
