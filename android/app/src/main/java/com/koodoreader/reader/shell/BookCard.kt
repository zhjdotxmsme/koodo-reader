package com.koodoreader.reader.shell

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
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
import com.koodoreader.core.data.entity.BookEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Deterministic placeholder palette for books without a decodable cover. */
private val CoverPalette = listOf(
    Color(0xFF3A6EA5), Color(0xFF6B8E23), Color(0xFF9C5B4F),
    Color(0xFF7A5C9E), Color(0xFF2E7D6B), Color(0xFF8A6D3B),
)

@Composable
fun BookCard(book: BookEntity, onClick: () -> Unit) {
    Column(modifier = Modifier.clickable(onClick = onClick)) {
        BookCover(
            cover = book.cover,
            title = book.name ?: "?",
            seed = book.key,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .clip(RoundedCornerShape(8.dp)),
        )
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
}

@Composable
private fun BookCover(cover: String?, title: String, seed: String, modifier: Modifier = Modifier) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, cover) {
        value = decodeCover(cover)
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

/** Desktop covers are stored as `data:image/...;base64,...` strings. */
private suspend fun decodeCover(cover: String?): ImageBitmap? = withContext(Dispatchers.Default) {
    if (cover.isNullOrBlank()) return@withContext null
    runCatching {
        val base64 = cover.substringAfter("base64,", "")
        if (base64.isBlank()) return@runCatching null
        val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull()
}
