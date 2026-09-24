# P8 收尾清单：兜底岛下线 · 体积优化 · Crash 监控 · Macrobenchmark 固化

| 项 | 值 |
|---|---|
| 任务卡 | `t-muexn6g8-xxvzbr`「P8：收尾」（验收清单 [1]–[5]） |
| 日期 | 2026-09-24 |
| 隔离 | git worktree `.worktrees/p8-wrap-up`，分支 `task/p8-wrap-up-xxvzbr`，基线 commit `9abe7904` |
| 关联 | `docs/android-native-migration.md` §6 P8/§7 构建/§8 指标/§9 R6·R7·R8、`docs/adr/ADR-003-fallback-island.md`、`docs/android-baseline.json`、`docs/appendix-c-agpl-compliance.md` |
| 本清单产出 | ADR-005（§1.4）+ 逐格式下线表（§2）+ 体积杠杆矩阵（§3）+ Crash 骨架（§4）+ Macrobenchmark/CI（§5）+ 3 个待应用的构建 patch（§6） |

> **执行约束（本卡追加）**：不得实改 `android/app/build.gradle`、`android/settings.gradle`、`android/build.gradle`、`package.json`、`main.js`。因此 §3/§4/§5 的构建接线以 **patch 文本** 交付（`docs/p8-rollout-patches/`），由维护者 review 后应用。

---

## 0. 结论摘要（TL;DR）

| 验收项 | 现状 | 关键证据 | 待办 |
|---|---|---|---|
| **[1] 兜底岛按格式下线** | ◐ **前置未完成 → 保留最小兜底**（ADR-005）。`feature/webisland` 模块**从不存在**；兜底岛实为 `:app` 内一组资产 + 3 个类 | §1.1 盘点表；`android/feature/` 目录不存在、全仓 `webisland` 0 命中 | P8-F1（intent 路由）、P8-F3（flavor 迁移）；漫画/FB2/DOCX/HTML/MHTML、简繁转换**尚不能下线** |
| **[2] ABI 拆分 + 动态特性，体积达标并记录** | ◐ 实测完成 + 目标已建模；**ABI 拆分收益为 0**（APK 内零 `.so`），真正的杠杆是剥离兜底岛资产（−7.78 MB 压缩） | §3.1 逐项实测；`docs/android-baseline-after.json` | 应用 `android-app-build-gradle.patch` 后出包回填 `after.measured` |
| **[3] Crash 监控接入** | ◐ 骨架落地（`android/feature/crash`，纯 JVM、零依赖、带单测）；**不依赖真实后端**，红线为 `RedactionOnlyCallback` | §4；`android/feature/crash/src/**`（7 文件） | 后端适配器 + `Application.onCreate` 一行接线（patch 已含依赖） |
| **[4] Macrobenchmark 指标进 CI** | ◐ 骨架 + CI 入口脚本落地；四项指标各有一个 benchmark 类；CI job 片段待维护者合入 workflow | §5；`android/benchmarks/**`、`scripts/ci-macro-benchmark.sh` | 真机跑一次校准；`openBook` trace 段落（P8-F2） |
| **[5] AGPL-3.0 合规自查** | ☑ 完成 | `docs/appendix-c-agpl-compliance.md` | 发布流程按 §C.6 清单执行 |

**一句话**：本卡把能"只靠新增文件"完成的部分全部做实（骨架、脚本、基线记录、patch、ADR），把必须动主仓构建/源码的部分固化成可 review 的 patch + 明确前置，而不是绕过约束硬改。

---

## 1. 兜底岛现状盘点

### 1.1 兜底岛到底在哪（不是模块，是资产 + 类）

| 分类 | 位置 | 证据 |
|---|---|---|
| A. 资产（gitignored，由构建脚本暂存） | `android/app/src/main/assets/webapp/**` | `.gitignore:59`；实测 271 条目 / 21.11 MB raw / **7.78 MB 压缩** |
| B. 宿主类（`:app` main sourceSet） | `MainActivity.kt`（WebView + JS 桥）、`LocalAssetServer.kt`（127.0.0.1 回环静态服务）、`NativeEventDispatcher.kt`（21 事件消费） | `android/app/src/main/java/com/koodoreader/reader/` |
| C. 清单 | `AndroidManifest.xml` 中 `MainActivity` 的 `VIEW` / `SEND` / `koodo-reader://` intent-filter | 该 activity 对 native 变体**仍可被外部 intent 唤起** |
| D. 协议守卫（ADR-003 纪律 1：退役完成前不得删除） | `src/utils/android/nativeBridge.js`、`folderBridge.js` + Jest；`scripts/build-android.js --audit` | 迁移方案 §7.3 |
| E. **不属于**兜底岛 | `assets/pdfengine/**`、`pdfhost/PdfJsHostBridge.kt`、`shell/NativePdfScreen.kt` | P3 决策：pdf.js 在**专用 WebView** 里跑（`docs/android-pdf-poc.md`） |

**没有 `feature/webisland` 模块**：`android/feature/` 目录不存在，全仓 `webisland` 0 命中（详见 `docs/p8-rollout-patches/feature-webisland-remove.patch` 的证据块）。迁移方案 §3 架构图里的 `feature/webisland/` 是 P0 的**规划**，实现时兜底岛留在 `:app`。

### 1.2 关键实测：native 变体当前**仍然**打包兜底岛

- `scripts/build-android.js` 只在 `targets` 含 `webview` 时才暂存资产（`needsWebAssets = targets.includes("webview")`，L517），**且不清理上一次 webview 构建留下的 `assets/webapp`**（`stageAssets` 的 `fs.rmSync` 只在被调用时执行，L363-364）。
- CI 现有步骤是 `--target webview,native`（`.github/workflows/release-android.yml`），一次 staging 供两个目标使用 → **`*-native*.apk` 里含完整兜底岛**。
- 实测印证：`app-debug.apk` 内 `assets/webapp/*` = 7.78 MB 压缩（占 24.88 MB 的 31.3%）。

这条是 [1] 与 [2] 的交汇点：**剥离兜底岛资产是当前唯一被实测确认的大额体积杠杆**，而它被 intent 路由（P8-F1）挡住 —— 剥了资产却留下指向 `MainActivity` 的 `VIEW/SEND` filter，会让"用其它应用打开电子书"变成白屏（`LocalAssetServer` 404）。

### 1.3 口径修正：§8「主变体不再加载 WebView」不可按字面验收

| 事实 | 影响 |
|---|---|
| PDF 原生轨（P3）**就是**用 WebView 跑 pdf.js（`pdfhost/PdfJsHostBridge.kt:46` 自建 WebView，零 `.so`） | "主变体不再加载 WebView" 若按字面验收，P3 决策本身即违规 |
| 兜底岛 WebView（`MainActivity`）与 PDF 引擎 WebView 是**两个不同实例、不同用途** | 可区分 |

**建议验收口径（本清单主张，供 ADR-005 采纳）**：

> P8 的"下线"= 主变体启动/阅读路径**不再依赖兜底岛**（不加载 `assets/webapp`、不实例化 React 宿主的 WebView、外部 intent 不再落到 `MainActivity`）。
> 引擎内部为特定格式（PDF）使用受控 WebView **不算**兜底岛加载，判据是：该 WebView 只加载 `assets/pdfengine`，且不注册外部 intent 入口。

### 1.4 ADR-005 兜底岛下线决策：**前置未完成，保留最小兜底**

> 模板见 `docs/android-native-migration.md` 附录 B。编号 005 接续 ADR-001~003（004 已被 i18n 子集策略占用）。

- **日期**：2026-09-24
- **状态**：已采纳（P8）
- **关联**：ADR-003（四态生命周期，本 ADR 是其"④ 退役"态的落地判定）、R6/R7、验收清单 [1]/[2]

#### 背景

ADR-003 把 P8 定义为"④ 退役：主变体不再加载 WebView；兜底岛代码按 FB2/DOCX 原生进度决定转动态特性模块或彻底移除"。P8 到场的实际前置状态与当初设想有差距：

1. **兜底岛不是模块**（§1.1）——"移除模块"这个动作不存在，实际要动的是资产暂存策略、`:app` 源码和清单，三者都需要改被本卡约束禁止修改的文件。
2. **逐格式下线未走完**：FB2 / DOCX / HTML / MHTML 未原生（迁移方案 §3.1 明列为"后续单独立项"）；漫画（CBZ/CBR/CBT/CB7）原生 `engine/image` 未开工；MOBI 的 HUFF/CDIC 缺失；TXT/MD 尚未接排版；简繁转换（`zh-convert.ts`）未移植。**当前没有任何格式可以宣布"只走原生"**。
3. **`-Ptarget` 是构建标志，不是 variant**：没有 per-target 的 manifest/sourceSet，因此无法用一行 patch 把 `MainActivity` 从 native 变体摘掉（§3.4）。

#### 决策

1. **保留最小兜底**：`webview` 轨继续作为**长期可发布形态**保留（ADR-003 纪律 2 不变），`native` 轨在 P8 只做**可验证的减法**，不做破坏性切除。
2. **P8 只批准一项立即生效的减法**：`-PstripIsland=true`（opt-in）剥离 `assets/webapp`。**默认关闭**，启用前置是 P8-F1（intent 路由迁移）完成。
3. **明确不做**：不在 P8 删除 `MainActivity` / `LocalAssetServer` / `NativeEventDispatcher` / `nativeBridge.js` / `folderBridge.js`（ADR-003 纪律 1 仍然有效）。
4. **下线改由 P8.1 承担**：把 `-Ptarget` 升级为真正的 product flavor（`webview` / `native`），届时 per-flavor manifest + sourceSet 才能干净地摘掉宿主类、切断外部 intent、并顺手把 Compose 从 `webview` 变体移出（R7）。
5. **验收口径按 §1.3 修正**，并在 `docs/android-native-migration.md` 后续修订时同步（本卡不改该文件）。

#### 备选方案与理由

| 备选 | 否决理由 |
|---|---|
| A. P8 强行切除宿主类 + 删资产，一步到位 | 需改被禁文件；且 6 类格式会立刻不可读（FB2/DOCX/HTML/MHTML/漫画 + 简繁转换缺失时的原文呈现），等于把"收尾"变成功能性回归 |
| B. 完全不动，把 P8 记为未完成 | 体积与指标固化两项本可交付，放弃等于把 R7 风险原样留到发布后 |
| C. 剥离资产但保留 intent filter（只做体积） | 制造"白屏 open-with"缺陷，比不剥离更糟 |
| D. 用 aapt `ignoreAssetsPattern` 在 native 目标默认剥离 | 与 C 同因；故做成**显式 opt-in** 而非跟随 `-Ptarget` |

#### 影响

**正面**：①体积收益可测可控（−7.78 MB 压缩，−31.3%），且开关可回滚；②ADR-003 的"逐格式"纪律不被破坏；③把"为什么 P8 没有整体下线"写成可审查的决策记录，而不是含糊的"未完成"。
**负面**：①native 变体在 P8-F1 完成前仍带着兜底岛资产，"主变体不含兜底岛"这一条**未达成**；②`-PstripIsland` 多了一个构建开关，需要 CI 至少跑一次带开关的构建防止腐化。

#### 验证方式

1. `git apply --check` 三个 patch 全部通过（已在 worktree 验证：exit 0）。
2. 出包断言：`unzip -l app-debug.apk | grep -c 'assets/webapp'` → 带 `-PstripIsland=true` 时**必须为 0**，默认构建**必须非 0**（对照）。
3. intent 冒烟：从文件管理器"打开方式"发送 EPUB → native 变体必须进入原生导入/阅读路径（P8-F1 完成后）。
4. 回归：`webview` 轨完整走一遍导入 + 阅读（ADR-003 要求的下线前冒烟）。

#### 回滚方案

`-PstripIsland` 是单开关：去掉该参数即恢复原状；patch 未应用则主仓零改动。宿主类与协议守卫在本 ADR 下**从未被删除**，故无代码级回滚需求。

---

## 2. 兜底岛按格式下线清单（验收 [1]）

### 2.1 逐格式状态与可下线判定

判定依据：原生实现位置（代码存在性）+ 测试文件数 + 迁移方案附录 A.2/A.4 的标注。**"可下线"= 该格式在 native 轨能独立完成打开与阅读**，本机无设备，故一律标"需真机回归"。

| 格式 | 原生后端现状 | 代码/测试证据 | 可下线？ | 阻塞项 |
|---|---|---|---|---|
| **EPUB** | ◐ 引擎层齐备（`engine/layout` 12/f5、`engine/gesture` 13/f5、`engine/annotate` 14/f6、`engine/toc`、`engine/link` 13/f6；`core/importer/EpubBook.kt`） | 44+ 单测文件 | **接近**（本卡未验证端到端） | 真机回归；兜底岛作为回退需保留到回归通过 |
| **PDF** | ◐ `engine/pdf`（19/f9）+ `:app` 的 pdf.js WebView 宿主 | `docs/android-pdf-poc.md` | 否（**引擎本身用 WebView**，见 §1.3） | 口径已修正；`assets/pdfengine` 必须永远随 native 轨打包 |
| **MOBI / AZW3 / AZW** | ◐ `engine/mobi`（16/f7），HUFF/CDIC 未实现 | 附录 A.2；卡 `t-muexn60r` | 否 | HUFF/CDIC 缺失时部分书不可读 |
| **TXT / MD** | ◐ `engine/text`（13/f4）解析 + 章节切分完成，未接排版 | 附录 A.2 | 否 | 未接 `engine/layout` |
| **CBZ / CBR / CBT / CB7** | ☐ **无原生渲染器**（`engine/image` 不存在；`ComicRender` 0 命中；仅导入期封面抽取 `ComicCover.kt`） | grep 证据 | **否（最大缺口）** | `engine/image` 未开工（P5 后续规划） |
| **FB2 / DOCX / HTML / MHTML** | ☐ 无原生 | 附录 A.2 明列"兜底岛长期驻留" | 否 | 单独立项 |
| **简繁转换** | ☐ `zh-convert.ts` 未移植（`zhConvert`/`OpenCC` 0 命中） | grep 证据 | 否 | 属 P6 增强，非"兜底岛专属"，但缺失会导致原生轨行为与桌面不一致 |
| **TTS / 词典 / 翻译 / OCR** | ☐ `TextToSpeech` 0 命中 | grep 证据 | 否 | P6 |

**结论**：**0 个格式满足"只走原生"**，因此 ADR-005 决策 1（保留最小兜底）成立。

### 2.2 下线步骤（每个格式一次，顺序固定）

1. **路由切换**：把该格式的打开路径从兜底岛切到原生后端（代码内 `FormatRoutes` 类单一事实源；当前不存在，P8.1 需补）。
2. **回归集**：该格式样本集全部可读 + 排版不崩 + 标注 CFI 往返一致（ADR-002，≥99% 位置一致率）。
3. **兜底岛冒烟**：`--target webview` 构建仍能打开同一样本（ADR-003 纪律 2：webview 轨永久保留）。
4. **路由表更新**：同步 `docs/android-native-migration.md` §3.1 与附录 A.2 状态列（同 PR）。
5. **外部 intent 收口**：当**最后一个**外部可打开格式完成原生时，才把 `AndroidManifest.xml` 上 `MainActivity` 的 `VIEW/SEND` filter 迁到 `.shell.NativeShellActivity`（P8-F1）。

### 2.3 回滚

格式路由是数据驱动的单表：把该行改回兜底岛即恢复渲染；资产暂存与 `MainActivity` 在本 ADR 下从未删除，回滚无需构建改动。

### 2.4 待办项（P8 挂账，按优先级）

| ID | 待办 | 阻塞的验收点 | 代价 |
|---|---|---|---|
| **P8-F1** | 把 `MainActivity` 的 `VIEW` / `SEND` / `koodoreader://` intent-filter 迁到原生壳（或按 flavor 拆分清单） | 挡住 `-PstripIsland` 的启用；[1][2] | 中（需改清单 + 原生导入入口接 `ACTION_VIEW`） |
| **P8-F2** | 阅读器打开路径包 `Trace.beginSection("openBook")` | [4] open EPUB 指标 | 小（3 行） |
| **P8-F3** | `-Ptarget` → product flavor（`webview` / `native`）：per-flavor manifest + sourceSet，native 去掉宿主类、webview 去掉 Compose | [1] 彻底下线；R7 体积 | 大（P8.1，2–3 天 + 回归） |
| **P8-F4** | `engine/image`（漫画）原生渲染器 | [1] 漫画下线 | 大（P5 规划） |

---

## 3. 体积优化清单（验收 [2]）

### 3.1 优化前实测（可复现）

测量对象：`android/app/build/outputs/apk/debug/app-debug.apk`，构建时间 2026-09-24 11:22:40（本机 mtime）。方法：逐条读取 zip 条目（`Length` = 未压缩，`CompressedLength` = 压缩后）。完整数据见 `docs/android-baseline-after.json`。

| 分组 | 未压缩 MB | 压缩 MB | 条目 | 备注 |
|---|---|---|---|---|
| `assets/webapp`（兜底岛） | 21.11 | **7.78** | 271 | 单文件 `main.e6b24e94.js` 10.72 raw / 3.27 压缩 |
| `classes*.dex` | 22.16 | 8.34 | 9 | debug + 未 R8 + multidex |
| `res` | 14.11 | 8.28 | 80 | |
| `res/font` | 14.07 | 8.25 | 2 | `lxgw_wenkai_lite.ttf` 13.23 raw（占 res 93.8%） |
| `assets/locales` | 0.18 | 0.06 | 3 | |
| `lib/**/*.so` | **0** | **0** | **0** | ★ 零原生库 |
| **合计** | **58.00** | **24.88** | 432 | APK 落盘 24.95 MB |

两条关键实测结论：

1. **ABI 拆分在当前 APK 上收益为 0**（无任何 `.so`；pdf.js 以纯 JS 交付）。
2. **最大可动项是兜底岛资产（7.78 MB 压缩，31.3%）**，其次是内置中文字体与 debug dex。

### 3.2 ABI 拆分清单

| 项 | 结论 |
|---|---|
| 现状 | `android.config.json`: `abis: ["arm64-v8a","armeabi-v7a"]`、`splitPerAbi: true`；per-ABI 出包由 `scripts/build-android.js` 用 `-PabiFilters` 驱动（已工作） |
| Gradle 侧 `splits { abi { … } }` | 长年缺失（`:app` 无 `splits` 块）。**patch 中补齐，opt-in `-PsplitAbi=true`** |
| 为何 opt-in | 与既有 `-PabiFilters` 流程语义重叠，同时开启会出现"双份拆分"；且默认关闭保证 CI 行为不变 |
| 体积预期 | **0 MB**（零 `.so`）。保留理由：将来引入任意原生库（ML Kit、NDK 编解码器）时自动生效，不必再改构建脚本 |
| 附带补齐 | patch 内含 `benchmark` build type 与 `:feature:crash` 依赖；`splits` 与 `-PabiFilters` 互斥保护（`&& !hasAbiFilters`） |
| 版本号注意 | 若走 Play 分发且启用 splits，需为每个 ABI 生成不同 `versionCode`（AGP 不会自动加偏移）。当前用 `-PabiFilters` 单 ABI 出包，无此问题 |

### 3.3 体积杠杆矩阵

| ID | 杠杆 | 预期（压缩 MB） | 置信度 | 前置 |
|---|---|---|---|---|
| **L1** | `-PstripIsland=true` 剥离兜底岛资产 | **−7.78** | **实测**（被移除条目之和；成包后需复核） | P8-F1 |
| **L2** | 内置中文字体按需下载 / 子集化（`lxgw_wenkai_lite.ttf`） | −7.9（估） | 估算（`res/font` 合计 8.25，inter 占 0.84 raw） | 产品决策（离线可用性） |
| **L3** | release + R8 发布形态 | 未测 | 待测（需签名出包） | keystore / CI |
| **L4** | flavor 拆分（native 去宿主类、webview 去 Compose） | 未测 | 待测 | P8-F3 |
| **L5** | ABI 拆分 | **0** | 实测 | 无 |
| **L6** | App Bundle 交付 | ~0 | 推断（无 `.so`、字体不按密度） | Play 决策 |

**L1 后可达 17.10 MB（24.88 − 7.78）**——这是本卡唯一敢下硬结论的体积数字。

### 3.4 动态特性模块 / flavor 迁移：为什么不是"一行 patch"

要把兜底岛变成 `com.android.dynamic-feature`（或按 flavor 彻底移出 native 轨），需要：

1. `settings.gradle` 注册 `:feature:webisland`（当前**模块不存在**，先得把 `MainActivity`/`LocalAssetServer`/`NativeEventDispatcher` 从 `:app` 搬过去）；
2. `:app` 声明 `dynamicFeatures = [':feature:webisland']`；
3. 清单拆分：`MainActivity` 与 intent-filter 移入 dynamic feature 的 manifest，且 dynamic feature 里的 activity **不能被外部 intent 直接解析**（install-time / on-demand 语义差异）→ 外部"打开方式"必须落到原生壳（= P8-F1）；
4. `webview` 轨仍要一个**完整**包（ADR-003 纪律 2）→ 只能靠 flavor 或第二个 `applicationId` 并存。

因此 P8 的交付是：**patch（减法）+ ADR（决策）+ 待办（P8-F3 的明确边界）**，而非假装完成。

### 3.5 出包与回填配方

```bash
# 1) 应用 patch（3 个，顺序无关）
git apply docs/p8-rollout-patches/android-settings-gradle.patch
git apply docs/p8-rollout-patches/android-app-build-gradle.patch
git apply docs/p8-rollout-patches/android-app-manifest.patch

# 2) 对照：默认构建仍含兜底岛
cd android && gradle :app:assembleDebug -Ptarget=native
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep -c 'assets/webapp'   # > 0

# 3) 剥离：必须为 0
gradle :app:assembleDebug -Ptarget=native -PstripIsland=true
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep -c 'assets/webapp'   # 0

# 4) 发布形态（需 keystore）
gradle :app:assembleRelease -Ptarget=native -Pkeystore=… -PstorePassword=… -PkeyAlias=… -PkeyPassword=…
```

回填 `docs/android-baseline-after.json` 的 `after.measured` 并把 `status` 改为 `measured`。

---

## 4. Crash 监控接入（验收 [3]）

### 4.1 模块结构（新增，纯 JVM，零运行时依赖）

```
android/feature/crash/
├── build.gradle                      org.jetbrains.kotlin.jvm（无 Android 插件 → 无需 SDK 即可单测）
└── src/main/kotlin/com/koodoreader/feature/crash/
    ├── CrashEvent.kt                 CrashLevel / StackFrame / CauseNode / CrashBreadcrumb /
    │                                 CrashEvent（纯数据，**不持有 Throwable**）/ CrashEventFactory
    ├── Redaction.kt                  BeforeSendCallback 接口 + PiiRedactor + **RedactionOnlyCallback**
    ├── CrashBackends.kt              CrashBackend SPI + NoopCrashBackend + RecordingCrashBackend
    ├── CrashReporter.kt              面包屑环 / before-send 链 / install-uninstall（handler 链保留）
    └── CrashMonitoring.kt            进程级入口（install / useBackend / breadcrumb / flush）
```

**为什么不引入 Sentry/Crashlytics 依赖**：本卡的显式约束是"只写接口，不强制依赖真实后端"；此外任何后端 SDK 都会进 APK 体积与隐私面，必须由发布方按需决定。`CrashBackends.kt` 的 KDoc 给出两个适配器的接入配方（坐标写在注释里，不进构建）。

### 4.2 红线：`RedactionOnlyCallback`

| 规则 | 内容 |
|---|---|
| 契约 | **只脱敏，绝不丢事件**：`dropsEvents = false`，`beforeSend` 从不返回 `null`（崩溃不能因为"分类不了"而丢失） |
| 变换范围 | ①正则替换 ②超长截断 ③删除整键（`extras` 黑名单） ④面包屑限量 32 条 |
| 脱敏规则（有序） | Authorization/Bearer、JWT、`password=/token=`、URL query 密钥 → `<token>`；邮箱 → `<email>`；IPv4 → `<ip>`；32+ hex（书 md5/书 key）→ `<hash>`；`content://`/`file://`/`koodo-reader://` → `<uri>`；Windows/POSIX 绝对路径 → `<path>`；栈帧文件名去掉目录 |
| 整键删除 | `path/file/bookPath/title/bookTitle/author/search/selection/note/annotation/email/token/user…`（`DEFAULT_DENIED_EXTRA_KEYS`，可审查的稳定清单） |
| 依据 | 与 AGPL 无关，但与本仓库"日志不得含令牌/完整书籍路径"的既有规范一致（`CLAUDE.md` 开发规范） |

### 4.3 接入步骤

1. **构建接线**（patch）：`android-settings-gradle.patch` 注册 `:feature:crash`；`android-app-build-gradle.patch` 加 `implementation project(':feature:crash')`。
2. **`Application.onCreate`**（`:app`，需改代码 → 未在本卡修改）：
   ```kotlin
   CrashMonitoring.install()                        // 面包屑 + 崩溃捕获，不发送
   CrashMonitoring.breadcrumb("app", "onCreate")
   // 用户同意后：CrashMonitoring.useBackend(SentryBackend(...))
   ```
3. **后端适配器**（由发布方实现，`CrashBackend` 三个方法）：Sentry 用 `beforeSend` 直接接 `RedactionOnlyCallback`；Crashlytics 无 before-send 钩子，适配器需对每个 custom key / log 行先过 `PiiRedactor`。
4. **设置项**：默认关闭（`NoopCrashBackend`），UI 开关只控制"是否 attach 后端"，脱敏在任何情况下都生效。

### 4.4 验证方式

| 手段 | 覆盖 |
|---|---|
| `gradle :feature:crash:test`（JUnit5，2 个测试类 / 15 用例） | 路径/邮箱/IP/md5/token 脱敏、extras 黑名单、栈帧文件名、截断与面包屑限量、before-send 顺序与丢弃语义、后端抛异常被吞、handler 链保留（前一个 handler 仍收到原始 `Throwable`）、install 幂等、close 后停止上报 |
| 本机执行状态 | **未执行**：本机无 gradle CLI（PATH 仅 JDK 11）。测试文件已就绪，接线 patch 应用后一条命令可跑 |
| 灾备演练（建议） | debug 构建里 `CrashMonitoring.current.captureException(RuntimeException("/storage/emulated/0/books/x.epub failed"))` → 断言后端收到的消息含 `<path>` |

---

## 5. Macrobenchmark 固化（验收 [4]）

### 5.1 夹具约定

- 设备/模拟器 API 29+（`<profileable>` 启动剖析；帧指标 API 23+ 即可）。
- 目标应用**不可 debuggable**（Macrobenchmark 硬性要求）→ 用新增的 `benchmark` build type（release 形态 + debug 签名 + 非调试，见 app patch）。
- 夹具书需先进入书架，且标题在**首屏**可见：`-e benchmarkBookTitle "benchmark-300ch.epub"`（1 MB / 300 章，对齐迁移方案 §8）。

### 5.2 指标 → 实现映射

| 指标 | 目标 | 实现 | 备注 |
|---|---|---|---|
| 冷启动 P90 | ≤ 1500 ms | `StartupBenchmark.coldStartup`（`StartupTimingMetric`，`CompilationMode.None()`，COLD，10 次）+ `scripts/measure-cold-start.js` 的 `am start -W` P90 | 脚本优先采用后者（真 P90，且不需要 profileable）；前者提供 warm 对照 |
| 打开 EPUB | ≤ 1200 ms | `OpenEpubBenchmark.openEpubFirstFrame`（`TraceSectionMetric("openBook", First)`） | **需 P8-F2**（阅读器打 trace 段落）；未接线时用 `assumeTrue` 跳过而非伪报。另设 `fixtureBookOpens` 冒烟（`FrameTimingMetric`，不依赖 trace） |
| 翻页 P90 | < 50 ms | `PageTurnBenchmark.pageTurnFrames`（`FrameTimingMetric` P90，setup 打开书、measure 只做 20 次翻页） | P90 直接来自 `frameDurationCpuMs.P90`，无需换算 |
| 内存峰值 | < 350 MB | `MemoryPeakBenchmark`（`dumpsys meminfo` 的 `TOTAL PSS` 采样，120 页读通） | 非 `MacrobenchmarkRule`：公开指标里没有"峰值 PSS"；结果写 JSON + logcat，超预算默认只记录（`-e benchmarkFailureOnBudget=true` 才失败） |

### 5.3 CI 入口

`scripts/ci-macro-benchmark.sh`（新增，唯一 CI 入口）：

```
bash scripts/ci-macro-benchmark.sh [--serial id] [--book-title t] [--iterations n] [--cold-runs n]
                                   [--out dir] [--budget file] [--strict] [--allow-no-device] [--dry-run]
```
- 建包 `:app:assembleBenchmark :benchmarks:assembleBenchmark -Ptarget=native` → 安装 → 关动画 → 跑 instrumentation → 拉回 Benchmark JSON / 内存报告 → 跑 `measure-cold-start.js` → 与 `docs/android-baseline-after.json` 的 `budgets` 对比，输出表格 + 退出码（超预算 1；无数据默认仅告警，`--strict` 时失败）。
- 无设备时 `--allow-no-device` → SKIP 退出 0（保证普通 CI 不被设备缺失染红）。

**workflow 片段（由维护者合入，本卡不改现有 workflow）**：

```yaml
  android-macrobenchmark:
    name: Android performance budgets (needs a device)
    runs-on: [self-hosted, android-device]     # 真机/常驻模拟器；GHA 托管 runner 无设备
    if: github.event_name == 'workflow_dispatch'
    steps:
      - uses: actions/checkout@v6
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: "17" }
      - name: Install Gradle 8.5
        run: |
          curl -fsSL -o /tmp/gradle.zip https://services.gradle.org/distributions/gradle-8.5-bin.zip
          unzip -q /tmp/gradle.zip -d /opt && echo "/opt/gradle-8.5/bin" >> "$GITHUB_PATH"
      - name: Go / No-go — performance budgets
        run: bash scripts/ci-macro-benchmark.sh --out out/macrobenchmark --strict
      - uses: actions/upload-artifact@v4
        if: always()
        with: { name: macrobenchmark, path: out/macrobenchmark }
```
> 普通（托管 runner）CI 建议加一条 `bash scripts/ci-macro-benchmark.sh --allow-no-device` 作为**接线存活检查**，确保脚本与 benchmark 源码不会腐化。

### 5.4 已知限制（诚实记录）

1. **本机无法执行**：无 gradle CLI、无 Android SDK 调用、无真机 → benchmark 代码与脚本**未在设备上跑过**，只做了静态评审 + 脚本逻辑的功能测试（见 §6 自验）。
2. **首次真机运行需要校准**：夹具标题、翻页手势参数（`Ui.turnPage` 的 swipe 区间）、`dumpsys` 解析都需一次现场确认。
3. **`openBook` trace 段落未接线**（P8-F2），该指标当前为 NO DATA。
4. CI 中设备方案（self-hosted 真机 vs 常驻模拟器）需维护者选型；模拟器的内存指标不可用于验收。

---

## 6. 交付物索引与自验

### 6.1 文件 → 验收项

| 文件 | 验收项 | 状态 |
|---|---|---|
| `docs/p8-rollout-checklist.md`（本文件，含 ADR-005） | [1][2][3][4] | 新增 |
| `docs/p8-rollout-patches/feature-webisland-remove.patch` | [1] | 新增（**声明文件**，无 hunk，N/A） |
| `docs/p8-rollout-patches/android-app-build-gradle.patch` | [1][2][3][4] | 新增（`git apply --check` 通过） |
| `docs/p8-rollout-patches/android-settings-gradle.patch` | [3][4] | 新增（`git apply --check` 通过） |
| `docs/p8-rollout-patches/android-app-manifest.patch` | [4] | 新增（`git apply --check` 通过） |
| `android/feature/crash/**`（build.gradle + 5 主源 + 2 测试） | [3] | 新增 |
| `android/benchmarks/**`（build.gradle + manifest + 5 源） | [4] | 新增 |
| `scripts/ci-macro-benchmark.sh` | [4] | 新增 |
| `docs/android-baseline-after.json` | [2][4] | 新增 |
| `docs/appendix-c-agpl-compliance.md` | [5] | 新增 |

### 6.2 自验记录（本机可做的都做了）

| 检查 | 手段 | 结果 |
|---|---|---|
| 3 个 patch 可应用 | `git apply --check`（对 worktree 干净基线 `9abe7904`） | 全部 exit 0 |
| patch 与主仓未提交改动的关系 | 主仓 `android/app/build.gradle`、`android/settings.gradle` 有未提交的 P2 改动（4 条依赖 + 4 段 include），但 4 个 hunk 的上下文均不与它们重叠；对**主仓当前工作区**再跑一次 `git apply --check` | 全部 exit 0 → **无需 rebase，可直接应用** |
| patch 文件行尾 | 统一为 LF（生成时曾为 CRLF：git 仍能识别，但会把目标文件写成 CRLF，徒增 diff 噪音） | 4 个文件均 LF-only |
| `android-baseline-after.json` 合法 | `JSON.parse` | 通过；`budgets`/`savingsModel` 字段齐全 |
| CI 脚本语法 | `bash -n` | **无法执行**（沙箱拒绝 Git-Bash/WSL 启动：`CreateFileMapping … Win32 error 5` / WSL `E_ACCESSDENIED`）→ 改为：剥 heredoc 后 if/fi、case/esac、do/done 配平检查 + 全文逐行评审 |
| CI 脚本比对逻辑 | 抽出内嵌 JS，用合成数据跑 5 种场景（全过 / 单项超 / 无数据 / `--strict` / 指标归属） | 通过：退出码 0/1 正确，`startupTimeMs` 与 `openBookMs` 归属正确，来源可追溯 |
| 体积实测 | 逐条读 APK zip（未压缩/压缩字节） | 表见 §3.1；零 `.so` 结论可靠 |
| 未见 `webisland` 模块 | 目录检查 + 全仓 grep | 0 命中（声明文件内已记录证据） |
| 未触碰禁改文件 | `git status`（worktree 内） | 仅新增文件，无修改 |

### 6.3 未完成 / 风险

| # | 项 | 影响 | 建议 |
|---|---|---|---|
| 1 | P8-F1 intent 路由未迁移 | `-PstripIsland` 不能启用，[1][2] 只做到"可验证的减法" | 下一个卡优先做 |
| 2 | benchmark/crash 均未在真机/JVM 实跑 | 首次运行可能需要小幅校准（API 细节、手势参数） | 有设备的机器跑 `ci-macro-benchmark.sh --strict` 一次 |
| 3 | patch 与主仓未提交改动 | 已验证互不冲突（§6.2），**风险已消除** | 若 P2 之后又改动这两文件的同名区域，应用前重跑 `git apply --check` |
| 4 | `splits`/`androidResources`/`dynamic-feature` 的 DSL 细节未编译验证 | 应用后首次编译可能需要微调 | 应用后立刻 `gradle :app:assembleDebug` |
| 5 | AGPL 合规依赖发布流程执行 | 分发义务不因文档存在而自动履行 | 按 `docs/appendix-c-agpl-compliance.md` §C.6 清单逐项打勾 |
