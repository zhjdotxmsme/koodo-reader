package com.koodoreader.reader.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.koodoreader.core.designsystem.SpaceTokens

/**
 * One tappable row inside a settings group.
 *
 * Local to W4 — the shared `KoodoSettingRow` primitive belongs to `:core:ui` and
 * lands with the component batch. Keeping it here for now avoids growing
 * `:core:ui` with a single-use widget.
 */
@Composable
private fun SettingRow(
    title: String,
    summary: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(
                horizontal = SpaceTokens.SCREEN_HORIZONTAL.dp,
                vertical = SpaceTokens.md.dp,
            ),
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        if (summary != null) {
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(
            start = SpaceTokens.SCREEN_HORIZONTAL.dp,
            end = SpaceTokens.SCREEN_HORIZONTAL.dp,
            top = SpaceTokens.lg.dp,
            bottom = SpaceTokens.xs.dp,
        ),
    )
}

/**
 * Settings tab — the grouped shell (settings page 2026-09-29 spec §2), the
 * readest-style section list: every section is a row that leads to a REAL
 * feature row that does nothing ("a settings row that does nothing is a lie" —
 * that is why each section below has an owning screen or a working control).
 *
 * Sections: 通用 / 外观 / 阅读 / 内容源 / 翻译与 AI / 语音朗读 / 数据 / 关于.
 */
@Composable
fun SettingsScreen(
    onOpenBackup: () -> Unit = {},
    onOpenTrash: () -> Unit = {},
    onOpenDictionary: () -> Unit = {},
    onOpenAppearance: () -> Unit = {},
    onOpenReading: () -> Unit = {},
    onOpenTranslate: () -> Unit = {},
    onOpenTts: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val i18n = LocalI18n.current
    fun t(key: String) = i18n.localization.t(key)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = t("Settings"),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(
                start = SpaceTokens.SCREEN_HORIZONTAL.dp,
                end = SpaceTokens.SCREEN_HORIZONTAL.dp,
                top = SpaceTokens.lg.dp,
            ),
        )

        // ── 通用 ─────────────────────────────────────────────────────────────
        SectionHeader(t("General"))
        // Language lives here (moved from the library overflow in W5a).
        val language by i18n.language.collectAsState()
        SettingRow(
            title = t("Language"),
            summary = languageLabel(language),
            onClick = {
                val idx = I18nState.CHOICES.indexOf(language)
                val next = I18nState.CHOICES[(idx + 1) % I18nState.CHOICES.size]
                i18n.setLanguage(next)
                // Keep 简繁 AUTO mode in step with the reader language.
                com.koodoreader.reader.zhconvert.ZhConvertBridge.setReaderLanguage(next)
            },
        )

        // ── 外观 ─────────────────────────────────────────────────────────────
        SectionHeader(t("Appearance"))
        SettingRow(
            title = t("App theme and reader page theme"),
            summary = t("App theme + reader page colours"),
            onClick = onOpenAppearance,
        )

        // ── 阅读 ─────────────────────────────────────────────────────────────
        SectionHeader(t("Reading"))
        SettingRow(
            title = t("Font size"),
            summary = t("Reader default font size"),
            onClick = onOpenReading,
        )

        // ── 内容源 ──────────────────────────────────────────────────────────
        SectionHeader(t("Content Sources"))
        SettingRow(
            title = t("Dictionary"),
            summary = t("Import and manage offline dictionaries"),
            onClick = onOpenDictionary,
        )

        // ── 翻译与 AI ──────────────────────────────────────────────────────
        SectionHeader(t("Translation"))
        SettingRow(
            title = t("API key"),
            summary = t("Selection translation (Google / Microsoft / DeepL)"),
            onClick = onOpenTranslate,
        )

        // ── 语音朗读 ───────────────────────────────────────────────────────
        SectionHeader(t("Text to speech"))
        SettingRow(
            title = t("Speed"),
            summary = t("Rate, pitch, volume"),
            onClick = onOpenTts,
        )

        // ── 数据 ───────────────────────────────────────────────────────────
        SectionHeader(t("Data"))
        SettingRow(
            title = t("Backup / restore"),
            summary = t("Export or import a full library backup"),
            onClick = onOpenBackup,
        )
        SettingRow(
            title = t("Trash"),
            summary = t("Books removed from the library"),
            onClick = onOpenTrash,
        )

        // ── 关于 ───────────────────────────────────────────────────────────
        SectionHeader(t("About"))
        SettingRow(
            title = "Readme Reader",
            // Reuses the existing desktop keys rather than adding near-duplicates.
            summary = t("Version") + " " + t("License"),
            onClick = onOpenAbout,
        )

        HorizontalDivider()
    }
}
