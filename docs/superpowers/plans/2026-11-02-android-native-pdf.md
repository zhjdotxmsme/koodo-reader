# Android Native PDF Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** ~~用 PDFium 替换 pdf.js~~ **（2026-11-03 修订）** 按 [`docs/android-pdf-poc.md`](../../android-pdf-poc.md) 验收 **pdf.js WebView 主路径**（PdfRenderer 仅快照/打印）；PDFium 因 16KB 页对齐与维护状态 **不纳入** 本仓库。功能目标仍为：文本选择、高亮、笔记、搜索、目录、密码、OCR、重排与 ABI 门禁。

**Architecture:** 主阅读在 **引擎 WebView** 内运行 vendored **pdf.js**（`assets/pdfengine/`，与桌面同源）；`PdfReaderController` / `NativePdfScreen` 为宿主壳。`engine:pdf` 的 `CfiPdfMapper` 继续负责 Room 标注 JSON；**PdfRenderer** 仅可选快照/打印，不替代主渲染。

**Tech Stack:** Kotlin、Compose、pdf.js（Apache-2.0）、现有 loopback `LocalAssetServer`、Room、ML Kit OCR（可选）、JUnit/Android instrumentation。

## Global Constraints

- 主路径 **允许** 专用 PDF WebView；禁止再引入 Pdfium / 新 .so（16KB 页风险，见 POC）。
- 不得静默切到未验收的原生 PDF 后端；PdfRenderer 失败不得冒充主阅读器。
- 保留目录、搜索、密码、裁边、双页、OCR、重排和快照。
- PDF 标注使用指纹、1 基页号、归一化矩形及文本引用，能从笔记跳回原选区。
- 1000 页 PDF 打开 ≤ 1.5 秒；没有设备结果不算最终完成。

## File Map

- `android/app/src/main/assets/pdfengine/`：pdf.js + worker + `engine.mjs`（Apache-2.0，与桌面同源）。
- `android/app/src/main/java/com/koodoreader/reader/LocalAssetServer.kt`：loopback 提供 pdfengine 与书籍 URI。
- `android/app/src/main/java/com/koodoreader/reader/pdfhost/PdfJsHostBridge.kt`：WebView ↔ pdf.js 桥（打开/渲染/文本/搜索）。
- `android/app/src/main/java/com/koodoreader/reader/shell/NativePdfScreen.kt` + `PdfReaderController.kt`：Compose 壳与状态。
- `android/engine/pdf/`：`CfiPdfMapper`、搜索/视口/标注 JVM 模型（**不**新增 Pdfium 适配层）。

---

### Task 1: pdf.js 资产、loopback 与无 .so 门禁

**Files:**
- Verify: `android/app/src/main/assets/pdfengine/`（`pdf.mjs`、`pdf.worker.mjs`、`engine.mjs`、`index.html`）
- Verify: `android/app/src/main/java/com/koodoreader/reader/LocalAssetServer.kt`
- Test: `android/app/src/test/java/com/koodoreader/reader/pdfhost/PdfEngineReadyLatchTest.kt`
- Modify: `docs/android-pdf-poc.md`（结论与 Goal 一致）
- Run: `scripts/check-elf-16kb.js` on native release APK

**Interfaces:**
- Produces: loopback URL 可加载 pdfengine；release APK **不**因 PDF 阅读新增 `.so`。
- 设备验收（非 JVM）：`PdfJsHostBridge` 打开样例 PDF → 文本层可选 → 搜索命中（见 Task 2）。

- [x] **Step 1: 资产与 JVM 就绪测试**

Run: `./gradlew :engine:pdf:test :app:testDebugUnitTest --tests com.koodoreader.reader.pdfhost.PdfEngineReadyLatchTest`
Expected: PASS（pdf.js 路径不依赖 Pdfium）。

- [ ] **Step 2: 组装 native release 并检查 ELF**

Run: `node scripts/build-android.js --target native --release --no-split`
Run: `node scripts/check-elf-16kb.js <apk>`
Expected: PASS；无新增 pdfium `.so`。

- [ ] **Step 3: 设备 smoke（pdf.js）**

在真机打开 ≥100 页 PDF：首屏可见、目录非空、划词有选区；记录到 `docs/android-native-validation-2026-11-02.md`。

- [ ] **Step 4: 提交**

```bash
git add docs/android-pdf-poc.md docs/android-native-validation-2026-11-02.md && git commit -m "docs(android): pdf.js acceptance gates for native pdf"
```

### Task 2: CFI 标注、搜索与 PdfReaderController 契约

**Files:**
- `android/engine/pdf/src/test/kotlin/com/koodoreader/engine/pdf/CfiPdfMapperTest.kt`（已有）
- `android/engine/pdf/src/test/kotlin/com/koodoreader/engine/pdf/PdfSearchEngineTest.kt`（已有）
- `android/app/src/main/java/com/koodoreader/reader/shell/PdfReaderController.kt`
- `android/app/src/main/java/com/koodoreader/reader/pdfhost/PdfJsHostBridge.kt`

**Interfaces:**
- Room `range` JSON 继续经 `CfiPdfMapper`（指纹、1 基页、归一化矩形）。
- 搜索/翻页/密码错误经 `PdfReaderController` + bridge，不得新增 Pdfium 调用方。

- [x] **Step 1: JVM 回归**

Run: `./gradlew :engine:pdf:test`
Expected: PASS（`CfiPdfMapperTest`、`PdfSearchEngineTest` 等）。

- [ ] **Step 2: 设备 — 选择 → 高亮 → 笔记 → 跳回**

在 `NativePdfScreen` 完成一轮并记入 validation 清单。

- [ ] **Step 3: 提交**

```bash
git add android/engine/pdf android/app && git commit -m "test(android): pdf cfi and search regression"
```

> **Note:** 下文 Task 3–5 仍为 **Pdfium 草稿**（位图缓存 / PdfiumEngine）。在 pdf.js 主路径下 **不要实现**；后续迭代应改写为 WebView 缓存与 `PdfJsHostBridge` 行为，或整段删除。

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
