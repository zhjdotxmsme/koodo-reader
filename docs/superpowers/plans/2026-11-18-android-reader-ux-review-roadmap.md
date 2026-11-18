# Android 原生阅读器 UI/UX 审查与后续优化路线图

> 日期：2026-11-18 · 范围：`android/` `-Ptarget=native` · 基线：M1–M3 能力已落地  
> 关联规格：`docs/superpowers/specs/2026-10-21-android-full-reader-design.md`

---

## 1 · 已完成 UI/UX 资产（审查结论）

### 1.1 共享阅读壳（质量：良好，可扩展）

| 组件 | 位置 | 作用 |
|------|------|------|
| `ReaderTopBar` | `shell/ReaderChrome.kt` | 沉浸式顶栏渐变 + 动画显隐 |
| `ReaderBottomProgress` | 同上 | 章/页/百分比 + 点进目录 |
| `ReaderSystemBars` | `shell/ReaderSystemBars.kt` | 全屏、图标深浅、刘海、与书架主题衔接 |
| `ReaderChromeRevealStrip` | `shell/ReaderImmersiveChrome.kt` | 隐藏 chrome 时顶部下滑唤出 |
| `ReaderPageGestures` + prefs | M2 | 可配点击区、滑动、音量键 |
| `ReaderColorTokens` / `SpaceTokens` | designsystem | 阅读区间距与 chrome 透明度统一 |
| `koodoPressScale` | core-ui | 触控反馈一致性 |

**优点：** EPUB/PDF 已共用顶栏与底栏进度模式；chrome **overlay 内容**（不 resize viewport）避免 Foliate 重排闪屏——注释与实现一致，属于正确 UX 决策。

### 1.2 重排书（`NativeEpubScreen`）

| 能力 | UX 表现 |
|------|---------|
| Foliate 主路径 + Canvas/NativeText 回退 | 多引擎，用户感知应为「一本打开」；失败时有 `ErrorState` + Retry |
| 目录 / 搜索 | 顶栏 **PRIMARY**：TOC、搜索（`ReaderChromeAction`） |
| 外观 | `ReaderAppearanceSheet`：主题圆点、滚动/分页、字号/行高/边距 |
| 阅读设置 | `ReaderSettingsSheet`：全屏、段间距、亮度、常亮、方向、自定义背景 |
| 进度 | 底栏：章名 · 页 · %（Foliate / Canvas 三套文案已分支） |
| 交互 | 双击返回退出 Snackbar；选区菜单；书签 flash；TTS/翻译/AI 在 overflow |
| 增强 | Bionic/Ruler/段落/速读仅在非 Foliate 分支露出（避免无效项） |

**优点：** 功能密度接近桌面 Koodo + 移动刚需（TTS、常亮、全屏）。  
**风险：** `NativeEpubScreen` 单体过大（~2.7k 行），后续 UX 迭代易漏改分支。

### 1.3 PDF（`NativePdfScreen`）

| 能力 | UX 表现 |
|------|---------|
| 栅格 + 重排 WebView | M3；菜单切换 raster/reflow |
| 双页 / 裁边 | 全局 `ReadingSettingsRoute` + 菜单项；**未进** `ReaderSettingsSheet` |
| 目录 / 搜索 / OCR / 分享 | 顶栏 + overflow |
| 缩放 | overflow 内 **「−」「+」**（无 i18n、无图标语义） |
| 进度 | `ReaderBottomProgress`（与 EPUB 对齐） |
| 标注 | `BookAnnotationsScreen`、more → Notes |

### 1.4 全局设置（`ReadingSettingsRoute`）

- 性能档、手势、字体、双栏/双页、规则/CSS、PDF 裁边/重排、RSVP 等 **长列表单页滚动**。
- 与书内 `ReaderAppearanceSheet` / `ReaderSettingsSheet` **字段重叠**（字号、字体、双页、列数）。

### 1.5 漫画（`ComicViewerActivity`）

- 独立 Activity，手势与 chrome 策略与 EPUB/PDF **未完全组件化**（审查项：spread/裁边复用体验）。

---

## 2 · 主要 UX 问题（按严重度）

| ID | 问题 | 影响 | 证据 |
|----|------|------|------|
| **U1** | **设置三入口、两套真源** | 用户不知改字号该去「外观 / 设置 Sheet / 应用阅读设置」 | `ReadingSettingsRoute` vs `ReaderAppearanceSheet` vs `ReaderSettingsSheet` |
| **U2** | **Overflow 菜单过载** | 发现成本高、小屏需多次滚动 | EPUB more 10+ 项；PDF 含 OCR/分享/模式切换混排 |
| **U3** | **格式间 parity 不齐** | PDF 缺书内裁边/重排/双页滑块；缩放用符号菜单 | `ReaderSettingsSheet` 无 `pdfCrop`/`pdfReflow` |
| **U4** | **文案与 i18n 债务** | 无障碍与本地化不一致 | PDF「−/+」；EPUB「A− 120%」；书签 `✓` 硬编码 |
| **U5** | **M3 能力可发现性弱** | spread/裁边/重排依赖长期记忆 | 无首次提示、无模式芯片（chip）在底栏 |
| **U6** | **平板/横屏未产品化** | 双页、双栏与 `doublePage` 关系难理解 | 仅开关，无「当前模式」说明 |
| **U7** | **错误与等待态不统一** | Toast vs `ErrorState` vs 线性进度混用 | PDF 中途错误 Toast；打开失败另有态 |
| **U8** | **阅读主题 vs 书架主题** | 两套 `MaterialTheme`/`ShellAppearance` 切换认知 | `NativeEpubScreen` 内嵌 `readerScheme` |
| **U9** | **Canvas 增强项隐藏规则** | Foliate 用户看不到 bionic 等（正确）但无「为何没有」说明 | 仅 `!useFoliate` 分支 |
| **U10** | **测试与文档** | 真机 smoke 有清单，无 UX 回归矩阵 | M1/M3 device checklist 偏功能 |

---

## 3 · 设计原则（后续统一）

1. **一书内完成 80% 调节**；应用设置只放默认、手势、规则/CSS、备份相关。  
2. **同一字段唯一主界面**：字号/主题/分页 → 书内；默认值 → 应用设置（可选「同步为默认」）。  
3. **格式共享壳、格式特异区块**：`ReaderSettingsSheet` 增加 `ReaderFormatSection.PDF | REFLOW | COMIC`。  
4. **显性模式状态**：底栏或顶栏副标题展示 `Raster / Reflow · Spread · Lite`。  
5. **渐进披露**：Primary 3 图标 + 统一「阅读控制中心」BottomSheet，替代长 Dropdown。

---

## 4 · 优化方案（三轨并行）

### 轨 A · 信息架构（优先级 P0，建议 M4a）

**目标：** 减入口、消重复。

| 任务 | 方案 | 交付 |
|------|------|------|
| A1 | 合并书内调节 | 新组件 `ReaderControlCenterSheet`（Tab：**外观** | **版式** | **PDF** | **高级**）吸纳 Appearance + Settings 高频项 |
| A2 | 瘦身 `ReadingSettingsRoute` | 保留：手势、性能档、规则/CSS、默认字体；移除与书内重复的即时滑块（或标「默认值」） |
| A3 | 统一更多菜单 | `ReaderOverflowMenu` 数据驱动：`ReaderChromeAction` + 格式扩展项，PDF/EPUB 同序不同 enabled |
| A4 | PDF 书内补齐 | `pdfCropMarginPx` / `pdfReflow` / `doublePage` 进入 PDF 的 Control Center |

**验收：** 新用户 3 分钟内能完成：改主题、开双页、PDF 裁边 16px（无进全局设置）。

### 轨 B · 体验抛光（P1，M4b）

| 任务 | 方案 |
|------|------|
| B1 | 缩放/字号 | PDF 用 `IconButton` + `t("Zoom out/in")`；EPUB A± 迁入顶栏 chips 或 Control Center |
| B2 | 模式芯片 | chrome 显隐时底栏左侧 `FilterChip`：Spread / Reflow / Lite（只读状态 + 一点进设置） |
| B3 | 加载骨架 | Foliate `foliateReady`、PDF `rendering` 统一 `ReaderLoadingOverlay`（进度可选） |
| B4 | 错误态 | 全书内致命错误只用 `ErrorState`；Toast 仅轻量非阻断 |
| B5 | 书签反馈 | 用书内 Snackbar 替代顶栏 `✓ Bookmark` 文案闪烁 |
| B6 | 无障碍 | overflow 项补全 `contentDescription`；滑块关联 `semantics` 当前值 |

### 轨 C · 场景扩展（P2，M5+）

| 任务 | 方案 |
|------|------|
| C1 | 平板 | `WindowWidthSizeClass`：宽屏默认 spread 提示；双栏与 `doublePage` 互斥说明 UI |
| C2 | 漫画 | `ComicViewer` 接入 `ReaderTopBar` / 裁边参数 |
| C3 | 首次阅读引导 | 一次性 Coach Mark（点击区、chrome、PDF 扫描→OCR） |
| C4 | 与桌面心智对齐 | 设置页链到 `docs/android-user-guide.md`（M4 文档里程碑） |
| C5 | 性能档 UX | Lite 切换时 Snackbar 说明「已降分辨率/单栏」 |

---

## 5 · 推荐里程碑

```mermaid
flowchart LR
  M4a[M4a IA 与 Control Center]
  M4b[M4b 抛光与 a11y]
  M4c[M4c 备份与用户指南]
  M5[M5 平板与漫画壳统一]
  M4a --> M4b --> M4c --> M5
```

| 里程碑 | 周期（估） | 核心产出 |
|--------|-----------|----------|
| **M4a** | 1–2 周 | `ReaderControlCenterSheet` + overflow 重构 + PDF 书内 M3 项 |
| **M4b** | 1 周 | i18n/图标/加载错误统一 + UX 截图回归清单 |
| **M4c** | 随 M4 数据 | 备份字段 UX + 用户指南（规格已有） |
| **M5** | 2 周 | 平板布局 + 漫画 chrome 统一 |

---

## 6 · UX 验收矩阵（后续每版勾选）

| 场景 | EPUB | PDF | 漫画 |
|------|------|-----|------|
| 首次打开 3s 内有反馈 | ☐ | ☐ | ☐ |
| chrome 切换不重排抖动 | ☐ | ☐ | ☐ |
| 底栏进度可进目录 | ☐ | ☐ | ☐ |
| 书内改字号立即生效 | ☐ | ☐（reflow） | ☐ |
| 双页开关位置一致 | ☐ | ☐ | ☐ |
| 无硬编码英文/符号菜单 | ☐ | ☐ | ☐ |
| TalkBack 读完顶栏 | ☐ | ☐ | ☐ |

---

## 7 · 技术约束（实现时注意）

- Foliate viewport **禁止**因 Scaffold padding 变化而 resize（保持现有 overlay 架构）。  
- PDF 重排与栅格切换需 **保留页码** 与 Snackbar 提示。  
- 所有新文案走 `t("key")` + `en.json` + `sync-locales-android.js`。  
- 大改优先抽离 `NativeEpubScreen` 的 chrome 子树到 `reader/chrome/` 包，降低回归面。

---

## 8 · 可执行任务清单

M4a 实现分解（PR 切分、T1–T10、DoD）：  
[`docs/superpowers/plans/2026-11-18-android-reader-m4a-control-center.md`](2026-11-18-android-reader-m4a-control-center.md)

---

## 9 · 待产品确认（可选决策）

1. **默认 chrome 显隐**：打开书时工具栏默认显示（当前）vs 默认隐藏（Legera 向）？  
2. **全局设置是否保留完整字号滑块**：作「新书架默认」还是删除仅留书内？  
3. **AI / 翻译入口**：长期放 overflow 还是底栏第四位 Primary？

---

*审查基于 2026-11-18 代码树；若 Canvas 回退或 `NativeTextReader` 占比上升，需单独立项复评。*