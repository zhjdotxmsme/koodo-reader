package com.koodoreader.engine.toc

/**
 * One chapter's plain-text content for search indexing.
 *
 * Produced by the EPUB parser (the caller holds the ZIP / XHTML) by stripping
 * HTML tags and concatenating the text of each spine item.
 *
 * @property spineIndex 0-based chapter index, matching [SpineChapter].
 * @property title chapter title from the spine / TOC (may be `null`).
 * @property text the chapter's plain text (no HTML, no whitespace other than
 *   what is already in the source — this is used directly for substring search).
 * @property cfiStart the chapter's starting CFI (e.g. from [CfiFake.fromIndex]).
 */
data class ChapterText(
    val spineIndex: Int,
    val title: String?,
    val text: String,
    val cfiStart: String,
)
