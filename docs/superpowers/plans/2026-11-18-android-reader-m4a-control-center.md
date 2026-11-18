# M4a — 阅读控制中心 + 设置 IA 收口（可执行任务清单）

**Goal：** 书内完成 80% 阅读调节；合并 `ReaderAppearanceSheet` + `ReaderSettingsSheet` 高频项；统一 EPUB/PDF overflow；PDF 书内暴露 M3（裁边/重排/双页）。

**Status（2026-11-18）：** T1–T7、T9、**T6** 已落地；`ReaderOverflowCatalogTest` 3/3；APK `app-nativeHost-debug.apk` 已构建。待办：**T10** 真机烟雾（需 USB 授权后 `adb install -r`）。

**前置：** [`2026-11-18-android-reader-ux-review-roadmap.md`](2026-11-18-android-reader-ux-review-roadmap.md)

**非目标（本里程碑不做）：** 漫画壳统一、平板自适应、首次引导 Coach Mark、备份 UX（归 M4c）。

---

## 0 · 拍板默认值（开工前确认一次）

| 决策 | 建议默认 | 备注 |
|------|----------|------|
| 打开书 chrome | **默认隐藏**（保持 `CHROME_VISIBLE_ON_OPEN = false`） | 与现行为一致 |
| 全局「阅读设置」字号滑块 | **保留，标注「默认用于新书」** | 删滑块易惹老用户不满 |
| 更多菜单入口 | 保留 **⋯**，首项 **「阅读选项」** → Control Center | 替代分列 Appearance + Settings |
| Control Center 形态 | `ModalBottomSheet` + **横向 Tab**（Material `TabRow`） | 与现有 Sheet 一致 |

---

## 1 · 组件设计（实现前只定接口）

### 1.1 新文件

```
android/app/src/main/java/com/koodoreader/reader/reader/
  ReaderControlCenterSheet.kt      # 主 UI：Tab + 各分区 Composable
  ReaderControlCenterSections.kt # Appearance / Layout / Pdf / Advanced 子页
  ReaderOverflowMenu.kt          # 数据驱动的 DropdownMenu（可选 T2）
```

### 1.2 枚举与策略

```kotlin
// 新建 reader/ReaderFormatKind.kt
enum class ReaderFormatKind { REFLOW, PDF, COMIC, TEXT }

// ReaderControlCenterTab — JVM 可测
enum class ReaderControlCenterTab(val i18nKey: String) {
    APPEARANCE("Appearance"),
    LAYOUT("Layout"),
    PDF("PDF display"),      // 仅 ReaderFormatKind.PDF 时 enabled/visible
    ADVANCED("Advanced"),    // 亮度、常亮、方向、自定义背景等
}
```

**Tab 可见性：**

| Tab | REFLOW (Foliate) | REFLOW (Canvas) | PDF |
|-----|------------------|-----------------|-----|
| Appearance | 主题、字号、行高、边距 | 同左 + Foliate 主题色 | 仅 reflow 下字号（或灰显说明栅格无效） |
| Layout | 滚动/分页、双页、双栏 | 双栏、bionic 等 | 双页 |
| PDF | 隐藏 | 隐藏 | 裁边、重排开关、缩放提示 |
| Advanced | chrome + 段落间距 + 方向 | 同左 | 同左 + OCR 入口链接（仅跳转，不嵌 OCR UI） |

### 1.3 状态契约

- **本书即时：** `ReaderFeatureState` + `ReaderChromeUiState` — 每次 `onChange` 由宿主 `commitFeatures` / `onChromeState` 持久化（与现 `ReaderSettingsSheet` 相同）。
- **不引入第二套 prefs**；全局 `ReadingSettingsRoute` 仍写同一 `ReaderFeaturePrefs` / `ReaderGesturePrefs`。
- **Foliate 外观：** Tab Appearance 内调用现有 `FoliateAppearance` / `ThemeKind` 回调，等价于现 `ReaderAppearanceSheet` props。

---

## 2 · 任务分解（按依赖顺序）

### T1 · Tab 策略单测（TDD 先写）

| # | 内容 | 文件 |
|---|------|------|
| T1.1 | `ReaderFormatKind` + `ReaderControlCenterTabs.forFormat(kind, useFoliate)` 返回 tab 列表 | `reader/ReaderControlCenterTabs.kt` |
| T1.2 | JVM 测试：PDF 含 `PDF` tab；Foliate 无 Canvas-only 项 | `ReaderControlCenterTabsTest.kt` |

**验收：** `:app:testDebugUnitTest --tests …ReaderControlCenterTabsTest`

---

### T2 · 抽出 Section Composable（无接线）

| # | 内容 | 来源迁移 |
|---|------|----------|
| T2.1 | `AppearanceSection` | 从 `ReaderAppearanceSheet.kt` 搬主题圆点、flow chips |
| T2.2 | `LayoutSection` | 字号 Slider、行高、边距、`scrolled`、**doublePage**、**columnsPerPage**（Foliate 与 Canvas 分支 props） |
| T2.3 | `PdfDisplaySection` | `pdfCropMarginPx` Slider、`pdfReflow` Switch、双页 Switch（与全局字段同） |
| T2.4 | `AdvancedSection` | 从 `ReaderSettingsSheet.kt` 搬：全屏、段间距、亮度、常亮、方向、自定义背景 |

**约束：** 仅搬迁 UI，不删旧 Sheet 直到 T4 完成（避免半迁移）。

**验收：** Compose Preview 可选；至少编译通过。

---

### T3 · `ReaderControlCenterSheet` 组装

| # | 内容 |
|---|------|
| T3.1 | `ModalBottomSheet` + `TabRow` + `when(selectedTab)` 渲染 T2 sections |
| T3.2 | 打开时 `initialTab` 参数（PDF 从菜单进可默认 `PDF` tab） |
| T3.3 | `windowInsetsPadding` / `SpaceTokens` 与现有 Sheet 对齐 |

**验收：** 在 `NativeEpubScreen` 临时开关下可手动打开（feature flag `useControlCenter = true` 本地常量即可）。

---

### T4 · 接线 EPUB

| # | 文件 | 改动 |
|---|------|------|
| T4.1 | `NativeEpubScreen.kt` | `showAppearance` / `showReaderSettings` → 单一 `showControlCenter`；更多菜单一项「阅读选项」 |
| T4.2 | 同上 | 删除 overflow 内重复：Appearance、Settings、A−/A+（字号进 Layout tab） |
| T4.3 | 同上 | Foliate `setViewSettings` 仍由既有 `LaunchedEffect(viewSettings)` 驱动 |
| T4.4 | 保留 | TTS、书签、笔记、AI 仍在 overflow（或 Advanced 底部链式入口，二选一，推荐仍 overflow） |

**验收：** foliate `test-foliate-host.js` 无变；冒烟：改主题/双页/字号当场生效。

---

### T5 · 接线 PDF

| # | 文件 | 改动 |
|---|------|------|
| T5.1 | `NativePdfScreen.kt` | `ReaderSettingsSheet` → `ReaderControlCenterSheet(format = PDF)` |
| T5.2 | 同上 | 更多菜单：移除独立 PDF reflow 项、双页项（进 Layout/PDF tab）；**缩放**改为 `t("Zoom out")` / `t("Zoom in")` 或图标+CD |
| T5.3 | 同上 | `LaunchedEffect` 已有 crop/reflow/spread 逻辑不变，仅 UI 入口迁移 |
| T5.4 | 同上 | `ReaderBottomProgress` 可选：label 追加 `· Reflow` / `· Spread`（只读，P1 可并 M4b） |

**验收：** `node scripts/test-pdfengine.js`；真机 M3 清单 P1–P6 仍过。

---

### T6 · `ReaderOverflowMenu` 统一（可与 T4/T5 并行）

| # | 内容 |
|---|------|
| T6.1 | `ReaderOverflowMenu(items: List<ReaderOverflowItem>)` 封装 `DropdownMenu` + `koodoPressScale` |
| T6.2 | `ReaderOverflowItems.forEpub(...)` / `forPdf(...)` 工厂 |
| T6.3 | 单测：PRIMARY 不变；overflow 顺序稳定（快照或 list equality） |

---

### T7 · 瘦身 `ReadingSettingsRoute`

| # | 内容 |
|---|------|
| T7.1 | 各滑块标题加副文案 `t("Default for new sessions")`（新 i18n key） |
| T7.2 | 将 **PDF display** 区块标注「亦可在 PDF 阅读选项中调节」 |
| T7.3 | **不删除** 手势/性能/规则/CSS（仍仅全局合理） |
| T7.4 | 可选：双页/双栏/字号在全局标为 advanced collapsible（`AnimatedVisibility`） |

**验收：** `ReaderFeatureStateTest` 往返不变。

---

### T8 · i18n

| key（en.json） | 用途 |
|----------------|------|
| `Reading options` | 更多菜单 → Control Center |
| `Default for new sessions` | 全局设置副标题 |
| `Zoom out` / `Zoom in` | PDF 缩放 |
| `Also adjustable while reading` | 全局 PDF 区块提示 |
| `Layout` | Tab（若与现有 Layout 重复则复用） |

运行：`node scripts/sync-locales-android.js`

---

### T9 · 删除死代码

| # | 条件 |
|---|------|
| T9.1 | EPUB/PDF 均不再引用后，**废弃** `ReaderAppearanceSheet.kt` 或保留为 `AppearanceSection` 内部 private（推荐后者，删顶层 public） |
| T9.2 | `ReaderSettingsSheet.kt` 缩为 Advanced-only 或删除并由 Section 替代 |

---

### T10 · 文档与回归

| # | 内容 |
|---|------|
| T10.1 | 更新 UX roadmap 状态 → M4a 已落地 |
| T10.2 | 扩展 `m3-device-smoke-checklist.md` → **阅读选项** 路径各点一次 |
| T10.3 | 全规格索引 `2026-10-21-android-full-reader-design.md` 增加 M4a 行 |

---

## 3 · 建议 PR 切分（便于审查）

| PR | 包含任务 | 约行数 |
|----|----------|--------|
| **PR-1** | T1 + T2 + T3（新组件，无接线） | ~400–600 |
| **PR-2** | T4 EPUB 接线 + T6 | ~200–350 |
| **PR-3** | T5 PDF + i18n T8 | ~150–250 |
| **PR-4** | T7 + T9 + T10 | ~100–200 |

---

## 4 · 验证命令（每 PR）

```powershell
node scripts/test-foliate-host.js
node scripts/test-pdfengine.js
cd android
.\gradlew.bat --no-build-cache :app:testDebugUnitTest `
  --tests com.koodoreader.reader.reader.ReaderControlCenterTabsTest `
  --tests com.koodoreader.reader.shell.ReaderFeatureStateTest `
  --tests com.koodoreader.reader.shell.ReaderPageGesturesTest
```

真机（解锁后）：

1. EPUB → ⋯ → **阅读选项** → 外观/版式改双页 → 分页双栏  
2. PDF → **阅读选项** → 裁边 16px + 重排切换  
3. 设置 → 阅读 → 改默认字号 → 新开书验证默认值

---

## 5 · 风险与回滚

| 风险 | 缓解 |
|------|------|
| Foliate 双触发 `setViewSettings` | Control Center 合并 state，单次 `commitFeatures` |
| Sheet 过高小屏键盘 | `verticalScroll` 每 Tab 内独立 |
| 回滚 | 保留 git 标签；`useControlCenter` 编译期 flag 一周内再删 |

---

## 6 · 完成定义（DoD）

- [x] EPUB/PDF 各仅 **一个** 书内设置入口（阅读选项）  
- [x] PDF 裁边/重排/双页 **不进** 全局才能调到（书内 PDF tab + 全局默认并存）  
- [x] 无新增硬编码菜单符号（−/+）  
- [ ] 单测 + foliate/pdf node 测试绿（本地 Gradle 锁偶发需 reloc 脚本）  
- [x] en.json + android locales 同步  
- [x] 旧 Sheet 已删除（T9）  
- [ ] 用户未要求前 **不 git commit**（仓库惯例）

---

*任务清单版本 2026-11-18 · 对应 UX 审查 M4a 轨*