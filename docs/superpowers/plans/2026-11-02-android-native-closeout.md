# Android Native Closeout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 补齐书架、导出与同步边界，删除全部旧阅读链路，并以唯一原生 APK 通过自动化和设备验收。

**Architecture:** 原生 Room/私有偏好是唯一产品数据源；JSON/Markdown 导出复用 `core:dbio`。同步只定义快照接口。所有旧组件在新文本和 PDF 计划验收后删除，构建脚本只生成原生目标。

**Tech Stack:** Kotlin、Compose、Room、现有 BackupBundle/DataExport、Gradle、GitHub Actions、Macrobenchmark。

## Global Constraints

- 前置：导入/元数据、原生文本、原生 PDF 三份计划的自动化任务已通过。
- 不迁移或删除旧 WebView 数据；已有原生 Room 数据保留。
- 最终正文不依赖 WebView，发布构建不含 React 岛、Foliate、pdf.js 或书籍回环 HTTP 服务。
- 不同步任何网络数据；WebDAV、本地文件夹、S3/MinIO 仅预留接口；同步快照包含元数据、进度、标注、书签、设置和统计，详细 DTO/codec 由 `2026-11-02-android-stats-sync.md` 实现。
- 设备矩阵和性能原始结果未通过时，不得宣称全面收口完成。

## File Map

- `android/app/src/main/java/com/koodoreader/reader/shell/RecentBooks.kt`：最近阅读时间与排序规则。
- `android/core/sync/src/main/kotlin/com/koodoreader/core/sync/`：同步快照和 target/coordinator 接口，纯 JVM。
- `android/core/dbio/src/main/kotlin/com/koodoreader/core/dbio/DataExport.kt`：Markdown 出口接线和位置字段验证。
- `android/app/build.gradle`、`scripts/build-android.js`、`src/utils/android/androidBuild.js`：唯一原生构建。
- `android/app/src/main/AndroidManifest.xml`：唯一原生入口与 Intent。
- `.github/workflows/release-android.yml`：自动化和设备结果门禁。

---

### Task 1: 书架最近阅读、重命名和阅读进度一致性

**Files:**
- Create: `android/app/src/main/java/com/koodoreader/reader/shell/RecentBooks.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/ReaderProgressPrefs.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/LibraryPrefs.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/LibraryLogic.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/LibraryScreen.kt`
- Test: `android/app/src/test/java/com/koodoreader/reader/shell/RecentBooksTest.kt`

**Interfaces:**
- Produces: `RecentBooks.markOpened(bookKey, now)`、`recentKeys()`；存私有偏好，不改桌面 schema。
- Consumes: 原生文本/PDF/漫画的统一打开入口。
- 重命名使用导入计划 Task 4 的元数据编辑器。

- [ ] **Step 1: 写最近阅读和进度清理测试**

```kotlin
@Test
fun `recent books are newest first and deduplicated`() {
    val store = RecentBooks(InMemoryPrefs())
    store.markOpened("a", 10); store.markOpened("b", 20); store.markOpened("a", 30)
    assertEquals(listOf("a", "b"), store.recentKeys())
}

@Test
fun `purging a book clears its recent and progress entries`() {
    val store = RecentBooks(InMemoryPrefs().apply { putLong("recent:a", 30) })
    store.remove("a")
    assertFalse(store.recentKeys().contains("a"))
}
```

封面生成沿用现有 importer；书卡展示名称、封面、进度和最近阅读排序。文本、PDF、漫画打开都调用同一 `markOpened`，彻底删除时清除阅读进度和最近记录。

- [ ] **Step 2: 确认测试失败**

Run: `./gradlew :app:testDebugUnitTest --tests '*RecentBooksTest'`
Expected: FAIL，最近阅读存储不存在。

- [ ] **Step 3: 实现有界最近阅读记录**

```kotlin
class RecentBooks(private val prefs: RecentPrefs) {
    fun markOpened(bookKey: String, now: Long) {
        val next = prefs.all().toMutableMap()
        next[bookKey] = now
        prefs.write(next.entries.sortedByDescending { it.value }.take(200).associate { it.key to it.value })
    }
    fun recentKeys(): List<String> = prefs.all().entries.sortedByDescending { it.value }.map { it.key }
    fun remove(bookKey: String) { prefs.write(prefs.all() - bookKey) }
}
```

`RecentPrefs` 是 SharedPreferences 的窄接口；测试用内存实现。`LibraryLogic` 的最近阅读排序按键映射排列，不复制书籍文件或元数据。

- [ ] **Step 4: 验证书架、打开和删除回归**

Run: `./gradlew :app:testDebugUnitTest :core:dbio:test`
Expected: PASS；重启后最近顺序和进度不变，删除后无残留。

- [ ] **Step 5: 提交**

```bash
git add android/app android/core/dbio && git commit -m "feat(android): unify recent reading and library progress"
```

### Task 2: JSON/Markdown 标注导出与同步接口预留

**Files:**
- Modify: `android/core/dbio/src/main/kotlin/com/koodoreader/core/dbio/DataExport.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/BackupViewModel.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/BackupScreen.kt`
- Create: `android/core/sync/build.gradle`
- Create: `android/core/sync/src/main/kotlin/com/koodoreader/core/sync/SyncTarget.kt`
- Create: `android/core/sync/src/main/kotlin/com/koodoreader/core/sync/SyncCoordinator.kt`
- Create: `android/core/sync/src/test/kotlin/com/koodoreader/core/sync/SyncCoordinatorTest.kt`
- Modify: `android/settings.gradle`

**Interfaces:**
- Produces: `SyncTarget.read(): InputStream?`、`write(snapshot: ByteArray)`。
- Produces: `SyncCoordinator.export(target, snapshot)` 和 `import(target): ByteArray?`；不包含网络、Room、Context 或冲突策略。
- JSON/Markdown 导出使用现有 `DataExport.NoteRow`，包含 `bookKey`、章节、CFI/range、文本、笔记和颜色。

- [ ] **Step 1: 写失败导出与同步契约测试**

```kotlin
@Test
fun `markdown export retains note and highlight text`() {
    val output = DataExport.encode(listOf(noteRow()), DataExport.Format.MD)
    assertTrue(output.contains("> selected text"))
    assertTrue(output.contains("**Note:** note body"))
}

@Test
fun `sync coordinator writes exact snapshot and never invents remote data`() {
    val target = MemorySyncTarget()
    SyncCoordinator.export(target, byteArrayOf(1, 2, 3))
    assertArrayEquals(byteArrayOf(1, 2, 3), SyncCoordinator.import(target))
}
```

- [ ] **Step 2: 确认测试失败**

Run: `./gradlew :core:dbio:test :core:sync:test`
Expected: FAIL，MD 出口或 `core:sync` 尚未接线。

- [ ] **Step 3: 实现最小同步边界和 MD UI**

```kotlin
interface SyncTarget {
    fun read(): java.io.InputStream?
    fun write(snapshot: ByteArray)
}

object SyncCoordinator {
    fun export(target: SyncTarget, snapshot: ByteArray) = target.write(snapshot)
    fun import(target: SyncTarget): ByteArray? = target.read()?.use { it.readBytes() }
}
```

`BackupScreen` 的 Notes 和 Highlights 行各增加 MD 按钮，调用现有 `exportData` 并传 `BackupDataExportFormat.MD`。直接使用已有 `BackupDataExportFormat.MD`，其 `toCore()` 已映射到 `DataExport.Format.MD`。同步模块只被测试引用，不加入应用依赖，不出现 WebDAV 设置项。

- [ ] **Step 4: 验证 JSON/MD round-trip 和同步隔离**

Run: `./gradlew :core:dbio:test :core:sync:test :app:testDebugUnitTest`
Expected: PASS；导出记录可由 `DataImport` 重新关联到原书位置，APK 不含同步实现依赖。

- [ ] **Step 5: 提交**

```bash
git add android/core/dbio android/core/sync android/app android/settings.gradle && git commit -m "feat(android): export annotations and define sync boundary"
```

### Task 3: 删除 React/Foliate/pdf.js 与唯一原生构建

**Files:**
- Delete: `android/app/src/main/java/com/koodoreader/reader/MainActivity.kt`
- Delete: `android/app/src/main/java/com/koodoreader/reader/NativeEventDispatcher.kt`
- Delete: `android/app/src/main/java/com/koodoreader/reader/LocalAssetServer.kt`
- Delete: `android/app/src/main/java/com/koodoreader/reader/shell/ReaderAssetHost.kt`
- Delete: `android/app/src/main/java/com/koodoreader/reader/epubhost/FoliateHostBridge.kt`
- Delete: `android/app/src/main/java/com/koodoreader/reader/pdfhost/PdfJsHostBridge.kt`
- Delete: `android/app/src/main/assets/foliate/`
- Delete: `android/app/src/main/assets/pdfengine/`
- Modify: `android/app/src/main/AndroidManifest.xml`
- Modify: `android/app/build.gradle`
- Modify: `scripts/build-android.js`
- Modify: `src/utils/android/androidBuild.js`
- Modify: `scripts/check-import-rules.js`
- Test: `src/utils/android/androidBuild.test.js`

**Interfaces:**
- Consumes: 已通过的原生文本/PDF 主链路。
- Produces: 唯一 `native` 构建；`LauncherAlias.targetActivity = .shell.NativeShellActivity`，VIEW/SEND 直接由原生宿主处理。
- 删除 `ReaderAssetHost` 参数、`READER_ENGINE`、`NATIVE_SHELL` 产品开关及 `webview` 构建目标。

- [ ] **Step 1: 写失败构建与资产守卫测试**

```javascript
test('native is the only android target', () => {
  const plan = getBuildPlan({ targets: ['native'] });
  expect(plan.map(item => item.target)).toEqual(['native']);
  expect(() => getBuildPlan({ targets: ['webview'] })).toThrow(/unsupported target/);
  expect(plan.every(item => item.stageWebapp === false)).toBe(true);
});
```

另加静态守卫：主源码不得引用 `FoliateHostBridge`、`PdfJsHostBridge`、`LocalAssetServer`；APK 不得包含 `assets/webapp`、`assets/foliate`、`assets/pdfengine`。

- [ ] **Step 2: 确认旧构建测试失败**

Run: `npx jest src/utils/android/androidBuild.test.js --runInBand`
Expected: FAIL，因为仍接受 `webview` 且会 stage webapp。

- [ ] **Step 3: 修改入口和构建计划**

```groovy
def buildTarget = 'native'
manifestPlaceholders = [nativeLauncher: '.shell.NativeShellActivity']
buildConfigField 'boolean', 'NATIVE_SHELL', 'true'
```

实际修改中删除未使用的 BuildConfig 字段，而不是保留常量假开关。`scripts/build-android.js` 的默认和允许目标都只保留 `native`，帮助文本同步。Manifest 将 VIEW/SEND 过滤器移到 `NativeShellActivity`；`MainActivity` 删除后不得残留引用。文本/PDF 阅读屏直接接收文件和引擎，不再要求本地 HTTP 可用。

- [ ] **Step 4: 构建并检查唯一 APK**

Run: `node scripts/build-android.js --target native --debug --no-split && npx jest src/utils/android/androidBuild.test.js --runInBand && ./gradlew :app:testDebugUnitTest`
Expected: PASS；APK 不含三类旧资产，冷启动进入原生书库，所有格式路由仍可用。

- [ ] **Step 5: 提交**

```bash
git add android scripts/build-android.js src/utils/android && git commit -m "refactor(android): remove legacy web reading tracks"
```

### Task 4: CI、文档、真实样本和设备发布门禁

**Files:**
- Modify: `.github/workflows/release-android.yml`
- Modify: `scripts/ci-macro-benchmark.sh`
- Modify: `docs/android-native-migration.md`
- Modify: `docs/android-user-guide.md`
- Modify: `docs/android-native-shell-usage.md`
- Modify: `docs/android-end-to-end-availability-2026-10-09.md`
- Create: `docs/android-native-validation-2026-11-02.md`

**Interfaces:**
- Produces: CI 的全量 JVM/Android 单元测试、构建、格式/Schema/16 KB 守卫和 Macrobenchmark 结果收集。
- Produces: 人工设备矩阵记录，包含设备、API、页大小、ABI、样本哈希和原始指标路径。

- [ ] **Step 1: 写 CI 门禁和过期断言清单**

```yaml
- name: Test all Android modules
  working-directory: android
  run: ./gradlew test :app:testDebugUnitTest --no-daemon
- name: Build native APK
  run: node scripts/build-android.js --target native --debug --no-split
- name: Verify native library page alignment
  run: node scripts/check-elf-16kb.js android/app/build/outputs/apk/debug/app-debug.apk
```

文档检查必须删除“默认 WebView”“CBR 走兜底岛”“PDF 无文本层”“Foliate 默认”等旧描述，改为已实现的兼容边界。不得把旧报告中的历史结论改写成当时未发生的事实，可在报告顶部标注已被新版本替代。

- [ ] **Step 2: 本地运行完整自动化**

Run: `./gradlew test :app:testDebugUnitTest && node scripts/check-import-rules.js && node scripts/check-room-schema.js && npx jest src/utils/android/androidBuild.test.js --runInBand`
Expected: PASS；无跳过主引擎测试。

- [ ] **Step 3: 执行真实样本和设备矩阵**

Run: `scripts/ci-macro-benchmark.sh`
Expected: 保存原始 JSON，满足 300 章 EPUB ≤ 1.2 秒、翻页 P90 < 50 ms、10 万字 < 350 MB、1000 页 PDF ≤ 1.5 秒；1000 本导入不 OOM。

在最低 API、4 KB 页设备、Android 15+ 16 KB 页设备逐项执行：导入、外部直达、元数据编辑、分页/滚动、进度恢复、标注、书签、搜索、JSON/MD 导出、PDF 选择/笔记和漫画。记录 PASS/FAIL，不允许用 JVM 测试替代。

- [ ] **Step 4: 验证最终交付物和清理产物**

Run: `node scripts/build-android.js --target native --release --no-split && node scripts/check-elf-16kb.js <release-apk> && git status --short`
Expected: 唯一原生 release APK 成功；所有 ABI/16 KB 通过；除计划源码、文档和既有用户文件外无测试日志/临时产物。

- [ ] **Step 5: 提交**

```bash
git add .github/workflows/release-android.yml scripts/ci-macro-benchmark.sh docs && git commit -m "test(android): gate native reader release on device validation"
```
