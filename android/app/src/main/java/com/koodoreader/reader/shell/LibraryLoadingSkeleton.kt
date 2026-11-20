package com.koodoreader.reader.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.koodoreader.core.designsystem.SpaceTokens
import com.koodoreader.core.ui.component.KoodoBookCardShape
import com.koodoreader.core.ui.component.shimmer

/**
 * Shelf loading placeholder: shimmering cover tiles, shown only while the
 * first Room emission is pending; Success/Error remove it immediately.
 *
 * ## Why the clip comes first
 *
 * The shimmer is a *brush* on the tile's background. Clipping afterwards
 * would round the corners but leave the underlying rectangle square, so the
 * clip has to happen BEFORE the brush is painted — otherwise the tile shows
 * a square shimmer with rounded transparent corners.
 */
@Composable
fun LibraryLoadingSkeleton(tileCount: Int = 12) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 112.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = SpaceTokens.SCREEN_HORIZONTAL.dp,
            end = SpaceTokens.SCREEN_HORIZONTAL.dp,
            top = SpaceTokens.lg.dp,
            bottom = SpaceTokens.lg.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(SpaceTokens.GRID_GAP_HORIZONTAL.dp),
        verticalArrangement = Arrangement.spacedBy(SpaceTokens.GRID_GAP_VERTICAL.dp),
        userScrollEnabled = false,
    ) {
        items(tileCount) {
            Box(
                modifier = Modifier
                    .aspectRatio(0.72f)
                    // Clip first, then paint the shimmer: the opposite order
                    // leaves a square shimmer with rounded corners cut out.
                    .clip(KoodoBookCardShape)
                    .shimmer(),
            )
        }
    }
}
