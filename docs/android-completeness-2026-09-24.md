# Android 原生轨完整度验证 · 2026-09-24

> 继 `docs/MERGE-AUDIT-2026-09-24.md`（合并 + 代码审核）之后的**可运行性验证**。
> 工作目录 `E:\open-source\koodo-reader` · HEAD 为合并后的 15 个 commit（未 push）
> 工具链：Gradle 8.5（`C:\Users\54389\AppData\Local\Gradle\gradle-8.5`）+ JDK 17（`D:\jdk-17\jdk-17.0.13+11`）+ Android SDK（`android/local.properties`）
> **真机相关校验一律跳过**（用户要求）：无设备、无 Macrobenchmark、无冷启动/翻页/内存指标。

---

## 0 · 结论

| 维度 | 结论 |
|---|---|
| 能否构建 | ✅ **`:app` 首次可构建**：debug / release / benchmark 三个变体全部编译通过；`assembleDebug`、`assembleRelease` 均 BUILD SUCCESSFUL |
| 产物 | debug 30.97 MB（含兜底岛）· `-PstripIsland=true` 23.12 MB · release(R8、未签名) 18.78 MB |
| 单元测试 | 1154 个唯一测试 / **46 失败** / 22 个有测试的 module 中 **16 个全绿**（失败集中在 6 个既有 module） |
| Module 结构 | **24 个真实 module** 全部注册且可编译（此前 `gradle projects` 里 4 个是「幽灵工程」——目录不存在，已注释并标注） |
| 产品可用面 | ⚠️ **只有 3 条链路在 UI 里可达**：书库/回收站/备份（P1+P7）与 **PDF 阅读（P3）**。EPUB/MOBI/TXT/MD/CBZ 阅读仍走兜底岛或占位页；P6 六个功能模块（TTS/词典/翻译/OCR/统计/简繁）**已交付、已测，但没有任何入口** |
| 无法在此验证 | 真机 4 项指标、1000 本导入、Macrobenchmark（按用户要求跳过） |

一句话：**代码交付面很宽（1154 个测试、24 个 module 全部可编译），产品接线面很窄（只有 PDF 一条原生阅读链路）。**

---

## 1 · 本次为「可构建」修掉的问题

合并后第一次真正编译 `:app`，暴露 10 个错误（全部来自 P2/P3/P7 提交，`git diff HEAD -- android/app/src/main/java` 当时为空，即提交时从未编译）：

| 文件 | 错误 | 修法 |
|---|---|---|
| `shell/NativePdfScreen.kt` | `collectAsState` 缺 import | 补 `androidx.compose.runtime.collectAsState` |
| 同上 | `Icons.Filled.MenuBook / ZoomIn / ZoomOut` 在 `material-icons-extended`，本项目只用 `material-icons-core` | Outline 改用 `Icons.AutoMirrored.Filled.List`；缩放改用 `−` / `+` 文本（避免为一个图标引入 ~20 MB 的 extended 依赖，见下） |
| `shell/NativeReaderScreen.kt` | `viewModel()` 缺 import → 被解析成调用同名参数 | 补 `androidx.lifecycle.viewmodel.compose.viewModel` |
| `shell/DesktopBridge.kt:372` | 跨 module 公开属性 `b.md5` 无法 smart cast | 用局部 `val md5` |
| `shell/ReaderGestureModifier.kt:140` | `ReaderGestureModifier` 是 `@Composable` **函数**（返回 `Modifier`），却被当作类型 | 返回类型改 `Pair<Modifier, Float>`；`offsetPx` 补 `remember` |

另有两处「状态不实」的清理：

1. **4 个幽灵 module**：`settings.gradle` 里 `:core:archive` / `:engine:fb2` / `:engine:htmlbook` / `:engine:docx` 来自 P5-FB2 评估卡的 **settings 模板**，但目录从未创建。Gradle 容忍它们（空工程、无任务），于是 4 个「计划中的」module 在 `gradle projects` 里看起来像已交付。已注释掉并注明解除条件（看板 D0 + R1 + P5.5 卡）。
2. **`:feature:ocr` 的下载适配层**：`MlKitModelDownloader.kt` 依赖的 `OptionalModuleApi` 在 ML Kit 所有已发布版本里都不存在（19.0.1 是最新版），已连同唯一消费方 `OcrIndexDatabase.kt` 隔离出编译，模块其余逻辑 29/29 通过。

---

## 2 · 构建验证

```
gradle -p android :app:compileDebugKotlin :app:compileReleaseKotlin :app:compileBenchmarkKotlin   → BUILD SUCCESSFUL
gradle -p android :app:assembleDebug -Ptarget=native                                              → BUILD SUCCESSFUL
gradle -p android :app:assembleDebug -Ptarget=native -PstripIsland=true -PsplitAbi=true           → BUILD SUCCESSFUL
gradle -p android :app:assembleRelease -Ptarget=native                                           → BUILD SUCCESSFUL
```

| 形态 | APK | 磁盘 | 条目压缩和 | dex | res | assets/webapp | 其他 assets | lib |
|---|---|---|---|---|---|---|---|---|
| debug（默认） | `app-debug.apk` | **30.97 MB** | 30.79 MB | 11.97 MB | 8.43 MB | 7.78 MB | pdfengine 1.74 + locales 0.06 | 0.02 |
| debug `-PstripIsland` | `app-debug.apk` | **23.12 MB** | 22.99 MB | 11.97 MB | 8.43 MB | **0（条目数 0）** | pdfengine 1.74 | 0.02 |
| release（R8，未签名） | `app-release-unsigned.apk` | **18.78 MB** | 18.64 MB | **1.18 MB** | 8.43 MB | 6.62 MB | — | 0.02 |

解读：

- **P8 L1 杠杆实测成立**：`-PstripIsland=true` 省 7.85 MB 且 `assets/webapp` 条目归零（`docs/android-baseline-after.json` 的 `after.measured` 已回填，status 由 `pending-build` 改为 `measured`）。
- **P8 L3 杠杆实测**：R8 把 dex 从 11.97 MB 压到 1.18 MB。release 形态下 **res 的 8.43 MB（其中 8.25 MB 是内置字体 lxgw_wenkai_lite.ttf）成为最大单项**，即 P8 的 L2 杠杆。
- **`.so` 不再是 0**：`:feature:tts` 引入 `androidx.datastore` 后，APK 内出现 `lib/arm64-v8a/libdatastore_shared_counter.so`（7112 B）。用直接解析 ELF program header 的方式核对：`PT_LOAD p_align = 0x4000 (16384)` → **满足 Android 15+ 16 KB page size 要求**。
- ~~`scripts/check-elf-16kb.js` 本身**在本机跑不了**（需要 `unzip` + `readelf`，Windows 上没有）~~ → **已修复**：脚本重写为纯 Node（自解析 ZIP + ELF program header），本机实测通过；CI 已接入（见 §8）。

---

## 3 · 测试矩阵（去重后）

```
gradle -p android --continue test     → BUILD FAILED（6 个 module 的既有失败）
```

> Android 库 module 会按 debug/release 两个变体各跑一遍，下表为**去重后**的唯一测试数（原始执行数约 1400）。

| module | 测试 | 失败 | | module | 测试 | 失败 |
|---|---|---|---|---|---|---|
| `:app` | 11 | 0 ✅ | | `engine:mobi` | 74 | 0 ✅ |
| `core:common` | 22 | 0 ✅ | | `engine:pdf` | 61 | **5** ❌ |
| `core:dbio` | 22 | **3** ❌ | | `engine:text` | 71 | 0 ✅ |
| `core:importer` | 38 | 0 ✅ | | `engine:toc` | 42 | **17** ❌ |
| `core:locale` | 51 | **4** ❌ | | `feature:crash` | 15 | 0 ✅ |
| `engine:annotate` | 112 | **11** ❌ | | `feature:dictionary` | 63 | 0 ✅ |
| `engine:cfi` | 4 | 0 ✅ | | `feature:ocr` | 29 | 0 ✅ |
| `engine:feature` | 84 | 0 ✅ | | `feature:stats` | 34 | 0 ✅ |
| `engine:gesture` | 40 | **6** ❌ | | `feature:translate` | 78 | 0 ✅ |
| `engine:image` | 67 | 0 ✅ | | `feature:tts` | 55 | 0 ✅ |
| `engine:layout` | 109 | 0 ✅ | | | | |
| `engine:link` | 72 | 0 ✅ | | **合计** | **1154** | **46** |

失败清单一句话版（完整清单见 `docs/MERGE-AUDIT-2026-09-24.md` §3.1）：

- `engine:toc` 17 项最严重 —— `ReadingPosition` 编解码产出**非法 JSON**（键未加引号，属写用户数据），`ProgressComputer` 百分比全错，`SearchIndex` 命中/排名偏移。
- `engine:annotate` 11 项 —— CFI range 处理、`isValid` 对畸形 CFI 返回 true、日期往返 2024-01-01 → 2023-11-15。
- `engine:gesture` 6 项 —— 手势/点击区映射错位。
- `core:locale` 4 项 —— OpenCC MaxMatch、回退链、台味用词。
- `engine:pdf` 5 项 —— outline 前序遍历、snapshot、viewport 数学。
- `core:dbio` 3 项 —— 桌面 `exportType` 契约、md5 bookKey、单书导出形态。

---

## 4 · 功能完整度（对照 `docs/android-native-migration.md` 的 P0–P8）

图例：✅ 已交付且可达 / 🟡 已交付但未接线（休眠）/ ❌ 未交付

| 阶段 | 计划目标 | 代码 | 可编译 | 测试 | UI 可达 |
|---|---|---|---|---|---|
| P0 基线 | 基线文档、脚本、schema.lock | ✅ | — | — | n/a |
| P1 原生壳 + 数据层 | Compose 壳、Room、SAF 导入、书库/回收站/备份 | ✅ `:app` shell + `core:data/dbio/importer/common` | ✅ | `:app` 11 · core 82 | ✅ 书库 / 回收站 / 备份 |
| **P2 EPUB 原生阅读器 ★** | 分页/主题/目录/进度/标注/脚注/内链 | 🟡 `engine:{cfi,layout,gesture,annotate,link,toc}` + 字体体系 | ✅ | layout 109 · link 72 · cfi 4 · gesture 40 · annotate 112 · toc 42 | ❌ **未接线**：`ShellNavHost` 只有 `"PDF"` 分支，其余格式落到 `ReaderPlaceholderScreen`；`NativeReaderScreen` 存在但**不可达** |
| **P3 PDF 原生阅读器 ★** | pdf.js WebView 渲染、搜索、大纲、密码、批注 | ✅ `engine:pdf` + `pdfhost` + `NativePdfScreen` | ✅ | 61 | ✅ `format == "PDF"`；但工具栏 **大纲/搜索/导出快照仍是 TODO 空操作** |
| P4 MOBI/AZW3 | PalmDOC + MOBI6/KF8 + EXTH | 🟡 `engine:mobi`（HUFF/CDIC 明确未实现，抛类型化错误） | ✅ | 74 | ❌ 未接线（走兜底岛） |
| P5 TXT/MD | 编码探测 + Markdown 子集 | 🟡 `engine:text` | ✅ | 71 | ❌ 未接线 |
| P5 CBZ/CBR/CBT/CB7 | 懒加载图片阅读器 | 🟡 `engine:image`（CBZ/CBT/CB7 实装，**CBR 不原生**） | ✅ | 78 | ❌ 未接线 |
| P5.5 FB2/DOCX/HTML/MHTML | 单独立项评估 | ✅ 评估 + ADR-006（**仅文档**） | n/a | n/a | ❌ 4 个 include 是幽灵工程（已注释） |
| P6 阅读增强 | TTS / 词典 / 翻译·AI / 段落·速读·阅读尺 / 统计 / OCR | 🟡 6 个 feature module + `core:locale` 全部交付 | ✅ | 6 模块 289 全绿；locale 51（4 失败） | ❌ **无任何入口**（`ShellNavHost` 里没有对应屏幕） |
| P7 本地备份 | zip 导入导出、数据导入导出（无云同步） | ✅ `BackupScreen` + `core:dbio` | ✅ | 22（3 失败） | ✅ |
| P8 收尾 | 兜底岛下线、体积优化、Crash、Macrobenchmark | ✅ crash + benchmarks + 4 个 patch；体积已实测 | ✅ | crash 15 | 🟡 默认构建**仍打包兜底岛**（等 P8-F1 intent 路由迁移）；`:benchmarks` 需真机 |

补充量化（源码规模）：

| 区域 | 文件 | 行数 |
|---|---|---|
| `android/engine`（11 个引擎 module） | 167 | 23,658 |
| `android/feature`（6 个功能 module） | 96 | 15,443 |
| `android/core`（5 个核心 module） | 64 | 8,797 |
| `:app`（Compose 壳 + WebView 宿主） | 32 | 4,906 |
| `android/benchmarks` | 5 | 366 |

**关键结构事实**：`:app` 源码实际只 `import` 了 6 个 module —— `core:common`、`core:data`、`core:dbio`、`core:importer`、`engine:gesture`、`engine:pdf`。其余已注册、已接进 `:app` classpath 的 module（`engine:annotate/link/toc/image`、`core:locale`、`feature:stats/ocr/tts/translate/dictionary/crash`）**没有任何调用点**，属「编译期在、运行期不用」。

---

## 5 · 缺口清单（按优先级）

| # | 缺口 | 影响 | 阻断条件 |
|---|---|---|---|
| 1 | **EPUB 原生阅读器未接线** | P2（8–12 周的主力阶段）在 UI 上等于没做；EPUB 仍走兜底岛 | 需要 reader host：`:engine:layout` 接管排版 + `NativeReaderScreen` 接入 `ShellNavHost` + 与 CFI 存储打通 |
| 2 | **P6 六个模块无入口** | TTS/词典/翻译/OCR/统计/简繁 交付了但用户摸不到 | 需要各自宿主屏幕 + 导航项 + manifest 接线（TTS 前台服务/通知/`<queries>` 未加） |
| 3 | ~~`engine:toc` ReadingPosition JSON 非法~~ | **已修**（见 §8） | — |
| 4 | ~~46 个失败测试~~ | **已全绿**：1155 个唯一测试 / 0 失败 / 22 module 全绿（见 §8） | — |
| 5 | OCR 下载适配层 | OCR 无法按需下载模型 | ML Kit 的 options 不是 `OptionalModuleApi`，需改设计（改跟随 ML Kit 自身下载 / 换 bundled 制品） |
| 6 | ~~CB7（7z）~~ 已修 / CBR（rar） | CB7 可原生读；CBR 仍不可 | CB7 已接 commons-compress（见 §9）；**CBR 按 ADR-002 明确不做原生**（无纯 JVM 可用 RAR5 解压器，继续走兜底岛） |
| 7 | MOBI HUFF/CDIC | 部分老 mobi 读不了 | `engine:mobi` 明确未实现压缩 17480 |
| 8 | PDF 工具栏 3 个 TODO | 大纲/搜索/导出快照不可用 | 接 `PdfHostBridge` 已有接口 |
| 9 | MHTML/HTML/FB2/DOCX | 4 种格式无原生实现 | 看板 D0（XHTML→TextBlock 扁平化）+ R1（core/archive） |
| 10 | ~~`scripts/check-elf-16kb.js` 依赖 unzip/readelf~~ | **已修**：纯 Node 实现 + CI 接入（见 §8） | — |
| 11 | 内置字体 8.25 MB | release 形态最大单项 | P8 L2（按需下载/子集化） |

---

## 6 · 真机项（本次未验证，按用户要求跳过）

- 冷启动 P90 ≤ 1.5 s、打开 EPUB P90 ≤ 1.2 s、翻页 P90 < 50 ms、内存峰值 < 350 MB —— `docs/android-baseline-after.json: deviceMetrics` 仍为 `pending-device`。
- Macrobenchmark（`android/benchmarks`，4 个 benchmark 类）需真机/模拟器且 app 不可 debuggable；`scripts/ci-macro-benchmark.sh` 未执行。
- 1000 本导入复测、桌面 `.db` 双向可读的真机验证。
- 真机安装/启动（连 APK 是否能装上、能否打开都没验）—— 本次只验证到「能构建、单测通过」这一层。

---

## 7 · 建议

1. **先把 P2 接线做完**（reader host），否则 P2 的 8–12 周投入在用户侧为 0；P6 模块同理，至少把统计/词典/简繁这类低风险入口接上。 ← **仍待做**
2. ~~`ReadingPosition` JSON 缺陷优先修~~ → **已修**（`engine:toc` 提交）。
3. ~~CI 加上 `gradle :app:assembleDebug` + `gradle test` 两道门槛~~ → **已接入**（`gradle test` 为全量门禁；`:app:assembleDebug` 由既有 build-android.js 步骤承担，见 §8）。
4. ~~把 `check-elf-16kb.js` 改成纯 Node，接入 CI~~ → **已完成**（纯 Node + CI 步骤）。
5. 真机验证另开一轮（有设备时），本文档的 `deviceMetrics` 与 Macrobenchmark 才有结论。 ← **仍待做**

---

## 8 · 修复进展（同日，缺陷清零）

§3 的 46 个失败测试与 §5 的缺口 3/4/10 已全部修完，每个主题单独提交（未 push）：

| 提交 | 主题 | 结果 |
|---|---|---|
| `56a1b2cd` | `engine:toc` 17 个失败 | 43/43 ✅（含**数据损坏级**的 `PositionCodec` 非法 JSON + decode 重加引号） |
| `16c999ce` | `engine:annotate` 11 个 + `engine:cfi` 加固 | 112/112 ✅（cfi 对「无 step 的 CFI」抛 `EMPTY_PATH`） |
| `c72aa45d` | `engine:gesture` 6 个失败 | 40/40 ✅（`GestureEngine(config)` 忽略 config、`FlingPhysics` 负速度归零、甩动方向、TapZone 右上区） |
| `c9b55989` | `engine:pdf` 5 个失败 | 61/61 ✅（`PdfZoom` 哨兵被自家校验拒绝、`fromDesktop` 越界处理、selfCheck 入口类名） |
| `6ef61c90` | `core:locale` 4 个失败 | 51/51 ✅（4 处均为测试期望写错，逐条与模块内已通过用例交叉核对） |
| `e9ab3c39` | `core:dbio` 3 个失败 | 22/22 ✅（测试流未关闭导致 @TempDir 清理失败、导出 JSON 空格、夹具违反 byKey⊇byMd5 不变量） |
| `2fe78ea3` | 16 KB 守卫纯 Node 化 + CI 门禁 | 真机无设备也能跑；CI 单测改全量 `gradle test` |

最终验证（本机 Gradle 8.5 + JDK 17 + Android SDK 34）：

```
gradle -p android --continue test                     → BUILD SUCCESSFUL
                                                       1155 个唯一测试 / 0 失败 / 22 个 module 全绿
gradle -p android :app:assembleDebug -Ptarget=native  → BUILD SUCCESSFUL（30.97 MB）
node scripts/check-elf-16kb.js <app-debug.apk>        → 4 个 .so 全 PASS（p_align=0x4000），exit 0
自检 runner（cfi/annotate/gesture/pdf/toc/locale）      → 全部 PASS（修复前 pdf/gesture 是坏的）
```

仍未做（不在本轮范围）：§5 的缺口 1/2（P2 reader host、P6 六个模块入口——产品接线）、5/7/8/9/11，以及 §6 的全部真机项。

---

## 9 · 缺口修复进展（第二轮：CB7 实装）

针对 §5 缺口 6 的 CB7 部分，`engine:image` 的 `SevenZExtractor` 从 47 行骨架换成真实实现（提交 `37e4c6ee`，纯 Java，**零新增 `.so`**）：

| 项 | 内容 |
|---|---|
| 依赖 | `org.apache.commons:commons-compress:1.27.1` + `org.tukaani:xz:1.10`（两者都是纯 JVM；xz 是 commons-compress 解 LZMA/LZMA2 的运行期后端） |
| 解析 | `SevenZFile.builder().setFile(file).get()` → `listPages` 分页表；`\` → `/` 归一化；`openPage` 每次重开句柄，`OwnedEntryStream` 负责关闭 |
| 上限 | 单页 `MAX_PAGE_BYTES = 64 MiB`，超限抛类型化错误（防 zip-bomb） |
| 明确边界 | **BCJ2**（多输入流编码器，commons-compress 不支持：`Multi input/output stream coders are not yet supported`）与**加密头**（AES）抛 `UnsupportedArchiveException`，由兜底岛接管；错误文案指向兜底岛 |
| 路由 | `ArchiveExtractor` 中 `SEVEN_ZIP.support = Support.READY` |
| 测试 | 新增 `SevenZExtractorTest` 11 个用例（LZMA/LZMA2/**COPY** 三种可读变体 + BCJ2 负例 + 加密头负例 + 截断 + 越界页码 + 路由 + 体积上限），`:engine:image` 78/78 |

夹具：`android/engine/image/src/test/resources/sevenz/`（5 个 ~1.4 KB 归档，`README.md` 记录了用 7-Zip 复现的命令；BCJ2 与 AES 加密头两个夹具 commons-compress **写不出来**，是 7z.exe 生成的二进制）。

验证：

```
gradle -p android :engine:image:test                   → 78/78 ✅
gradle -p android :app:assembleDebug -Ptarget=native   → BUILD SUCCESSFUL（33.54 MB，+2.29 MB）
node scripts/check-elf-16kb.js <app-debug.apk>         → 4 个 .so 全 PASS，exit 0
```

体积代价：debug APK 31.25 → 33.54 MB（无 R8），`libdatastore_shared_counter.so` 仍是首个 `.so`（p_align 0x4000），**未引入任何新原生库**，16 KB 判定不变。

CBR 结论：**不做原生**。ADR-002 的矩阵里 CBR 标为 `DEFERRED`——纯 JVM 侧没有可用的 RAR5 解压器（junrar 只到 RAR4 且对 RAR5 无效，其余方案都带 `.so`），强行实装会同时破坏「零原生依赖」与「体积」两条约束，继续由兜底岛承担。
