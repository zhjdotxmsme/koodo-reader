package com.koodoreader.engine.layout.reader

import com.koodoreader.engine.layout.LayoutPosition
import com.koodoreader.engine.layout.LayoutResult

/**
 * The current render mode: [PAGED] paginates (turn pages), [SCROLL] is
 * continuous vertical scrolling.
 *
 * Per the spec (`2026-11-02-android-native-consolidation-design.md` §6.3):
 * "分页与滚动共享同一章节布局结果，避免两套文字测量和标注坐标" — both modes
 * are derived from the *same* [LayoutResult], so tap-to-select and annotation
 * coordinates are mode-independent.
 */
enum class PageRenderMode {
    PAGED,
    SCROLL,
}

/**
 * One viewport of a [ReaderFrame] in the chosen [mode].
 *
 * @property layout the full chapter layout (shared across pages/scroll rows).
 * @property location the document position at the viewport's anchor (chapter
 *   start + char offset, matching [TextLocation]).
 * @property mode how the viewport is sliced from [layout].
 *
 * PAGED fields (ignored in SCROLL):
 * @property page index of the visible page within this chapter's pagination
 *   (0-based). Total is `layout.pageCount`.
 *
 * SCROLL fields (ignored in PAGED):
 * @property scrollTopPx the y at which the viewport's anchor begins (the
 *   line's [LayoutLine.y] for the character under the cursor).
 * @property scrollHeightPx the cumulative height of all lines (the total
 *   scrollable content height for this chapter).
 */
data class ReaderView(
    val layout: LayoutResult,
    val location: TextLocation,
    val mode: PageRenderMode,
    // PAGED
    val page: Int = 0,
    // SCROLL
    val scrollTopPx: Float = 0f,
    val scrollHeightPx: Float = 0f,
) {
    /** Number of pages in this chapter (PAGED mode; `layout.pageCount`). */
    val totalPages: Int get() = layout.pageCount

    /** True when [layout] has at least one page. */
    val hasNextPage: Boolean get() = page < layout.pageCount - 1
    val hasPrevPage: Boolean get() = page > 0
}

/**
 * Renders a [ReaderFrame] (chapter layout + location) into a [ReaderView]
 * for the chosen [mode].
 *
 * - PAGED: find the page containing [frame]'s location; return its index.
 * - SCROLL: find the line containing the location's character; return its
 *   y as `scrollTopPx` and the cumulative layout height as `scrollHeightPx`.
 *
 * The contract is that **pagination and scrolling produce the same
 * [TextLocation] for the same character** — `frame.location` is unchanged —
 * so tapping the same physical character in either mode gives the same
 * annotation anchor.
 */
object PageRenderer {

    fun render(frame: ReaderFrame, mode: PageRenderMode): ReaderView = when (mode) {
        PageRenderMode.PAGED -> {
            val page = frame.layout.lines
                .firstOrNull { line ->
                    val block = frame.layout.blocks.getOrNull(line.blockIndex) ?: return@firstOrNull false
                    frame.location.start in (block.globalStart + line.start)..(block.globalStart + line.end)
                }
                ?.page
                ?: 0
            ReaderView(
                layout = frame.layout,
                location = frame.location,
                mode = PageRenderMode.PAGED,
                page = page,
            )
        }
        PageRenderMode.SCROLL -> {
            val scrollHeight = frame.layout.lines.fold(0f) { acc, line -> acc + line.height }
            val anchorY = frame.layout.lines
                .firstOrNull { line ->
                    val block = frame.layout.blocks.getOrNull(line.blockIndex) ?: return@firstOrNull false
                    frame.location.start in (block.globalStart + line.start)..(block.globalStart + line.end)
                }
                ?.y
                ?: 0f
            ReaderView(
                layout = frame.layout,
                location = frame.location,
                mode = PageRenderMode.SCROLL,
                scrollTopPx = anchorY,
                scrollHeightPx = scrollHeight,
            )
        }
    }

    /**
     * The [TextLocation] at a given page-relative (x, y) point — the same
     * hit-testing [LayoutResult.positionAt] uses, mapped back to a
     * [TextLocation] for [AnnotationStore] compatibility.
     */
    fun positionAt(frame: ReaderFrame, x: Float, y: Float): TextLocation? {
        val position: LayoutPosition = frame.layout.positionAt(x, y) ?: return null
        val block: com.koodoreader.engine.layout.LayoutBlock =
            frame.layout.blocks.firstOrNull {
                it.spineIndex == position.spineIndex &&
                    it.elementIndex == position.elementIndex
            }
            ?: frame.layout.blocks.firstOrNull { it.spineIndex == position.spineIndex }
            ?: return null
        val globalStart = block.globalStart
        return TextLocation(
            chapterId = frame.chapterId,
            start = globalStart + position.charOffset,
            end = globalStart + position.charOffset,
        )
    }
}
