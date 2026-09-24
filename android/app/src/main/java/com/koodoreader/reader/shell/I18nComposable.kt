package com.koodoreader.reader.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Composition entry for the desktop-parity translator. Provided once at the
 * activity root ([NativeShellActivity]); every screen calls [t].
 */
val LocalI18n = staticCompositionLocalOf<I18nState> {
    error("I18nState not provided — wrap content in CompositionLocalProvider(LocalI18n = ...)")
}

/** Desktop-parity translate: t("Deleted Books") → 回收站 / Trash. */
@Composable
fun t(key: String): String {
    val i18n = LocalI18n.current
    val language by i18n.language.collectAsState()
    // `language` is read only to drive recomposition on live switches.
    @Suppress("UNUSED_EXPRESSION")
    language
    return i18n.localization.t(key)
}
