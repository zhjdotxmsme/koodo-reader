package com.koodoreader.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.koodoreader.core.designsystem.SpaceTokens
import com.koodoreader.core.ui.theme.CoverBadgeTextStyle
import com.koodoreader.core.ui.theme.KoodoChipShape

/**
 * The small badge drawn on top of a book cover.
 *
 * Two kinds exist, distinguished by their position on the cover:
 *  - the **format label** (top-left): "EPUB", "PDF", "MOBI"…
 *  - the **progress badge** (bottom-right): "45%"
 *
 * ## Why they are on the cover and not under it
 *
 * The card already prints the title and the author below the artwork. Putting
 * the format there would make the third line of text compete with the title,
 * and putting the progress there as well would make four. On the artwork they
 * read as *labels on an object* — the way a library sticker sits on a spine —
 * and they never take a line away from the title.
 *
 * ## Legibility over arbitrary artwork
 *
 * The cover is arbitrary artwork, so the badge cannot rely on contrast with
 * what is behind it. It therefore carries its own scrim: a dark, translucent
 * plate behind the glyphs. That is why the badge works on a white cover and a
 * black cover alike.
 */
@Composable
fun CoverBadge(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Black.copy(alpha = 0.55f),
    contentColor: Color = Color.White,
) {
    Text(
        text = text,
        style = CoverBadgeTextStyle,
        color = contentColor,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(KoodoChipShape)
            .background(containerColor)
            .padding(
                horizontal = SpaceTokens.BADGE_HORIZONTAL_PADDING.dp,
                vertical = SpaceTokens.BADGE_VERTICAL_PADDING.dp,
            ),
    )
}

/**
 * Formats a 0..1 progress fraction as the percentage shown on the cover.
 *
 * Values below 1% round to "1%" rather than "0%" — a badge reading "0%" on a
 * book the user has started looks like a bug.
 */
fun formatProgressBadge(fraction: Float): String {
    val percent = (fraction.coerceIn(0f, 1f) * 100f).toInt()
    return if (percent < 1) "1%" else "$percent%"
}
