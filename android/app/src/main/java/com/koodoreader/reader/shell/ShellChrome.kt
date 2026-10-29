package com.koodoreader.reader.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Shared chrome colours for the four shell tabs.
 *
 * Keeps Library / Notes / Settings / the bottom bar on one visual language
 * (Readest-like: no heavy outlines, muted selection, paper surfaces) without
 * inventing a second design system.
 */
object ShellChrome {
    val searchCorner = RoundedCornerShape(12.dp)
    val groupCorner = RoundedCornerShape(16.dp)

    @Composable
    fun searchFieldColors() = TextFieldDefaults.colors(
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
        disabledIndicatorColor = Color.Transparent,
        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        cursorColor = MaterialTheme.colorScheme.onSurface,
    )

    @Composable
    fun shelfChipColors() = FilterChipDefaults.filterChipColors(
        containerColor = Color.Transparent,
        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        selectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        selectedLabelColor = MaterialTheme.colorScheme.onSurface,
    )

    @Composable
    fun shelfChipBorder(selected: Boolean) = FilterChipDefaults.filterChipBorder(
        enabled = true,
        selected = selected,
        borderColor = Color.Transparent,
        selectedBorderColor = Color.Transparent,
        disabledBorderColor = Color.Transparent,
        disabledSelectedBorderColor = Color.Transparent,
    )

    @Composable
    fun navItemColors() = NavigationBarItemDefaults.colors(
        selectedIconColor = MaterialTheme.colorScheme.onSurface,
        selectedTextColor = MaterialTheme.colorScheme.onSurface,
        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        indicatorColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun segmentedColors() = SegmentedButtonDefaults.colors(
        activeContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        activeContentColor = MaterialTheme.colorScheme.onSurface,
        inactiveContainerColor = Color.Transparent,
        inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Grouped paper card used by Settings, Appearance and Notes. */
@Composable
fun ShellSettingsGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = ShellChrome.groupCorner,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Column(content = content)
    }
}
