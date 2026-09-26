package com.koodoreader.reader.epubhost

import com.koodoreader.engine.layout.HtmlFlattener
import com.koodoreader.engine.layout.LayoutLine
import com.koodoreader.engine.layout.PaginatorOptions
import com.koodoreader.engine.layout.SpineItem
import com.koodoreader.engine.layout.TextBlock
import com.koodoreader.engine.layout.TextMeasurer
import com.koodoreader.engine.text.ChapterSplitter
import com.koodoreader.engine.text.CharsetDetector
import com.koodoreader.engine.text.MarkdownRenderer
import com.koodoreader.engine.text.TextDecoder
import java.io.File

/**
 * TXT / MD → 分页结果的会话（多格式接入）。
 *
 *  - TXT：读文件 → [CharsetDetector]/[TextDecoder] 编码探测解码 →
 *    [ChapterSplitter] 划章 → 每章按段落拆 [TextBlock]（不经 HTML 解析）；
 *  - MD：解码后 [MarkdownRenderer] 渲染为 HTML → [HtmlFlattener] 扁平化。
 *
 * 分页/CFI 委托 [PagedDocumentSession]（与 EPUB 同管道）。
 */
class TextBookSession private constructor(
    private val paged: PagedDocumentSession,
) : ReaderSession {

    override val pageCount: Int get() = paged.pageCount
    override val chapterCount: Int get() = paged.chapterCount

    override fun pageLines(page: Int): List<LayoutLine> = paged.pageLines(page)
    override fun cfiForPage(page: Int): String? = paged.cfiForPage(page)
    override fun pageForCfi(cfi: String): Int? = paged.pageForCfi(cfi)
    override fun pageOfChapter(chapterOrder: Int): Int = paged.pageOfChapter(chapterOrder)
    override fun chapterLabel(chapterOrder: Int): String = paged.chapterLabel(chapterOrder)
    override fun close() {}

    companion object {

        /** 是否原生文本轨可处理的扩展名（TXT/MD/MARKDOWN）。 */
        fun supportsExtension(name: String): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext == "txt" || ext == "md" || ext == "markdown"
        }

        fun open(
            file: File,
            viewportWidthPx: Float,
            viewportHeightPx: Float,
            measurer: TextMeasurer,
            options: PaginatorOptions = PaginatorOptions(),
        ): TextBookSession {
            val bytes = file.readBytes()
            val guess = CharsetDetector.detect(bytes)
            val text = TextDecoder.decode(bytes, guess)
            val chapterList = ChapterSplitter.split(text)

            val chapters = chapterList.mapIndexed { i, ch ->
                val body = ch.extract(text)
                val blocks = if (file.name.lowercase().endsWith(".md") ||
                    file.name.lowercase().endsWith(".markdown")
                ) {
                    val html = MarkdownRenderer.render(body)
                    HtmlFlattener.DEFAULT.flatten(html, spineIndex = i + 1).blocks
                } else {
                    paragraphsToBlocks(body, i)
                }
                PagedDocumentSession.Chapter(
                    index = i,
                    label = ch.title.ifEmpty { "第 ${i + 1} 节" },
                    blocks = blocks,
                )
            }

            val paged = PagedDocumentSession.create(
                chapters = chapters,
                viewportWidthPx = viewportWidthPx,
                viewportHeightPx = viewportHeightPx,
                measurer = measurer,
                options = options,
            )
            return TextBookSession(paged)
        }

        /** 纯文本按空行分段（空行 = 段落边界），一段一个 TextBlock。 */
        private fun paragraphsToBlocks(body: String, chapterIndex: Int): List<TextBlock> {
            val normalized = body.replace("\r\n", "\n")
            val paragraphs = normalized.split(Regex("\n[ \t]*\n"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            return paragraphs.mapIndexed { i, p ->
                TextBlock.of(
                    text = p,
                    elementIndex = i,
                )
            }
        }
    }
}