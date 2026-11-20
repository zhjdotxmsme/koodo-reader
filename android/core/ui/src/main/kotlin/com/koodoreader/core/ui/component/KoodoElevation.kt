package com.koodoreader.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.koodoreader.core.designsystem.ElevationTokens

/**
 * The book-card drop shadow.
 *
 * The shell is otherwise flat (hierarchy comes from the tonal `surfaceContainer*`
 * ramp), but a book cover is a physical object standing on a shelf — a flat
 * tile reads as a coloured rectangle, not as a book. So the grid card is the
 * one deliberate exception, and it gets a soft, downward, contact-style
 * shadow that follows the artwork's own rounded silhouette.
 *
 * @param shape the card's shape; the shadow is clipped to it, so pass the SAME
 *   shape the card is drawn with or the shadow will show square corners.
 * @param elevation corner radius of the shadow blur (Android's shadow API
 *   conflates blur radius and elevation — [ElevationTokens.CARD_SHADOW_BLUR]
 *   is the intended value).
 * @param dark true in dark mode, where a black shadow is invisible and the
 *   separation instead comes from a faint light halo.
 */
fun Modifier.bookCardShadow(
    shape: Shape,
    dark: Boolean,
    elevation: Dp = ElevationTokens.CARD_SHADOW_BLUR.dp,
): Modifier = if (dark) {
    // On a dark page a black shadow cannot be seen; the halo does the work.
    this.shadow(
        elevation = elevation,
        shape = shape,
        clip = false,
        ambientColor = Color(ElevationTokens.CARD_HALO_DARK),
        spotColor = Color(ElevationTokens.CARD_HALO_DARK),
    )
} else {
    this.shadow(
        elevation = elevation,
        shape = shape,
        clip = false,
        ambientColor = Color.Black.copy(alpha = ElevationTokens.CARD_SHADOW_ALPHA),
        spotColor = Color.Black.copy(alpha = ElevationTokens.CARD_SHADOW_ALPHA),
    )
}

/**
 * The rounded silhouette a book cover is clipped to.
 *
 * 12dp — the same radius as the card container, so the artwork's edge and the
 * card's edge are one line rather than two.
 */
val KoodoBookCardShape: Shape = RoundedCornerShape(ElevationTokens.CARD_CORNER_RADIUS.dp)
