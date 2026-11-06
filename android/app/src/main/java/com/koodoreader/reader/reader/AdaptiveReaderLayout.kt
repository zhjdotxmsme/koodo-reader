package com.koodoreader.reader.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.koodoreader.reader.shell.t
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.koodoreader.core.designsystem.MotionTokens
import com.koodoreader.reader.R
import com.koodoreader.reader.shell.ReaderWindowSize

/**
 * Adaptive reader scaffold: EXPANDED (>=600 dp) shows a dismissible sidebar
 * (TOC / annotations) next to the content; COMPACT keeps single-column
 * content and the caller presents panels as bottom sheets instead.
 *
 * The sidebar visibility is hoisted ([sidebarOpen]) so the caller can own it
 * with `rememberSaveable` and flip it from [TocSidebar]'s close button.
 * Collapsing the sidebar reflows the content column — that is expected.
 */
@Composable
fun AdaptiveReaderLayout(
    widthDp: Int,
    sidebar: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
    sidebarOpen: Boolean = true,
    content: @Composable () -> Unit,
) {
    if (ReaderWindowSize.fromWidth(widthDp) == ReaderWindowSize.EXPANDED && sidebar != null) {
        Row(modifier) {
            AnimatedVisibility(
                visible = sidebarOpen,
                enter = slideInHorizontally(tween(MotionTokens.NORMAL)) { -it },
                exit = slideOutHorizontally(tween(MotionTokens.NORMAL)) { -it },
                modifier = Modifier.weight(0.32f),
            ) {
                Box(Modifier.fillMaxHeight()) { sidebar() }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) { content() }
        }
    } else {
        Box(modifier) { content() }
    }
}


/** Permanent TOC sidebar for EXPANDED readers: title search, current-chapter
 *  highlight, tap-to-jump and an optional ≥48dp close affordance. */
@Composable
fun TocSidebar(
    viewModel: TocViewModel,
    currentChapterId: String,
    onJump: (String) -> Unit,
    onClose: (() -> Unit)? = null,
) {
    val query by viewModel.query.collectAsState()
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                singleLine = true,
                placeholder = { Text(t("Search")) },
                modifier = Modifier.weight(1f).padding(8.dp),
            )
            if (onClose != null) {
                // IconButton 的默认最小触达即 48dp。
                IconButton(onClick = onClose) {
                    Icon(
                        painterResource(R.drawable.ic_close),
                        contentDescription = t("Close"),
                    )
                }
            }
        }
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
