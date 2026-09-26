package com.koodoreader.reader.imagehost

/*
 * ============================================================================
 * Compose 宿主（单图/双页 + 缩放平移）—— 自 engine/image 的移植模板落位（P5-CBZ-5）
 * ============================================================================
 * 原 `engine/image/.../host/ComicViewerHost.kt`（纯 JVM 模块不参与编译的模板）
 * 按 docs/patches/p5-image.patch §3 的步骤 1 移入 :app，包名调整为
 * com.koodoreader.reader.imagehost。:app 已有 compose 依赖（activity-compose /
 * material3 / foundation），由 :app:compileDebugKotlin 门禁编译验证。
 *
 * 逻辑边界依旧很薄：所有决策（翻页落点/窗口/缩放夹取）都在
 * engine:image 的纯 Kotlin 侧，宿主只负责「快照 → UI + 手势回灌」。
 * 入口：ComicViewerActivity（VIEW/SEND intent 的 CBZ/CBT/CB7 原生路由）。
 */

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import com.koodoreader.engine.image.ComicPage
import com.koodoreader.engine.image.ComicViewerModel
import com.koodoreader.engine.image.ContentSize
import com.koodoreader.engine.image.Viewport
import com.koodoreader.engine.image.ZoomPan
import com.koodoreader.engine.image.ZoomPanState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 宿主解码回调：`ComicPage.bytes`（已驻留）→ 屏幕位图。 */
fun interface ComicPageDecoder {
    /**
     * @param targetWidth 目标宽度（px）：单页视口宽度或双页时的半宽；
     *        实现应用 `BitmapFactory.Options.inSampleSize` 按需降采样，
     *        避免 4K 彩页直接占满堆。
     */
    suspend fun decode(page: ComicPage, targetWidth: Int): ImageBitmap?
}

/**
 * 阅读器宿主：手势 + 版面 + 预取。
 *
 * @param onUiToggle 点中间区域（或双击后的单击）时宿主自行切换工具栏/进度条
 * @param onExitRequested 末页再往后翻时请求退出（不要在这里直接 finish）
 */
@Composable
fun ComicViewerHost(
    model: ComicViewerModel,
    decoder: ComicPageDecoder,
    ioScope: CoroutineScope,
    modifier: Modifier = Modifier,
    onUiToggle: () -> Unit = {},
    onExitRequested: () -> Unit = {},
) {
    var snapshot by remember { mutableStateOf(model.open(0)) }
    var zoom by remember { mutableStateOf(ZoomPanState.FIT) }
    var viewport by remember { mutableStateOf(Viewport(0f, 0f)) }

    // 每屏的内容尺寸：单页用自身尺寸，双页用「两张并排」的合成尺寸
    val contentSize: ContentSize = remember(snapshot.spread) {
        val widths = snapshot.pages.filterNotNull()
        when {
            widths.isEmpty() -> ContentSize(1f, 1f)
            widths.size == 1 -> ContentSize(widths[0].width.toFloat(), widths[0].height.toFloat())
            else -> ContentSize(
                widths.sumOf { it.width }.toFloat(),
                widths.maxOf { it.height }.toFloat(),
            )
        }
    }

    // 翻页时重置缩放（桌面行为：新页回到适屏，不复用上一页的放大位置）
    LaunchedEffect(snapshot.spread.start) { zoom = ZoomPanState.FIT }

    // 预取：窗口由引擎决定（当前页 + 后 3 页），宿主只管丢到 IO 线程（不阻塞 UI）
    LaunchedEffect(snapshot.spread.start) {
        ioScope.launch { model.prefetch() }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { viewport = Viewport(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(snapshot.spread.start, viewport) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    zoom = if (gestureZoom != 1f) {
                        ZoomPan.pinch(zoom, gestureZoom, viewport.centerX, viewport.centerY, viewport, contentSize)
                    } else {
                        ZoomPan.pan(zoom, pan.x, pan.y, viewport, contentSize)
                    }
                }
            }
            .pointerInput(snapshot.spread.start, zoom.isZoomed, viewport) {
                detectTapGestures(
                    onDoubleTap = { at ->
                        zoom = ZoomPan.doubleTap(zoom, at.x, at.y, viewport, contentSize)
                    },
                    onTap = { at ->
                        // 放大状态下单击先复位，不翻页（避免误触翻页丢位置）
                        if (zoom.isZoomed) {
                            zoom = ZoomPanState.FIT
                        } else {
                            when (tapZone(at.x, viewport.width)) {
                                TapZone.LEFT -> model.previous()?.let { snapshot = it }
                                TapZone.RIGHT -> model.next()?.let { snapshot = it } ?: onExitRequested()
                                TapZone.CENTER -> onUiToggle()
                            }
                        }
                    },
                )
            },
    ) {
        if (snapshot.spread.isDouble) {
            // 双页：visualOrder 已按阅读方向排好（RTL 时左右互换）
            Row(modifier = Modifier.fillMaxSize()) {
                for (index in snapshot.spread.visualOrder) {
                    PagePane(
                        page = snapshot.pages.getOrNull(snapshot.spread.pages.indexOf(index)),
                        decoder = decoder,
                        zoom = zoom,
                        viewport = Viewport(viewport.width / 2f, viewport.height),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            }
        } else {
            PagePane(
                page = snapshot.pages.firstOrNull(),
                decoder = decoder,
                zoom = zoom,
                viewport = viewport,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    DisposableEffect(model) {
        onDispose { model.close() }
    }
}

/** 单页/半页绘制：占位（尺寸已知时按比例留白，防翻页跳动）+ 位图 + 缩放层。 */
@Composable
private fun PagePane(
    page: ComicPage?,
    decoder: ComicPageDecoder,
    zoom: ZoomPanState,
    viewport: Viewport,
    modifier: Modifier = Modifier,
) {
    val targetWidth = viewport.width.toInt().coerceAtLeast(1)
    val bitmap by produceState<ImageBitmap?>(initialValue = null, page?.index, targetWidth) {
        value = page?.let { decoder.decode(it, targetWidth) }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image == null) {
            // 未驻留/解码中：用引擎给的固有尺寸占位（ImageHeader 已解析，无需解码像素），
            // 这样翻页时布局不跳动；page == null 表示该页读取失败，留纯黑底。
            val ratio = page?.aspectRatio?.takeIf { it > 0f } ?: (2f / 3f)
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.98f)
                    .aspectRatio(ratio)
                    .background(if (page == null) Color.Black else Color(0xFF1A1A1A)),
            )
        } else {
            Image(
                bitmap = image,
                contentDescription = page?.entryName,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val k = ZoomPan.displayScale(
                            viewport,
                            ContentSize(image.width.toFloat(), image.height.toFloat()),
                            zoom.scale,
                        )
                        scaleX = k
                        scaleY = k
                        translationX = zoom.offsetX
                        translationY = zoom.offsetY
                        transformOrigin = TransformOrigin.Center
                    },
            )
        }
    }
}

private enum class TapZone { LEFT, CENTER, RIGHT }

/** 左右各 1/3 翻页、中间 1/3 呼出 UI —— 与桌面漫画阅读器的点击区一致。 */
private fun tapZone(x: Float, width: Float): TapZone {
    if (width <= 0f) return TapZone.CENTER
    val ratio = x / width
    return when {
        ratio < 1f / 3f -> TapZone.LEFT
        ratio > 2f / 3f -> TapZone.RIGHT
        else -> TapZone.CENTER
    }
}
