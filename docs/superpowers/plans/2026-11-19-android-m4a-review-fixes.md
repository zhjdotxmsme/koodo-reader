# M4a 审查修复 + 既有红灯清理 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 2026-11-19 完成情况核查中确认的 1 个 Critical 回归（字典目录缩减阻断发版）、3 个 Important 问题（主线程 ISBN 解析、备份脚本二进制损坏、导入白名单无提示）和 2 个既有测试红灯（benchmarks 编译失败、PdfReflowHighlightsTest / pdfengine 单测），并完成 M4a 遗留的真机烟雾。

**Architecture:** 全部为定点修复，不改架构。每个 Task 独立可测、独立提交。修复来源：`0db750a4..2504ddf2` 范围的独立代码审查（无未修复 Critical/Important 遗留给本计划之外）+ 核查期实跑确认的测试失败。

**Tech Stack:** Kotlin / Jetpack Compose（:app）、Groovy Gradle、Node 校验脚本（check-dict-catalog / test-pdfengine / sync-locales-android）、PowerShell 5.1+（备份脚本）。

## Global Constraints

- 工作目录 `E:/open-source/koodo-reader`，就地工作在 `dev` 分支（用户已明确允许频繁提交，沿用本轮先前约定）。
- 仓库惯例 commit 信息：`fix(android): <english subject>`，与 git log 既有风格一致。
- 每个 Task 结束恰好一个 commit；不得夹带任务外改动。
- Gradle 命令在 Git Bash 中执行（`cd android && ./gradlew ...`）；涉及 :app 的测试/打包加 `-Ptarget=native`。
- **不改动**书籍解析、数据模型、CFI/页码、标注定位、搜索、同步、第三轮沉浸化 UI（ReaderChrome/ReaderSystemBars 链路）——本计划只做审查清单内修复。
- 不新增第三方依赖（Task 3 的 `org.json:json` 例外：这是仓库既有模式，`:engine:pdf` 已在用同版本 20231013）。
- locale 改动一律走 `src/assets/locales/*.json` 源文件 + `node scripts/sync-locales-android.js` 同步；android 资产目录不接受源文件之外的 key（sync 的 `--check` 模式按字节比对）。
- 既有 backlog 不在本计划：`check-locales.mjs` 2 处注册表漂移（b59ad363 既有，需单独产品决策）。

## 背景（零上下文工程师必读）

- M4a 大提交 `2504ddf2`（11-18）把 `android/app/src/main/assets/dicts/catalog.json` 从 11 个条目减到 3 个。校验脚本 `scripts/check-dict-catalog.js` 要求与桌面 `CloudDictList` 完全一致（EXPECTED_COUNT = 11），且该脚本在 `.github/workflows/release-android.yml:90` 运行——不改回 11 个，下次 tag 发版必失败。决策已定：**恢复 11 条目**（桌面仍发 11 个，校验契约就是桌面 parity；"聚焦中文 MDX"若要做，须另行改动桌面与校验，不在本计划）。
- `android/benchmarks`（P8 交付，`fa4fde36`）从未编译通过：4 个源文件导入 `androidx.benchmark.macro.MacrobenchmarkRule`，而 `benchmark-macro-junit4:1.2.4` 中该类在 `androidx.benchmark.macro.junit4` 包（已从 Gradle 缓存 jar 核对，jar 内成员完整，根因仅导入包名错误；`pressHome`/`device` 等连带报错是 receiver 类型解析失败的级联）。
- `:app:testNativeHostDebugUnitTest` 唯一红灯 `PdfReflowHighlightsTest.json includes reflow bbox on matching page only`：`CfiPdfMapper.serialise`（:engine:pdf）在 JVM 上调用 `org.json.JSONObject.put`，命中 android.jar "not mocked" stub。`:engine:pdf` 自己的 build.gradle 已用 `compileOnly` + `testImplementation 'org.json:json:20231013'` 解决同问题，:app 缺这条 testImplementation。
- `scripts/test-pdfengine.js` 62 项中 1 项失败：engine 成功路径回调 `host.onResult(id, payload, null)`，测试断言 `error === ''`。Kotlin 侧契约 `FoliateJsResult.kt:4-6` 明确"success uses `null` or `''`"，两个 bridge 都经 `errorOrNull` 处理——**测试断言过严，改测试不改引擎**。
- `BookMetadataDialog.kt:42-44` 在 `remember(book.key)` 里同步调用 `BookIsbnProbe.probe(book)`（完整 `EpubBook.parse` / `MobiMetadataReader.read`），组合发生在主线程——打开"编辑元数据"即卡 UI。
- `scripts/android-pull-device-backup.ps1` 用 `Invoke-Adb shell ... | Set-Content -Encoding Byte` 和 `>` 拉取 `.db`/书籍二进制：PS 5.1 下文本流会 UTF-16 重编码静默损坏，且第 29/31 行对同一文件拉了两次（第二次覆盖第一次）。
- 导入白名单 `BookRules.LIBRARY_IMPORT_EXTENSIONS = epub/pdf/mobi/azw/azw3` 是 M4a 计划外变更，已定保留白名单（文件夹导入过滤垃圾文件的初衷合理），但拒绝时无任何格式说明。桌面/安卓共享 locale key，不能改共享 key 的文案（桌面支持更多格式）；方案是**新增仅安卓引用的新 key**。

---

### Task 1: 恢复字典目录 11 条目（解除发版阻断）

**Files:**
- Modify: `android/app/src/main/assets/dicts/catalog.json`（整文件恢复）

**Interfaces:**
- Consumes: `f886f30c` 版本的 catalog（M4a 削减前的已知良好状态）
- Produces: 无代码接口；`node scripts/check-dict-catalog.js` 恢复绿色

- [ ] **Step 1: 确认当前失败**

Run: `node scripts/check-dict-catalog.js`
Expected: `[check-dict-catalog] FAIL — 2 check(s) failed`（Android has 3 entries; expected 11）

- [ ] **Step 2: 确认无代码硬编码被删条目**

Run: `grep -rn "basic-english-irish\|english-italian\|jmdict\|moby\|wordnet\|opted\|english-arabic\|tcm_textbook" android/ --include=*.kt`
Expected: 无输出（词典列表完全由 catalog.json 驱动）。若有命中，停止并回报，不要继续。

- [ ] **Step 3: 恢复文件**

```bash
git show f886f30c:android/app/src/main/assets/dicts/catalog.json > android/app/src/main/assets/dicts/catalog.json
```

- [ ] **Step 4: 验证校验通过**

Run: `node scripts/check-dict-catalog.js`
Expected: `[check-dict-catalog] OK — 11 cloud dicts match desktop (bytes=0, bundled=[])`

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/assets/dicts/catalog.json
git commit -m "fix(android): restore full dictionary catalog for release parity check"
```

---

### Task 2: 修复 benchmarks 模块编译（P8 遗留）

**Files:**
- Modify: `android/benchmarks/src/main/kotlin/com/koodoreader/benchmarks/StartupBenchmark.kt:4`
- Modify: `android/benchmarks/src/main/kotlin/com/koodoreader/benchmarks/PageTurnBenchmark.kt:5`
- Modify: `android/benchmarks/src/main/kotlin/com/koodoreader/benchmarks/OpenEpubBenchmark.kt:5`
- Modify（条件触发）: `android/benchmarks/build.gradle`

**Interfaces:**
- Produces: `:benchmarks:compileBenchmarkKotlin` 编译通过（device 运行的 connectedBenchmarkAndroidTest 不在本 Task，真机部分归 Task 10）

- [ ] **Step 1: 确认当前失败**

Run: `cd android && ./gradlew :benchmarks:compileBenchmarkKotlin --console=plain`
Expected: BUILD FAILED，`Unresolved reference: MacrobenchmarkRule`

- [ ] **Step 2: 修正 3 处导入**

三个文件中的同一行：

```kotlin
import androidx.benchmark.macro.MacrobenchmarkRule
```

改为：

```kotlin
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
```

（`StartupBenchmark.kt:4`、`PageTurnBenchmark.kt:5`、`OpenEpubBenchmark.kt:5`。`pressHome`/`startActivityAndWait`/`device` 报错是 receiver 解析失败的级联，导入修正后随 `MacrobenchmarkScope` 正常解析，不要改这些调用点。）

- [ ] **Step 3: 重新编译**

Run: `cd android && ./gradlew :benchmarks:compileBenchmarkKotlin --console=plain`
Expected: BUILD SUCCESSFUL。

若仍报 `This Metric API is experimental` 为 **error**，执行 Step 4；若为 warning 或消失，跳过 Step 4。

- [ ] **Step 4（条件）: 加 opt-in 编译参数**

在 `android/benchmarks/build.gradle` 的 `kotlinOptions` 块中：

```groovy
    kotlinOptions {
        jvmTarget = '17'
        freeCompilerArgs += "-opt-in=androidx.benchmark.ExperimentalMetricApi"
    }
```

然后重跑 Step 3 的命令，Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: Commit**

```bash
git add android/benchmarks/
git commit -m "fix(android): correct MacrobenchmarkRule import package in benchmarks"
```

---

### Task 3: :app 单测补 org.json 真实实现（修既有 1 红）

**Files:**
- Modify: `android/app/build.gradle`（dependencies 块，`testImplementation 'org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.1'` 行之后）

**Interfaces:**
- Consumes: `:engine:pdf` 的既有模式（`android/engine/pdf/build.gradle:54-64` 的注释解释了 compileOnly + testImplementation 双轨）
- Produces: `:app:testNativeHostDebugUnitTest` 338/338 全绿

- [ ] **Step 1: 确认当前失败**

Run: `cd android && ./gradlew :app:testNativeHostDebugUnitTest -Ptarget=native --console=plain`
Expected: 338 tests completed, 1 failed（`PdfReflowHighlightsTest.json includes reflow bbox on matching page only`，`Method put in org.json.JSONObject not mocked`）

- [ ] **Step 2: 添加 testImplementation**

在 `android/app/build.gradle` 的 dependencies 块末尾（`kotlinx-coroutines-test` 行之后）加：

```groovy
    // org.json is an ANDROID PLATFORM API (android.jar) at runtime; on the JVM
    // unit-test classpath it hits the "not mocked" stub. Mirror :engine:pdf's
    // pattern: real org.json artifact for unit tests only. Fixes
    // PdfReflowHighlightsTest (CfiPdfMapper.serialise -> JSONObject.put).
    testImplementation 'org.json:json:20231013'
```

- [ ] **Step 3: 重跑测试**

Run: `cd android && ./gradlew :app:testNativeHostDebugUnitTest -Ptarget=native --console=plain`
Expected: BUILD SUCCESSFUL，338 tests, 0 failed

- [ ] **Step 4: Commit**

```bash
git add android/app/build.gradle
git commit -m "test(android): give unit tests a real org.json implementation"
```

---

### Task 4: pdfengine 测试期望对齐 onResult 契约（修既有 1 红）

**Files:**
- Modify: `scripts/test-pdfengine.js:312`

**Interfaces:**
- Consumes: 契约文档 `android/app/src/main/java/com/koodoreader/reader/epubhost/FoliateJsResult.kt:4-9`（success 允许 `null` 或 `''`，`errorOrNull` 统一处理）；`engine.mjs:368` 成功路径传 `null`
- Produces: `node scripts/test-pdfengine.js` 无 FAILED

- [ ] **Step 1: 确认当前失败**

Run: `node scripts/test-pdfengine.js 2>&1 | tail -3`
Expected: `[test-pdfengine] 1/62 checks FAILED`（`dispatch reports no error on success: expected "", got null`）

- [ ] **Step 2: 修正断言**

`scripts/test-pdfengine.js:312`：

```js
        eq('dispatch reports no error on success', seen[0].error, '');
```

改为：

```js
        check('dispatch reports no error on success',
            seen[0].error === '' || seen[0].error === null,
            JSON.stringify(seen[0].error));
```

（契约允许 null 与 '' 两种成功表示；只改测试，不改引擎行为。）

- [ ] **Step 3: 重跑**

Run: `node scripts/test-pdfengine.js 2>&1 | tail -3`
Expected: 无 `FAILED` 行，退出码 0

- [ ] **Step 4: Commit**

```bash
git add scripts/test-pdfengine.js
git commit -m "test(pdfengine): accept null success error per onResult contract"
```

---

### Task 5: ISBN 探测移出组合主线程

**Files:**
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/BookMetadataDialog.kt:42-44`（及其附近 UI 与 import）

**Interfaces:**
- Consumes: `BookIsbnProbe.probe(book): String?`（`reader/metadata/BookIsbnProbe.kt`，IO 安全）
- Produces: 无新接口；对话框打开立即渲染，ISBN 字段异步填充

- [ ] **Step 1: 改同步探测为异步**

`BookMetadataDialog.kt` 顶部 import 增加：

```kotlin
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
```

`42-44` 行：

```kotlin
    var isbn by remember(book.key) {
        mutableStateOf(BookIsbnProbe.probe(book).orEmpty())
    }
```

替换为：

```kotlin
    var isbn by remember(book.key) { mutableStateOf("") }
    var isbnProbing by remember(book.key) { mutableStateOf(true) }
    LaunchedEffect(book.key) {
        isbnProbing = true
        isbn = withContext(Dispatchers.IO) { BookIsbnProbe.probe(book).orEmpty() }
        isbnProbing = false
    }
```

- [ ] **Step 2: 探测期间给 ISBN 字段一个进度提示**

在 `MetadataField(t("ISBN"), isbn, !saving) { isbn = it }`（约 122 行）之前插入：

```kotlin
                if (isbnProbing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
```

（`LinearProgressIndicator` 已在 import 中，无需新增。）

- [ ] **Step 3: 编译 + 既有测试回归**

Run: `cd android && ./gradlew :app:compileNativeHostDebugKotlin :app:testNativeHostDebugUnitTest -Ptarget=native --console=plain`
Expected: BUILD SUCCESSFUL（无现成对话框组合测试，回归即验证；线程性修复靠代码审查把关，不再新增测试）

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/koodoreader/reader/shell/BookMetadataDialog.kt
git commit -m "fix(android): probe book ISBN off the main thread"
```

---

### Task 6: 备份拉取脚本字节安全

**Files:**
- Modify: `scripts/android-pull-device-backup.ps1`（27-40 行的拉取循环整体替换）

**Interfaces:**
- Produces: 同路径 `.tools/device-backup/` 产物，但字节与设备一致；新增脚本内 `Save-AdbBinary` 函数（私有）

- [ ] **Step 1: 替换拉取逻辑**

删除 `scripts/android-pull-device-backup.ps1` 第 27-40 行（`foreach ($f in $files) { ... }` 到 books 循环结束），替换为：

```powershell
function Save-AdbBinary([string]$remotePath, [string]$localPath) {
    # adb exec-out emits raw bytes; route stdout at the process level.
    # A PowerShell pipeline would decode/re-encode the stream (UTF-16LE on
    # Windows PowerShell 5.1) and silently corrupt the database file.
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $adb
    $argList = New-Object System.Collections.Generic.List[string]
    $argList.Add("exec-out")
    if ($Serial) { $argList.Add("-s"); $argList.Add($Serial) }
    $argList.Add("shell")
    $argList.Add("run-as $pkg cat $remotePath")
    $psi.Arguments = (($argList | ForEach-Object { '"{0}"' -f $_ }) -join " ")
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $proc = [System.Diagnostics.Process]::Start($psi)
    $out = [System.IO.File]::Create($localPath)
    $proc.StandardOutput.BaseStream.CopyTo($out)
    $out.Dispose()
    $proc.WaitForExit()
    if ($proc.ExitCode -ne 0) { throw "adb exec-out failed for $remotePath" }
    $remoteSize = ((Invoke-Adb shell "run-as $pkg wc -c < $remotePath") -join "").Trim()
    $localSize = (Get-Item $localPath).Length
    if ($remoteSize -ne "$localSize") {
        Write-Warning "$remotePath size mismatch (remote=$remoteSize local=$localSize)"
    } else {
        Write-Host "[pull-backup] $remotePath -> $localPath ($localSize bytes)"
    }
}

foreach ($f in $files) {
    Save-AdbBinary $f.remote (Join-Path $dest $f.local)
}
# books/covers: list and pull one by one
$bookList = (Invoke-Adb shell "run-as $pkg ls files/books" 2>$null) -split "\s+" | Where-Object { $_ -match '\.' }
foreach ($b in $bookList) {
    if ($b -match '^\d') {
        Save-AdbBinary "files/books/$b" (Join-Path $dest "books\$b")
    }
}
Write-Host "[pull-backup] wrote $dest (sizes verified)"
```

（同时消除了原 29/31 行对同一文件拉取两次的重复。restore 脚本 `adb push` 本身字节安全，不改。）

- [ ] **Step 2: 语法检查（无需设备）**

Run:
```bash
powershell -NoProfile -Command '$t=$null;$e=$null;[void][System.Management.Automation.Language.Parser]::ParseFile("scripts/android-pull-device-backup.ps1",[ref]$t,[ref]$e); if ($e.Count) { $e | ForEach-Object { $_.Message }; exit 1 } else { "parse OK" }'
```
Expected: `parse OK`

- [ ] **Step 3: Commit**

```bash
git add scripts/android-pull-device-backup.ps1
git commit -m "fix(android): pull device backup with byte-exact adb exec-out"
```

（真机往返实测归 Task 10。）

---

### Task 7: 导入白名单的用户可见提示

**Files:**
- Modify: `src/assets/locales/en.json`（新增 1 key）
- Modify: `src/assets/locales/zh-CN.json`（新增 1 key 译文）
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/LibraryViewModel.kt:403`

**Interfaces:**
- Consumes: `BookRules.LIBRARY_IMPORT_EXTENSIONS`（epub/pdf/mobi/azw/azw3）
- Produces: 新 i18n key `No supported ebooks in this folder (EPUB · PDF · MOBI · AZW3)`（仅安卓引用，桌面无引用，不影响桌面文案）

- [ ] **Step 1: 在源 locale 文件新增 key**

`src/assets/locales/en.json`（放在 "No supported ebooks in this folder" 条目附近）：

```json
  "No supported ebooks in this folder (EPUB · PDF · MOBI · AZW3)": "No supported ebooks in this folder (EPUB · PDF · MOBI · AZW3)",
```

`src/assets/locales/zh-CN.json`（同位置）：

```json
  "No supported ebooks in this folder (EPUB · PDF · MOBI · AZW3)": "此文件夹内没有可导入的电子书（支持 EPUB、PDF、MOBI、AZW3）",
```

- [ ] **Step 2: 同步到安卓资产**

Run: `node scripts/sync-locales-android.js`
Expected: 输出显示 en / zh-CN keys 数 +1（约 1518 / 1529），无 FAIL

Run: `node scripts/sync-locales-android.js --check`
Expected: exit 0（全部 OK）

- [ ] **Step 3: LibraryViewModel 换用新 key**

`LibraryViewModel.kt:403`：

```kotlin
                emptyCatalogKey = "No supported ebooks in this folder",
```

改为：

```kotlin
                emptyCatalogKey = "No supported ebooks in this folder (EPUB · PDF · MOBI · AZW3)",
```

（只改文件夹导入路径；多选文件路径 "No supported ebook files selected" 属用户已主动选文件的场景，不加提示，避免冗余。）

- [ ] **Step 4: 编译 + 测试**

Run: `cd android && ./gradlew :app:compileNativeHostDebugKotlin :app:testNativeHostDebugUnitTest -Ptarget=native --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/assets/locales/en.json src/assets/locales/zh-CN.json android/app/src/main/assets/locales/ android/app/src/main/java/com/koodoreader/reader/shell/LibraryViewModel.kt
git commit -m "feat(android): name supported formats in import rejection hint"
```

---

### Task 8: Minor 批量修复

**Files:**
- Modify: `android/app/src/main/java/com/koodoreader/reader/metadata/GoogleBooksBookMetadataProvider.kt`（buildString 查询串）
- Modify: `src/assets/locales/en.json` + `src/assets/locales/zh-CN.json`（4 个缺失 key）+ sync
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/BookMetadataViewModel.kt`（Failed 死状态接线）
- Modify: `android/app/src/main/java/com/koodoreader/reader/reader/ReaderControlCenterTab.kt` + `ReaderControlCenterSheet.kt:70` + `ReaderControlCenterTabsTest.kt:19`（删除 useFoliate 死参数）

**Interfaces:**
- Produces: `ReaderControlCenterTabs.forFormat(format: ReaderFormatKind)`（签名变化，少一个参数；调用点与测试同步更新）

- [ ] **Step 1: GoogleBooks 查询串用空格连接**

`GoogleBooksBookMetadataProvider.kt` 中：

```kotlin
            buildString {
                append("intitle:").append(t)
                if (a.isNotEmpty()) append("+inauthor:").append(a)
            }
```

改为：

```kotlin
            buildString {
                if (t.isNotEmpty()) append("intitle:").append(t)
                if (a.isNotEmpty()) {
                    if (isNotEmpty()) append(' ')
                    append("inauthor:").append(a)
                }
            }
```

（`+` 会被 urlEncode 成字面 `%2B`；空格编码后是 Google `q` 参数的规范 AND 分隔。）

- [ ] **Step 2: 补 4 个缺失 i18n key**

先确认缺失：Run: `grep -n '"WebDAV"\|"Follow system"\|"Custom background"\|"Keep screen on"' src/assets/locales/en.json src/assets/locales/zh-CN.json`
Expected: 无输出（确认 4 个 key 均不存在再添加；若已存在则跳过对应行）。

`src/assets/locales/en.json` 新增：

```json
  "WebDAV": "WebDAV",
  "Follow system": "Follow system",
  "Custom background": "Custom background",
  "Keep screen on": "Keep screen on",
```

`src/assets/locales/zh-CN.json` 新增：

```json
  "WebDAV": "WebDAV",
  "Follow system": "跟随系统",
  "Custom background": "自定义背景",
  "Keep screen on": "保持屏幕常亮",
```

Run: `node scripts/sync-locales-android.js && node scripts/sync-locales-android.js --check`
Expected: 两次都 exit 0

- [ ] **Step 3: Failed 死状态接线**

`BookMetadataViewModel.kt` 的 `searchOnline` 中：

```kotlin
            val items = withContext(Dispatchers.IO) {
                runCatching {
                    PublicMetadataSearch.search(transport, name, author, isbn, cacheDir)
                }.getOrDefault(emptyList())
            }
            _searchState.value = when {
                items.isEmpty() -> BookMetadataSearchUiState.Empty
                else -> BookMetadataSearchUiState.Results(items)
            }
```

改为：

```kotlin
            val result = withContext(Dispatchers.IO) {
                runCatching { PublicMetadataSearch.search(transport, name, author, isbn, cacheDir) }
            }
            _searchState.value = result.fold(
                onSuccess = { items ->
                    if (items.isEmpty()) BookMetadataSearchUiState.Empty
                    else BookMetadataSearchUiState.Results(items)
                },
                onFailure = { BookMetadataSearchUiState.Failed },
            )
```

（对话框已有 `Failed` 分支渲染 `t("Failed to fetch metadata")`，key 在两侧 locale 均存在，无需新增。）

- [ ] **Step 4: 删除 useFoliate 死参数**

`ReaderControlCenterTab.kt`：

```kotlin
    fun forFormat(format: ReaderFormatKind, useFoliate: Boolean = true): List<ReaderControlCenterTab> =
```

改为：

```kotlin
    fun forFormat(format: ReaderFormatKind): List<ReaderControlCenterTab> =
```

`ReaderControlCenterSheet.kt:70`：

```kotlin
    val tabs = remember(format, useFoliate) { ReaderControlCenterTabs.forFormat(format, useFoliate) }
```

改为：

```kotlin
    val tabs = remember(format) { ReaderControlCenterTabs.forFormat(format) }
```

`ReaderControlCenterTabsTest.kt:19`：

```kotlin
        val tabs = ReaderControlCenterTabs.forFormat(ReaderFormatKind.REFLOW, useFoliate = true)
```

改为：

```kotlin
        val tabs = ReaderControlCenterTabs.forFormat(ReaderFormatKind.REFLOW)
```

（测试文件 12 行原本就不传第二参，无需改。改完 grep 确认无残留：`grep -rn "useFoliate" android/app/src --include=*.kt`，Expected: 无输出。）

- [ ] **Step 5: 全量回归**

Run: `cd android && ./gradlew :app:testNativeHostDebugUnitTest :app:assembleNativeHostDebug -Ptarget=native -PversionCode=20414 -PversionName=2.4.4-native --console=plain`
Expected: BUILD SUCCESSFUL（338 tests, 0 failed）

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/koodoreader/reader/metadata/GoogleBooksBookMetadataProvider.kt android/app/src/main/java/com/koodoreader/reader/shell/BookMetadataViewModel.kt android/app/src/main/java/com/koodoreader/reader/reader/ReaderControlCenterTab.kt android/app/src/main/java/com/koodoreader/reader/reader/ReaderControlCenterSheet.kt android/app/src/test/java/com/koodoreader/reader/reader/ReaderControlCenterTabsTest.kt src/assets/locales/en.json src/assets/locales/zh-CN.json android/app/src/main/assets/locales/
git commit -m "fix(android): metadata query join, failed-search state, dead param, locale keys"
```

---

### Task 9: 过程收尾（文档 + 全量回归）

**Files:**
- Modify: `.superpowers/sdd/progress.md`（追加本轮修复记录）

- [ ] **Step 1: 补记 progress.md**

在文件末尾（第三轮段落之后）追加：

```markdown

# SDD progress — M4a 审查修复 (2026-11-19)
Plan: docs/superpowers/plans/2026-11-19-android-m4a-review-fixes.md. Base 2504ddf2.
来源：完成情况核查（Task 8 证据 + 独立审查 42433fd3..2504ddf2，结论 With fixes）。
Task 1: 恢复 dicts/catalog.json 11 条目（check-dict-catalog 恢复绿，解除 release-android.yml 阻断）。
Task 2: benchmarks MacrobenchmarkRule 导入包修正（:benchmarks:compileBenchmarkKotlin 首次编译通过）。
Task 3: :app testImplementation org.json:json:20231013（PdfReflowHighlightsTest 转绿，338/338）。
Task 4: test-pdfengine.js 成功路径 error 断言对齐 null/'' 契约（62/62）。
Task 5: BookMetadataDialog ISBN 探测移出组合主线程（LaunchedEffect + Dispatchers.IO）。
Task 6: android-pull-device-backup.ps1 改 exec-out 字节级拉取 + 大小校验（真机往返归 Task 10）。
Task 7: 导入拒绝提示新增仅安卓 key（en/zh-CN + sync），白名单本身保留。
Task 8: GoogleBooks 查询串空格连接、4 个缺失 i18n key、Failed 死状态接线、删 useFoliate 死参数。
Task 9: progress.md 补记 + 全量回归。
Task 10（阻塞，需 USB 设备）: M4a T10 真机烟雾 + 备份脚本往返实测 +（可选）benchmark device run。
```

- [ ] **Step 2: 全量回归（含 Task 2 的 benchmarks 编译）**

Run:
```bash
node scripts/check-dict-catalog.js && \
node scripts/test-foliate-host.js 2>&1 | tail -1 && \
node scripts/test-pdfengine.js 2>&1 | tail -1 && \
cd android && ./gradlew :benchmarks:compileBenchmarkKotlin :core:designsystem:test :core:designsystem:selfCheck :engine:annotate:test :core:importer:test :app:testNativeHostDebugUnitTest :app:assembleNativeHostDebug -Ptarget=native -PversionCode=20414 -PversionName=2.4.4-native --console=plain
```
Expected: 所有 node 校验无 FAIL；Gradle BUILD SUCCESSFUL（app 套件 338 tests, 0 failed）

- [ ] **Step 3: Commit**

```bash
git add .superpowers/sdd/progress.md
git commit -m "docs(android): record m4a review fixes round in sdd progress"
```

---

### Task 10: M4a 真机烟雾（阻塞前置：USB 设备）

**前置条件：** `adb devices` 显示设备为 `device` 状态（非 unauthorized / 空）。未满足时本 Task 整体挂起，不得用模拟器替代（macrobenchmark 要求真机）。

**Files:**
- 无代码改动（验证型 Task）；发现问题时另起计划修复

- [ ] **Step 1: 覆盖安装（保留数据）**

```bash
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" install -r android/app/build/outputs/apk/nativeHost/debug/app-nativeHost-debug.apk
```
Expected: `Success`

- [ ] **Step 2: M4a T10 三点清单**（plan `docs/superpowers/plans/2026-11-18-android-reader-m4a-control-center.md` §4）

1. EPUB → ⋯ → 阅读选项 → 外观/版式改双页 → 分页双栏生效
2. PDF → 阅读选项 → 裁边 16px + 重排切换生效
3. 设置 → 阅读 → 改默认字号 → 新开书验证默认值
另加 Task 6 验证：`powershell -File scripts/android-pull-device-backup.ps1` 后 `.tools/device-backup/databases/koodo.db` 大小与 `adb shell run-as com.koodoreader.reader wc -c < databases/koodo.db` 一致（脚本内已自检，此处复核日志无 Warning）。

- [ ] **Step 3:（可选）benchmark device run**

Run: `bash scripts/ci-macro-benchmark.sh`（按脚本内说明需 benchmark variant、fixture EPUB；若环境不齐，记录跳过原因，不得跳过 Step 1/2）

- [ ] **Step 4: 记录结果**

把通过/失败项追加到 `.superpowers/sdd/progress.md` Task 10 行后，单独 commit：`docs(android): record m4a device smoke results`。

---

## Self-Review 记录

- 审查清单覆盖：Critical #1（dict catalog）→ Task 1；Important #2（ISBN 主线程）→ Task 5；Important #3（备份脚本）→ Task 6；Important #4（白名单提示）→ Task 7；既有红灯 benchmarks / PdfReflowHighlightsTest / pdfengine → Task 2/3/4；Minor #5（Failed 死状态）、#8（useFoliate）、#11（GoogleBooks +）、#9（WebDAV 等缺失 key）、#10 部分（冗余字号滑块——评审保留不动，不属缺陷）→ Task 8；Minor #6（Suggestions 提示文案）、#7（文件末尾换行）、#12（Obscurer 恢复逻辑）评估为可接受现状，不在本计划。
- M4a T10 真机烟雾 → Task 10（阻塞项已标注前置条件）。
- 类型/签名一致性：`forFormat` 签名变化在 Task 8 内一次性改完调用点与测试；新 i18n key 在 Task 7/8 中给出完整 JSON 行；`Save-AdbBinary` 为脚本私有函数无外部消费方。
