package com.koodoreader.core.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.koodoreader.core.designsystem.ColorTokens
import com.koodoreader.core.designsystem.MotionTokens
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
    menu: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {},
    /** True while the caller is still resolving the cover → shimmer placeholder. */
    coverLoading: Boolean = false,
    /** Multi-select mode is on; exposes checkbox semantics for TalkBack. */
    selectionActive: Boolean = false,
    /** Selection state of this tile, read by screen readers in multi-select. */
    selected: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val clickableModifier = if (selectionActive) {
        Modifier.semantics {
            role = Role.Checkbox
            this.selected = selected
        }
    } else {
        Modifier
    }
    Box(modifier = modifier) {
        Column(
            modifier = clickableModifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        ) {
            Box {
                KoodoBookCover(
                    model = model,
                    cover = cover,
                    coverLoading = coverLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(COVER_ASPECT)
                        .clip(KoodoCoverShape),
                )
                if (model.isFavorite) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = favoriteContentDescription,
                        tint = MaterialTheme.colorScheme.secondary,
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
        menu(this)
    }
}

/**
 * Compact list-view row (view mode = list).
 *
 * Long-press + menu slot mirror [KoodoBookCard] so grid and list expose the
 * same per-book actions — the menu wording comes from the caller's i18n.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KoodoBookRow(
    model: BookCardModel,
    cover: Painter?,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    favoriteContentDescription: String? = null,
    menu: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {},
    /** True while the caller is still resolving the cover → shimmer placeholder. */
    coverLoading: Boolean = false,
    /** Multi-select mode is on; exposes checkbox semantics for TalkBack. */
    selectionActive: Boolean = false,
    /** Selection state of this row, read by screen readers in multi-select. */
    selected: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val clickableModifier = if (selectionActive) {
        Modifier.semantics {
            role = Role.Checkbox
            this.selected = selected
        }
    } else {
        Modifier
    }
    Box(modifier = modifier) {
        Row(
            modifier = clickableModifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            KoodoBookCover(
                model = model,
                cover = cover,
                coverLoading = coverLoading,
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
                    tint = MaterialTheme.colorScheme.secondary,
                )
            }
        }
        menu(this)
    }
}

/** Cover phases: shimmer while decoding, art when decoded, else placeholder. */
private enum class CoverPhase { LOADING, READY, FAILED }

/** Cover art, shimmer while loading, or the deterministic placeholder when
 *  nothing decoded (resolution failed / no cover at all). */
@Composable
private fun KoodoBookCover(
    model: BookCardModel,
    cover: Painter?,
    coverLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    val phase = when {
        cover != null -> CoverPhase.READY
        coverLoading -> CoverPhase.LOADING
        else -> CoverPhase.FAILED
    }
    Crossfade(
        targetState = phase,
        animationSpec = tween(MotionTokens.NORMAL),
        label = "bookCover",
    ) { current ->
        when (current) {
            CoverPhase.READY -> Image(
                painter = cover!!,
                contentDescription = model.title,
                modifier = modifier,
                contentScale = ContentScale.Crop,
            )
            CoverPhase.LOADING -> Box(modifier = modifier.shimmer())
            CoverPhase.FAILED -> Box(
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
}

/**
 * Standard book-cover aspect ratio (width / height). ~1:1.39, matching the
 * 0.72 the library grid used, kept as one constant so the grid and the list row
 * cannot drift apart.
 */
private const val COVER_ASPECT = 0.72f
