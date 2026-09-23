# Koodo Reader Android：WebView → 原生迁移方案（方案 A）

| 项 | 值 |
|---|---|
| 状态 | 已锁定决策，进入 Phase 0 |
| 目标 | 用 Kotlin/Compose 原生重写 Android 客户端；WebView 仅作为少数格式的**兜底岛**保留 |
| 引擎策略 | 以 `koodo-reader/kookit` + `foliate-js` 为**规范与参考实现**，把渲染引擎移植为 Kotlin 原生引擎（见 §2 前提修正） |
| 范围 | 功能基准 = 桌面端；**不做云同步**，保留本地备份导入/导出 |
| 格式优先级 | EPUB → PDF → MOBI/AZW3 → 其他格式（§6.5 单列后续规划） |
| 工时口径 | 1 名熟悉 Kotlin/Android + 阅读器领域的全职工程师；P0 后校准 |

---

## 1. 现状与问题根因

### 1.1 现有实现（WebView 版）

| 层 | 文件 | 现状 |
|---|---|---|
| 宿主 | `android/app/src/main/java/com/koodoreader/reader/MainActivity.kt` | WebView 壳 + JS 桥；`pickFolder/listFolder/openExternal/getInfo/pickFile/setMenuLabels` |
| 静态服务 | `LocalAssetServer.kt` | 零依赖回环 HTTP（127.0.0.1 + 随机端口），服务 `assets/webapp`，支持单段 Range 与 SPA 回退 |
| 事件 | `NativeEventDispatcher.kt` | 消费引擎 `postMessage` 事件（翻页/划词/看图/链接/脚注），原生菜单与全屏看图 |
| 协议 | `src/utils/android/nativeBridge.js`、`folderBridge.js` | **单一事实源**，均有 Jest 单测 |
| 构建 | `scripts/build-android.js` + `src/utils/android/androidBuild.js`（纯逻辑 + Jest） | 把 `build/` stage 到 `assets/webapp`，per-ABI 出 APK，`--audit` 校验资源完整性 |

### 1.2 体验差的根因（可从代码直接推出）

1. **启动**：React 首屏 + 40+ 语言 JSON + 引擎 bundle + 8 个 WASM/JS 库（`index.html` 一次性加载 pdf.js、sql.js、7z、unrar、tesseract、onnxruntime、esearch-ocr、fabric）。
2. **交互**：翻页/划词/菜单是 `evaluateJavascript` 回注 + `postMessage` 事件，链路长且频繁跨 JS 边界。
3. **内存**：pdf.js / sql.js / tesseract / onnxruntime 全部驻留 WebView。
4. **手势/滚动**：非原生，惯性、边缘回弹、输入法与无障碍均受限。
5. **能力缺失**（README 已声明）：`better-sqlite3`、云同步插件、原生 OCR 在 APK 中不可用。

---

## 2. 前提修正：关于"kookit 开源可以直接用"

已核实（`koodo-reader/kookit` @ `dev`、`johnfactotum/foliate-js` @ `main`、本仓库 `LICENSE`）：

| 核查项 | 结论 | 对方案的影响 |
|---|---|---|
| kookit 形态 | TypeScript + Rollup 的**浏览器渲染引擎**（`src/index.ts` 桌面 ESM、`src/mobile.ts` 移动 UMD） | 运行前提是 `document` / `iframe` / `Range` / CSS columns / `scrollLeft`，**Kotlin 无法直接调用** |
| 核心实现 | `GeneralRender` 基类（1400+ 行）派生 `EpubRender / MobiRender / HtmlRender / Fb2Render / TxtRender / MdRender / DocxRender / PdfRender / PdfTextRender / ComicRender / CacheRender`；底层 foliate-js + pdf.js | 原生侧只能**移植**（直译纯逻辑 + 重写排版/手势层） |
| 依赖与许可 | kookit = **AGPL-3.0**；foliate-js = **MIT**（vendored: zip.js BSD-3、fflate MIT、PDF.js Apache）；本仓库 = **AGPL-3.0** | 复用/移植**无法律障碍**；衍生 APK 必须继续按 AGPL-3.0 开源 |
| 官方移动端 | kookit `CLAUDE.md` 的构建产物指向 `koodo-reader-expo/assets/lib/kookit-mobile.min.js` | 官方 Android 是 **React Native(Expo) + 同一套 WebView 引擎**；生态内无原生 Kotlin 先例，必须自研 |

**结论**：方案 A 的正确定义是

> 以 kookit / foliate-js 为**规范与参考实现**，把渲染引擎**移植成 Kotlin 原生引擎**。
> 真正"直接拿来用"的是：其**算法规范**、**数据格式（CFI / 标注结构）**、**SQL 语句**，以及——**WebView 兜底岛中的完整引擎**（零移植成本）。

**重要收益**：现有 WebView 版资产（`LocalAssetServer`、`nativeBridge.js`、`NativeEventDispatcher.kt`、`assets/webapp`）**全部不作废**，整体降级为兜底岛，因此迁移期间始终有可发布版本。

---

## 3. 目标架构

```
Koodo Reader Android (Kotlin/Compose)
├── app/                        原生壳：导航、DI、Intent/深链、启动加速
├── core/
│   ├── common/                 工具、i18n（读 src/assets/locales/*.json，key 与桌面一致）
│   ├── data/                   Room（与桌面 schema 列名对齐）+ 文件存储 + 本地备份导入导出
│   └── designsystem/           主题/字体/排版令牌（对齐 theme.css 与桌面 reader config）
├── engine/                     ★ 原生化的"kookit 等价物"（新代码主体）
│   ├── cfi/                    cfi.ts + epubcfi.js 直译（纯逻辑，先单测覆盖）→ 本阶段已落地
│   ├── epub/                   EPUB 解析 + 原生排版
│   ├── pdf/                    Pdfium/PdfBox 渲染 + 文本层 + 搜索 + 大纲
│   ├── mobi/                   mobi.js 移植（PalmDOC/HUFF-CDIC/KF8/EXTH）
│   ├── text/ image/            TXT/MD、CBZ/CBR/CBT/CB7（后续阶段）
│   ├── layout/ annotate/ gesture/   对应 layoutUtil / noteUtil / touchUtil
│   └── feature/                paragraphMode / speedReading / readingRuler / textRule / ocrCache
├── feature/
│   ├── library/ reader/ notes/ settings/ stats/
│   ├── tts/ dict/ translate/
│   └── webisland/              ★ 兜底岛：现 WebView + LocalAssetServer + kookit 原样复用
└── bridge/                     过渡期 JS 桥（nativeBridge.js / folderBridge.js 契约）
```

### 3.1 格式路由

| 格式 | 渲染后端 | 阶段 |
|---|---|---|
| EPUB | 原生 `engine/epub` | P2 |
| PDF | 原生 `engine/pdf` | P3 |
| MOBI / AZW3 / AZW | 原生 `engine/mobi`（此前走兜底岛） | P4 |
| TXT / MD | 原生 `engine/text` | P5 |
| CBZ / CBR / CBT / CB7 | 原生 `engine/image` | P5 |
| FB2 / DOCX / MHTML / HTML | 兜底岛（kookit 原样跑） | 后续单独立项 |
| 词典 / TTS / 翻译 / OCR | 原生 | P6 |

---

## 6. 阶段计划

| 阶段 | 内容 | 交付物 | 预估 | 验收标准 |
|---|---|---|---|---|
| **P0 基线** | 引擎能力盘点、逐文件 LOC 基线、`schema.lock` 提取、性能基线、ADR | `docs/` 文档、`scripts/android-baseline.js`、`schema.lock` | 1 周 | 基线可复现；表结构确认 |
| **P1 原生壳 + 数据层** | Compose 导航壳、Room、SAF 导入（规则迁 Kotlin 为单一事实源）、封面、书库/书架/收藏/回收站、设置 key 对齐、Intent 入库 | 可安装 APK（阅读仍走兜底岛） | 3–4 周 | 1000 本导入不 OOM；冷启动 P90 ≤ 1.5 s；`.db` 与桌面双向可读 |
| **P2 EPUB 原生阅读器** ★ | `engine/cfi` 接 DOM 等价层、EPUB 解析、原生分页/滚动、主题/字体/行距/边距、目录、进度、书签、高亮/笔记、划词菜单、脚注、内链 | 原生 EPUB 闭环 | **8–12 周** | 翻页 P90 < 50 ms；10 万字内存峰值 < 350 MB；桌面标注双向兼容 |
| **P3 PDF 原生阅读器** ★ | Pdfium 渲染（POC 定夺渲染库）、缩放/滚动/双页、文本层选择与搜索、大纲、密码、批注叠加 | 原生 PDF 闭环 | 6–8 周 | 1000 页 PDF 打开 < 1.5 s；文本搜索准确率对齐 pdf.js 基线 |
| **P4 MOBI / AZW3 原生** | `engine/mobi`：PalmDOC + HUFF/CDIC 解压、MOBI6/KF8、EXTH、资源抽取、HTML 清洗 → 复用 P2 排版与标注管线 | MOBI/AZW3 脱离兜底岛 | 4–6 周 | 20 本样本集（老 mobi + KF8）全部可读且排版不崩 |
| **P5 其他格式适配（后续规划）** | ① TXT/MD 原生分页（含编码探测，替代 chardet）② CBZ/CBR/CBT/CB7 原生图片阅读器 + 懒加载（对齐"当前页 + 后 3 页"、卸载 -4 页）③ FB2 / DOCX / MHTML / HTML 单独立项评估 | 各格式原生 reader | 6–8 周 | 逐格式回归；兜底岛按格式逐个下线 |
| **P6 阅读增强** | TTS（Android TTS + MediaSession/前台服务）、词典（原生 MDX/MDD）、划词翻译/AI、段落模式/速读/阅读尺/文本规则、统计（对齐 `/stats`）、OCR（ML Kit 按需下载） | 功能对齐桌面端 | 6–8 周 | 附录 A 对照表逐项打勾 |
| **P7 本地备份与数据管理** | 备份 zip 导出/导入（结构与桌面一致）、数据导入导出、**无云同步** | 可迁移数据集 | 2–3 周 | 桌面导出 → 安卓导入 → 数据 100% 一致，反向同 |
| **P8 收尾** | 兜底岛下线、APK 体积优化（ABI 拆分/动态特性）、Crash 监控、Macrobenchmark 固化 | 正式版 | 2 周 | 主变体不再加载 WebView（若 FB2/DOCX 亦完成原生） |

**合计约 12–18 人月**；**EPUB 原生可用（P0–P2 结束）约 3–4 个月**，是第一个"体验显著改善"的里程碑。

---

## 7. 构建与 CI 改造（`--target` 双目标）

### 7.1 目标（target）概念
| target | 含义 | 资源需求 |
|---|---|---|
| `webview` | 现有形态：打包网页产物到 `assets/webapp`，WebView + 回环服务渲染 | `build/` 网页产物 + `--audit` 校验 `index.html` 引用 |
| `native` | 原生形态：仅打包原生资源，不含网页产物 | Kotlin 源码模块 `engine/*`、`feature/*` |

- `android.config.json` 增加 `targets: ["webview"]`（默认保持现状，避免破坏现有 CI）。
- 构建计划按 `target × buildType × abi` 展开，并向 Gradle 传递 `-Ptarget=<target>`（与既有 `-PabiFilters` 同风格）。
- `getArtifactPath`：`webview` 目标保持现有命名（向后兼容）；`native` 目标在文件名中带 `-native` 后缀，避免同目录冲突。
- `validatePreconditions`：`native` 目标**不要求**网页产物存在；`stageAssetsPlan` 仅对需要网页资产的目标产出 staging 项。

### 7.2 CLI
```
node scripts/build-android.js --target native   --debug --abi arm64-v8a
node scripts/build-android.js --target webview  --audit --dry-run
node scripts/build-android.js --target native,webview --no-split
```

### 7.3 CI
- `release-android.yml` 现有 job 保持（webview 目标），新增原生单元测试步骤：`gradle :engine:cfi:test`（JVM 模块，不需要 Android SDK）。
- Jest 侧保留 `nativeBridge.test.js` / `folderBridge.test.js` / `androidBuild.test.js` 作为**协议与构建守卫**。

---

## 8. 验收指标（P0 基线定义，后续用 Macrobenchmark 固化）

| 指标 | 目标 |
|---|---|
| 冷启动 P90 | ≤ 1.5 s（原生壳） |
| 打开 EPUB（1 MB，300 章） | ≤ 1.2 s |
| 翻页延迟 P90 | < 50 ms |
| 滚动手势 | 与系统一致（原生 fling） |
| 内存峰值（10 万字 EPUB） | < 350 MB |
| APK 体积（arm64，native 目标） | 先测基线，再定目标（不含兜底岛 webapp 时可显著下降） |
| 标注兼容 | 桌面 ↔ 安卓 双向位置一致率 ≥ 99% |

---

## 9. 风险登记

| # | 风险 | 影响 | 对策 |
|---|---|---|---|
| R1 | 引擎为浏览器形态，无法直接复用 | 工期大幅拉长 | 已按"移植 + 兜底岛"双轨设计；优先移植纯逻辑模块（`engine/cfi` 已落地） |
| R2 | CFI ↔ 原生 DOM 定位精度 | 标注错位、数据不可信 | `engine/cfi` bug-for-bug 对齐上游 + 黄金向量回归；DOM 等价层单测覆盖 |
| R3 | 原生排版与 CSS columns 页码不一致 | 用户感知"进度变了" | 只承诺 CFI 定位一致；UI 上展示章节 + 百分比而非绝对页码 |
| R4 | PDF 文本层质量 | 选择/搜索不可用 | P0 做 Pdfium vs PdfRenderer+PdfBox 的 POC，以"文本搜索准确率"定夺 |
| R5 | MOBI/AZW3 无成熟 Kotlin 库 | P4 进度风险 | 优先移植 `mobi.js`（算法密集但可纯逻辑单测）；期间兜底岛兜住 |
| R6 | 双轨维护成本 | 人力分散 | 兜底岛冻结（只修崩溃）；设定期限按格式逐个下线 |
| R7 | APK 体积（兜底岛 + 原生引擎并存） | 安装包偏大 | 动态特性模块 / ABI 拆分；P8 移除兜底岛 |
| R8 | License 合规（AGPL-3.0） | 分发受限 | 保留 AGPL 与源码可得性；对外声明移植来源 |

---

## 10. Phase 0 Checklist

- [ ] 引擎能力盘点（逐项跑现 WebView 版功能，产出清单）
- [ ] 逐文件 LOC 基线：`node scripts/android-baseline.js --modules`（kookit / foliate-js）
- [ ] 桌面 `.db` schema 提取 → `schema.lock`：`node scripts/android-baseline.js --schema <data.db 路径>`
- [ ] 性能基线（冷启动 / 打开 / 翻页 / 内存 / APK 体积）记录到 `docs/android-baseline.json`
- [ ] PDF 渲染库 POC 结论（Pdfium vs PdfRenderer+PdfBox）
- [ ] ADR-001 架构选型、ADR-002 定位与标注兼容策略、ADR-003 兜底岛生命周期
- [ ] 桌面端功能对照表（附录 A）填写完成

---

## 附录 A：桌面端功能 ↔ Android 原生对照表（模板）

| 能力 | 桌面端实现位置 | Android 原生目标 | 阶段 | 状态 |
|---|---|---|---|---|
| 书库/书架/收藏/回收站 | `src/containers/lists/*` | `feature/library` | P1 | ☐ |
| 批量导入（本地目录） | `src/components/importLocal` | SAF + `core/data` | P1 | ☐ |
| 阅读（EPUB） | kookit `EpubRender` | `engine/epub` | P2 | ☐ |
| 阅读（PDF） | kookit `PdfRender`/`PdfTextRender` | `engine/pdf` | P3 | ☐ |
| 阅读（MOBI/AZW3） | kookit `MobiRender` | `engine/mobi` | P4 | ☐ |
| 高亮/笔记/书签 | `utils/reader/noteUtil.ts` + kookit `noteUtil` | `engine/annotate` | P2 | ☐ |
| 主题/字体/行距/边距 | `utils/reader/themeUtil.ts`、`styleUtil.ts` | `core/designsystem` | P2 | ☐ |
| 段落模式/速读/阅读尺 | kookit `paragraphModeUtil`/`speedReadingUtil`/`readingRulerUtil` | `engine/feature` | P6 | ☐ |
| 文本替换规则 | kookit `textRuleUtil` | `engine/feature` | P6 | ☐ |
| TTS | `utils/reader/ttsUtil.ts` | `feature/tts` | P6 | ☐ |
| 词典 | `utils/file/dictUtil.ts` + `js-mdict` | `feature/dict` | P6 | ☐ |
| 划词翻译 / AI | `utils/plugins/renderer/*`、`utils/request/aiBridge.ts` | `feature/translate` | P6 | ☐ |
| 统计 | `src/pages/stats/*` | `feature/stats` | P6 | ☐ |
| OCR | tesseract/onnxruntime(web) / 原生 OCR(桌面) | ML Kit | P6 | ☐ |
| 备份/恢复 | `utils/file/backup.ts`、`restore.ts` | `core/data` | P7 | ☐ |
| 云同步 / WebDAV / S3 | `main.js` + 插件 | **不做** | — | ✗ |

---

## 附录 B：ADR 模板

```markdown
# ADR-00X <决策标题>

- 日期：
- 状态：提议 / 已采纳 / 已废弃

## 背景
## 决策
## 备选方案与理由
## 影响（正面 / 负面）
## 验证方式（如何证明决策正确）
## 回滚方案
```

---

## 附录 C：License 与合规

- 本仓库：**AGPL-3.0**（根 `LICENSE`）。
- 移植来源：`koodo-reader/kookit`（**AGPL-3.0**）、`johnfactotum/foliate-js`（**MIT**，vendored zip.js BSD-3 / fflate MIT / PDF.js Apache）。
- 义务：APK 分发须继续以 AGPL-3.0 提供完整对应源码，并在代码注释/文档中标注移植来源与许可证。
- 若未来需要闭源分发：**不得移植** kookit / foliate-js 代码，只能改用 MIT/BSD 的独立实现（工期显著增加）。

