package com.koodoreader.reader.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.koodoreader.core.designsystem.SpaceTokens
import com.koodoreader.core.ui.component.shimmer

/** Shelf loading placeholder: shimmering cover tiles, shown only while the
 *  first Room emission is pending; Success/Error remove it immediately. */
@Composable
fun LibraryLoadingSkeleton(tileCount: Int = 12) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 112.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(SpaceTokens.SCREEN_HORIZONTAL.dp),
        horizontalArrangement = Arrangement.spacedBy(SpaceTokens.GRID_GAP_HORIZONTAL.dp),
        verticalArrangement = Arrangement.spacedBy(SpaceTokens.GRID_GAP_VERTICAL.dp),
        userScrollEnabled = false,
    ) {
        items(tileCount) {
            Box(
                modifier = Modifier
                    .padding(top = SpaceTokens.md.dp)
                    .aspectRatio(0.72f)
                    .shimmer()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp),
                    ),
            )
        }
    }
}
