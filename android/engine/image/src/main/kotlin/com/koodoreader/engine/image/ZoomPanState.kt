package com.koodoreader.engine.image

/** 视口尺寸（px 或 dp 皆可，只要与 [ContentSize] 同单位）。 */
data class Viewport(val width: Float, val height: Float) {
    val isValid: Boolean get() = width > 0f && height > 0f
    val centerX: Float get() = width / 2f
    val centerY: Float get() = height / 2f
}

/** 内容（图片）原始尺寸。 */
data class ContentSize(val width: Float, val height: Float) {
    val isValid: Boolean get() = width > 0f && height > 0f
}

/**
 * 缩放/平移状态。`scale = 1` 表示「适屏（contain）」，不是「原始像素」。
 * 偏移以**视口中心**为原点，正数向右/向下。
 *
 * 所有变换都是纯函数（[ZoomPan] 里的算子返回新状态），因此可以完整单测，
 * Compose 侧只需 `remember { mutableStateOf(ZoomPanState.FIT) }`。
 */
data class ZoomPanState(
    val scale: Float = ZoomPanState.MIN_SCALE,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
) {
    val isZoomed: Boolean get() = scale > ZoomPanState.MIN_SCALE + ZoomPanState.EPS

    /**
     * 9 元素变换矩阵，顺序与 `android.graphics.Matrix.setValues` / Compose
     * `graphicsLayer` 需要的仿射参数一致：
     * `[k, 0, tx, 0, k, ty, 0, 0, 1]`（k 已含适屏比例 × 用户缩放）。
     */
    fun matrix(viewport: Viewport, content: ContentSize): FloatArray {
        val k = ZoomPan.displayScale(viewport, content, scale)
        val tx = viewport.centerX - content.width * k / 2f + offsetX
        val ty = viewport.centerY - content.height * k / 2f + offsetY
        return floatArrayOf(k, 0f, tx, 0f, k, ty, 0f, 0f, 1f)
    }

    companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 4f

        /** 双击放大到的倍数（桌面漫画阅读器的手感，非精确 2.0）。 */
        const val DOUBLE_TAP_SCALE = 2.5f
        const val EPS = 0.001f

        /** 适屏、无偏移。 */
        val FIT = ZoomPanState()
    }
}

/**
 * 缩放平移算子（纯函数）+ 边界约束。
 *
 * 约束规则：
 *  - 缩放范围 `[MIN_SCALE, MAX_SCALE]`；
 *  - 偏移量按「内容在视口内不能露白」（当内容比视口小，对应轴偏移强制为 0）
 *    夹取 —— 与桌面 `comic` 的双指缩放后回弹一致；
 *  - 捏合以**焦点不漂移**为准则（焦点下的内容坐标在缩放前后保持不动）。
 */
object ZoomPan {

    /** 亚像素余量：内容超出视口不足这么多像素时不认为「可以拖动」。 */
    const val PAN_SLACK_PX = 0.5f

    /** contain 比例：内容完整放进视口。 */
    fun fitScale(viewport: Viewport, content: ContentSize): Float {
        if (!viewport.isValid || !content.isValid) return 1f
        return minOf(viewport.width / content.width, viewport.height / content.height)
    }

    /** 实际绘制比例 = 适屏比例 × 用户缩放。 */
    fun displayScale(viewport: Viewport, content: ContentSize, scale: Float): Float =
        fitScale(viewport, content) * clampScale(scale)

    fun clampScale(scale: Float): Float =
        scale.coerceIn(ZoomPanState.MIN_SCALE, ZoomPanState.MAX_SCALE)

    /**
     * X 轴允许的最大偏移（内容比视口宽时为半差，否则 0）。
     *
     * 亚像素余量（[PAN_SLACK_PX]）内视为「刚好适屏」并返回 0：
     * `fitScale` 是浮点除法（如 800/1500），回乘后可能多出 1e-5 px 的余量，
     * 不处理的话「内容明明正好适屏却能被拖动一像素」，手感是抖的。
     */
    fun maxOffsetX(viewport: Viewport, content: ContentSize, scale: Float): Float {
        val drawn = content.width * displayScale(viewport, content, scale)
        val slack = (drawn - viewport.width) / 2f
        return if (slack <= PAN_SLACK_PX) 0f else slack
    }

    fun maxOffsetY(viewport: Viewport, content: ContentSize, scale: Float): Float {
        val drawn = content.height * displayScale(viewport, content, scale)
        val slack = (drawn - viewport.height) / 2f
        return if (slack <= PAN_SLACK_PX) 0f else slack
    }

    /** 夹取到合法状态（每次变换后都应调用）。 */
    fun clamp(state: ZoomPanState, viewport: Viewport, content: ContentSize): ZoomPanState {
        val scale = clampScale(state.scale)
        val mx = maxOffsetX(viewport, content, scale)
        val my = maxOffsetY(viewport, content, scale)
        return ZoomPanState(
            scale = scale,
            // `coerceIn(-0f, 0f)` 会返回 -0.0f，而 -0.0f 与 0.0f 在 data class
            // 的 equals/matrix 里并不等价 → 统一归一为 +0.0f
            offsetX = state.offsetX.coerceIn(-mx, mx).withoutNegativeZero(),
            offsetY = state.offsetY.coerceIn(-my, my).withoutNegativeZero(),
        )
    }

    private fun Float.withoutNegativeZero(): Float = if (this == 0f) 0f else this

    /** 双指捏合：`zoomFactor` 为本次手势的相对倍数，焦点为视口坐标。 */
    fun pinch(
        state: ZoomPanState,
        zoomFactor: Float,
        focusX: Float,
        focusY: Float,
        viewport: Viewport,
        content: ContentSize,
    ): ZoomPanState = scaleTo(state, state.scale * zoomFactor, focusX, focusY, viewport, content)

    /** 以 [focusX]/[focusY] 为不动点缩放到 [targetScale]。 */
    fun scaleTo(
        state: ZoomPanState,
        targetScale: Float,
        focusX: Float,
        focusY: Float,
        viewport: Viewport,
        content: ContentSize,
    ): ZoomPanState {
        val newScale = clampScale(targetScale)
        val oldScale = clampScale(state.scale)
        if (oldScale <= 0f) return clamp(ZoomPanState(newScale), viewport, content)
        val r = newScale / oldScale
        val fx = focusX - viewport.centerX
        val fy = focusY - viewport.centerY
        val moved = ZoomPanState(
            scale = newScale,
            offsetX = fx + (state.offsetX - fx) * r,
            offsetY = fy + (state.offsetY - fy) * r,
        )
        return clamp(moved, viewport, content)
    }

    /** 拖动平移（增量）。 */
    fun pan(state: ZoomPanState, dx: Float, dy: Float, viewport: Viewport, content: ContentSize): ZoomPanState =
        clamp(ZoomPanState(state.scale, state.offsetX + dx, state.offsetY + dy), viewport, content)

    /**
     * 双击：已放大 → 回到适屏；否则以点击点为焦点放大到
     * [ZoomPanState.DOUBLE_TAP_SCALE]。
     */
    fun doubleTap(
        state: ZoomPanState,
        tapX: Float,
        tapY: Float,
        viewport: Viewport,
        content: ContentSize,
    ): ZoomPanState = if (state.isZoomed) {
        ZoomPanState.FIT
    } else {
        scaleTo(state, ZoomPanState.DOUBLE_TAP_SCALE, tapX, tapY, viewport, content)
    }

    /**
     * 惯性滑动后的收尾：把状态夹回边界（Compose 侧 fling 结束调用；
     * 速度积分由 :engine:gesture 负责，这里只做边界与缩放约束）。
     */
    fun settle(state: ZoomPanState, viewport: Viewport, content: ContentSize): ZoomPanState =
        clamp(state, viewport, content)
}
