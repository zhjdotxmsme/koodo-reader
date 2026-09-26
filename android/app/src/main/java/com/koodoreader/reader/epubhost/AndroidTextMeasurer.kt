package com.koodoreader.reader.epubhost

import android.graphics.Paint
import com.koodoreader.engine.layout.TextMeasurer

/**
 * 设备端 [TextMeasurer]：`TextPaint.measureText` 驱动，与分页引擎的契约
 * 一致（宽度像素、letter-spacing 逐字符叠加、行盒 ascent/descent）。
 *
 * 这是「真机渲染 = 分页测量」的关键一环：JVM 测试用确定性 measurer 跑
 * 分页算法，设备用这套 TextPaint 口径跑同一算法，产出的行几何（x/y/
 * baseline）与 Canvas 绘制完全对应——CFI 行级定位因此与所见图文一致。
 *
 * @param density 屏幕密度（sp→px 换算，LayoutTokens.fontSizePx 是 sp 值 ×
 *   density 得物理 px，与 Canvas 的 onSizeChanged px 一致）
 * @param fontFamily 默认字体（null = 系统默认；字体切换后续卡从
 *   FontManager/TypefaceCache 注入）
 */
class AndroidTextMeasurer(
    private val density: Float,
    fontFamily: android.graphics.Typeface? = null,
) : TextMeasurer {

    private val family: android.graphics.Typeface? = fontFamily

    override fun width(text: String, fontSizePx: Float, letterSpacingPx: Float): Float =
        runWidth(text, fontSizePx, letterSpacingPx)

    override fun prefixWidth(text: String, endExclusive: Int, fontSizePx: Float, letterSpacingPx: Float): Float =
        runWidth(text.substring(0, endExclusive.coerceIn(0, text.length)), fontSizePx, letterSpacingPx)

    override fun lineHeightPx(fontSizePx: Float): Float {
        val fm = paint(fontSizePx).fontMetrics
        return fm.descent - fm.ascent
    }

    override fun ascentPx(fontSizePx: Float): Float = -paint(fontSizePx).fontMetrics.ascent

    /**
     * letter-spacing 是「字符间」叠加（引擎契约）；Android 的
     * TextPaint.letterSpacing 是 em 单位且作用于整段——逐字符测量求和才能
     * 满足 prefixWidth 的切片一致性（monotonic 二分前提，见接口 KDoc）。
     */
    private fun runWidth(text: String, fontSizePx: Float, letterSpacingPx: Float): Float {
        if (text.isEmpty()) return 0f
        val p = paint(fontSizePx)
        p.letterSpacing = 0f
        var width = 0f
        for (i in text.indices) {
            width += p.measureText(text, i, i + 1)
            if (i > 0) width += letterSpacingPx
        }
        return width
    }

    /** 每次测量新建 Paint：测量是纯函数（无共享可变状态），行为确定。 */
    private fun paint(fontSizePx: Float): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = family
            textSize = fontSizePx * density
        }
}