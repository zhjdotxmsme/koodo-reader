# M4a 审查修复 + 既有红灯清理（2026-11-19）

> 对应计划：`docs/superpowers/plans/2026-11-19-android-m4a-review-fixes.md`
> 基线：`2504ddf2`（M4a 大提交）
> 来源：M4a 完成情况核查（Task 8 证据）+ 独立代码审查 `42433fd3..2504ddf2`（结论：With fixes）

本轮只做定点修复，不改架构；每个 Task 一个独立提交。

## 修复清单

| # | 级别 | 问题 | 修复 | 提交 |
|---|------|------|------|------|
| 1 | Critical | `dicts/catalog.json` 被 M4a 从 11 条目减到 3 条目；`check-dict-catalog`（`release-android.yml:90`）要求与桌面 `CloudDictList` 一致（EXPECTED_COUNT = 11）→ 下次 tag 发版必失败 | 恢复 `f886f30c` 版本的 11 条目 | `7f3c0564` |
| 2 | 既有红灯 | `android/benchmarks` 从未编译通过：4 个源文件导入 `androidx.benchmark.macro.MacrobenchmarkRule`，而 `benchmark-macro-junit4:1.2.4` 中该类在 `androidx.benchmark.macro.junit4` 包 | 修正 3 处导入；`TraceSectionMetric` 需要 `@ExperimentalMetricApi` opt-in | `ffeb6859` |
| 3 | 既有红灯 | `PdfReflowHighlightsTest`：`CfiPdfMapper.serialise` 在 JVM 上调用 `org.json.JSONObject.put`，命中 android.jar 的 "not mocked" stub | `:app` 补 `testImplementation 'org.json:json:20231013'`（沿用 `:engine:pdf` 的既有模式） | `e2920b85` |
| 4 | 既有红灯 | `test-pdfengine.js` 62 项中 1 项失败：引擎成功路径回调 `host.onResult(id, payload, null)`，测试断言 `error === ''` | 断言对齐 `FoliateJsResult.errorOrNull` 契约（`null` 与 `''` 都表示成功）；**只改测试，不改引擎** | `2646fdea` |
| 5 | Important | `BookMetadataDialog` 在 `remember(book.key)` 里同步调用 `BookIsbnProbe.probe(book)`（完整 `EpubBook.parse` / `MobiMetadataReader.read`），组合发生在主线程 | `LaunchedEffect` + `Dispatchers.IO` 异步探测，探测期间显示进度条 | `c86fc670` |
| 6 | Important | `android-pull-device-backup.ps1` 用 `Invoke-Adb shell ... \| Set-Content -Encoding Byte` 和 `>` 拉取二进制：PS 5.1 下文本流会 UTF-16 重编码静默损坏；且第 29/31 行对同一文件拉了两次 | 改 `adb exec-out` + 进程级 `BaseStream.CopyTo` 字节级拉取 + 大小校验；顺带消除重复拉取 | `da006b30` |
| 7 | Important | 导入白名单 `BookRules.LIBRARY_IMPORT_EXTENSIONS`（epub/pdf/mobi/azw/azw3）被拒绝时无任何格式说明 | 新增**仅安卓引用**的 i18n key（共享 key 不能改，桌面支持更多格式） | `eb48eab7` |
| 8 | Minor 批量 | GoogleBooks 查询串用 `+` 连接（会被编码成字面 `%2B`）；4 个缺失 i18n key；`BookMetadataSearchUiState.Failed` 是死状态；`ReaderControlCenterTabs.forFormat` 的 `useFoliate` 是死参数 | 空格连接 + 补 4 个 key（en/zh-CN）+ Failed 状态接线 + 删死参数 | `c6771c77` |

### 第 6 项的额外发现（超出原计划）

原计划只提到二进制损坏。实测发现 `Invoke-Adb` 本身在 Windows PowerShell 5.1 下也是坏的：

```powershell
& @($base + $Args)   # 调用运算符把数组拼成"一个命令名"
```

错误信息为 `The term 'adb.exe shell ...' is not recognized`，也就是说 **adb 根本没有被执行**，
备份脚本从来就没真正拉过数据（`.tools/device-backup/` 里 2026-10-20 的产物来自更早的脚本版本）。
正确的写法是逐个 splat：`& $adb @base @Args`。本轮一并修复。

## 验证

```
node scripts/check-dict-catalog.js      → OK — 11 cloud dicts match desktop
node scripts/test-foliate-host.js        → OK  49 passed
node scripts/test-pdfengine.js           → OK — 62 checks passed
node scripts/sync-locales-android.js --check → OK (11 locales)
gradlew :benchmarks:compileBenchmarkKotlin   → BUILD SUCCESSFUL（首次编译通过）
gradlew :core:designsystem:test :core:designsystem:selfCheck
        :engine:annotate:test :core:importer:test
        :app:testNativeHostDebugUnitTest :app:testWebviewDebugUnitTest
        :app:assembleNativeHostDebug        → BUILD SUCCESSFUL
```

测试汇总：**3851 tests, 0 failed**（app 1967 / engine 933 / feature 503 / core 448）。
`:app` 单测 339 项全绿，含此前红灯 `PdfReflowHighlightsTest`。

### 第 6 项的字节级验证

真机往返（Task 10）尚未进行，因此本轮用一个 mock adb 做了字节级回归：
payload 刻意包含 NUL、`0xFF`、`0x1A`、CRLF、UTF-8 BOM 与非法 UTF-8 序列。

- 修复后的脚本：每个文件与设备 payload **逐字节相同**。
- 修复前的脚本：**连文件都没产出**（splatting 缺陷），证实原脚本从未正常工作。

## 遗留

- **Task 10（阻塞）**：M4a T10 真机三点清单 + 备份脚本真机往返 + 可选 benchmark device run，
  需要 USB 设备（`adb devices` 当前为空）。macrobenchmark 必须真机，不用模拟器替代。
- `check-locales.mjs` 2 处注册表漂移（`b59ad363` 既有）不在本计划范围，需单独产品决策。
- `.superpowers/sdd/progress.md` 已被 `.gitignore` 忽略（`/.superpowers`），故本轮过程记录落在此文件。
