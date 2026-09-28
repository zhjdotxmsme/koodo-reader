package com.koodoreader.reader.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.koodoreader.core.designsystem.SpaceTokens
import com.koodoreader.engine.annotate.AnnotationKind

/**
 * Notes tab — cross-book highlights, notes and bookmarks (design doc §5).
 *
 * Aggregation comes from [NotesViewModel] (a `combine` over the existing
 * `observeAll()` DAOs — no schema change) and the list rules come from
 * [NotesAggregation], which is pure and unit-tested.
 *
 * Tapping a row opens the book at the annotation's CFI position (when one is
 * recorded) or at last-read position otherwise. The `onJump` callback is
 * supplied by the [ShellNavHost] route layer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(
    viewModel: NotesViewModel = viewModel(),
    /** Called when the user taps an annotation row: `(bookKey, cfi-or-null)`. */
    onJump: (bookKey: String, cfi: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var filterIndex by remember { mutableIntStateOf(0) }
    val filters = NotesFilter.entries
    val i18n = LocalI18n.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    val selected = filters[filterIndex]
    val sections = remember(state.items, state.titles, selected) {
        NotesAggregation.group(
            NotesAggregation.filter(state.items, selected),
            state.titles,
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = SpaceTokens.SCREEN_HORIZONTAL.dp,
                    vertical = SpaceTokens.md.dp,
                ),
        ) {
            filters.forEachIndexed { index, f ->
                SegmentedButton(
                    selected = index == filterIndex,
                    onClick = { filterIndex = index },
                    shape = SegmentedButtonDefaults.itemShape(index, filters.size),
                    label = { Text(i18n.localization.t(f.labelKey)) },
                )
            }
        }

        if (sections.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(SpaceTokens.sm.dp),
                ) {
                    Text(
                        text = i18n.localization.t("No notes yet"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = SpaceTokens.xl.dp),
                    )
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                sections.forEach { section ->
                    item(key = "header:${section.bookKey}") {
                        Text(
                            text = section.bookTitle,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(
                                start = SpaceTokens.SCREEN_HORIZONTAL.dp,
                                end = SpaceTokens.SCREEN_HORIZONTAL.dp,
                                top = SpaceTokens.lg.dp,
                                bottom = SpaceTokens.xs.dp,
                            ),
                        )
                    }
                    items(section.items, key = { it.key }) { item ->
                        NoteRow(item = item, onClick = { onJump(section.bookKey, item.cfi) })
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteRow(item: NoteListItem, onClick: () -> Unit) {
    val i18n = LocalI18n.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                horizontal = SpaceTokens.SCREEN_HORIZONTAL.dp,
                vertical = SpaceTokens.sm.dp,
            ),
    ) {
        Text(
            text = item.displayText,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = buildString {
                append(kindLabel(item.kind, i18n))
                if (item.chapter.isNotBlank()) append(" · ").append(item.chapter)
                // The note body is already the primary line when there is no
                // highlight, so only show it here as a secondary marker.
                if (item.hasNote && item.selectedText.isNotBlank()) {
                    append(" · ").append(item.noteText)
                }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
        HorizontalDivider(modifier = Modifier.padding(top = SpaceTokens.sm.dp))
    }
}

private fun kindLabel(kind: AnnotationKind, i18n: I18nState): String = when (kind) {
    AnnotationKind.HIGHLIGHT -> i18n.localization.t("Highlights")
    AnnotationKind.NOTE -> i18n.localization.t("Notes")
    AnnotationKind.BOOKMARK -> i18n.localization.t("Bookmarks")
}
