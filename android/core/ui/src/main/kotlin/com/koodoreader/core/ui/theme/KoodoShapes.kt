package com.koodoreader.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp
import com.koodoreader.core.designsystem.ShapeTokens

/**
 * Compose shapes built from [ShapeTokens].
 *
 * Mapping used across the app (documented in ShapeTokens):
 *   cards -> medium (12dp)
 *   Material containers -> large (16dp)
 *   bottom sheets -> extraLarge (28dp)
 *   controls, chips, text fields -> medium (12dp) / small (8dp)
 *   inline badges -> extraSmall (4dp)
 */
val KoodoShapes = Shapes(
    extraSmall = RoundedCornerShape(ShapeTokens.extraSmall.dp),
    small = RoundedCornerShape(ShapeTokens.small.dp),
    medium = RoundedCornerShape(ShapeTokens.medium.dp),
    large = RoundedCornerShape(ShapeTokens.large.dp),
    extraLarge = RoundedCornerShape(ShapeTokens.extraLarge.dp),
)

/** Card corner radius, named so components do not hardcode 12.dp. */
val KoodoCardShape = KoodoShapes.medium

/** Chip / filter corner radius. */
val KoodoChipShape = RoundedCornerShape(ShapeTokens.small.dp)

/** Book cover corner radius in the library grid. */
val KoodoCoverShape = RoundedCornerShape(ShapeTokens.small.dp)
