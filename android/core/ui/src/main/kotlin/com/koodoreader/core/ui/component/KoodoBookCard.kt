package com.koodoreader.core.ui.component

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.koodoreader.core.designsystem.ColorTokens
import com.koodoreader.core.ui.theme.KoodoCoverShape

/**
 * UI model for a book tile.
 *
 * Deliberately NOT `BookEntity`: `:core:ui` must not depend on `:core:data`, so
 * the design system stays reusable by `:app` and `:feature:*` alike and Compose
 * Preview needs no database. The caller maps its own row into this.
 *
 * ### Interface inversion
 * The library's previous `BookCard` took a `BookEntity` directly and decoded its
 * own cover, which coupled a UI component to the data layer, to `Context`, to
 * bitmap IO and to the app's i18n. This model plus the `cover: Painter?`
 * parameter is what breaks that: **cover resolution stays with the caller**.
 */
@Immutable
data class BookCardModel(
    val title: String,
    val author: String? = null,
    /** Already localised/uppercased by the caller (e.g. "EPUB"). */
    val formatLabel: String? = null,
    /** Stable identity used to pick a placeholder colour. */
    val coverSeed: String,
    val isFavorite: Boolean = false,
) {
    /** `"Author · FORMAT"`, skipping blanks — the subtitle rule the library used. */
    val subtitle: String
        get() = listOfNotNull(author, formatLabel)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
}

/**
 * Grid tile for the library.
 *
 * @param cover decoded cover, or null to draw the deterministic placeholder.
 * @param favoriteContentDescription screen-reader label for the star. Null hides
 *   the star from accessibility — pass a localised string, never a literal.
 * @param menu long-press sheet. Supplied by the caller so its wording goes
 *   through the app's i18n instead of being hardcoded in the design system.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KoodoBookCard(
    model: BookCardModel,
    cover: Painter?,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    favoriteContentDescription: String? = null,
    menu: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        Column(
            modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        ) {
            Box {
                KoodoBookCover(
                    model = model,
                    cover = cover,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(COVER_ASPECT)
                        .clip(KoodoCoverShape),
                )
                if (model.isFavorite) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = favoriteContentDescription,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp),
                    )
                }
            }
            Text(
                text = model.title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                text = model.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        menu()
    }
}

/**
 * Compact list-view row (view mode = list).
 *
 * `favoriteContentDescription` is nullable for the same reason as above; this row
 * shows the star without a tap target, so callers that cannot supply a
 * localised string should pass null rather than an English literal.
 */
@Composable
fun KoodoBookRow(
    model: BookCardModel,
    cover: Painter?,
    onClick: () -> Unit,
    favoriteContentDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        KoodoBookCover(
            model = model,
            cover = cover,
            modifier = Modifier
                .width(48.dp)
                .aspectRatio(COVER_ASPECT)
                .clip(KoodoCoverShape),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = model.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (model.isFavorite) {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = favoriteContentDescription,
                tint = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

/** Cover art, or the deterministic placeholder when nothing decoded. */
@Composable
private fun KoodoBookCover(
    model: BookCardModel,
    cover: Painter?,
    modifier: Modifier = Modifier,
) {
    if (cover != null) {
        Image(
            painter = cover,
            contentDescription = model.title,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = modifier.background(
                Color(ColorTokens.coverPlaceholderFor(model.coverSeed)),
            ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = model.title.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
            )
        }
    }
}

/**
 * Standard book-cover aspect ratio (width / height). ~1:1.39, matching the
 * 0.72 the library grid used, kept as one constant so the grid and the list row
 * cannot drift apart.
 */
private const val COVER_ASPECT = 0.72f
