package com.koodoreader.reader.epubhost

import com.koodoreader.core.designsystem.ThemeKind
import com.koodoreader.reader.shell.ReaderFeatureCodec
import com.koodoreader.reader.shell.ReaderFeatureState
import com.koodoreader.reader.shell.ReaderPerformancePolicy
import com.koodoreader.reader.shell.ReaderSpreadPolicy
import com.koodoreader.reader.shell.builtInPreset

object ReadestViewSettings {

    data class Snapshot(
        val bg: String,
        val fg: String,
        val defaultFontSize: Double,
        val lineHeight: Double,
        val marginPx: Int,
        val maxColumnCount: Int,
        val scrolled: Boolean,
        val overrideColor: Boolean,
        val isDarkMode: Boolean,
        val colorScheme: String,
        val defaultFont: String,
        val userStylesheet: String = "",
        val textRulesJson: String = "",
        val maxInlineSizePx: Int = 720,
    )

    fun from(kind: ThemeKind, features: ReaderFeatureState): Snapshot {
        val preset = kind.builtInPreset()
        val dark = kind == ThemeKind.NIGHT
        val fontSizePx = ReaderFeatureCodec.fontSizePx(features).toDouble()
        val font = features.fontKey.lowercase()
        val defaultFont = when (font) {
            "sans-serif", "serif", "monospace" -> font
            else -> "serif"
        }
        return Snapshot(
            bg = preset.backgroundColor,
            fg = preset.foregroundColor,
            defaultFontSize = fontSizePx / 1.25,
            lineHeight = ReaderFeatureCodec.clampedLineHeight(features).toDouble(),
            marginPx = ReaderFeatureCodec.clampedMargin(features).toInt().coerceAtLeast(0),
            maxColumnCount = ReaderSpreadPolicy.foliateMaxColumnCount(features),
            maxInlineSizePx = ReaderPerformancePolicy.foliateMaxInlineSizePx(features),
            scrolled = ReaderSpreadPolicy.foliateScrolled(features),
            overrideColor = true,
            isDarkMode = dark,
            colorScheme = if (dark) "dark" else "light",
            defaultFont = defaultFont,
            userStylesheet = ReaderFeatureCodec.userStylesheet(features),
            textRulesJson = features.textRulesJson,
        )
    }
}
