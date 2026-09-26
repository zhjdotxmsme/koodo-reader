package com.koodoreader.core.ui.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable

/**
 * The shell's top app bar.
 *
 * Thin wrapper over Material 3's `TopAppBar` that pins the decisions every
 * screen was making for itself:
 *  - the container is `surface`, NOT `surfaceContainer` — the bar sits flush with
 *    the page and hierarchy comes from the tonal ramp below it, not from the bar
 *    floating above;
 *  - the title uses `titleMedium` rather than each screen passing its own weight;
 *  - the back affordance is the auto-mirrored arrow, so RTL layouts point the
 *    right way (the previous hand-rolled bars used `Icons.Default.ArrowBack`,
 *    which does not mirror).
 *
 * Deliberately does NOT take a `title: @Composable` slot: the callers all pass a
 * plain, already-localised string, and a slot invites per-screen styling drift,
 * which is the thing this component exists to stop. Add the slot when a screen
 * genuinely needs one.
 *
 * @param backContentDescription screen-reader label for the back button —
 *   required when [onBack] is non-null, because an icon-only button with no label
 *   is invisible to TalkBack.
 * @param actions trailing actions; the caller supplies its own wording/i18n.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KoodoTopAppBar(
    title: String,
    onBack: (() -> Unit)? = null,
    backContentDescription: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { Text(text = title, style = MaterialTheme.typography.titleMedium) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = backContentDescription,
                    )
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}
