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
| **P3 PDF 原生阅读器 ★** | pdf.js WebView 渲染、搜索、大纲、密码、批注 | ✅ 本轮**真正接线**（此前只是路由到空壳屏幕，见 §10） | ✅ | 61+4 | ✅ `format == "PDF"`；工具栏**大纲/搜索/导出快照已可用**；**批注仍不可用**（栅格宿主无文本层，见 §10） |
| P4 MOBI/AZW3 | PalmDOC + MOBI6/KF8 + EXTH | 🟡 `engine:mobi`（HUFF/CDIC 明确未实现，抛类型化错误） | ✅ | 74 | ❌ 未接线（走兜底岛） |
| P5 TXT/MD | 编码探测 + Markdown 子集 | 🟡 `engine:text` | ✅ | 71 | ❌ 未接线 |
| P5 CBZ/CBR/CBT/CB7 | 懒加载图片阅读器 | 🟡 `engine:image`（CBZ/CBT/CB7 实装，**CBR 不原生**） | ✅ | 78 | ❌ 未接线 |
| P5.5 FB2/DOCX/HTML/MHTML | 单独立项评估 | ✅ 评估 + ADR-006（**仅文档**） | n/a | n/a | ❌ 4 个 include 是幽灵工程（已注释） |
| P6 阅读增强 | TTS / 词典 / 翻译·AI / 段落·速读·阅读尺 / 统计 / OCR | 🟡 6 个 feature module + `core:locale` 全部交付；TTS 的 manifest/`<queries>`/通知已接线（§11） | ✅ | 6 模块 289 全绿；locale 51（4 失败） | ❌ **仍无任何入口**（`ShellNavHost` 里没有对应屏幕；TTS 的服务与通知已可用，但没有启动它的 UI） |
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
| 2 | **P6 六个模块无入口** | TTS/词典/翻译/OCR/统计/简繁 交付了但用户摸不到 | 需要各自宿主屏幕 + 导航项 + manifest 接线（TTS 的 manifest/`<queries>`/通知/图标/i18n 已补，见 §11；**宿主屏幕仍缺**） |
| 3 | ~~`engine:toc` ReadingPosition JSON 非法~~ | **已修**（见 §8） | — |
| 4 | ~~46 个失败测试~~ | **已全绿**：1155 个唯一测试 / 0 失败 / 22 module 全绿（见 §8） | — |
| 5 | OCR 下载适配层 | OCR 无法按需下载模型 | ML Kit 的 options 不是 `OptionalModuleApi`，需改设计（改跟随 ML Kit 自身下载 / 换 bundled 制品） |
| 6 | ~~CB7（7z）~~ 已修 / CBR（rar） | CB7 可原生读；CBR 仍不可 | CB7 已接 commons-compress（见 §9）；**CBR 按 ADR-002 明确不做原生**（无纯 JVM 可用 RAR5 解压器，继续走兜底岛） |
| 7 | MOBI HUFF/CDIC | 部分老 mobi 读不了 | `engine:mobi` 明确未实现压缩 17480 |
| 8 | ~~PDF 工具栏 3 个 TODO~~ | **已修（见 §10）**：查证后发现整条 PDF 链路从未运行——空壳屏幕 + 6 个「编译通过但永不生效」的缺陷；本轮全部接线 | — |
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

仍未做（不在本轮范围）：§5 的缺口 1/2（P2 reader host、P6 六个模块入口——产品接线）、5/7/9/11，以及 §6 的全部真机项。

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
gradle -p android :app:assembleRelease -Ptarget=native → BUILD SUCCESSFUL（18.90 MB，+0.12 MB）
node scripts/check-elf-16kb.js <app-debug.apk>         → 4 个 .so 全 PASS，exit 0
node scripts/check-elf-16kb.js <app-release-*.apk>     → 4 个 .so 全 PASS，exit 0
```

体积代价：debug 31.25 → 33.54 MB（+2.29 MB，**无 R8**）；release 18.78 → 18.90 MB（**+0.12 MB**，commons-compress 被 R8 裁到只剩用到的一小部分）。`libdatastore_shared_counter.so` 仍是首个 `.so`（p_align 0x4000），**未引入任何新原生库**，16 KB 判定在 debug/release 两种形态下均不变。

CBR 结论：**不做原生**。ADR-002 的矩阵里 CBR 标为 `DEFERRED`——纯 JVM 侧没有可用的 RAR5 解压器（junrar 只到 RAR4 且对 RAR5 无效，其余方案都带 `.so`），强行实装会同时破坏「零原生依赖」与「体积」两条约束，继续由兜底岛承担。

---

## 10 · 缺口修复进展（第三轮：P3 PDF 链路真正接线）

缺口 8 原记录只有「工具栏 3 个 TODO」。查证后发现**整条 PDF 链路从未运行过**：`NativePdfScreen` 的正文是一段占位文本（"PDF native reader lands in P3"），`PdfJsHostBridge` 与 `PdfRendererSnapshot` 在 `:app` 里**没有任何实例化点**，`ShellNavHost` 只是把 `format == "PDF"` 路由到一个空壳。逐个复现出的缺陷（全部属「编译通过、永不生效」）：

| # | 缺陷 | 后果 / 证据 |
|---|---|---|
| 1 | `assets/pdfengine/*` 没有对应资源根 | `LocalAssetServer` 只以 `assets/webapp` 为根，`PdfJsHostBridge.bootstrap()` 请求的 `/assets/pdfengine/index.html` 在 APK 内不存在（`assets/pdfengine/` 与 `assets/webapp/` 是并列目录）→ 引擎永远 404 |
| 2 | pdf.js 用死 URL 取书 | 引擎里 `url: 'http://127.0.0.1/__books__/' + name` 硬编码 80 端口，忽略宿主实际绑定的临时端口，也忽略 `open()` 传入的路径 |
| 3 | 字符串结果被二次 JSON 编码 | `onResult` 对所有返回值 `JSON.stringify`：`renderPage` 的 base64 被包成带引号的 JSON 字符串（Kotlin `Base64.decode` 直接解错），`search`/`outline` 的 JSON 文本同样被再包一层引号（`JSONArray(...)` 解析失败） |
| 4 | 搜索扫不存在的 DOM | `collectMatches` 遍历 `.page-text-layer`，而本设计把页面画在离屏 canvas、从不生成文本层 → 搜索恒 0 结果；且 `executeCommand('find')` 之后同步取结果，时序也不成立 |
| 5 | 三处调用不存在的东西 | `close()` 发的是 `"close("`（语法错误）；`paintHighlights` 直接 `evaluateJavascript("paintHighlights(...)")`，但引擎只暴露 `window.__koodoPdf.call`；`open` 返回的 `pageWidthPt/HeightPt` 恒为 Letter |
| 6 | bootstrap 页引用了缺失资产 | `index.html` 请求 `./pdf_viewer.css`，该文件不在 `assets/pdfengine/` 中 |

本轮修复：

- **引擎逻辑独立成模块并可单测**：`assets/pdfengine/engine.mjs`（`index.html` 退化为薄引导）。`scripts/test-pdfengine.js` 用桩 pdf.js 在 Node 里跑 **56 项断言**（URL 逐字透传、base64 无 data-URL 前缀、大小写不敏感的全文档搜索且矩形为正、大纲解析与未解析项保留、payload 编码、边界语义，外加「index.html 不得引用缺失资产」的静态检查），已接入 CI（`release-android.yml` 新增一步）。
- **资源根**：`LocalAssetServer` 支持多根（默认 `webapp` + `pdfengine`），路径判定抽成纯函数 `AssetPaths`（`:app` JVM 测试 7 项）。
- **搜索真做**：按 `getTextContent()` 逐页扫文本，命中项的 `transform` 给出 PDF 用户空间矩形——`PdfSearchEngine.parseRects` 会**静默丢弃**空矩形或非正尺寸的命中，这条约束由 Node 测试钉住。跨 item 的匹配不拼接（已注明）。
- **UI 接线**：`NativePdfScreen` 真正渲染（pdf.js 逐页栅格 → Compose 绘制）；页导航（点击左右 1/4 区 + 底部 ‹/›）；缩放 ±（渲染宽度 = 视口 × 缩放）；**大纲抽屉**（新增 `OutlineResolver.flatten` + 4 项引擎测试，未解析条目保留并置灰）；**搜索对话框**（结果列表 + 循环跳转 + "No result found"）；**导出当前页并分享**（`PdfRendererSnapshot` → `cacheDir/pdf-shots/` → `FileProvider`，manifest 新增 provider 与 `res/xml/file_paths.xml`）；加密 PDF 的密码重试对话框。
- **宿主**：`NativeShellActivity` 启动 `LocalAssetServer` 并把书暴露为 `__books__/<name>`（`ReaderAssetHost`）；书籍文件解析抽成纯函数 `ReaderFiles`（`:app` JVM 测试 8 项：Room 记录路径优先 → `<key>.<ext>` 约定 → 前缀扫描，找不到返回 null 而非幽灵路径）。
- **i18n**：复用既有桌面 key（`Back`/`Loading`/`PDF outline`/`PDF empty outline`/`Search in book`/`No result found`/`Enter password`/`Wrong password`/`Previous page`/`Next page`/`Share`/`Close`）。「引擎不可用」页文案为英文直写：Android 语言包由 `sync-locales-android.js --check` 从桌面同步，单边新增 key 会让 CI 失败。

验证：

```
node scripts/test-pdfengine.js                        → 56/56 ✅
gradle -p android :app:testDebugUnitTest              → 26/26 ✅（新增 AssetPathsTest 7 + ReaderFilesTest 8）
gradle -p android :engine:pdf:test                    → 65/65 ✅（新增 flatten 4 项）
gradle -p android --continue test                     → BUILD SUCCESSFUL
                                                        1185 个唯一测试 / 0 失败 / 1496 次执行（debug+release 双跑）/ 22 module 全绿
gradle -p android :app:assembleDebug -Ptarget=native   → BUILD SUCCESSFUL（33.54 MB）
node scripts/check-elf-16kb.js <app-debug.apk>         → 4 个 .so 全 PASS，exit 0
APK 内容核对（zip 清单 + merged manifest）              → assets/pdfengine/engine.mjs 在包内（pdfengine 共 174 项）；
                                                        authority com.koodoreader.reader.fileprovider 已合并
```

**仍未做（明确边界，不是遗漏）**：

- **PDF 批注/高亮不可用**：栅格宿主没有可选中的文本层，引擎的 `selectedRect()` 只能返回 null、`paintHighlights()` 返回 false（`engine.mjs` 顶部已写明并记日志）。要支持批注必须换宿主设计（显示 WebView + 真文本层，或 Compose 侧叠加文本层），属 P6 范围。
- **多页连续滚动 / 双页 / 阅读位置记忆 / CFI 打通**：仍缺（`PdfViewMode`、`CfiPdfMapper` 已交付但未接线）。
- **真机未验证**（按用户要求跳过）：WebView 能否 bootstrap、pdf.js 能否真的栅格化、分享能否唤起目标应用，本轮只到「可编译 + JVM/Node 单测 + APK 内容核对」这一层。

---

## 11 · 缺口修复进展（第四轮：P6-TTS 接线收尾）

对应看板卡 `t-mufbb7c8-gzipge`（P6-TTS-WIRE，卡内要求「不动 taskboard_move」）与 §5 缺口 2 的 TTS 部分。卡里列的 6 项此前**全部缺失**——TTS 模块 24 文件 4227 LOC 已交付，但服务没注册、引擎看不见、通知图标是彩色框架资源：

| 卡内项 | 处理 |
|---|---|
| 权限 | `POST_NOTIFICATIONS` + `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` |
| `<queries>` | 声明 `android.intent.action.TTS_SERVICE`——Android 11+ 缺它时 `TextToSpeech` 直接报告「无引擎」，引擎列表/音色目录全空，且**不报错** |
| 服务 | `com.koodoreader.feature.tts.ForegroundTtsService`，`foregroundServiceType="mediaPlayback"`，`exported=false` |
| 接收器 | `LockscreenControlsReceiver` 注册，intent-filter 的 9 个 action 与 `ACTIONS`（`MEDIA_BUTTON` + 8 个 `TtsMediaCommand.action`）逐一对齐 |
| 通知小图标 | 新增 `android/feature/tts/src/main/res/drawable/ic_tts_notification.xml`（Material `volume_up`，Apache-2.0，单色矢量）；`ForegroundTtsService` 默认图标从 `android.R.drawable.ic_media_play` 改为它——通知小图标只取 alpha，彩色框架资源在状态栏会糊成一团。宿主仍可用 `setNotificationIcon` 覆盖 |
| i18n | 桌面 `src/assets/locales/{en,zh-CN}.json` 各新增 `Pitch` / `Volume` / `Done` / `Text to speech`（`TtsControlSheet` 已在用这 4 个 key，之前靠 `t()` 回退到英文），并跑 `sync-locales-android.js` 同步进 APK |

**新增门禁**：`scripts/check-tts-manifest.js`（CI 一步）把上面 4 项接线变成机器可验证的约束——权限、`<queries>`、`mediaPlayback` 服务、以及 receiver 的 action 表与 `TtsMediaCommand` 枚举对齐。`LockscreenControlsReceiver` 的注释早就声称「manifest 与 ACTIONS 不会漂移」，但此前没有任何东西在检查它。

验证：

```
node scripts/check-tts-manifest.js            → 9/9 ✅（负例：删掉一个 action → exit 1 并点名缺失项）
node scripts/sync-locales-android.js --check  → OK（en 1391 / zh-CN 1401 keys）
gradle -p android :feature:tts:test           → 全绿
gradle -p android :app:assembleDebug -Ptarget=native → BUILD SUCCESSFUL
merged manifest 核对                           → 3 个权限 + TTS_SERVICE + service(mediaPlayback) + receiver 全部在包内
APK 内容核对                                   → res/drawable/ic_tts_notification.xml 已打包
```

**仍未做**：P6 六个模块的**宿主屏幕/导航入口**（缺口 2 的主体）——TTS 现在缺的只是一个启动它的 UI；Android 13+ 的 `POST_NOTIFICATIONS` 运行时请求也挂在同一处宿主里。另外 TTS 通知的 `Stop/Play/Pause/Resume/Previous/Next` 在 zh-CN 下仍回退英文（桌面 zh-CN 没有这几个 key），属 i18n 补全，不在本卡范围。

---

## 12 · 剩余缺口的体量切分与依赖（尚未动手项）

本轮把「能独立闭环」的缺口修完后，剩下 5 项卡在**体量**、**他人卡**或**产品决策**上。逐项给出可核对的判断，避免把「没做」包装成「做完了」。

### #1 EPUB 原生阅读器接线（P2 主体）—— 超出会话体量，需排期

**事实**：仓库里**没有** `android/engine/epub` 模块，`ShellNavHost` 只有 `"PDF"` 分支，EPUB 落到 `ReaderPlaceholderScreen`。已交付的是可复用的零件：`engine:{cfi,layout,gesture,annotate,link,toc}` + 字体体系（`FontCatalog/FontManager/FontFallbackResolver`）+ `NativeReaderScreen` 骨架。迁移方案自估 **8–12 周**。

**最小可行切分**（建议按此排期，每步都能单独验收）：

1. **`engine:epub` 只读解析**（2–3 周）：ZIP/OPF/NAV 解析 → 章节 `XHTML→TextBlock`（与 R1/D0 的产物对齐）→ 资源表；JVM 测试 + 桌面 CFI 黄金向量复用。
2. **排版接线**（2–3 周）：`engine:layout` 接管分页（视口/字体/行高/主题）→ `NativeReaderScreen` 渲染首页。
3. **导航与进度**（1–2 周）：`engine:toc` 目录、`engine:cfi` 位置、阅读位置持久化（ADR-002 的 CFI parity）。
4. **标注/链接**（2 周）：`engine:annotate` + `engine:link` 落地；批注与桌面 DB 双向可读。
5. **手势/主题收尾**（1–2 周）：`engine:gesture` 接入 + 字体/主题/双栏。

**本轮未做**：任何代码。原因：第 1 步就依赖 R1 的 `core:archive` 与 D0 的扁平化产物（见 #9），且 8–12 周无法在一个会话内诚实闭合。

### #2 P6 六个模块的宿主入口 —— 可做，但需按模块分批

TTS 的**接线**已完成（§11）。剩下的入口屏幕按风险分三档：

| 档 | 模块 | 说明 |
|---|---|---|
| 低 | 统计(`feature:stats`)、词典(`feature:dictionary`)、简繁(`core:locale`) | 无系统服务依赖、无权限、无网络，接 `ShellNavHost` + 一个屏幕即可 |
| 中 | 段落/速读/阅读尺（`engine:layout` 相关）、翻译(`feature:translate`) | 需要在阅读器内叠加 UI，依赖 #1 的 reader host 或 PDF 屏的宿主 |
| 高 | TTS(`feature:tts`)、OCR(`feature:ocr`) | 需要前台服务/权限运行时请求、模型下载（见 #5）；TTS 现在只差启动 UI |

### #5 OCR 下载适配层 —— 需产品决策（APK 体积 vs 首次下载）

卡点属实：`feature:ocr` 走 `OptionalModuleApi` 抽象，而 ML Kit 的 `TextRecognizerOptions` **不是** `OptionalModuleApi`，两者对不上。两条可行路线：

1. **跟随 ML Kit 自身下载**（`com.google.android.gms:play-services-mlkit-text-recognition`，Play 服务按需下发模型）：APK 几乎不增，首次识别需下载 + **依赖 Play 服务**（无 GMS 设备不可用）。
2. **bundled 制品**（`com.google.mlkit:text-recognition`，模型进 APK）：无网络可用、无 GMS 依赖，代价是 **APK 增加约 4–16 MB**（按脚本/语言子集浮动，且是 `.so`/模型二进制，需重跑 16 KB 守卫）。

**本轮未做**：改动方向取决于「APK 体积」与「无 GMS 可用性」哪个优先，属产品决策；不擅自选一种。

### #7 MOBI HUFF/CDIC —— 阻塞于他人卡，本轮不动手

`engine:mobi` 对 HUFF/CDIC 压缩的 MOBI 抛类型化错误（压缩方式 17480）。该项属卡 `t-muexn60r-4p3sky`（`in_review`，验收清单 1/6），**不越权接管**。本轮只记录依赖。

### #9 MHTML/HTML/FB2/DOCX —— 阻塞于他人卡，本轮不动手

- `D0`（`t-mufbaotg-g1abhm`，`in_progress`）：XHTML→TextBlock 扁平化，是 FB2/HTML/MHTML 的前置。
- `R1`（`t-mufbaou8-ew1jbe`，`in_review`）：`core:archive`，是 DOCX(OOXML)/MHTML(MIME) 的前置。
两个前置未落地前实现这 4 种格式只能重复造轮子，且会与 `docs/patches/{d0-textblock-flattener,r1-core-archive}.patch` 冲突。只记录依赖。

### #11 内置字体 8.25 MB —— 产品决策，本轮不动手

release 形态最大单项。三条路线：

1. **按需下载**（默认字体常驻、其它字体走下载）：体积最省，需网络 + 字体缓存/校验（新增一类资源下载，与 #5 的下载层可共用）。
2. **子集化**（按 CJK 常用字表裁剪）：离线可用，体积可压到 ~1–3 MB，代价是生僻字回退系统字体（字形不一致）。
3. **维持现状**：8.25 MB 换「任何设备、任何字符都一致」。

**本轮未做**：三条路线对用户体验的影响不同，需产品定；不擅自削减内置字体。

### 汇总（§5 缺口 → 本轮状态）

| # | 缺口 | 状态 |
|---|---|---|
| 1 | EPUB 原生阅读器 | 未做：8–12 周，切分见上 |
| 2 | P6 模块入口 | **部分完成**：TTS 接线 ✅（§11）；6 个宿主屏幕未做（分档见上） |
| 3 | `engine:toc` JSON | ✅ 已修（§8） |
| 4 | 46 个失败测试 | ✅ 已修（§8） |
| 5 | OCR 下载适配层 | 未做：待产品决策（两条路线） |
| 6 | CB7 / CBR | ✅ CB7 实装（§9）；CBR 明确不做原生 |
| 7 | MOBI HUFF/CDIC | 阻塞：卡 `t-muexn60r-4p3sky`（in_review） |
| 8 | PDF 工具栏 3 TODO | ✅ 已修（§10，实为整条链路未接线） |
| 9 | MHTML/HTML/FB2/DOCX | 阻塞：卡 D0（in_progress）+ R1（in_review） |
| 10 | 16 KB 守卫 | ✅ 已修（§8） |
| 11 | 内置字体 8.25 MB | 未做：待产品决策（三条路线） |



