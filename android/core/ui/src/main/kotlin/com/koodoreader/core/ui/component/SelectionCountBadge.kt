package com.koodoreader.core.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.koodoreader.core.designsystem.MotionTokens
import com.koodoreader.core.designsystem.SpaceTokens
import com.koodoreader.core.ui.theme.KoodoChipShape
import com.koodoreader.core.ui.theme.KoodoMotion

/**
 * The live character count shown while a text selection is being dragged.
 *
 * ## Why a live count
 *
 * Dragging a selection handle is a blind gesture: the finger covers the text
 * it is selecting, and the anchor is on the *other* side of the word. Without
 * a count, the user cannot tell whether the selection has grabbed three
 * characters or three hundred — they have to release, read the bottom sheet,
 * and start again.
 *
 * A count that updates while the handle moves turns the gesture into a
 * feedback loop: the user watches the number and stops when it is right.
 * This is the same affordance every mainstream reader ships.
 *
 * ## Counting CJK
 *
 * The count is in **characters**, not words. Chinese has no word delimiters,
 * so a word count would be meaningless; a character count is what a Chinese
 * reader actually wants to see. For mixed text the count is the total
 * character count, which is the honest measure.
 */
@Composable
fun SelectionCountBadge(
    visible: Boolean,
    characterCount: Int,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(MotionTokens.FAST, easing = KoodoMotion.Decelerate)),
        exit = fadeOut(tween(MotionTokens.FAST, easing = KoodoMotion.Accelerate)),
        modifier = modifier,
    ) {
        Text(
            text = "$characterCount",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clip(KoodoChipShape)
                .background(MaterialTheme.colorScheme.primary)
                .padding(
                    horizontal = SpaceTokens.sm.dp,
                    vertical = SpaceTokens.xs.dp,
                ),
        )
    }
}

/**
 * The character count of a selection.
 *
 * Whitespace-only selections count as zero — a drag that grabbed nothing but
 * a line break should not claim to have selected three characters.
 *
 * @return the number of non-whitespace characters, or 0 for a blank selection.
 */
fun selectionCharacterCount(text: String?): Int {
    if (text.isNullOrBlank()) return 0
    return text.count { !it.isWhitespace() }
}
