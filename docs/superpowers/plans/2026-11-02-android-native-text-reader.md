# Android Native Text Reader Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将文本阅读主链路改为 Kotlin/Canvas 的章节级增量加载，支持常见可重排格式、分页/滚动、稳定位置、标注和可取消全文搜索。

**Architecture:** `ChapterLoader` 只提供目录和目标章节，`NativeTextSession` 持有当前布局窗口，`PageRenderer` 共享分页与滚动布局结果。格式解析器输出统一章节模型；CFI 始终指向原始文本而非净化后的临时文本。

**Tech Stack:** Kotlin、Compose Canvas、现有 `engine:layout/text/mobi/fb2/docx/htmlbook/toc/annotate`、JUnit 5。

## Global Constraints

- 禁止 TXT 整本 `readBytes()` 和 EPUB 打开时读取全部 Spine 章节。
- 支持常见可重排 EPUB；固定版式、EPUB JavaScript、DRM 明确拒绝或降级，不显示空白页。
- 字号、字体、行距、边距、栏数、主题和视口变化不得触发整书分页。
- 文本标注使用章节 + 原始起止字符偏移 + 桌面兼容 CFI。
- 300 章 EPUB 首帧 ≤ 1.2 秒、翻页 P90 < 50 ms、10 万字峰值 < 350 MB；无设备结果不算最终完成。

## File Map

- `android/engine/layout/src/main/kotlin/com/koodoreader/engine/layout/reader/`：统一 `BookReader`、`ChapterLoader`、`PageRenderer` 和位置模型。
- `android/engine/text/src/main/kotlin/com/koodoreader/engine/text/StreamingTextIndex.kt`：TXT/MD 分块扫描、章节索引和窗口读取。
- `android/core/importer/src/main/kotlin/com/koodoreader/core/importer/EpubChapterLoader.kt`：EPUB 元数据/Spine/目标章加载与资源解析。
- `android/engine/layout/src/main/kotlin/com/koodoreader/engine/layout/css/`：常用 CSS 解析、级联和资源样式。
- `android/app/src/main/java/com/koodoreader/reader/reader/`：文本会话协调、分页/滚动 UI、标注、搜索和生命周期。
- 现有格式 Session 转为 `ChapterLoader` 适配器，不再承担整书分页。

---

### Task 1: 定义增量阅读契约和布局窗口

**Files:**
- Create: `android/engine/layout/src/main/kotlin/com/koodoreader/engine/layout/reader/BookReader.kt`
- Create: `android/engine/layout/src/main/kotlin/com/koodoreader/engine/layout/reader/ChapterLoader.kt`
- Create: `android/engine/layout/src/main/kotlin/com/koodoreader/engine/layout/reader/PageRenderer.kt`
- Create: `android/engine/layout/src/main/kotlin/com/koodoreader/engine/layout/reader/NativeTextSession.kt`
- Test: `android/engine/layout/src/test/kotlin/com/koodoreader/engine/layout/reader/NativeTextSessionTest.kt`

**Interfaces:**
- Produces: `TextLocation(chapterId: String, start: Int, end: Int)`、`ChapterDescriptor(id, title, level, charCount)`。
- Produces: `ChapterLoader.descriptors(): List<ChapterDescriptor>`、`load(id: String): SpineItem`、`close()`。
- Produces: `NativeTextSession.openAt(location)`、`next()`、`previous()`、`current: ReaderFrame`；`ReaderFrame` 包含当前 `LayoutResult` 和位置，不暴露整书页数。

- [ ] **Step 1: 写失败测试，证明打开首章不读取其他章**

```kotlin
@Test
fun `opening first chapter does not load the rest`() {
    val loader = CountingLoader(chapters = 300)
    val session = NativeTextSession(loader, TextMeasurers.mono(), PaginatorOptions())
    session.openAt(TextLocation("chapter-1", 0, 0), 400f, 700f)
    assertEquals(listOf("chapter-1"), loader.loaded)
}
```

`CountingLoader` 在测试文件实现固定 300 个描述符，每次 `load` 记录 ID 并返回只有一段文字的 `SpineItem`。

- [ ] **Step 2: 确认契约测试失败**

Run: `./gradlew :engine:layout:test --tests '*NativeTextSessionTest'`
Expected: FAIL，增量类型不存在。

- [ ] **Step 3: 实现最小会话和布局窗口**

```kotlin
data class TextLocation(val chapterId: String, val start: Int, val end: Int = start)
data class ReaderFrame(val chapterId: String, val layout: LayoutResult, val location: TextLocation)

class NativeTextSession(
    private val loader: ChapterLoader,
    private val measurer: TextMeasurer,
    private val options: PaginatorOptions,
) : AutoCloseable {
    lateinit var current: ReaderFrame
        private set

    fun openAt(location: TextLocation, width: Float, height: Float) {
        val chapter = loader.load(location.chapterId)
        val document = EpubDocument(listOf(chapter))
        val tokens = LayoutTokens(
            viewportWidthPx = width,
            viewportHeightPx = height,
            marginHorizontalPx = options.marginHorizontalPx,
            marginVerticalPx = options.marginVerticalPx,
            fontSizePx = options.fontSizePx,
            lineHeightMultiple = options.lineHeightMultiple,
        )
        val layout = LayoutEngine(measurer, options).layout(document, tokens)
        current = ReaderFrame(location.chapterId, layout, location)
    }

    override fun close() = loader.close()
}
```

页内导航通过 `LayoutResult.pages`；跨章时按 descriptor 顺序加载相邻章。布局窗口上限为当前章，只有跨章动画需要时才短暂保留相邻章，完成后释放旧结果。

- [ ] **Step 4: 验证跨章、缓存释放和非法位置**

Run: `./gradlew :engine:layout:test --tests '*NativeTextSessionTest'`
Expected: PASS；跳到最后章只加载目标章，不存在全局 `pageCount` 依赖。

- [ ] **Step 5: 提交**

```bash
git add android/engine/layout && git commit -m "refactor(android): add incremental text reader session"
```

### Task 2: TXT/MD 流式章节索引与窗口解码

**Files:**
- Create: `android/engine/text/src/main/kotlin/com/koodoreader/engine/text/StreamingTextIndex.kt`
- Create: `android/engine/text/src/main/kotlin/com/koodoreader/engine/text/WindowedTextDecoder.kt`
- Create: `android/engine/text/src/main/kotlin/com/koodoreader/engine/text/TextChapterLoader.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/epubhost/TextBookSession.kt`
- Test: `android/engine/text/src/test/kotlin/com/koodoreader/engine/text/StreamingTextIndexTest.kt`

**Interfaces:**
- Consumes: `TextSource.read(offset, len)`、`CharsetDetector.detect(source)`。
- Produces: `TextChapterSpan(id, title, byteStart, byteEnd, charStart, charCount)`。
- Produces: `TextChapterLoader(source, charset, index): ChapterLoader`，按字节范围读取并解码目标章。

- [ ] **Step 1: 写失败测试，使用超大稀疏源限制读取量**

```kotlin
@Test
fun `loading one chapter reads only its byte range`() {
    val source = RecordingSource("Preface\n\n第一章 开始\n正文A\n\n第二章 后续\n正文B")
    val loader = TextChapterLoader.open(source)
    loader.load(loader.descriptors()[1].id)
    assertTrue(source.bytesRead < source.length)
}
```

另测 UTF-8/GBK/GB2312/Big5、多字节字符跨 64 KiB 窗口、无标题长文本、MD 标题、EOF 和非法截断；不得调用 `readAll()`。

- [ ] **Step 2: 确认测试失败**

Run: `./gradlew :engine:text:test --tests '*StreamingTextIndexTest'`
Expected: FAIL，流式索引不存在。

- [ ] **Step 3: 实现有界扫描和边界续字节处理**

```kotlin
class WindowedTextDecoder(private val charset: Charset) {
    fun decodeWindow(source: TextSource, start: Long, end: Long): String {
        require(start >= 0 && end >= start && end <= source.length)
        val bytes = source.read(start, (end - start).toInt())
        return String(bytes, charset)
    }
}
```

扫描器以 64 KiB 块读取，保留末尾不完整的多字节序列到下一窗口；章节标题匹配复用 `ChapterSplitter` 的规则，但索引只保存行/标题和字节范围，不保存整本文本。无标题文件每 5000 个原始字符建立合成块，同时记录原始字节位置；支持用户标题正则和手动目录锚点。加载器仅解码请求章节范围，单章超过 200 KB 时按块预加载，禁止一次解码整章。

- [ ] **Step 4: 运行文本模块与性能保护测试**

Run: `./gradlew :engine:text:test`
Expected: PASS；`RecordingSource` 断言单章加载读取字节数小于整本且不超过章节窗口加解码边界。

- [ ] **Step 5: 提交**

```bash
git add android/engine/text android/app/src/main/java/com/koodoreader/reader/epubhost/TextBookSession.kt && git commit -m "fix(android): stream large text books by chapter"
```

### Task 3: EPUB 按需章节、资源与固定版式检测

**Files:**
- Create: `android/core/importer/src/main/kotlin/com/koodoreader/core/importer/EpubChapterLoader.kt`
- Create: `android/core/importer/src/main/kotlin/com/koodoreader/core/importer/EpubResourceLoader.kt`
- Modify: `android/core/importer/src/main/kotlin/com/koodoreader/core/importer/EpubSpine.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/epubhost/EpubBookSession.kt`
- Test: `android/core/importer/src/test/kotlin/com/koodoreader/core/importer/EpubChapterLoaderTest.kt`

**Interfaces:**
- Produces: `EpubChapterLoader.open(file): ChapterLoader`，打开只读 `container.xml`、OPF、NAV/NCX。
- Produces: `EpubResourceLoader.read(chapterHref, src): ByteArray?`，统一规范相对路径并拒绝逃逸。
- Produces: `EpubLayoutKind.REFLOWABLE` / `FIXED_LAYOUT`。

- [ ] **Step 1: 写失败测试**

```kotlin
@Test
fun `open reads metadata but not spine bodies`() {
    val archive = CountingEpubArchive(chapters = 300)
    val loader = EpubChapterLoader.open(archive)
    assertEquals(300, loader.descriptors().size)
    assertEquals(0, archive.chapterReads)
}

@Test
fun `resource path cannot escape package`() {
    assertNull(EpubResourceLoader.resolve("OEBPS/ch1.xhtml", "../../outside.png"))
}
```

固定版式 OPF (`rendition:layout pre-paginated`) 必须在测试中返回 `FIXED_LAYOUT`；图片、字体和 CSS 的相对路径有独立用例。

- [ ] **Step 2: 确认测试失败**

Run: `./gradlew :core:importer:test --tests '*EpubChapterLoaderTest'`
Expected: FAIL，按需加载器不存在。

- [ ] **Step 3: 实现打开期与阅读期分离**

```kotlin
object EpubResourceLoader {
    fun resolve(chapterHref: String, src: String): String? {
        if (src.startsWith('/') || src.contains(Regex("(^|/)\\.\\.(/|$)"))) return null
        val base = chapterHref.substringBeforeLast('/', "")
        val parts = (if (base.isEmpty()) src else "$base/$src").split('/').filter { it.isNotEmpty() && it != "." }
        return parts.joinToString("/")
    }
}
```

`EpubChapterLoader` 持有归档，只读取 OPF 元数据、manifest、spine 和目录；`load(id)` 才读取对应 XHTML。固定版式在导入元数据阶段拒绝，已有记录在打开阶段返回显式错误。

- [ ] **Step 4: 验证目录、章节、资源与旧 EPUB 测试**

Run: `./gradlew :core:importer:test`
Expected: PASS；300 章 fixture 的 `open` 章节正文读取数为 0。

- [ ] **Step 5: 提交**

```bash
git add android/core/importer android/app/src/main/java/com/koodoreader/reader/epubhost/EpubBookSession.kt && git commit -m "fix(android): load epub chapters on demand"
```

### Task 4: 常用 CSS 级联与样式化块模型

**Files:**
- Create: `android/engine/layout/src/main/kotlin/com/koodoreader/engine/layout/css/CssRule.kt`
- Create: `android/engine/layout/src/main/kotlin/com/koodoreader/engine/layout/css/CssParser.kt`
- Create: `android/engine/layout/src/main/kotlin/com/koodoreader/engine/layout/css/CssCascade.kt`
- Modify: `android/engine/layout/src/main/kotlin/com/koodoreader/engine/layout/BlockModel.kt`
- Modify: `android/engine/layout/src/main/kotlin/com/koodoreader/engine/layout/HtmlFlattener.kt`
- Test: `android/engine/layout/src/test/kotlin/com/koodoreader/engine/layout/css/CssCascadeTest.kt`

**Interfaces:**
- Produces: `CssParser.parse(text: String): List<CssRule>`；不支持的单条规则被跳过。
- Produces: `CssCascade.styleFor(element: CssElement, rules: List<CssRule>): ParagraphStyle`。
- `CssElement` 包含标签、id、class、父链和 inline declaration。

- [ ] **Step 1: 写级联、失败恢复和资源样式测试**

```kotlin
@Test
fun `inline style overrides external rule`() {
    val rules = CssParser.parse("p { font-size: 18px; color: #111; }")
    val element = CssElement(tag = "p", inline = "font-size: 22px")
    assertEquals(22f, CssCascade.styleFor(element, rules).fontSizePx)
}

@Test
fun `bad rule does not hide following valid rule`() {
    val rules = CssParser.parse("p { ??? } h1 { text-align: center; }")
    assertEquals(1, rules.size)
}
```

覆盖标签/class/id/后代选择器、字体、行距、边距、颜色、背景、对齐、缩进、`display:none` 和图片最大宽度。

- [ ] **Step 2: 确认测试失败**

Run: `./gradlew :engine:layout:test --tests '*CssCascadeTest'`
Expected: FAIL，CSS 类型不存在。

- [ ] **Step 3: 实现最小解析和确定性级联**

```kotlin
data class CssRule(val selector: String, val declarations: Map<String, String>)

object CssParser {
    fun parse(text: String): List<CssRule> = buildList {
        val pattern = Regex("([^{}]+)\\{([^{}]*)\\}")
        for (match in pattern.findAll(text)) {
            val declarations = match.groupValues[2].split(';').mapNotNull { declaration ->
                val colon = declaration.indexOf(':').takeIf { it > 0 } ?: return@mapNotNull null
                declaration.substring(0, colon).trim().lowercase() to declaration.substring(colon + 1).trim()
            }.toMap()
            if (declarations.isNotEmpty()) add(CssRule(match.groupValues[1].trim(), declarations))
        }
    }
}
```

`CssCascade` 按 inline > id > class > 标签及同优先级后出现规则合并。`HtmlFlattener` 将有效计算样式写入 `TextBlock.style`，未知属性不进入模型。

- [ ] **Step 4: 运行 CSS 和排版回归**

Run: `./gradlew :engine:layout:test`
Expected: PASS；原有文本与 CFI golden vector 不因未样式化 fixture 改变。

- [ ] **Step 5: 提交**

```bash
git add android/engine/layout && git commit -m "feat(android): apply common epub css styles natively"
```

### Task 5: 其余文本格式接入 ChapterLoader

**Files:**
- Create: `android/app/src/main/java/com/koodoreader/reader/reader/MobiChapterLoader.kt`
- Create: `android/app/src/main/java/com/koodoreader/reader/reader/Fb2ChapterLoader.kt`
- Create: `android/app/src/main/java/com/koodoreader/reader/reader/DocxChapterLoader.kt`
- Create: `android/app/src/main/java/com/koodoreader/reader/reader/WebChapterLoader.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/epubhost/MobiBookSession.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/epubhost/Fb2BookSession.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/epubhost/DocxBookSession.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/epubhost/WebBookSession.kt`
- Test: `android/app/src/test/java/com/koodoreader/reader/reader/ChapterLoaderAdaptersTest.kt`

**Interfaces:**
- Consumes: Task 1 `ChapterLoader` 和现有格式解析器。
- Produces: `FormatChapterLoaders.open(format: String, file: File): ChapterLoader`，拒绝 DRM/未知格式并抛可读异常。

- [ ] **Step 1: 写适配器契约测试**

```kotlin
@Test
fun `mobi adapter exposes stable chapter ids and readable body`() {
    val book = parsedMobiFixture()
    val loader = MobiChapterLoader(book)
    val chapter = loader.load(loader.descriptors().first().id)
    assertTrue(chapter.blocks.any { it.text.contains("正文") })
}
```

MOBI 以目录/标题边界拆章；无目录时按 20,000 原始字符产生合成章，避免整书成为一个布局单元。FB2 按 body/section，DOCX 按标题和长度边界，HTML/MHTML 按标题和长度边界。所有 ID 基于原始顺序，不能因净化文本改变。

- [ ] **Step 2: 确认测试失败**

Run: `./gradlew :app:testDebugUnitTest --tests '*ChapterLoaderAdaptersTest'`
Expected: FAIL，适配器不存在。

- [ ] **Step 3: 实现统一分派**

```kotlin
object FormatChapterLoaders {
    fun open(format: String, file: File): ChapterLoader = when (format.lowercase()) {
        "mobi", "azw", "azw3" -> MobiChapterLoader(MobiParser.parse(file) ?: throw IllegalArgumentException("Cannot parse MOBI"))
        "fb2" -> Fb2ChapterLoader(Fb2Document.fromFile(file))
        "docx" -> DocxChapterLoader(DocxDocument.fromFile(file))
        "html", "htm", "xhtml", "xml", "mhtml" -> WebChapterLoader.open(file)
        else -> throw IllegalArgumentException("Unsupported text format: $format")
    }
}
```

可解码但样式不完整时保留线性 `TextBlock`；DRM 继续传播明确异常。不得在适配器内调用 `PagedDocumentSession.create` 整书分页。

- [ ] **Step 4: 跑所有格式解析和适配器测试**

Run: `./gradlew :app:testDebugUnitTest :engine:mobi:test :engine:fb2:test :engine:docx:test :engine:htmlbook:test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add android/app android/engine/mobi android/engine/fb2 android/engine/docx android/engine/htmlbook && git commit -m "refactor(android): adapt document formats to chapter loaders"
```

### Task 6: 分页/滚动 UI、稳定标注与增量搜索

**Files:**
- Create: `android/app/src/main/java/com/koodoreader/reader/reader/TextReaderViewModel.kt`
- Create: `android/app/src/main/java/com/koodoreader/reader/reader/TextPageView.kt`
- Create: `android/app/src/main/java/com/koodoreader/reader/reader/TextScrollView.kt`
- Create: `android/app/src/main/java/com/koodoreader/reader/reader/AnnotationStore.kt`
- Create: `android/app/src/main/java/com/koodoreader/reader/reader/IncrementalBookSearch.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/epubhost/NativeEpubScreen.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/ReaderProgressPrefs.kt`
- Test: `android/app/src/test/java/com/koodoreader/reader/reader/TextReaderViewModelTest.kt`
- Test: `android/app/src/test/java/com/koodoreader/reader/reader/IncrementalBookSearchTest.kt`

**Interfaces:**
- Consumes: `NativeTextSession`、`ReaderFrame`、`TextLocation`、Room DAO。
- Produces: `AnnotationStore.saveHighlight(bookKey: String, location: TextLocation, color: Long)`、`saveNote(bookKey: String, location: TextLocation, text: String, color: Long)`、`addBookmark(bookKey: String, location: TextLocation, label: String)`；存储使用现有 Note/Bookmark schema。
- Produces: `IncrementalBookSearch.search(query: String, loader: ChapterLoader): Flow<TextSearchHit>`，每次只持有一章文本；`TextSearchHit(chapterId: String, start: Int, length: Int, excerpt: String)` 是文本搜索专用模型，不与 `engine:toc.SearchHit` 混用。

- [ ] **Step 1: 写失败测试**

```kotlin
@Test
fun `font change repaginates only current chapter and restores anchor`() = runTest {
    val vm = TextReaderViewModel(CountingLoader(300), FakeAnnotationStore())
    vm.open(TextLocation("chapter-1", 10))
    vm.setFontScale(1.4f)
    assertEquals(listOf("chapter-1", "chapter-1"), vm.loader.loaded)
    assertEquals(10, vm.currentLocation.start)
}

@Test
fun `search stops loading chapters after cancellation`() = runTest {
    val loader = CountingLoader(300)
    val job = launch { IncrementalBookSearch.search("needle", loader).collect {} }
    job.cancelAndJoin()
    assertTrue(loader.loaded.size < 300)
}
```

另测多色高亮、笔记关联、书签增删跳转、分页/滚动共用位置、退出恢复和文本净化前后的原始偏移。

- [ ] **Step 2: 确认测试失败**

Run: `./gradlew :app:testDebugUnitTest --tests '*TextReaderViewModelTest' --tests '*IncrementalBookSearchTest'`
Expected: FAIL，新协调器不存在。

- [ ] **Step 3: 实现锚点保存和可取消搜索**

```kotlin
interface AnnotationStore {
    fun saveHighlight(bookKey: String, location: TextLocation, color: Long)
    fun saveNote(bookKey: String, location: TextLocation, text: String, color: Long)
    fun addBookmark(bookKey: String, location: TextLocation, label: String)
}

data class TextSearchHit(val chapterId: String, val start: Int, val length: Int, val excerpt: String)

object IncrementalBookSearch {
    fun search(query: String, loader: ChapterLoader): Flow<TextSearchHit> = flow {
        for (chapter in loader.descriptors()) {
            kotlin.coroutines.coroutineContext.ensureActive()
            val text = loader.load(chapter.id).blocks.joinToString("\n") { it.text }
            var offset = text.indexOf(query, ignoreCase = true)
            while (offset >= 0) {
                val excerpt = text.substring((offset - 40).coerceAtLeast(0), (offset + query.length + 40).coerceAtMost(text.length))
                emit(TextSearchHit(chapter.id, offset, query.length, excerpt))
                offset = text.indexOf(query, offset + query.length, ignoreCase = true)
            }
        }
    }
}
```

`kotlin.coroutines.coroutineContext.ensureActive()` 使用 `kotlinx.coroutines.ensureActive` 扩展。ViewModel 在布局参数变化时先保存原始 `TextLocation`，再只重建当前章。`TextPageView` 使用 Canvas 当前页；`TextScrollView` 使用同一 `LayoutResult.lines` 和 Lazy 列表；两种模式都通过相同命中测试生成起止位置。

- [ ] **Step 4: 运行 UI 逻辑、标注和搜索回归**

Run: `./gradlew :app:testDebugUnitTest :engine:annotate:test :engine:toc:test`
Expected: PASS；无 CFI 时显示位置不可用，不静默跳到第 0 页。

- [ ] **Step 5: 提交**

```bash
git add android/app android/engine/annotate android/engine/toc && git commit -m "feat(android): unify paged and scrolling native text reading"
```

### Task 7: 文本真实样本与性能门禁

**Files:**
- Modify: `android/benchmarks/src/main/kotlin/com/koodoreader/benchmarks/OpenEpubBenchmark.kt`
- Modify: `android/benchmarks/src/main/kotlin/com/koodoreader/benchmarks/PageTurnBenchmark.kt`
- Modify: `android/benchmarks/src/main/kotlin/com/koodoreader/benchmarks/MemoryPeakBenchmark.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/reader/TextReaderViewModel.kt`
- Create: `android/app/src/androidTest/java/com/koodoreader/reader/reader/NativeTextReaderTest.kt`

- [ ] **Step 1: 添加会失败的打开追踪断言**

```kotlin
@Test
fun openEpubFirstFrame() {
    benchmarkRule.measureRepeated(
        packageName = BenchmarkTargets.PACKAGE,
        metrics = listOf(TraceSectionMetric("openBook", TraceSectionMetric.Mode.First)),
        compilationMode = CompilationMode.None(), startupMode = StartupMode.WARM, iterations = 10,
        setupBlock = { pressHome() },
        measureBlock = { startActivityAndWait(); openFixtureBook() },
    )
}
```

删除 `assumeTrue` 跳过逻辑。设备测试打开 300 章样本并断言只出现当前章加载日志。

- [ ] **Step 2: 确认新门禁因缺少 trace 失败**

Run: `./gradlew :benchmarks:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.koodoreader.benchmarks.OpenEpubBenchmark`
Expected: FAIL，`openBook` trace 不存在；无设备时明确记录 NOT RUN，不改回跳过。

- [ ] **Step 3: 加入精确 trace 边界**

```kotlin
android.os.Trace.beginSection("openBook")
try {
    session.openAt(location, width, height)
} finally {
    android.os.Trace.endSection()
}
```

首帧 trace 包括元数据、目标章解析、布局与提交首帧，不包括后续相邻章预取。

- [ ] **Step 4: 运行文本全量测试与设备指标**

Run: `./gradlew :engine:layout:test :engine:text:test :core:importer:test :app:testDebugUnitTest`
Expected: PASS。
Run on device: `scripts/ci-macro-benchmark.sh`
Expected: 首帧 ≤ 1.2 秒、翻页 P90 < 50 ms、内存峰值 < 350 MB；保存原始 JSON。

- [ ] **Step 5: 提交**

```bash
git add android/app android/benchmarks && git commit -m "test(android): enforce native text reader performance"
```
