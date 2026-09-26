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
import com.koodoreader.reader.BuildConfig

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
 * Settings tab — the grouped shell that absorbs what used to be buried in the
 * library's overflow menu (design doc §5).
 *
 * W4 scope: the groups and the wiring to the screens that ALREADY exist
 * (dictionary management, backup & restore, trash). No new setting is invented
 * here — the reader/appearance/voice rows are deliberately absent rather than
 * rendered as dead entries, because a settings row that does nothing is a lie.
 * Those land with their owning feature work.
 */
@Composable
fun SettingsScreen(
    onOpenBackup: () -> Unit = {},
    onOpenTrash: () -> Unit = {},
    onOpenDictionary: () -> Unit = {},
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

        SectionHeader(t("Content Sources"))
        SettingRow(
            title = t("Dictionary"),
            summary = t("Import and manage offline dictionaries"),
            onClick = onOpenDictionary,
        )

        // Language moved here from the library's overflow menu. It is added in
        // the same change that removes it from there, so the feature is never
        // unreachable — "no functional loss" is the acceptance bar for W5a.
        SectionHeader(t("Language"))
        val language by i18n.language.collectAsState()
        SettingRow(
            title = t("Language"),
            summary = languageLabel(language),
            onClick = {
                val idx = I18nState.CHOICES.indexOf(language)
                val next = I18nState.CHOICES[(idx + 1) % I18nState.CHOICES.size]
                i18n.setLanguage(next)
            },
        )

        HorizontalDivider()

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

        HorizontalDivider()

        SectionHeader(t("About"))
        SettingRow(
            title = "Koodo Reader",
            // Reuses the existing desktop key ("Version: ") rather than adding a
            // near-duplicate one.
            summary = t("Version") + BuildConfig.VERSION_NAME,
        )
    }
}
