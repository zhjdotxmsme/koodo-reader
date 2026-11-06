package com.koodoreader.reader.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import com.koodoreader.reader.shell.t
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.koodoreader.reader.shell.ReaderWindowSize

/**
 * Adaptive reader scaffold: EXPANDED (>=600 dp) shows a permanent sidebar
 * (TOC / annotations) next to the content; COMPACT keeps single-column
 * content and the caller presents panels as bottom sheets instead.
 */
@Composable
fun AdaptiveReaderLayout(
    widthDp: Int,
    sidebar: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (ReaderWindowSize.fromWidth(widthDp) == ReaderWindowSize.EXPANDED && sidebar != null) {
        Row(modifier) {
            Box(Modifier.weight(0.32f).fillMaxHeight()) { sidebar() }
            Box(Modifier.weight(0.68f).fillMaxHeight()) { content() }
        }
    } else {
        Box(modifier) { content() }
    }
}


/** Permanent TOC sidebar for EXPANDED readers: title search, current-chapter
 *  highlight and tap-to-jump. */
@Composable
fun TocSidebar(
    viewModel: TocViewModel,
    currentChapterId: String,
    onJump: (String) -> Unit,
) {
    val query by viewModel.query.collectAsState()
    Column {
        OutlinedTextField(
            value = query,
            onValueChange = viewModel::setQuery,
            singleLine = true,
            placeholder = { Text(t("Search")) },
            modifier = Modifier.fillMaxWidth().padding(8.dp),
        )
        LazyColumn {
            items(viewModel.filtered(), key = { it.id }) { chapter ->
                Text(
                    text = chapter.title ?: chapter.id,
                    color = if (chapter.id == currentChapterId) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onJump(chapter.id) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
    }
}
