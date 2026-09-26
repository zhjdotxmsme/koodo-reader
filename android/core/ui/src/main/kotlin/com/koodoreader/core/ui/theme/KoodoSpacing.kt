package com.koodoreader.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.koodoreader.core.designsystem.SpaceTokens

/**
 * Compose spacing scale, built from [SpaceTokens].
 *
 * Material 3 has no spacing token, which is why the previous shell accumulated
 * hand-tuned padding values. Every gap in the shell should come from here.
 */
@Immutable
data class KoodoSpacing(
    val xs: Dp = SpaceTokens.xs.dp,
    val sm: Dp = SpaceTokens.sm.dp,
    val md: Dp = SpaceTokens.md.dp,
    val lg: Dp = SpaceTokens.lg.dp,
    val xl: Dp = SpaceTokens.xl.dp,
    val xxl: Dp = SpaceTokens.xxl.dp,

    /** Horizontal screen padding. */
    val screenHorizontal: Dp = SpaceTokens.SCREEN_HORIZONTAL.dp,

    /** Vertical gap between list rows. */
    val listGap: Dp = SpaceTokens.LIST_GAP.dp,

    /** Horizontal gap between book covers. */
    val gridGapHorizontal: Dp = SpaceTokens.GRID_GAP_HORIZONTAL.dp,

    /** Vertical gap between cover rows. */
    val gridGapVertical: Dp = SpaceTokens.GRID_GAP_VERTICAL.dp,
)

/**
 * Static because the spacing scale never changes at runtime — it is not tied to
 * the light/dark choice, so a static local avoids needless recomposition.
 */
val LocalKoodoSpacing = staticCompositionLocalOf { KoodoSpacing() }
