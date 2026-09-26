package com.koodoreader.reader.shell

import android.graphics.BitmapFactory
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import com.koodoreader.core.data.entity.BookEntity
import com.koodoreader.core.ui.component.BookCardModel
import com.koodoreader.core.ui.component.KoodoBookCard
import com.koodoreader.core.ui.component.KoodoBookRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Library tiles — now a thin ADAPTER over `:core:ui`'s components.
 *
 * ### What stayed here and why (interface inversion)
 * This file used to BE the component, taking a [BookEntity] and decoding its own
 * cover. That coupled a card to `:core:data`, `Context`, bitmap IO and the app's
 * i18n, so it could not live in the shared design system. Now:
 *
 *  - the model is `:core:ui`'s [BookCardModel] (mapped by [toCardModel] below);
 *  - COVER RESOLUTION STAYS HERE — base64 row + on-disk cover file needs
 *    `Context` and `BitmapFactory`, which the design system must not touch. It
 *    hands the component a finished [Painter].
 *  - the long-press menu is passed as a slot, so its wording uses `t()` here
 *    rather than being hardcoded inside the shared component.
 *
 * No business logic lives in this file; the placeholder palette it used to
 * define moved to `ColorTokens.COVER_PLACEHOLDERS`.
 */
private fun BookEntity.toCardModel(isFavorite: Boolean) = BookCardModel(
    title = name.orEmpty(),
    author = author,
    formatLabel = format?.uppercase(),
    coverSeed = key,
    isFavorite = isFavorite,
)

/** Grid tile (view mode = grid). */
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
    KoodoBookCard(
        model = book.toCardModel(isFavorite),
        cover = rememberCoverPainter(book.cover, book.key, coverVersion),
        onClick = onClick,
        onLongClick = { menuOpen = true },
        // Was the hardcoded literal "Favorite", i.e. an untranslated string
        // exposed to TalkBack. Now routed through i18n; the key is added to
        // src/assets/locales/en.json with the rest of the W6a keys.
        favoriteContentDescription = t("Favorite"),
        menu = {
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = {
                        Text(if (isFavorite) t("Remove from favorite") else t("Add to favorite"))
                    },
                    onClick = { onToggleFavorite(); menuOpen = false },
                )
                DropdownMenuItem(
                    text = { Text(t("Move to trash")) },
                    onClick = { onMoveToTrash(); menuOpen = false },
                )
            }
        },
        modifier = modifier,
    )
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
    KoodoBookRow(
        model = book.toCardModel(isFavorite),
        cover = rememberCoverPainter(book.cover, book.key, coverVersion),
        onClick = onClick,
        favoriteContentDescription = t("Favorite"),
        modifier = modifier,
    )
}

/**
 * Resolve the cover for a book and hand it to the design system as a [Painter].
 *
 * Row base64 first (desktop-imported rows), then the `cover/` dir file written by
 * the native import ([CoverStore]), else null → the component draws its
 * deterministic placeholder.
 */
@Composable
private fun rememberCoverPainter(
    cover: String?,
    seed: String,
    coverVersion: Int,
): Painter? {
    val context = LocalContext.current
    val coverStore = remember(context) { CoverStore(context) }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, cover, seed, coverVersion) {
        value = decodeCover(cover, coverStore.fileFor(seed))
    }
    // BitmapPainter rather than an `asImagePainter()` extension: the extension
    // is not available in this Compose BOM's ui-graphics artifact.
    return bitmap?.let { BitmapPainter(it) }
}

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
