package com.koodoreader.feature.translate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * User-visible labels. Keys are the **desktop react-i18next keys** (identical
 * strings in `src/assets/locales/`), resolved through
 * `core:common`'s `Localization` — e.g.
 * `TranslationPopupLabels.from(localization::t)`. Missing keys fall back to the
 * key itself, which for these labels is already the English text.
 */
data class TranslationPopupLabels(
    val title: String = "Translate",
    val translating: String = "Translating…",
    val failed: String = "Translation failed",
    val copy: String = "Copy",
    val close: String = "Close",
    val retry: String = "Retry",
    val configureKey: String = "AI service",
) {
    companion object {
        fun from(translate: (String) -> String): TranslationPopupLabels = TranslationPopupLabels(
            title = translate("Translate"),
            translating = translate("Translating"),
            failed = translate("Translation failed"),
            copy = translate("Copy"),
            close = translate("Close"),
            retry = translate("Retry"),
            configureKey = translate("AI service"),
        )
    }
}

/**
 * Selection-translation popup (P6 acceptance #1): source switcher chips, the
 * translated text, the original selection and the copy/retry/close actions.
 *
 * Stateless by design — the reader owns a [TranslationPopupController] and just
 * re-renders this surface from [state]; every intent is a callback. On a wide
 * screen the caller wraps it in a `Popup` anchored to
 * [TranslationPopupState.anchor].
 */
@Composable
fun TranslationPopup(
    state: TranslationPopupState,
    sources: List<SourceChip>,
    onSourceSelected: (TranslationSourceId) -> Unit,
    onRetry: () -> Unit,
    onCopy: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    labels: TranslationPopupLabels = TranslationPopupLabels(),
) {
    if (!state.visible) {
        return
    }
    Surface(
        modifier = modifier.widthIn(min = 240.dp, max = 380.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Header(state = state, labels = labels)
            Spacer(Modifier.height(6.dp))
            SourceSwitcher(sources = sources, onSourceSelected = onSourceSelected, labels = labels)
            Spacer(Modifier.height(8.dp))
            Body(state = state, labels = labels)
            Spacer(Modifier.height(4.dp))
            Actions(state = state, onCopy = onCopy, onRetry = onRetry, onDismiss = onDismiss, labels = labels)
        }
    }
}

@Composable
private fun Header(state: TranslationPopupState, labels: TranslationPopupLabels) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = labels.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "${state.sourceLanguage} → ${state.targetLanguage}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SourceSwitcher(
    sources: List<SourceChip>,
    onSourceSelected: (TranslationSourceId) -> Unit,
    labels: TranslationPopupLabels,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        sources.forEach { chip ->
            TextButton(
                onClick = { onSourceSelected(chip.id) },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    text = if (chip.configured) chip.displayName else "${chip.displayName} · ${labels.configureKey}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (chip.active) FontWeight.Bold else FontWeight.Normal,
                    color = when {
                        chip.active -> MaterialTheme.colorScheme.primary
                        chip.configured -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.outline
                    },
                )
            }
        }
    }
}

@Composable
private fun Body(state: TranslationPopupState, labels: TranslationPopupLabels) {
    Column(modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
        if (state.hasResult) {
            Text(
                text = state.translatedText.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = state.selection,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = when (state.status) {
                    PopupStatus.TRANSLATING -> labels.translating
                    PopupStatus.FAILED, PopupStatus.NEEDS_CREDENTIALS ->
                        state.failureDetail ?: labels.failed
                    else -> state.selection
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.status == PopupStatus.FAILED || state.status == PopupStatus.NEEDS_CREDENTIALS) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

@Composable
private fun Actions(
    state: TranslationPopupState,
    onCopy: (String) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    labels: TranslationPopupLabels,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = { state.translatedText?.let(onCopy) },
            enabled = state.hasResult,
        ) { Text(labels.copy) }
        TextButton(
            onClick = onRetry,
            enabled = state.selection.isNotBlank() && state.status != PopupStatus.TRANSLATING,
        ) { Text(labels.retry) }
        TextButton(onClick = onDismiss) { Text(labels.close) }
    }
}
