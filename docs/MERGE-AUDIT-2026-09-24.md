# Worktree 合并 + 代码完成度审核 · 2026-09-24

> 工作目录 `E:\open-source\koodo-reader` · 合并前 HEAD `0ccac028`（toc模块）· 合并后仍为 `0ccac028`（**未 commit、未 push**）
> 输入：8 个 worktree（P5/P6/P8 八张卡）+ 上一会话的 `docs/MERGE-PLAN-2026-09-24.html`、`docs/BOARD-2026-09-24.html`
> 工具链：Gradle 8.5（`C:\Users\54389\AppData\Local\Gradle\gradle-8.5`）+ JDK 17（`D:\jdk-17\jdk-17.0.13+11`）+ Android SDK（`android/local.properties`）

---

## 0 · TL;DR

| 项 | 结果 |
|---|---|
| 8 张卡产出物并入 dev 工作区 | ✅ 176 个新文件（源码/文档），与各 worktree **逐文件 SHA256 一致** |
| 唯一内容缺口（locale JSON） | ✅ 已按 ADR-004 合并并重新生成，与卡产物逐字节一致 |
| 看板 P1 阻塞 B1（settings/app 注册） | ✅ 闭环：12 个 module 注册 + `:app` 8 个依赖 |
| 看板 P1 阻塞 B2（locale 红状态） | ✅ 闭环：`sync-locales-android.js --check` / `check-locales.mjs` 均通过 |
| 合并暴露的编译阻断 | ⚠️ 7 个 module / 9 个文件**从未被编译过**，已修（见 §2） |
| 全量 `gradle test` | ⚠️ **1154 个唯一测试 / 46 失败**（去重后；Android 库按 debug/release 双变体执行，原始执行数 ~1400）；22 个有测试的 module 中 16 个全绿 |
| `:app:assembleDebug` | 合并当时 ❌（`:app` 自身 10 处源码错误，P2/P3/P7 提交时未编译，见 §3.5）→ **后续已修复并出包**，见 [`docs/android-completeness-2026-09-24.md`](android-completeness-2026-09-24.md) |
| 未完成（看板其余 12 项） | 见 §4.2 |

**结论一句话**：合并本身已完成且可自证（每个新 module 都能编译、测试可跑）；真正的问题不在合并，而在于 **dev 分支上的 Android 原生轨从来没有被构建过**——包括 `:app` 自己。

---

## 1 · 合并清单

### 1.1 文件（8 个 worktree → 模块）

| worktree | 卡 | 并入内容 | 文件数 |
|---|---|---|---|
| `.worktrees/p5-fb2-eval` | P5 FB2/DOCX/HTML/MHTML 评估 | `docs/adr/ADR-006-*.md`、`docs/fb2-docx-html-mhtml-eval.md` | 2 |
| `.worktrees/p5-image` | P5 CBZ/CBR/CBT/CB7 | `android/engine/image/**`、`docs/p5-image-engine-adr.md` | 26 |
| `.worktrees/p6-dict` | P6 词典 | `android/feature/dictionary/**`、`docs/p6-dictionary-architecture.md` | 27 |
| `.worktrees/p6-stats-ocr` | P6 统计 + OCR | `android/feature/stats/**`、`android/feature/ocr/**`、`docs/p6-stats-ocr-design.md`、5 个 locale JSON | 22 |
| `.worktrees/p6-translate` | P6 翻译/AI | `android/feature/translate/**`、`docs/p6-translate-architecture.md` | 25 |
| `.worktrees/p6-tts` | P6 TTS | `android/feature/tts/**`、`docs/p6-tts-semantics-mapping.md` | 25 |
| `.worktrees/p6-zh-locale` | P6 简繁/locale | `android/core/locale/**`、`scripts/check-locales.mjs`、`docs/p6-zh-locale-design.md` | 18 |
| `.worktrees/p8-wrap-up` | P8 收尾 | `android/feature/crash/**`、`android/benchmarks/**`、`scripts/ci-macro-benchmark.sh`、`docs/p8-rollout-*`、`docs/appendix-c-agpl-compliance.md`、`docs/android-baseline-after.json` | 23 |

校验方法：对每个 worktree 的 `git status --porcelain -uall` 文件逐一与主仓同名文件做 SHA256 比对——主仓在本次会话开始时**已存在**这些文件（上一会话已复制），本次只发现 5 个 locale JSON 不一致（§1.2），其余全部一致。

### 1.2 locale（唯一真实内容缺口，B2）

| 文件 | 差异 | 处理 |
|---|---|---|
| `src/assets/locales/en.json` | `"Word count": "Word count"` 缺 1 键 | 取卡产物 |
| `src/assets/locales/zh-CN.json` | `"Word count": "字数"` 缺 1 键 | 取卡产物 |
| `android/app/src/main/assets/locales/{en,zh-CN,manifest}.json` | 是**生成物**，主仓落后（1377 键，synced 01:21） | 不直接覆盖，改跑 `node scripts/sync-locales-android.js` 重新生成 → 与卡产物 `en.json`/`zh-CN.json` **逐字节一致** |

验证：`sync-locales-android.js --check` ✅ · `check-locales.mjs` ✅（41 桌面 locale / 2 打包 / 39 按需，无 WARN）

### 1.3 构建注册（B1）

- `android/settings.gradle`：新增 12 个 `include`——
  卡的 9 个（`:engine:image` `:core:locale` `:feature:stats` `:feature:ocr` `:feature:tts` `:feature:translate` `:feature:dictionary` `:feature:crash` `:benchmarks`）
  **+ 3 个 P2 module（`:engine:annotate` `:engine:link` `:engine:toc`）**——它们已随 `9abe7904`/`0ccac028` 提交，却从未注册，属死代码。
- `android/build.gradle`：补 `com.android.library` 8.2.2 与 `com.android.test` 8.2.2（`apply false`）；原先只声明了 `com.android.application`，库模块无法 apply 插件。
- `android/app/build.gradle`：
  - 8 个 `implementation project(...)`（P2 三引擎 + image + locale + stats/ocr/tts/translate/dictionary + crash）；
  - P8 四个 hunk：`-PsplitAbi` / `-PstripIsland` 开关、`benchmark` build type（`:benchmarks` 的 target 变体）、crash 依赖。
- `android/app/src/main/AndroidManifest.xml`：P8 `<profileable android:shell="true" />`。
- 结果：`gradle projects` 列出 **28 个 module**（2 顶层 + 6 core + 14 engine + 6 feature），配置零告警。

### 1.4 工作区卫生

删除（均为上一轮合并/验证的临时产物，且 `net/` 下的 DLL 曾被 `git add` 进暂存区）：

| 对象 | 说明 |
|---|---|
| `net/rubygrapefruit/platform/windows-amd64/native-platform.dll` | Gradle launcher 解包残渣，**误暂存**，已 `git rm --cached` + 删除 |
| `.merge-temp-stash-backup/` | 588 MB / 2243 文件；与 `.review-tmp` 内容重复，已确认不含唯一数据（`toc/build.gradle` 与仓库一致） |
| `.p6-stats-ocr-check/`、`.tts-verify-test.log`、`$null`、`META-INF/` | 会话脚本/日志/重定向产物 |

`.gitignore` 补 2 行——`android/feature/*/build/`、`android/benchmarks/build/`：原有规则只覆盖 `android/engine/*/build/` 与 `android/core/*/build/`，导致本轮编译后 **1322 个构建产物**处于「可被误提交」状态。

### 1.5 回填 `docs/patches/`

5 份交付文档（`fb2-docx-html-mhtml-eval.md`、`p5-image-engine-adr.md`、`p6-dictionary-architecture.md`、`p6-stats-ocr-design.md`、`p6-zh-locale-design.md`）按路径引用 `docs/patches/*.patch`，故把这 7 个 patch 一并回填。**注意：`p5-fb2-settings-gradle.patch` 的内容已在 `0ccac028` 中落地，重复 apply 会失败。**

---

## 2 · 合并中修掉的编译阻断（这些代码从未被编译过）

注册模块 = 第一次真正编译它们。以下 9 个文件报错，均已最小化修复：

| 文件 | 错误 | 修法 |
|---|---|---|
| `engine/annotate/.../JsonValue.kt` | `entries[k] = v` 落在 `LinkedHashMap` 的只读 `entries` 上 | 改 `put(k, v)` |
| 同上 | `'\f'` —— **Kotlin 无 `\f` 转义**（Java 有） | 改 `'\u000C'` |
| `engine/annotate/.../AnnotationSchemaAlignmentTest.kt` | 调用成员扩展 `TableSpec.mismatches()` 未导入 | 加 `import ...AnnotationSchema.mismatches` |
| `engine/link/.../Footnote.kt` | 解构 lambda `(_, n) ->` 里使用 `it` | 改为命名参数 `(entry, n)` |
| `engine/link/.../LinkClassifier.kt` | **行为缺陷**：`epubcfi:2` 被判为可跳转 | CFI 体必须 `/` 开头（`:engine:cfi` 为对齐上游 epubcfi.js 不校验）；修后 72/72 通过 |
| `engine/toc/.../TocModel.kt` | 实例方法 `flatten` 被 companion object 调用（2 处）；`flatMap { flatten(it) }` 传的是节点不是列表 | `flatten` 移入 companion；改 `flatten(resolved)` / `flatten(rootNodes)` |
| `engine/toc/.../TocSelfCheck.kt` | 局部 `val` 先用后声明；`check(search(...).isEmpty()))` 括号错位 | 上移声明；补括号 |
| `core/locale/.../LocalePackStore.kt` | 文件索引是 `Map<String,String>`，却写入 `LocalePackInfo`（2 处，另一处内存索引本应保持对象） | 落盘处改 `info.encode()`，内存索引保持 `info` |
| `core/locale/.../LocaleSelfCheck.kt` | `failures.add(it)` —— `Throwable` 加入 `List<String>`（8 处） | 改 `it.message ?: it.toString()` |
| `feature/dictionary/.../ui/PopupWordDialog.kt` | `@Composable` 被放进 `AndroidView.update{}` 回调 | 提到 composable 体内求值 |
| `feature/tts/.../EngineEnumerator.kt` | `TextToSpeech.getDefaultEngine()` **不是静态方法**（javap 实证：`public java.lang.String getDefaultEngine()`） | 改读框架同源设置 `Settings.Secure.TTS_DEFAULT_SYNTH` |
| `feature/crash/src/test/.../RedactionOnlyCallbackTest.kt` | `CrashBreadcrumb` 缺 `atMillis` | 补 `atMillis = 0L` |

附带修复（`:app` 的既有依赖链）：`engine/pdf` 是「零依赖纯 JVM」module，却 `import org.json.*`（android.jar 独有）→ 加 `compileOnly` + `testImplementation org.json:json:20231013`；随后又暴露 3 处源码错误（`OutlineResolver.resolve` 返回 `List` 而非 `Tree`、`.isEmpty` 少括号、`PdfAnnotation.build(cfi = …)` 参数名应为 `cfiJson`，测试同错）——一并修掉。

### 2.1 无法修复、已隔离：OCR 的 Play-services 下载层

`feature/ocr/.../platform/MlKitModelDownloader.kt` **不可能**按现有依赖编译，且不是笔误：

```
javap play-services-mlkit-text-recognition-19.0.1/classes.jar
  com.google.mlkit.vision.text.latin.TextRecognizerOptions
    implements com.google.mlkit.vision.text.TextRecognizerOptionsInterface   ← 不是 OptionalModuleApi
javap ...TextRecognizerOptionsInterface
  public interface ... { getModuleId(); ... }   ← 没有 getOptionalFeatures()
```
而 `ModuleInstallClient.areModulesAvailable/installModules` 要求 `com.google.android.gms.common.api.OptionalModuleApi`。19.0.1 是**最新版**（19.0.0 相同），已发布版本里 options 类都不实现它；也无法用适配器桥接（接口不暴露 `Feature[]`）。

处理：`feature/ocr/build.gradle` 头部记录 BLOCKED 证据，并把 `MlKitModelDownloader.kt` + 唯一消费它的 `OcrIndexDatabase.kt` 从 Kotlin 编译任务排除（同 `:engine:image` 排除 Compose host 的做法）。纯 Kotlin 逻辑层不受影响——`:feature:ocr` 58/58 测试通过。

解除需要设计决策：① 改用 ML Kit 自身 recognizer 触发下载并据此表达 `DownloadState`；② 换 bundled `com.google.mlkit:text-recognition*`（代价：每脚本约 4 MB + 16 KB .so 风险，正是卡里刻意规避的）；③ 只保留 manifest `DEPENDENCIES` 预下载，去掉 ModuleInstall 层。

---

## 3 · 验证证据

### 3.1 测试矩阵（`gradle -p android --continue test`，全量重跑）

| module | 测试 | 失败 | |
|---|---|---|---|
| core:common | 22 | 0 | ✅ |
| core:importer | 38 | 0 | ✅ |
| core:locale | 51 | **4** | ❌ P6 卡交付 |
| core:dbio | 22 | **3** | ❌ 既有 |
| engine:cfi | 4 | 0 | ✅ |
| engine:annotate | 112 | **11** | ❌ 既有（P2，从未编译） |
| engine:feature | 84 | 0 | ✅ |
| engine:gesture | 40 | **6** | ❌ 既有（P2） |
| engine:image | 67 | 0 | ✅ P5 卡交付 |
| engine:layout | 109 | 0 | ✅ |
| engine:link | 72 | 0 | ✅（修复后） |
| engine:mobi | 74 | 0 | ✅ |
| engine:pdf | 61 | **5** | ❌ 既有（P3） |
| engine:text | 71 | 0 | ✅ |
| engine:toc | 42 | **17** | ❌ 既有（`0ccac028`，从未编译） |
| feature:crash | 15 | 0 | ✅ P8 卡交付 |
| feature:dictionary | 126 | 0 | ✅ P6 卡交付 |
| feature:ocr | 58 | 0 | ✅ P6 卡交付（下载层已隔离） |
| feature:stats | 68 | 0 | ✅ P6 卡交付 |
| feature:translate | 156 | 0 | ✅ P6 卡交付 |
| feature:tts | 110 | 0 | ✅ P6 卡交付 |
| **合计** | **1154（去重）** | **46** | 22 个有测试的 module 中 16 个全绿 |

> 上表的 feature:* 数字在首次统计时被 debug/release 双变体重复计入（dictionary 126→63、ocr 58→29、stats 68→34、translate 156→78、tts 110→55）；此处为去重后的唯一测试数。「原始执行数约 1400」是 Gradle 实际跑的次数。
>
> `:app` 的 `LibraryLogicTest`（11 个）在合并当时取自 **11:22 的旧构建**（当时 `:app` 无法编译），后续修复后已重新跑过：**11/11 通过**（`:app` 共 3 个变体 × 11）。
>
> **补记（同日）**：下表这 46 个失败**已全部修复**——`gradle -p android --continue test` 现在 BUILD SUCCESSFUL，**1155 个唯一测试 / 0 失败 / 22 个 module 全绿**。逐 module 的提交与结论（含「实现缺陷 vs 测试期望写错」的分类）见 [`docs/android-completeness-2026-09-24.md`](android-completeness-2026-09-24.md) §8。

典型失败（按严重度）：

1. **数据损坏风险** — `engine:toc` `ReadingPosition` 编解码产出**非法 JSON**：`Expected '"' at position 20 in {"bookKey":"my-book",spineIndex:3,...}`（键未加引号）；`ProgressComputer` 百分比全错（期望 1.0 得 0.333、期望 0.5 得 0.0），`SearchIndex` 命中数/排名偏移，`TocModel.lookupByTitle` 空查询返回 1 条。
2. `engine:annotate` — CFI range 处理抛 `CFI_RANGE_INCOMPLETE`；`isValid` 对畸形 CFI 返回 `true`；日期往返 2024-01-01 → 2023-11-15；golden vector 3 跨章 range 地址不对。
3. `engine:gesture` — 正向速度翻页得到 `Overscroll`、零位移得到 `PageTurn`、右点击区映射成上一页（6 项物理/手势逻辑）。
4. `core:locale` — 回退链把 `<Language>` 解析成 `<L>`；OpenCC MaxMatch 最长键未命中；`網上/軟體/網路` 台味用词与桌面 `convertWire` 语义不一致。
5. `engine:pdf` — `OutlineResolverTest`、`PdfSnapshotExporterTest`、`PdfViewModeTest`、`PdfViewportMathTest`（2）。
6. `core:dbio` — 桌面 `exportType` 契约断言、md5 bookKey 解析、单书导出形态；另 1 项为 `JUnitException: Failed to close extension context`（Windows 临时目录，可能环境相关）。

已核验这些失败与本次合并无关：相关测试不读主仓被改文件（无 `src/assets`、`Paths.get` 引用）；`src/assets/locales` 仅新增 1 键；`:core:dbio` 等 module 在上一会话的测试矩阵中从未运行。

### 3.2 构建图

`gradle projects` ✅ 28 module；`com.android.test` 的 `:benchmarks` 与 `:app` 的 `benchmark` build type 均被接受。

### 3.3 仓库守卫脚本

| 脚本 | 结果 |
|---|---|
| `check-room-schema.js` | ✅ 5 表对齐 schema.lock |
| `check-dbio-ddl.js` | ✅ 5 表对齐 |
| `check-import-rules.js` | ✅ |
| `gen-cfi-golden.js --check` | ✅ 76 向量未漂移 |
| `sync-locales-android.js --check` | ✅ |
| `check-locales.mjs` | ✅ 无 WARN |
| `check-elf-16kb.js` | ⚠️ 需 APK（出包后才能跑） |

### 3.4 `:feature:ocr` 隔离验证

`:feature:ocr:testDebugUnitTest` ✅ 58 测试（3 个测试类）；`:feature:ocr:compileDebug/ReleaseKotlin` ✅（排除 2 文件后）。

### 3.5 `:app:assembleDebug` 当时 ❌ —— **不是合并造成的**，后续已修复

> **补记（同日稍后）**：这 10 处错误已全部修掉，`:app` 现在 debug/release/benchmark 三个变体都能编译，`assembleDebug` / `assembleRelease` 均成功；修法与出包数据见 [`docs/android-completeness-2026-09-24.md`](android-completeness-2026-09-24.md) §1、§2。下面保留当时的原始证据，用于说明「问题在 dev 分支既有、与本次合并无关」。

```
> Task :app:compileDebugKotlin FAILED   (+ compileReleaseKotlin / compileBenchmarkKotlin)
e: .../shell/DesktopBridge.kt:372  Smart cast to 'String' is impossible ... 'b.md5' is a public API property declared in different module
e: .../shell/NativePdfScreen.kt    Unresolved reference: MenuBook / ZoomIn / ZoomOut / collectAsState
e: .../shell/NativeReaderScreen.kt:50  Expression 'viewModel' of type 'LibraryViewModel' cannot be invoked as a function
e: .../shell/ReaderGestureModifier.kt:140  Unresolved reference: ReaderGestureModifier
```

证据链（证明是既有问题）：

1. `git diff HEAD -- android/app/src/main/java` **为空**——本次会话一个字节都没动 `:app` 的 Kotlin 源码；
2. 出错文件分别来自 `141ce11b`（P3）、`9abe7904`（P2）、`fef93db0`（P7）；
3. `:app` 上一次成功的测试产物时间戳是 **11:22**，而这些提交是 **17:33** 之后的，等于这些提交从未被构建过；
4. 上一会话的 `test-matrix4.log` 中 `:engine:pdf:compileKotlin` 已经 FAILED（同一根因的另一个面）。

---

## 4 · 完成度审核结论

### 4.1 八张卡（代码本身）

| 卡 | 结论 |
|---|---|
| P5-FB2 评估 | ✅ 文档齐全（ADR-006 + 评估 + settings patch），无代码 |
| P5-CBZ 图片 | ✅ 67/67；`SevenZExtractor`(CB7)/`RarExtractor`(CBR) 仍是骨架（卡内已声明） |
| P6-TTS | ⚠️ 代码 110/110 通过，但**宿主接线未做**：manifest `<queries>`/`<service>`/`<receiver>`、POST_NOTIFICATIONS、通知图标、4 个 i18n key（= 看板 P6-TTS-WIRE） |
| P6-词典 | ✅ 126/126 + 模块已注册；与 OCR 的 `OnDemandDownloader` 归属单一化仍待定 |
| P6-翻译/AI | ✅ 156/156；history Room 表与 koodo.db 仍独立（= 看板 P6-TRANS-DB） |
| P6-统计+OCR | ⚠️ stats 68/68 ✅；OCR 逻辑 58/58 ✅ 但**下载层 BLOCKED**（§2.1） |
| P6-简繁/locale | ⚠️ 51 中 4 失败（OpenCC/回退链），其余通过；`check-locales.mjs` 已纳入仓库 |
| P8 收尾 | ⚠️ crash 15/15 ✅、benchmarks 已注册且 `:app` 有 `benchmark` 变体；但 `-PstripIsland` 仍被 P8-F1（intent 路由迁移）挡住，体积优化未实测，AGPL 3 缺口未闭环 |

### 4.2 看板 14 项状态

| 状态 | 项 |
|---|---|
| ✅ 本会话闭环 | **B1** settings/app 注册、**B2** locale 红状态 |
| ❌ 仍开（12 项） | D0（P2 XHTML→TextBlock 扁平化器）、R1（core/archive 抽象）、P5-CBZ-1（SevenZ 实现）、P5-CBZ-5（兜底岛路由切换）、P8-F1（intent 路由迁移）、P6-TTS-WIRE、P6-OCR-16K、P6-TRANS-DB、AGPL-GAP、P5-NATORD、P1-DEVICE（真机 4 指标）、P1-IMPORT（千本导入） |

### 4.3 本次新增发现（看板之外）

1. **3 个 P2 module 提交未注册 = 死代码**（`:engine:annotate` `:engine:link` `:engine:toc`）。
2. **9 个文件 / 7 个 module 从未编译过**——上一轮的「单测通过」是在各自 worktree 的一次性 JVM 工程里跑的，与真实 Gradle 构建不等价。
3. **`:app` 与 `:engine:pdf` 在 dev HEAD 就已无法构建**（§3.5），`:engine:pdf` 甚至用了 android.jar 独有的 `org.json` 却自称零依赖纯 JVM module。
4. **46 个行为测试失败**散布 6 个 module，其中 `engine:toc` 的 `ReadingPosition` 非法 JSON 属数据损坏级。
5. `.gitignore` 漏 `android/feature/*/build/`、`android/benchmarks/build/`（已补）。
6. `docs/android-baseline-after.json` 里的「本机无 gradle CLI / 无构建能力」记录有误：本机存在 Gradle 8.5 发行版与 `D:\jdk-17`，加 `org.gradle.java.installations.paths` 即可构建（本会话即以此完成验证）。
7. **4 个「幽灵 module」**：`settings.gradle` 里的 `:core:archive` / `:engine:fb2` / `:engine:htmlbook` / `:engine:docx` 来自 P5-FB2 评估卡的 settings 模板，但目录从未创建；Gradle 容忍空工程，于是 4 个计划中的 module 在 `gradle projects` 里显示为已交付。（后续已注释并标注解除条件 → 真实 module 数 = 24）
8. **APK 不再是「零 .so」**：`:feature:tts` 引入 `androidx.datastore` 后带进 `libdatastore_shared_counter.so`（7 KB，经 ELF program header 核对 `p_align=0x4000`，满足 Android 15+ 16 KB 要求）；同时 `scripts/check-elf-16kb.js` 依赖 `unzip`+`readelf`，Windows/CI 上跑不了。

---

## 5 · 建议

1. **提交前先拍板**：OCR 下载层方案（§2.1）与 46 个失败测试是修还是登记为卡（提交本身已由用户授权完成，见 §6）。
2. 把「`gradle test` + `:app:assembleDebug`」加入 CI 门槛——本轮所有问题的根因都是「提交前没有编译」。
3. ~~优先修 `:app` 的编译错误~~ **已完成**：10 处错误全修，`:app` 现在 debug/release/benchmark 均可编译并出包（见 `docs/android-completeness-2026-09-24.md`）。
4. `engine:toc` 的 `ReadingPosition` JSON 编解码优先于其它 toc 失败项（写入用户数据）。
5. 清理：9 个 worktree（含 2 个 locked）、10 个仍是 `9abe7904` 的 `task/*` 分支（其中 `task/p6-translate-tran` 无 worktree，是重复分支）。
6. 补 `.gitignore` 后确认无构建产物被 `git add`；`docs/patches/p5-fb2-settings-gradle.patch` 已应用，勿重复 apply。
7. **接线优先于继续铺模块**：P6 六个模块与 P2 的 EPUB 链路仍悬在 `ShellNavHost` 之外（见 `docs/android-completeness-2026-09-24.md` §4）。
   - **勘误（后续一轮）**：当时写的「只有 PDF 一条原生阅读链路可达」只对到**路由**这一层。查证后 `NativePdfScreen` 是一段占位文本，`PdfJsHostBridge`/`PdfRendererSnapshot` 在 `:app` 里**没有任何实例化点**，且引擎侧还有 6 个「编译通过但永不生效」的缺陷（资源根、80 端口死 URL、字符串结果被 JSON 二次编码、搜索扫不存在的 DOM 等）。该链路已在本轮真正接线，逐条证据见 `docs/android-completeness-2026-09-24.md` §10。

## 6 · 边界（本次未做）

- ~~未执行 `git commit` / `git push`~~：**后续已按用户授权把合并结果与修复提交为 15+2 个 commit（仍未 push）**。
- 未修 46 个行为失败（属新工作项，非「合并」范畴），仅修「不修就无法编译/无法验证」的阻断。
- 未跑真机、未跑 macrobenchmark（无设备，用户明确要求跳过）；release 出包为**未签名**形态。
- 未改桌面端（React/Electron）代码，仅新增 2 个 locale key。
- 后续的完整度验证另见 [`docs/android-completeness-2026-09-24.md`](android-completeness-2026-09-24.md)。
