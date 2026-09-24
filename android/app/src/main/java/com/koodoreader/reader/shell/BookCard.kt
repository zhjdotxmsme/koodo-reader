package com.koodoreader.reader.shell

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import com.koodoreader.core.data.entity.BookEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Deterministic placeholder palette for books without a decodable cover. */
private val CoverPalette = listOf(
    Color(0xFF3A6EA5), Color(0xFF6B8E23), Color(0xFF9C5B4F),
    Color(0xFF7A5C9E), Color(0xFF2E7D6B), Color(0xFF8A6D3B),
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookCard(
    book: BookEntity,
    onClick: () -> Unit,
    /** Bump (e.g. after an import) to re-resolve the on-disk cover file. */
    coverVersion: Int = 0,
    isFavorite: Boolean = false,
    onToggleFavorite: () -> Unit = {},
    onMoveToTrash: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Column(
            modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = { menuOpen = true }),
        ) {
            Box {
                BookCover(
                    cover = book.cover,
                    title = book.name ?: "?",
                    seed = book.key,
                    coverVersion = coverVersion,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.72f)
                        .clip(RoundedCornerShape(8.dp)),
                )
                if (isFavorite) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = "Favorite",
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp),
                    )
                }
            }
            Text(
                text = book.name.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                text = listOfNotNull(book.author, book.format?.uppercase())
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(if (isFavorite) t("Remove from favorite") else t("Add to favorite")) },
                onClick = { onToggleFavorite(); menuOpen = false },
            )
            DropdownMenuItem(
                text = { Text(t("Move to trash")) },
                onClick = { onMoveToTrash(); menuOpen = false },
            )
        }
    }
}

/** Compact list-view row (view mode = list). */
@Composable
fun BookListRow(
    book: BookEntity,
    isFavorite: Boolean,
    coverVersion: Int = 0,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BookCover(
            cover = book.cover,
            title = book.name ?: "?",
            seed = book.key,
            coverVersion = coverVersion,
            modifier = Modifier
                .width(48.dp)
                .aspectRatio(0.72f)
                .clip(RoundedCornerShape(4.dp)),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = book.name.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(book.author, book.format?.uppercase())
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isFavorite) {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = "Favorite",
                tint = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun BookCover(
    cover: String?,
    title: String,
    seed: String,
    modifier: Modifier = Modifier,
    coverVersion: Int = 0,
) {
    val context = LocalContext.current
    val coverStore = remember(context) { CoverStore(context) }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, cover, seed, coverVersion) {
        value = decodeCover(cover, coverStore.fileFor(seed))
    }
    val image = bitmap
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = title,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        val color = CoverPalette[kotlin.math.abs(seed.hashCode()) % CoverPalette.size]
        Box(modifier = modifier.background(color), contentAlignment = Alignment.Center) {
            Text(
                text = title.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
            )
        }
    }
}

/**
 * Row base64 cover first (desktop-imported rows), then the `cover/` dir
 * file written by the native import ([CoverStore]), else null → placeholder.
 */
private suspend fun decodeCover(
    cover: String?,
    coverFile: java.io.File?,
): ImageBitmap? = withContext(Dispatchers.IO) {
    if (!cover.isNullOrBlank()) {
        val decoded = runCatching {
            val base64 = cover.substringAfter("base64,", "")
            if (base64.isBlank()) return@runCatching null
            val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
        if (decoded != null) return@withContext decoded
    }
    if (coverFile != null && coverFile.isFile && coverFile.length() > 0) {
        runCatching { BitmapFactory.decodeFile(coverFile.path)?.asImageBitmap() }.getOrNull()
    } else {
        null
    }
}
