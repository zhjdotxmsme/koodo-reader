package com.koodoreader.reader.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.koodoreader.core.designsystem.MotionTokens
import com.koodoreader.core.designsystem.SpaceTokens
import com.koodoreader.core.ui.component.koodoPressScale
import com.koodoreader.core.ui.component.popupShadow
import com.koodoreader.core.ui.theme.KoodoMotion
import com.koodoreader.core.ui.theme.KoodoShapes
import com.koodoreader.reader.R

/**
 * Bottom-sheet selection menu for the reader.
 *
 * Replaces the previous anchor-point [DropdownMenu] with a stable bottom
 * sheet so the selected text stays visible and the menu is reachable on
 * all screen sizes.
 */
@OptIn(
    ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)
@Composable
fun ReaderSelectionBottomSheet(
    highlighted: Boolean,
    actions: List<SelectionMenuAction>,
    onDismiss: () -> Unit,
    onSaveHighlight: (colorIndex: Int) -> Unit,
    onDeleteHighlight: () -> Unit,
    onAction: (SelectionMenuAction) -> Unit,
    onAdjustSelection: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Cap the sheet height and let the content scroll. Without the cap, a
    // large system font (or a short landscape screen) pushes the bottom
    // actions — "Translate", "Note" — past the visible area, and
    // ModalBottomSheet does not scroll on its own, so they become
    // unreachable. Same pattern as ReaderControlCenterSheet.
    val maxSheetHeight = LocalConfiguration.current.screenHeightDp.dp * 0.7f
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        scrimColor = Color.Black.copy(alpha = 0.55f),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = SpaceTokens.sm.dp,
        shape = KoodoShapes.extraLarge,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        windowInsets = WindowInsets(0, 0, 0, 0),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                .padding(horizontal = SpaceTokens.md.dp)
                .padding(bottom = SpaceTokens.md.dp)
                // The popup layer of the shadow system (0 12 32): the sheet
                // floats above the page, so it gets the deeper shadow.
                .popupShadow(KoodoShapes.extraLarge),
        ) {
            Text(
                text = t("Selected text"),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = SpaceTokens.sm.dp),
            )

            if (highlighted) {
                SelectionActionRow(
                    icon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null) },
                    label = t("Delete"),
                    onClick = onDeleteHighlight,
                )
            } else {
                HighlightColorRow(onColorClick = onSaveHighlight)
            }

            Spacer(Modifier.height(SpaceTokens.sm.dp))
            HorizontalDivider()
            Spacer(Modifier.height(SpaceTokens.sm.dp))

            actions.forEach { action ->
                SelectionActionRow(
                    icon = { ActionIcon(action) },
                    label = t(action.titleKey),
                    onClick = { onAction(action) },
                )
            }
            onAdjustSelection?.let { onAdjust ->
                SelectionActionRow(
                    icon = { Icon(painterResource(R.drawable.ic_note), contentDescription = null) },
                    label = t("Adjust selection"),
                    onClick = onAdjust,
                )
            }
        }
    }
}

@Composable
private fun HighlightColorRow(
    onColorClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = listOf(
        Color(0xFFFBC02D) to t("Yellow"),
        Color(0xFF2E7D32) to t("Green"),
        Color(0xFFAD1457) to t("Pink"),
        Color(0xFF1D4ED8) to t("Blue"),
    )
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        colors.forEachIndexed { index, (color, label) ->
            TextButton(
                onClick = { onColorClick(index) },
                modifier = Modifier.koodoPressScale(),
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(color),
                )
                Spacer(Modifier.width(SpaceTokens.sm.dp))
                Text(label)
            }
        }
    }
}

@Composable
private fun SelectionActionRow(
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .koodoPressScale(),
        shape = KoodoShapes.small,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SpaceTokens.md.dp),
        ) {
            icon()
            Text(label, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun ActionIcon(action: SelectionMenuAction) {
    val resId = when (action) {
        SelectionMenuAction.COPY -> R.drawable.ic_note
        SelectionMenuAction.SEARCH -> R.drawable.ic_search
        SelectionMenuAction.DICTIONARY -> R.drawable.ic_dictionary
        SelectionMenuAction.TRANSLATE -> R.drawable.ic_brightness
        SelectionMenuAction.NOTE -> R.drawable.ic_note
    }
    Icon(painterResource(resId), contentDescription = null)
}
