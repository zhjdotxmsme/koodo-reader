package com.koodoreader.feature.dictionary.ui

import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.Html
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.view.View
import android.widget.TextView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.koodoreader.feature.dictionary.HtmlDefinitionRenderer

/**
 * 划词弹窗 — the word popup shown after a selection.
 *
 * RENDERING: MDX definitions are HTML fragments, so a `TextView` with
 * `Html.fromHtml` + [MddImageGetter] renders them (the desktop gets this for free by
 * injecting the same HTML into the DOM — popupDict/component.tsx:224-242, which also
 * re-loads `audio.audio-player` elements after render; the Android equivalent is
 * [DictPopupState.hasAudio] + `onPlayAudio`).
 *
 * LINKS: MDict definitions link internally with `entry://word` (and alias with the
 * `@@@LINK=` marker, already resolved by [HtmlDefinitionRenderer]). Those spans are
 * re-bound here to [onWordClick]; `http(s):` links go to [onOpenLink] like the
 * desktop's `openExternalUrl`.
 *
 * IMAGES: `<img src="…">` inside a definition points into the `.mdd` container, which
 * [HtmlDefinitionRenderer] rewrote to `dict-res://<dictId>/<key>`; [MddImageGetter]
 * asks the host for those bytes.
 */
@Composable
fun PopupWordDialog(
    state: DictPopupState,
    onDismiss: () -> Unit,
    onWordClick: (String) -> Unit = {},
    onOpenLink: (String) -> Unit = {},
    onPlayAudio: (String) -> Unit = {},
    onCopy: (String) -> Unit = {},
    strings: DictPopupStrings = DictPopupStrings(),
    /** Resolves `dict-res://<dictId>/<key>` (and bare container refs) to bytes. */
    resourceLoader: (dictId: String, key: String) -> ByteArray? = { _, _ -> null },
    modifier: Modifier = Modifier,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 160.dp, max = 520.dp),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = state.word,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    if (state.dictionaryName != null) {
                        Text(
                            text = state.dictionaryName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))

                when {
                    state.loading -> Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    ) { CircularProgressIndicator() }

                    state.error != null -> Text(
                        text = state.error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    // `dictUtil.lookupWord` toasts "Word not found in dictionary" (:96).
                    state.html == null -> Column {
                        Text(strings.notFound, style = MaterialTheme.typography.bodyMedium)
                        if (state.suggestions.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(strings.suggestions, style = MaterialTheme.typography.labelMedium)
                            for (suggestion in state.suggestions) {
                                TextButton(onClick = { onWordClick(suggestion) }) { Text(suggestion) }
                            }
                        }
                    }

                    else -> Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        DefinitionView(
                            html = state.html,
                            resourceLoader = resourceLoader,
                            onWordClick = onWordClick,
                            onOpenLink = onOpenLink,
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.hasAudio) {
                        TextButton(onClick = { onPlayAudio(state.word) }) { Text(strings.playAudio) }
                    }
                    if (state.html != null) {
                        TextButton(onClick = { onCopy(state.plainText ?: HtmlDefinitionRenderer.toPlainText(state.html)) }) {
                            Text(strings.copy)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onDismiss) { Text(strings.close) }
                }
            }
        }
    }
}

/** The HTML itself: one `TextView` behind an `AndroidView`, re-created when the word changes. */
@Composable
private fun DefinitionView(
    html: String,
    resourceLoader: (String, String) -> ByteArray?,
    onWordClick: (String) -> Unit,
    onOpenLink: (String) -> Unit,
) {
    // `rememberHtmlSpans` is @Composable, so it has to run during composition —
    // AndroidView's `update` lambda is not a composable scope.
    val spans = rememberHtmlSpans(html, resourceLoader) { target ->
        if (isInternalLink(target)) onWordClick(internalWord(target)) else onOpenLink(target)
    }
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { context ->
            TextView(context).apply {
                setTextIsSelectable(true)
                movementMethod = LinkMovementMethod.getInstance()
            }
        },
        update = { view ->
            view.text = spans
        },
    )
}

/** Cached per (html) so a recomposition does not re-parse the definition. */
@Composable
private fun rememberHtmlSpans(
    html: String,
    resourceLoader: (String, String) -> ByteArray?,
    onLinkClick: (String) -> Unit,
): CharSequence = remember(html) {
    val raw = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT, MddImageGetter(resourceLoader), null)
    bindLinks(raw, onLinkClick)
}

/**
 * `Html.fromHtml` produces [URLSpan]s; replace them with spans that route
 * `entry://word` back into the dictionary and everything else to the host.
 */
private fun bindLinks(text: CharSequence, onLinkClick: (String) -> Unit): CharSequence {
    if (text !is Spannable) return text
    val builder = SpannableStringBuilder(text)
    for (span in builder.getSpans(0, builder.length, URLSpan::class.java)) {
        val url = span.url ?: continue
        val start = builder.getSpanStart(span)
        val end = builder.getSpanEnd(span)
        builder.removeSpan(span)
        builder.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) = onLinkClick(url)
        }, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    return builder
}

private const val ENTRY_SCHEME = "entry://"

internal fun isInternalLink(url: String): Boolean = url.startsWith(ENTRY_SCHEME)

internal fun internalWord(url: String): String =
    if (isInternalLink(url)) url.removePrefix(ENTRY_SCHEME) else url

/**
 * Serves `<img>` sources from the `.mdd` container.
 *
 * `Html/ImageGetter.getDrawable` is called on the UI thread during layout, so the
 * lookup must stay cheap: [resourceLoader] is expected to be a cache-backed
 * `MddParser.locateFlexible` call (the app keeps the parser open).
 */
class MddImageGetter(
    private val resourceLoader: (dictId: String, key: String) -> ByteArray?,
) : Html.ImageGetter {

    override fun getDrawable(source: String?): Drawable? {
        if (source.isNullOrEmpty()) return null
        val (dictId, key) = HtmlDefinitionRenderer.resourceKeyFromUrl(source)
            ?: ("" to source.replace('/', '\\'))
        val bytes = resourceLoader(dictId, key) ?: return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return BitmapDrawable(null, bitmap).apply {
            // MDX resources are authored at screen scale; keep the natural size but
            // never let one image push the dialog past its height cap.
            setBounds(0, 0, intrinsicWidth, intrinsicHeight)
        }
    }
}

/** Popup strings (the app passes `:core:common` i18n values). */
data class DictPopupStrings(
    val notFound: String = "Word not found in dictionary",
    val suggestions: String = "Did you mean",
    val playAudio: String = "Play pronunciation",
    val copy: String = "Copy",
    val close: String = "Close",
)

/** Everything the popup shows for one lookup. */
data class DictPopupState(
    val word: String = "",
    val html: String? = null,
    val plainText: String? = null,
    val dictionaryName: String? = null,
    val suggestions: List<String> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    /** The definition contains `<audio …>`; the app can play it from the `.mdd`. */
    val hasAudio: Boolean = false,
) {
    companion object {
        /** Derive the audio hint from the resolved HTML. */
        fun of(
            word: String,
            html: String?,
            dictionaryName: String? = null,
            suggestions: List<String> = emptyList(),
        ): DictPopupState = DictPopupState(
            word = word,
            html = html,
            plainText = html?.let { HtmlDefinitionRenderer.toPlainText(it) },
            dictionaryName = dictionaryName,
            suggestions = suggestions,
            hasAudio = html?.contains("<audio", ignoreCase = true) == true,
        )
    }
}
