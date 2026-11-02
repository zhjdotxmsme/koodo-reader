# Android Native PDF Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用 PDFium 替换 pdf.js 主阅读链路，提供直接位图渲染、文本选择、高亮、笔记、搜索、目录、密码、OCR 和重排，并满足 ABI/16 KB 要求。

**Architecture:** 纯 JVM `PdfEngine` 定义文档、页面、文本和搜索模型；Android `PdfiumEngine` 适配 `io.legere:pdfiumandroid`。Compose 通过有界缓存消费 Bitmap 和页面文本，标注继续使用桌面兼容的 `CfiPdfMapper` JSON。

**Tech Stack:** Kotlin、PDFium、`io.legere:pdfiumandroid:2.0.3`、Compose、Room、ML Kit OCR、JUnit/Android instrumentation。

## Global Constraints

- 书籍正文不使用 WebView，不经过 PNG/Base64/JavaScript 桥。
- PDFium 所有目标 ABI 和 16 KB 对齐必须通过；失败时不得静默退回 pdf.js。
- 保留目录、搜索、密码、裁边、双页、OCR、重排和快照。
- PDF 标注使用指纹、1 基页号、归一化矩形及文本引用，能从笔记跳回原选区。
- 1000 页 PDF 打开 ≤ 1.5 秒；没有设备结果不算最终完成。

## File Map

- `android/engine/pdf/src/main/kotlin/com/koodoreader/engine/pdf/PdfEngine.kt`：原生引擎中性接口和模型。
- `android/engine/pdf/src/main/kotlin/com/koodoreader/engine/pdf/PdfPageCache.kt`：按字节预算的通用缓存策略。
- `android/app/src/main/java/com/koodoreader/reader/pdfhost/PdfiumEngine.kt`：PDFium Android 适配器。
- `android/app/src/main/java/com/koodoreader/reader/shell/PdfReaderController.kt`：直接位图渲染、取消和状态。
- `android/app/src/main/java/com/koodoreader/reader/pdfhost/PdfSelectionModel.kt`：字符选择、坐标转换和文本引用。
- `android/app/src/main/java/com/koodoreader/reader/pdfhost/PdfSelectionOverlay.kt`：Compose 选择和高亮层。

---

### Task 1: PDFium 依赖、ABI 与文本能力验收构建

**Files:**
- Modify: `android/app/build.gradle:162-240`
- Create: `android/app/src/androidTest/java/com/koodoreader/reader/pdfhost/PdfiumCapabilityTest.kt`
- Create: `android/app/src/androidTest/assets/reader/text-page.pdf`
- Modify: `scripts/check-elf-16kb.js`（仅在发现新库过滤规则误报时）
- Modify: `docs/android-pdf-poc.md`

**Interfaces:**
- Produces: 可供 Task 2 使用的 `PdfiumCoreKt`、`PdfDocumentKt`、`PdfPageKt`、`PdfTextPageKt` 依赖。
- Produces: 设备上可验证的 `open → page → text → charBox/rects → bitmap` 链路。

- [ ] **Step 1: 先加入固定依赖和失败能力测试**

```gradle
implementation 'io.legere:pdfiumandroid:2.0.3'
```

```kotlin
@Test
fun pdfiumReadsTextAndCoordinates() = runTest {
    val file = AssetCopies.copy(context, "reader/text-page.pdf")
    val core = PdfiumCoreKt(context, Dispatchers.IO)
    val document = core.openDocument(file)
    val page = document.openPage(0)!!
    val text = page.openTextPage()
    assertTrue(text.textPageGetText(0, text.textPageCountChars())!!.contains("Native PDF"))
    assertNotNull(text.textPageGetCharBox(0))
    assertTrue(text.textPageCountRects(0, 1) > 0)
    text.close(); page.close(); document.close()
}
```

如果库的 suspend 包装名与上述不同，使用同版本普通 `PdfiumCore` API，但测试断言保持不变；不得删除文本坐标断言。

- [ ] **Step 2: 运行依赖解析、构建和设备测试**

Run: `./gradlew :app:assembleDebug :app:assembleRelease`
Expected: PASS 且 Maven 固定到 2.0.3。
Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.koodoreader.reader.pdfhost.PdfiumCapabilityTest`
Expected: PASS；若文本 API 或原生加载失败，本任务阻断后续 PDF 任务。

- [ ] **Step 3: 检查所有 ABI 和 16 KB 页对齐**

Run: `node scripts/check-elf-16kb.js <path-to-apk>`
Expected: PASS，输出每个新 `.so` 的对齐结果。至少构建 `arm64-v8a`、`armeabi-v7a`、`x86_64`；缺任一目标 ABI 即失败。

- [ ] **Step 4: 更新 PDF POC 结论**

记录选择 2.0.3 的文本 API 证据、PDFium 版本、包体积增量和 ABI 结果；将旧“pdf.js 主路径”结论标为被 2026-11-02 原生设计替代。

- [ ] **Step 5: 提交**

```bash
git add android/app scripts/check-elf-16kb.js docs/android-pdf-poc.md && git commit -m "build(android): validate native pdfium engine"
```

### Task 2: 引擎中性模型与 PDFium 文本适配

**Files:**
- Create: `android/engine/pdf/src/main/kotlin/com/koodoreader/engine/pdf/PdfEngine.kt`
- Modify: `android/engine/pdf/src/main/kotlin/com/koodoreader/engine/pdf/PdfHostBridge.kt`
- Create: `android/app/src/main/java/com/koodoreader/reader/pdfhost/PdfiumEngine.kt`
- Create: `android/app/src/main/java/com/koodoreader/reader/pdfhost/PdfiumTextMapper.kt`
- Test: `android/engine/pdf/src/test/kotlin/com/koodoreader/engine/pdf/PdfEngineContractTest.kt`
- Modify: `android/app/src/androidTest/java/com/koodoreader/reader/pdfhost/PdfiumCapabilityTest.kt`

**Interfaces:**
- Produces: `PdfEngine.open(file: File, password: String?)`、`render(page: Int, spec: RenderSpec): Bitmap`、`text(page: Int): PdfTextPageModel`、`outline()`、`metadata()`、`close()`。
- `PdfTextPageModel(page, chars: List<PdfCharModel>)`；`PdfCharModel(char, left, top, right, bottom)` 使用归一化 `0..1` 页面坐标。
- `PdfHostBridge` 的旧 JS 方法不再新增调用方；旧实现暂时保留至最终删除计划。

- [ ] **Step 1: 写模型边界和坐标测试**

```kotlin
@Test
fun `text model normalises coordinates and preserves character order`() {
    val model = PdfiumTextMapper.map(page = 7, pageWidth = 200f, pageHeight = 400f, chars = listOf(
        NativeChar("A", 20f, 40f, 30f, 60f),
    ))
    assertEquals(7, model.page)
    assertEquals(0.1f, model.chars[0].left)
    assertEquals(0.1f, model.chars[0].top)
    assertEquals("A", model.chars[0].char)
}
```

增加空文本页、CJK 代理对、负坐标 clamp、密码错误和关闭后调用失败测试。

- [ ] **Step 2: 确认模型测试失败**

Run: `./gradlew :engine:pdf:test --tests '*PdfEngineContractTest'`
Expected: FAIL，模型和 mapper 不存在。

- [ ] **Step 3: 实现中性接口与坐标转换**

```kotlin
data class PdfCharModel(
    val char: String, val left: Float, val top: Float, val right: Float, val bottom: Float,
)
data class PdfTextPageModel(val page: Int, val chars: List<PdfCharModel>) {
    val text: String get() = chars.joinToString("") { it.char }
}

data class NativeChar(
    val char: String, val left: Float, val top: Float, val right: Float, val bottom: Float,
)

object PdfiumTextMapper {
    fun map(page: Int, pageWidth: Float, pageHeight: Float, chars: List<NativeChar>): PdfTextPageModel {
        require(pageWidth > 0f && pageHeight > 0f)
        return PdfTextPageModel(page, chars.map {
            PdfCharModel(it.char,
                (it.left / pageWidth).coerceIn(0f, 1f), (it.top / pageHeight).coerceIn(0f, 1f),
                (it.right / pageWidth).coerceIn(0f, 1f), (it.bottom / pageHeight).coerceIn(0f, 1f))
        })
    }
}
```

`NativeChar` 和 mapper 放在 app 的 `PdfiumTextMapper.kt`，JVM 坐标算法可单独测试且不引用 Android `RectF`。`PdfiumEngine.text` 使用 `openTextPage()`、`textPageGetUnicode`、`textPageGetCharBox`，在 `finally` 关闭文本页；`render` 使用 `renderPageBitmap` 直接写 ARGB_8888。

- [ ] **Step 4: 运行 JVM 和设备契约测试**

Run: `./gradlew :engine:pdf:test && ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.koodoreader.reader.pdfhost.PdfiumCapabilityTest`
Expected: PASS；设备断言文字、坐标和 Bitmap 均非空。

- [ ] **Step 5: 提交**

```bash
git add android/engine/pdf android/app && git commit -m "feat(android): adapt pdfium to native reader model"
```

### Task 3: 直接渲染、有界缓存与现有 PDF 状态迁移

**Files:**
- Create: `android/engine/pdf/src/main/kotlin/com/koodoreader/engine/pdf/PdfPageCache.kt`
- Create: `android/app/src/main/java/com/koodoreader/reader/pdfhost/PdfBitmapCache.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/PdfReaderController.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/NativePdfScreen.kt`
- Test: `android/engine/pdf/src/test/kotlin/com/koodoreader/engine/pdf/PdfPageCacheTest.kt`
- Test: `android/app/src/test/java/com/koodoreader/reader/shell/PdfReaderControllerTest.kt`

**Interfaces:**
- Consumes: Task 2 `PdfEngine`。
- Produces: `PdfBitmapCache.get/page/put/evict`，预算按 `width * height * 4` 字节计算。
- Controller 的公开 UI 状态保持现有字段，替换 `PdfHostBridge` 为 `PdfEngine`。

- [ ] **Step 1: 写缓存和取消测试**

```kotlin
@Test
fun `cache evicts old pages by byte budget`() {
    val cache = PdfPageCache<Int>(maxBytes = 300, sizeOf = { it })
    cache.put(1, 100); cache.put(2, 100); cache.put(3, 200)
    assertNull(cache.get(1))
    assertEquals(200, cache.get(3))
}
```

Controller 测试使用 Fake `PdfEngine`，连续 `goTo(2); goTo(3)` 后只允许第 3 页结果提交；双页仍生成 companion，缩放缓存键包含宽度/缩放/裁边。

- [ ] **Step 2: 确认测试失败**

Run: `./gradlew :engine:pdf:test :app:testDebugUnitTest --tests '*PdfPageCacheTest' --tests '*PdfReaderControllerTest'`
Expected: FAIL，缓存不存在或旧 controller 不符合 Fake 接口。

- [ ] **Step 3: 实现字节预算缓存和渲染代次**

```kotlin
class PdfPageCache<T : Any>(private val maxBytes: Long, private val sizeOf: (T) -> Long) {
    private val values = LinkedHashMap<Int, T>(16, 0.75f, true)
    private var valuesBytes = 0L

    @Synchronized fun put(page: Int, value: T) {
        values.remove(page)?.let { valuesBytes -= sizeOf(it) }
        values[page] = value
        valuesBytes += sizeOf(value)
        while (valuesBytes > maxBytes && values.size > 1) {
            val iterator = values.entries.iterator()
            val eldest = iterator.next()
            valuesBytes -= sizeOf(eldest.value)
            iterator.remove()
        }
    }

    @Synchronized fun get(page: Int): T? = values[page]
    @Synchronized fun remove(page: Int) {
        values.remove(page)?.let { valuesBytes -= sizeOf(it) }
    }
}
```

实现时先修复该骨架的 elder 计算为直接比较 `valuesBytes > maxBytes`，并保证当前页可保留。Controller 用单调 `renderGeneration` 丢弃旧结果，取消协程并关闭离开窗口的 PDFium 页面。只预取当前页 ±1，Bitmap 默认 64 MB 总预算。

- [ ] **Step 4: 验证翻页、缩放、双页、裁边和关闭**

Run: `./gradlew :engine:pdf:test :app:testDebugUnitTest`
Expected: PASS；快速翻页无旧帧覆盖，缓存不超过预算。

- [ ] **Step 5: 提交**

```bash
git add android/engine/pdf android/app && git commit -m "perf(android): render native pdf pages with bounded cache"
```

### Task 4: PDF 文本选择、高亮、笔记与书签

**Files:**
- Create: `android/app/src/main/java/com/koodoreader/reader/pdfhost/PdfSelectionModel.kt`
- Create: `android/app/src/main/java/com/koodoreader/reader/pdfhost/PdfSelectionOverlay.kt`
- Create: `android/app/src/main/java/com/koodoreader/reader/pdfhost/PdfAnnotationRepository.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/NativePdfScreen.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/NotesViewModel.kt`
- Modify: `android/engine/pdf/src/main/kotlin/com/koodoreader/engine/pdf/CfiPdfMapper.kt`
- Test: `android/app/src/test/java/com/koodoreader/reader/pdfhost/PdfSelectionModelTest.kt`
- Test: `android/engine/pdf/src/test/kotlin/com/koodoreader/engine/pdf/PdfAnnotationsTest.kt`

**Interfaces:**
- Consumes: `PdfTextPageModel`、`PdfiumEngine`、`NoteDao`、`BookmarkDao`。
- Produces: `PdfSelection(page, startChar, endChar, text, rects)`。
- Produces: `PdfAnnotationRepository.saveHighlight/saveNote/addBookmark`，`cfi` 使用 `CfiPdfMapper.serialise` 的桌面 JSON。
- `CfiPdfMapper.PdfRange` 扩展为同一选区可序列化多个矩形，并保持旧单矩形 JSON 可读。

- [ ] **Step 1: 写跨行选择和桌面兼容测试**

```kotlin
@Test
fun `dragging backwards normalises selection and joins text`() {
    val selection = PdfSelectionModel.select(page = twoLinePage(), start = charIndex(8), end = charIndex(2))
    assertEquals(2, selection.startChar)
    assertEquals(8, selection.endChar)
    assertEquals("跨行文本", selection.text)
    assertEquals(2, selection.rects.size)
}
```

另测空白页、旋转、缩放、CJK、复制、多色高亮、笔记关联和旧 PDF CFI 单矩形 round-trip。

- [ ] **Step 2: 确认测试失败**

Run: `./gradlew :app:testDebugUnitTest :engine:pdf:test --tests '*PdfSelectionModelTest' --tests '*PdfAnnotationsTest'`
Expected: FAIL，选择模型不存在。

- [ ] **Step 3: 实现选择和持久化**

```kotlin
data class PdfSelection(
    val page: Int, val startChar: Int, val endChar: Int,
    val text: String, val rects: List<CfiPdfMapper.PdfRange>,
)

object PdfSelectionModel {
    fun select(page: PdfTextPageModel, start: Int, end: Int): PdfSelection {
        val from = minOf(start, end).coerceAtLeast(0)
        val to = maxOf(start, end).coerceAtMost(page.chars.lastIndex)
        val chars = page.chars.subList(from, to + 1)
        val rects = chars.groupBy { it.top }.map { (_, line) ->
            CfiPdfMapper.PdfRange("", page.page, line.minOf { it.left }, line.minOf { it.top },
                line.maxOf { it.right } - line.minOf { it.left }, line.maxOf { it.bottom } - line.minOf { it.top })
        }
        return PdfSelection(page.page, from, to, chars.joinToString("") { it.char }, rects)
    }
}
```

生产代码把真实 fingerprint 注入每个 range，序列化 `startChar/endChar` 和文本引用到现有 `range` 列，避免 Room schema 改变。Compose 覆盖层把归一化矩形乘当前显示宽高，拖动手柄只更新选择，不重新渲染底层 Bitmap。

- [ ] **Step 4: 运行选择、Room 和笔记跳转测试**

Run: `./gradlew :app:testDebugUnitTest :engine:pdf:test :engine:annotate:test`
Expected: PASS；从 Notes 点击 PDF 笔记后恢复页号和选区，而非只到页首。

- [ ] **Step 5: 提交**

```bash
git add android/app android/engine/pdf android/engine/annotate && git commit -m "feat(android): add native pdf text annotations"
```

### Task 5: 搜索、目录、密码、重排、OCR 与性能验收

**Files:**
- Modify: `android/app/src/main/java/com/koodoreader/reader/pdfhost/PdfiumEngine.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/PdfReaderController.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/PdfOcrController.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/NativePdfScreen.kt`
- Create: `android/app/src/androidTest/java/com/koodoreader/reader/pdfhost/NativePdfReaderTest.kt`
- Modify: `android/benchmarks/src/main/kotlin/com/koodoreader/benchmarks/OpenEpubBenchmark.kt`（仅共享 trace helper，不改 EPUB 指标）

**Interfaces:**
- Consumes: PDFium `findStart`、bookmark、metadata API 和现有 OCR/Password/Search 组件。
- Produces: `PdfEngine.search(query)`、`reflow(page)`、`metadata()` 和 1000 页打开 trace `openPdf`。

- [ ] **Step 1: 写功能等价和扫描件边界测试**

```kotlin
@Test
fun `scanned page reports no text instead of blank reflow`() {
    val result = PdfReflowPolicy.forText("")
    assertEquals(PdfReflowPolicy.OCR_REQUIRED, result)
}
```

设备测试覆盖密码错误/正确、目录跳转、搜索下一命中、重排、扫描件 OCR、快照和关闭后资源释放；搜索逐页可取消。

- [ ] **Step 2: 运行并确认旧实现不能满足新引擎契约**

Run: `./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.koodoreader.reader.pdfhost.NativePdfReaderTest`
Expected: FAIL，直到所有调用从 `PdfHostBridge` 切换到 `PdfEngine`。

- [ ] **Step 3: 完成 PDFium 功能适配**

```kotlin
fun reflowPage(page: PdfTextPageModel): String =
    PdfReflowPolicy.forText(page.text).let { policy ->
        if (policy == PdfReflowPolicy.OCR_REQUIRED) ""
        else page.chars.joinToString("") { it.char }
    }
```

生产重排按字符坐标聚合行、按原页面顺序输出段落，再用现有 Android 文本组件显示；不使用 WebView。OCR 位图直接来自 `PdfiumEngine.render`，搜索和 OCR 都在 IO/默认调度器上运行且检查取消。

- [ ] **Step 4: 跑完整 PDF 自动化和 1000 页性能测试**

Run: `./gradlew :engine:pdf:test :app:testDebugUnitTest :app:connectedDebugAndroidTest`
Expected: PASS。
Run: `scripts/ci-macro-benchmark.sh`
Expected: 1000 页 PDF 打开 ≤ 1.5 秒，保存 `openPdf` trace 和内存原始结果。

- [ ] **Step 5: 提交**

```bash
git add android/app android/engine/pdf android/benchmarks && git commit -m "feat(android): complete native pdf reader feature parity"
```
