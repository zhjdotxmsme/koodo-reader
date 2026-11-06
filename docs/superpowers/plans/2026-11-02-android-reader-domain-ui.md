# Android Reader Domain and UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有桌面兼容存储之上提供统一领域模型，并补齐书架、阅读设置、选择/标注、目录/搜索及大屏交互。

**Architecture:** `core:reader` 保存纯 Kotlin 领域模型和 mapper；Compose UI 通过 ViewModel + StateFlow 消费。物理存储继续使用现有 Room、私有偏好和统计库，不建立同名新表。

**Tech Stack:** Kotlin、Jetpack Compose/M3、Room、StateFlow、JUnit 5、现有 reader engines。

## Global Constraints

- `bookKey: String` 是唯一持久化身份，不新增 Long 物理主键或同名 Room 表。
- 不迁移/删除旧 WebView 数据，保留已有原生数据。
- 元数据编辑和移动不修改原始 SAF 文件；移动仅处理应用私有副本。
- 正文保持原生渲染；文本位置使用原始章节/偏移，PDF 使用页码和归一化矩形。
- 总页数后台增量计算，未完成显示“计算中”；不得为首屏整书分页。
- 所有 UI 状态经 ViewModel + StateFlow，异步任务有 Loading/Success/Error 和取消。

## File Map

- `android/core/reader/src/main/kotlin/com/koodoreader/core/reader/`：领域模型、解析接口和 mapper。
- `android/app/src/main/java/com/koodoreader/reader/shell/`：书架多选、排序和私有文件移动。
- `android/app/src/main/java/com/koodoreader/reader/reader/`：阅读设置、选择、标注和搜索 UI。
- `android/app/src/main/java/com/koodoreader/reader/shell/ReaderFeatureState.kt`：设置单一事实源。

---

### Task 1: 领域模型、统一解析接口与持久化 mapper

**Files:**
- Create: `android/core/reader/build.gradle`
- Create: `android/core/reader/src/main/kotlin/com/koodoreader/core/reader/ReaderModels.kt`
- Create: `android/core/reader/src/main/kotlin/com/koodoreader/core/reader/ReaderContracts.kt`
- Create: `android/core/reader/src/main/kotlin/com/koodoreader/core/reader/ReaderMappers.kt`
- Modify: `android/settings.gradle`
- Modify: `android/app/build.gradle`
- Test: `android/core/reader/src/test/kotlin/com/koodoreader/core/reader/ReaderMappersTest.kt`

**Interfaces:**
- Produces: `Book`、`Chapter`、`Highlight`、`Note`、`Bookmark`、`ReadingProgress`、`SearchIndexEntry` 纯模型。
- Produces: `BookParser`、`ChapterLoader`、`TextExtractor`、`PageRenderer`、`AnnotationMapper`。
- Produces: `ReaderMappers.toBook(BookEntity)`、`toBookEntity(Book)`；身份始终为 `bookKey`。

- [x] **Step 1: 写失败 mapper 测试**

```kotlin
@Test
fun `book mapper preserves desktop identity and edited metadata`() {
    val entity = BookEntity(key = "native-1", name = "Title", author = "Author", md5 = "md5", path = "/books/native-1.epub")
    val book = ReaderMappers.toBook(entity)
    assertEquals("native-1", book.bookKey)
    assertEquals("Title", ReaderMappers.toBookEntity(book).name)
    assertEquals("md5", ReaderMappers.toBookEntity(book).md5)
}
```

增加文本/PDF 位置、笔记关联、独立笔记、书签和进度 round-trip；不存在的历史时间保持可空，不伪造创建时间。

- [ ] **Step 2: 确认失败**

Run: `./gradlew :core:reader:test --tests '*ReaderMappersTest'`
Expected: FAIL，模块/模型不存在。

- [ ] **Step 3: 实现核心模型和接口**

```kotlin
data class Book(
    val bookKey: String, val title: String, val author: String?, val filePath: String,
    val format: String, val coverPath: String?, val fileSize: Long,
    val lastReadTime: Long?, val isDeleted: Boolean, val createdAt: Long?, val updatedAt: Long?,
)

data class TextAnchor(val chapterId: String?, val chapterHref: String?, val startOffset: Int, val endOffset: Int)
data class PdfAnchor(val page: Int, val rectJson: String)

interface BookParser { fun parse(file: java.io.File): ParsedBook }
interface ChapterLoader { fun descriptors(): List<Chapter>; fun load(id: String): ChapterContent }
interface TextExtractor { fun extract(chapter: ChapterContent): TextContent }
interface PageRenderer { fun render(content: ChapterContent, settings: ReaderLayout): ReaderPage }
interface AnnotationMapper { fun toStored(bookKey: String, anchor: TextAnchor): StoredAnchor }
```

`ParsedBook`、`ChapterContent`、`TextContent`、`ReaderLayout`、`ReaderPage`、`StoredAnchor` 在同模块按引擎现有字段定义，接口不包含 Android/Room 类型。`ReaderMappers` 单独负责实体转换。

- [ ] **Step 4: 运行模型和现有数据回归**

Run: `./gradlew :core:reader:test :app:testDebugUnitTest :core:dbio:test`
Expected: PASS；schema 守卫无变化。

- [ ] **Step 5: 提交**

```bash
git add android/core/reader android/settings.gradle android/app/build.gradle && git commit -m "feat(android): add reader domain contracts"
```

### Task 2: 书架多选、排序、进度与私有文件移动

**Files:**
- Create: `android/app/src/main/java/com/koodoreader/reader/shell/LibrarySelection.kt`
- Create: `android/app/src/main/java/com/koodoreader/reader/shell/BookFileMover.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/LibraryLogic.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/LibraryViewModel.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/LibraryScreen.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/BookCard.kt`
- Test: `android/app/src/test/java/com/koodoreader/reader/shell/LibrarySelectionTest.kt`
- Test: `android/app/src/test/java/com/koodoreader/reader/shell/BookFileMoverTest.kt`

**Interfaces:**
- Produces: `LibrarySelection.toggle/selectAll/clear` 和 `BookFileMover.move(book, targetDir): BookEntity`。
- 复用导入计划的元数据编辑和最终收口计划的最近阅读存储。

- [ ] **Step 1: 写失败测试**

```kotlin
@Test
fun `multi delete only affects selected books`() {
    val state = LibrarySelection().toggle("a").toggle("b")
    assertEquals(setOf("a", "b"), state.selected)
}

@Test
fun `move updates only private path and preserves book identity`() {
    val moved = BookFileMover.move(book(key = "k", path = oldFile.path), targetDir)
    assertEquals("k", moved.key)
    assertEquals(targetDir.resolve("k.epub").path, moved.path)
}
```

覆盖书名/作者/格式/大小/最近阅读排序、未读、默认封面、封面失败、移动冲突回滚和原 SAF 文件不变。

- [ ] **Step 2: 确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests '*LibrarySelectionTest' --tests '*BookFileMoverTest'`
Expected: FAIL，新类型不存在。

- [ ] **Step 3: 实现选择状态与原子移动**

```kotlin
data class LibrarySelection(val selected: Set<String> = emptySet()) {
    fun toggle(key: String) = copy(selected = selected.toMutableSet().apply { if (!add(key)) remove(key) })
    fun clear() = LibrarySelection()
}

object BookFileMover {
    fun move(book: BookEntity, targetDir: File): BookEntity {
        val source = File(book.path ?: throw IllegalArgumentException("Book path missing"))
        val target = File(targetDir, source.name)
        require(!target.exists()) { "Target exists" }
        require(targetDir.isDirectory || targetDir.mkdirs()) { "Cannot create target" }
        if (!source.renameTo(target)) throw IOException("Cannot move book")
        return book.copy(path = target.path)
    }
}
```

移动前用 `ReaderFiles.resolveBookFile` 验证来源位于应用书籍目录；Room 更新失败时移回原路径。UI 增加多选工具栏、排序项、进度和最近时间；加载骨架使用 Compose 动画，不新增库。

- [ ] **Step 4: 运行书架与备份回归**

Run: `./gradlew :app:testDebugUnitTest :core:dbio:test`
Expected: PASS；删除/移动后备份路径与阅读文件解析一致。

- [ ] **Step 5: 提交**

```bash
git add android/app && git commit -m "feat(android): complete library book management"
```

### Task 3: 阅读设置、沉浸式和位置恢复

**Files:**
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/ReaderFeatureState.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/ReaderFeaturePrefs.kt`
- Create: `android/app/src/main/java/com/koodoreader/reader/reader/ReaderWindowController.kt`
- Create: `android/app/src/main/java/com/koodoreader/reader/reader/ReaderSettingsSheet.kt`
- Create: `android/app/src/main/java/com/koodoreader/reader/reader/ReaderProgressModel.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/epubhost/NativeEpubScreen.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/NativePdfScreen.kt`
- Test: `android/app/src/test/java/com/koodoreader/reader/shell/ReaderFeatureStateTest.kt`
- Test: `android/app/src/test/java/com/koodoreader/reader/reader/ReaderProgressModelTest.kt`

**Interfaces:**
- Produces: 新增设置 `paragraphSpacingPx`、`brightness`、`keepScreenOn`、`orientation`、`customBackgroundArgb`。
- Produces: `ReaderProgressModel.remainingMinutes(charsRemaining, charsPerMinute)` 和页数 Loading 状态。

- [ ] **Step 1: 写设置与进度失败测试**

```kotlin
@Test
fun `new reader settings survive codec round trip`() {
    val state = ReaderFeatureState(paragraphSpacingPx = 12f, brightness = 0.4f, keepScreenOn = true, orientation = "landscape")
    assertEquals(state, ReaderFeatureCodec.fromMap(ReaderFeatureCodec.toMap(state)))
}

@Test
fun `unknown total pages stays pending instead of zero`() {
    assertEquals(PageCountState.Pending, ReaderProgressModel.pageState(null))
}
```

覆盖分页/滚动、深灰夜间、护眼、自定义背景、横竖屏、位置恢复和剩余时间边界。

- [ ] **Step 2: 确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests '*ReaderFeatureStateTest' --tests '*ReaderProgressModelTest'`
Expected: FAIL，新字段/进度状态不存在。

- [ ] **Step 3: 实现设置和窗口控制**

```kotlin
fun ReaderProgressModel.remainingMinutes(charsRemaining: Int, charsPerMinute: Int): Int? =
    if (charsRemaining < 0 || charsPerMinute <= 0) null
    else ((charsRemaining + charsPerMinute - 1) / charsPerMinute).coerceAtLeast(0)

fun ReaderWindowController.apply(activity: Activity, state: ReaderFeatureState) {
    activity.window.attributes = activity.window.attributes.apply { screenBrightness = state.brightness.coerceIn(0f, 1f) }
    activity.window.decorView.keepScreenOn = state.keepScreenOn
}
```

设置编解码将新增字段加入白名单和备份映射。BottomSheet 实时更新 StateFlow；窗口控制器在设置变化和 onDispose 时应用/恢复。总页数使用 `PageCountState.Pending/Ready(value)`，不触发整书同步分页。

- [ ] **Step 4: 运行设置、备份和阅读回归**

Run: `./gradlew :app:testDebugUnitTest :core:dbio:test`
Expected: PASS；新增设置重启和备份恢复后一致。

- [ ] **Step 5: 提交**

```bash
git add android/app android/core/dbio && git commit -m "feat(android): persist complete reader presentation settings"
```

### Task 4: 选择手柄、标注 CRUD、目录与搜索状态

**Files:**
- Create: `android/app/src/main/java/com/koodoreader/reader/reader/SelectionController.kt`
- Create: `android/app/src/main/java/com/koodoreader/reader/reader/BookAnnotationsViewModel.kt`
- Create: `android/app/src/main/java/com/koodoreader/reader/reader/BookAnnotationsScreen.kt`
- Create: `android/app/src/main/java/com/koodoreader/reader/reader/TocViewModel.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/reader/TextPageView.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/reader/IncrementalBookSearch.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/NotesScreen.kt`
- Test: `android/app/src/test/java/com/koodoreader/reader/reader/SelectionControllerTest.kt`
- Test: `android/app/src/test/java/com/koodoreader/reader/reader/BookAnnotationsViewModelTest.kt`
- Test: `android/app/src/test/java/com/koodoreader/reader/reader/TocViewModelTest.kt`

**Interfaces:**
- Consumes: 文本计划 `TextLocation`、`AnnotationStore`、增量搜索；PDF 计划 `PdfSelection`。
- Produces: `SelectionController.start/moveStart/moveEnd/finish` 和标注 CRUD StateFlow。

- [ ] **Step 1: 写失败交互与 CRUD 测试**

```kotlin
@Test
fun `backward drag normalises anchors without losing source offsets`() {
    val state = SelectionController().start(10).moveEnd(3).finish()
    assertEquals(3, state.start)
    assertEquals(10, state.end)
}

@Test
fun `note edit preserves highlight link and created time`() = runTest {
    val updated = annotations.editNote(noteKey = "n", content = "updated")
    assertEquals("h", updated.highlightKey)
    assertEquals(createdAt, updated.createdAt)
}
```

覆盖四色改色/删除、独立笔记、关联笔记、书签增删跳转、目录筛选/当前章、搜索命中次数/取消/失败重试。

- [ ] **Step 2: 确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests '*SelectionControllerTest' --tests '*BookAnnotationsViewModelTest' --tests '*TocViewModelTest'`
Expected: FAIL，控制器不存在。

- [ ] **Step 3: 实现选择状态与本书标注仓库**

```kotlin
data class SelectionState(val start: Int = -1, val end: Int = -1, val active: Boolean = false)

class SelectionController {
    var state = SelectionState(); private set
    fun start(offset: Int) = apply { state = SelectionState(offset, offset, true) }
    fun moveEnd(offset: Int) = apply { state = state.copy(end = offset) }
    fun finish(): SelectionState {
        val current = state
        return current.copy(start = minOf(current.start, current.end), end = maxOf(current.start, current.end), active = false)
    }
}
```

ViewModel 调现有 DAO，更新后重新观察 StateFlow；不新建平行数据库。标注页和搜索页提供加载、空、错误重试；选中后菜单为复制/高亮/笔记/搜索，已有高亮菜单为改色/删除/关联笔记。

- [ ] **Step 4: 运行标注、搜索和导出回归**

Run: `./gradlew :app:testDebugUnitTest :engine:annotate:test :engine:toc:test :core:dbio:test`
Expected: PASS；JSON/MD 导出保留回跳位置。

- [ ] **Step 5: 提交**

```bash
git add android/app android/engine/annotate android/engine/toc android/core/dbio && git commit -m "feat(android): complete annotation and search interactions"
```

### Task 5: 响应式布局、加载状态与 Compose 设备验收

**Files:**
- Create: `android/app/src/main/java/com/koodoreader/reader/shell/ReaderWindowSize.kt`
- Create: `android/app/src/main/java/com/koodoreader/reader/reader/AdaptiveReaderLayout.kt`
- Create: `android/app/src/main/java/com/koodoreader/reader/shell/LibraryLoadingSkeleton.kt`
- Create: `android/app/src/androidTest/java/com/koodoreader/reader/reader/AdaptiveReaderLayoutTest.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/LibraryScreen.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/epubhost/NativeEpubScreen.kt`

**Interfaces:**
- Produces: `ReaderWindowSize.fromWidth(widthDp): COMPACT/EXPANDED`，600 dp 为分界。
- Produces: 大屏双页和常驻目录/标注侧栏，手机单栏。

- [ ] **Step 1: 写失败布局测试**

```kotlin
@Test
fun `600dp is the expanded reader boundary`() {
    assertEquals(ReaderWindowSize.COMPACT, ReaderWindowSize.fromWidth(599))
    assertEquals(ReaderWindowSize.EXPANDED, ReaderWindowSize.fromWidth(600))
}
```

Compose 测试断言手机不显示侧栏、大屏显示侧栏；书架大屏多列，错误 Snackbar 不阻断其他操作。

- [ ] **Step 2: 确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests '*ReaderWindowSizeTest'`
Expected: FAIL，布局策略不存在。

- [ ] **Step 3: 实现纯策略和自适应布局**

```kotlin
enum class ReaderWindowSize { COMPACT, EXPANDED;
    companion object { fun fromWidth(widthDp: Int) = if (widthDp >= 600) EXPANDED else COMPACT }
}
```

`AdaptiveReaderLayout` 在 EXPANDED 使用 Row + 权重分配目录/正文，COMPACT 使用 BottomSheet；不引入 PC 模块。骨架/Shimmer 只在 Loading 出现，Success/Error 立即移除。

- [ ] **Step 4: 运行 Android 测试与响应式场景**

Run: `./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.koodoreader.reader.reader.AdaptiveReaderLayoutTest`
Expected: PASS；无设备记录 NOT RUN，不以编译代替交互验收。

- [ ] **Step 5: 提交**

```bash
git add android/app && git commit -m "feat(android): add adaptive reader and loading states"
```
