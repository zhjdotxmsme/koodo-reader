package com.koodoreader.reader.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.annotation.DrawableRes
import androidx.compose.ui.res.painterResource
import com.koodoreader.core.ui.component.koodoPressScale
import com.koodoreader.reader.R
import com.koodoreader.reader.shell.ReaderFeatureCodec
import com.koodoreader.reader.shell.ReaderFeatureState
import com.koodoreader.reader.shell.t

enum class ReaderOverflowActionId {
    READING_OPTIONS,
    ZOOM_OUT,
    ZOOM_IN,
    OCR,
    SHARE,
    BOOKMARK,
    NOTES,
    TTS,
    BIONIC,
    RULER,
    PARAGRAPH_MODE,
    SPEED_READING,
    TWO_COLUMN,
    ASK_AI,
}

data class ReaderOverflowItem(
    val id: ReaderOverflowActionId,
    val label: String,
    val enabled: Boolean = true,
    @DrawableRes val leadingIcon: Int? = null,
    val onClick: () -> Unit,
)

/** Stable action order for JVM tests ([ReaderOverflowCatalogTest]). */
object ReaderOverflowCatalog {

    fun epubIds(useFoliate: Boolean): List<ReaderOverflowActionId> = buildList {
        add(ReaderOverflowActionId.READING_OPTIONS)
        add(ReaderOverflowActionId.BOOKMARK)
        add(ReaderOverflowActionId.NOTES)
        add(ReaderOverflowActionId.TTS)
        if (!useFoliate) {
            add(ReaderOverflowActionId.BIONIC)
            add(ReaderOverflowActionId.RULER)
            add(ReaderOverflowActionId.PARAGRAPH_MODE)
            add(ReaderOverflowActionId.SPEED_READING)
            add(ReaderOverflowActionId.TWO_COLUMN)
        }
        add(ReaderOverflowActionId.ASK_AI)
    }

    fun pdfIds(): List<ReaderOverflowActionId> = listOf(
        ReaderOverflowActionId.ZOOM_OUT,
        ReaderOverflowActionId.ZOOM_IN,
        ReaderOverflowActionId.OCR,
        ReaderOverflowActionId.SHARE,
        ReaderOverflowActionId.READING_OPTIONS,
        ReaderOverflowActionId.NOTES,
    )
}

object ReaderOverflowItems {

    @Composable
    fun forEpub(
        useFoliate: Boolean,
        features: ReaderFeatureState,
        onReadingOptions: () -> Unit,
        onBookmark: () -> Unit,
        onNotes: () -> Unit,
        onTts: () -> Unit,
        onAskAi: () -> Unit,
        onCommitFeatures: (ReaderFeatureState) -> Unit,
    ): List<ReaderOverflowItem> {
        val handlers = mapOf(
            ReaderOverflowActionId.READING_OPTIONS to ReaderOverflowItem(
                ReaderOverflowActionId.READING_OPTIONS,
                t("Reading options"),
                leadingIcon = R.drawable.ic_settings,
                onClick = onReadingOptions,
            ),
            ReaderOverflowActionId.BOOKMARK to ReaderOverflowItem(
                ReaderOverflowActionId.BOOKMARK,
                t("Bookmark"),
                onClick = onBookmark,
            ),
            ReaderOverflowActionId.NOTES to ReaderOverflowItem(
                ReaderOverflowActionId.NOTES,
                t("Notes"),
                onClick = onNotes,
            ),
            ReaderOverflowActionId.TTS to ReaderOverflowItem(
                ReaderOverflowActionId.TTS,
                t("Speak the text"),
                onClick = onTts,
            ),
            ReaderOverflowActionId.BIONIC to ReaderOverflowItem(
                ReaderOverflowActionId.BIONIC,
                t("Fast reading mode (make the first half of the word bold)"),
                onClick = { onCommitFeatures(features.copy(bionic = !features.bionic)) },
            ),
            ReaderOverflowActionId.RULER to ReaderOverflowItem(
                ReaderOverflowActionId.RULER,
                t("Enable reading ruler"),
                onClick = { onCommitFeatures(features.copy(ruler = !features.ruler)) },
            ),
            ReaderOverflowActionId.PARAGRAPH_MODE to ReaderOverflowItem(
                ReaderOverflowActionId.PARAGRAPH_MODE,
                t("Enable paragraph mode"),
                onClick = { onCommitFeatures(features.copy(paragraphMode = !features.paragraphMode)) },
            ),
            ReaderOverflowActionId.SPEED_READING to ReaderOverflowItem(
                ReaderOverflowActionId.SPEED_READING,
                t("Enable speed reading"),
                onClick = { onCommitFeatures(features.copy(speedReading = !features.speedReading)) },
            ),
            ReaderOverflowActionId.TWO_COLUMN to ReaderOverflowItem(
                ReaderOverflowActionId.TWO_COLUMN,
                t("Two-column layout"),
                onClick = {
                    val next = if (ReaderFeatureCodec.clampedColumns(features) >= 2) 1 else 2
                    onCommitFeatures(features.copy(columnsPerPage = next))
                },
            ),
            ReaderOverflowActionId.ASK_AI to ReaderOverflowItem(
                ReaderOverflowActionId.ASK_AI,
                t("Ask AI"),
                onClick = onAskAi,
            ),
        )
        return ReaderOverflowCatalog.epubIds(useFoliate).mapNotNull { handlers[it] }
    }

    @Composable
    fun forPdf(
        exporting: Boolean,
        onZoomOut: () -> Unit,
        onZoomIn: () -> Unit,
        onOcr: () -> Unit,
        onShare: () -> Unit,
        onReadingOptions: () -> Unit,
        onNotes: () -> Unit,
    ): List<ReaderOverflowItem> {
        val handlers = mapOf(
            ReaderOverflowActionId.ZOOM_OUT to ReaderOverflowItem(
                ReaderOverflowActionId.ZOOM_OUT,
                t("Zoom out"),
                onClick = onZoomOut,
            ),
            ReaderOverflowActionId.ZOOM_IN to ReaderOverflowItem(
                ReaderOverflowActionId.ZOOM_IN,
                t("Zoom in"),
                onClick = onZoomIn,
            ),
            ReaderOverflowActionId.OCR to ReaderOverflowItem(
                ReaderOverflowActionId.OCR,
                t("OCR"),
                onClick = onOcr,
            ),
            ReaderOverflowActionId.SHARE to ReaderOverflowItem(
                ReaderOverflowActionId.SHARE,
                t("Share"),
                enabled = !exporting,
                onClick = onShare,
            ),
            ReaderOverflowActionId.READING_OPTIONS to ReaderOverflowItem(
                ReaderOverflowActionId.READING_OPTIONS,
                t("Reading options"),
                leadingIcon = R.drawable.ic_settings,
                onClick = onReadingOptions,
            ),
            ReaderOverflowActionId.NOTES to ReaderOverflowItem(
                ReaderOverflowActionId.NOTES,
                t("Notes"),
                onClick = onNotes,
            ),
        )
        return ReaderOverflowCatalog.pdfIds().mapNotNull { handlers[it] }
    }
}

@Composable
fun ReaderOverflowDropdown(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    items: List<ReaderOverflowItem>,
    modifier: Modifier = Modifier,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
    ) {
        items.forEach { item ->
            DropdownMenuItem(
                text = { Text(item.label) },
                enabled = item.enabled,
                leadingIcon = item.leadingIcon?.let { res ->
                    { Icon(painterResource(res), contentDescription = null) }
                },
                onClick = {
                    onDismissRequest()
                    item.onClick()
                },
                modifier = Modifier.koodoPressScale(),
            )
        }
    }
}

@Composable
fun ReaderMoreIconWithOverflow(
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    items: List<ReaderOverflowItem>,
    enabled: Boolean = true,
) {
    Box {
        IconButton(
            onClick = { onMenuExpandedChange(true) },
            enabled = enabled,
            modifier = Modifier.koodoPressScale(),
        ) {
            Icon(
                painterResource(R.drawable.ic_more),
                contentDescription = t("More"),
            )
        }
        ReaderOverflowDropdown(
            expanded = menuExpanded,
            onDismissRequest = { onMenuExpandedChange(false) },
            items = items,
        )
    }
}