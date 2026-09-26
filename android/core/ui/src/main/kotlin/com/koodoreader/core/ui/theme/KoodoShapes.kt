package com.koodoreader.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp
import com.koodoreader.core.designsystem.ShapeTokens

/**
 * Compose shapes built from [ShapeTokens].
 *
 * Mapping used across the app (documented in ShapeTokens):
 *   cards & bottom sheets -> large (16dp)
 *   controls, chips, text fields -> medium (12dp) / small (8dp)
 *   inline badges -> extraSmall (4dp)
 *
 * 16dp cards and 8dp chips deliberately match the reference reading app's
 * `rounded-box` / `rounded-btn` scale.
 */
val KoodoShapes = Shapes(
    extraSmall = RoundedCornerShape(ShapeTokens.extraSmall.dp),
    small = RoundedCornerShape(ShapeTokens.small.dp),
    medium = RoundedCornerShape(ShapeTokens.medium.dp),
    large = RoundedCornerShape(ShapeTokens.large.dp),
    extraLarge = RoundedCornerShape(ShapeTokens.extraLarge.dp),
)

/** Card corner radius, named so components do not hardcode 16.dp. */
val KoodoCardShape = RoundedCornerShape(ShapeTokens.large.dp)

/** Chip / filter corner radius. */
val KoodoChipShape = RoundedCornerShape(ShapeTokens.small.dp)

/** Book cover corner radius in the library grid. */
val KoodoCoverShape = RoundedCornerShape(ShapeTokens.small.dp)
