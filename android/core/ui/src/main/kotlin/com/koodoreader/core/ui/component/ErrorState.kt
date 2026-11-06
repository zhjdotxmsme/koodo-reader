package com.koodoreader.core.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.koodoreader.core.designsystem.SpaceTokens

/**
 * Shared error placeholder: centred icon + message + optional retry button.
 * Strings are passed in by the caller so `:core:ui` stays i18n-free.
 */
@Composable
fun ErrorState(
    message: String,
    onRetry: (() -> Unit)? = null,
    retryText: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            painter = rememberVectorPainter(Icons.Outlined.Warning),
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(SpaceTokens.lg.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        if (onRetry != null && retryText != null) {
            Spacer(Modifier.size(SpaceTokens.lg.dp))
            Button(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(retryText)
            }
        }
    }
}
