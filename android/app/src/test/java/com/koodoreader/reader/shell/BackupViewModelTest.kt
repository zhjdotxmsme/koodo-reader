package com.koodoreader.reader.shell

import com.koodoreader.engine.feature.JsonValue
import com.koodoreader.engine.feature.TextRuleJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackupViewModelTest {

    @Test
    fun `desktop rule warnings reach the finished import report`() {
        val current = ReaderFeatureState(
            textRulesJson = """[{"id":"old","pattern":"old","replacement":"value"}]""",
        )
        val config = desktopConfig(
            ruleList = """["1"]""",
            rules = """{"1":{"id":"1","replacement":"bar"}}""",
            readerConfig = """{"customBookCSS":"p{margin:0}","isCustomBookCSS":"yes"}""",
        )

        val restored = DesktopPrefsBridge.applyDesktopConfigWithWarnings(config, current)
        val message = BackupViewModel.formatImportReport(emptyReport(), restored.warnings)

        assertEquals(current.textRulesJson, restored.state.textRulesJson)
        assertEquals("p{margin:0}", restored.state.customBookCss)
        assertTrue(restored.warnings.any { "missing 'pattern'" in it })
        assertTrue("warnings:" in message)
        assertTrue("missing 'pattern'" in message)
    }

    @Test
    fun `invalid desktop CSS preserves settings and warns in the finished report`() {
        val current = ReaderFeatureState(
            customBookCss = "body{color:black}",
            customBookCssEnabled = true,
        )
        val config = desktopConfig(
            ruleList = "[]",
            rules = "{}",
            readerConfig = """{"customBookCSS":42,"isCustomBookCSS":"maybe"}""",
        )

        val restored = DesktopPrefsBridge.applyDesktopConfigWithWarnings(config, current)
        val message = BackupViewModel.formatImportReport(emptyReport(), restored.warnings)

        assertEquals(current.customBookCss, restored.state.customBookCss)
        assertEquals(current.customBookCssEnabled, restored.state.customBookCssEnabled)
        assertFalse(restored.warnings.isEmpty())
        assertTrue("readerConfig.customBookCSS" in message)
        assertTrue("readerConfig.isCustomBookCSS" in message)
    }

    private fun emptyReport() = ImportReport(
        tables = emptyMap(),
        coversImported = 0,
        booksImported = 0,
        failures = emptyList(),
    )

    private fun desktopConfig(
        ruleList: String,
        rules: String,
        readerConfig: String,
    ): String = TextRuleJson.render(
        JsonValue.Obj(linkedMapOf(
            "textRuleList" to JsonValue.Str(ruleList),
            "textRules" to JsonValue.Str(rules),
            "readerConfig" to JsonValue.Str(readerConfig),
        )),
    )
}
